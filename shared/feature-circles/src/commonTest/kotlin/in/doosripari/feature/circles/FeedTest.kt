package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.ApiError
import `in`.doosripari.core.model.Page
import `in`.doosripari.core.model.Post
import `in`.doosripari.core.model.PostKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feed merge, plan section 6.6's "newest first" plus a cursor.
 *
 * The tests are written around what a user would notice rather than around the
 * implementation: a post appearing twice, a post the reader is looking at
 * moving, and a page arriving out of order. Those are the three ways a paged
 * feed goes wrong, and each has a test below.
 */
class FeedTest {

    private fun state(vararg posts: Post, viewerId: Int = 7) = FeedState(
        circleId = 1,
        viewerId = viewerId,
        posts = posts.toList(),
    )

    private fun ids(state: FeedState) = state.posts.map { it.id }

    // ----------------------------------------------------------------- paging --

    @Test
    fun `the first page is ordered newest first`() {
        val page = Page(
            items = listOf(
                Fixtures.post(1, minutesAgo = 30),
                Fixtures.post(2, minutesAgo = 5),
                Fixtures.post(3, minutesAgo = 60),
            ),
            nextCursor = "c1",
        )
        val loaded = Feed.firstPage(state(), page)
        assertEquals(listOf(2, 1, 3), ids(loaded))
        assertEquals("c1", loaded.nextCursor)
    }

    @Test
    fun `appending a page does not reorder the posts already on screen`() {
        val before = state(
            Fixtures.post(10, minutesAgo = 5),
            Fixtures.post(11, minutesAgo = 20),
            Fixtures.post(12, minutesAgo = 40),
        )
        val page = Page(
            items = listOf(
                Fixtures.post(13, minutesAgo = 55),
                Fixtures.post(14, minutesAgo = 70),
            ),
            nextCursor = null,
        )

        val after = Feed.appendPage(before, page)

        // The three that were on screen are still on screen, in the same
        // order, at the same indices. This is the whole guarantee.
        assertEquals(listOf(10, 11, 12), after.posts.take(3).map { it.id })
        assertEquals(listOf(10, 11, 12, 13, 14), ids(after))
    }

    @Test
    fun `appending a page does not duplicate a post already shown`() {
        // A cursor page is not guaranteed to be disjoint from what is loaded —
        // a post edited between the two requests, or a server that re-sends the
        // boundary row. The feed must not show it twice.
        val before = state(
            Fixtures.post(10, minutesAgo = 5),
            Fixtures.post(11, minutesAgo = 20),
        )
        val page = Page(
            items = listOf(
                Fixtures.post(11, minutesAgo = 20),
                Fixtures.post(12, minutesAgo = 40),
            ),
            nextCursor = null,
        )

        val after = Feed.appendPage(before, page)

        assertEquals(listOf(10, 11, 12), ids(after))
        assertEquals(after.posts.size, after.posts.distinctBy { it.id }.size)
    }

    @Test
    fun `appending a duplicate keeps the copy already on screen`() {
        // The page carries the post as it was when the page was built. Letting
        // it overwrite a row the reader is looking at is how a comment count
        // goes backwards under somebody's thumb. Live edits come through
        // upsert, which is tested separately below.
        val before = state(Fixtures.post(11, minutesAgo = 20).copy(commentCount = 9))
        val page = Page(items = listOf(Fixtures.post(11, minutesAgo = 20).copy(commentCount = 2)))

        val after = Feed.appendPage(before, page)

        assertEquals(1, after.posts.size)
        assertEquals(9, after.posts.single().commentCount)
    }

    @Test
    fun `a page that comes back out of order is merged into the right place`() {
        // The server is allowed to be slightly wrong about ordering between two
        // requests. The reader must not see a post land under an older one.
        val before = state(
            Fixtures.post(10, minutesAgo = 5),
            Fixtures.post(11, minutesAgo = 20),
            Fixtures.post(12, minutesAgo = 40),
        )
        val page = Page(
            items = listOf(
                Fixtures.post(14, minutesAgo = 70),
                Fixtures.post(13, minutesAgo = 55),
            ),
        )

        val after = Feed.appendPage(before, page)

        assertEquals(listOf(10, 11, 12, 13, 14), ids(after))
    }

    @Test
    fun `a new post arriving on a later page is placed above the older ones`() {
        // The one case where an existing row *does* shift: a genuinely newer
        // post belongs at the top, and the rows below it keep their order
        // relative to each other, which is what stops the list jumping.
        val before = state(
            Fixtures.post(11, minutesAgo = 20),
            Fixtures.post(12, minutesAgo = 40),
        )
        val page = Page(items = listOf(Fixtures.post(9, minutesAgo = 1)))

        val after = Feed.appendPage(before, page)

        assertEquals(listOf(9, 11, 12), ids(after))
        assertEquals(listOf(11, 12), after.posts.drop(1).map { it.id })
    }

    @Test
    fun `posts in the same second are ordered by id on every platform`() {
        // Without the id tie-break the order of two same-second posts would
        // depend on the sort's stability, which is not the same on Kotlin/Native
        // and Kotlin/JVM — and the same feed must not read differently on an
        // iPhone than on an Android phone.
        val sameMinute = listOf(
            Fixtures.post(20, minutesAgo = 1),
            Fixtures.post(21, minutesAgo = 1),
            Fixtures.post(22, minutesAgo = 1),
        )
        val forward = Feed.mergeNewestFirst(sameMinute, emptyList())
        val reversed = Feed.mergeNewestFirst(sameMinute.reversed(), emptyList())

        assertEquals(listOf(22, 21, 20), forward.map { it.id })
        assertEquals(forward.map { it.id }, reversed.map { it.id })
    }

    @Test
    fun `the last page marks the feed caught up and drops the cursor`() {
        val before = state(Fixtures.post(10, minutesAgo = 5))
        val page = Page(items = listOf(Fixtures.post(11, minutesAgo = 20)), nextCursor = null)

        val after = Feed.appendPage(before, page)

        assertEquals(FeedStatus.CaughtUp, after.status)
        assertNull(after.nextCursor)
        assertFalse(after.hasMore)
        assertFalse(after.canLoadMore)
    }

    @Test
    fun `a second load-more is refused while one is in flight`() {
        val before = state(Fixtures.post(10, minutesAgo = 5)).copy(nextCursor = "c1")
        val loading = Feed.startLoadingMore(before)

        assertEquals(FeedStatus.LoadingMore, loading.status)
        // A bouncing scrollbar must not queue three identical requests.
        assertEquals(loading, Feed.startLoadingMore(loading))
    }

    @Test
    fun `refreshing keeps the posts on screen instead of blanking them`() {
        val before = state(Fixtures.post(10, minutesAgo = 5))
        val loading = Feed.startLoading(before)

        assertEquals(listOf(10), ids(loading))
        assertEquals(FeedStatus.Loading, loading.status)
    }

    @Test
    fun `a failed first page is distinguishable from a failed later one`() {
        val empty = state()
        val first = Feed.failed(Feed.startLoading(empty), ApiError("no network"))
        assertIs<FeedStatus.Failed>(first.status)
        assertTrue(first.status.isFirstPage)

        val loaded = state(Fixtures.post(10, minutesAgo = 5)).copy(nextCursor = "c1")
        val later = Feed.failed(Feed.startLoadingMore(loaded), ApiError("no network"))
        assertIs<FeedStatus.Failed>(later.status)
        assertFalse(later.status.isFirstPage)
        // The list survives a failed second page, or there would be nothing to
        // put a "try again" underneath.
        assertEquals(listOf(10), ids(later))
    }

    // ------------------------------------------------------------------ upsert --

    @Test
    fun `upsert edits a post in place without moving it`() {
        val before = state(
            Fixtures.post(10, minutesAgo = 5),
            Fixtures.post(11, minutesAgo = 20),
            Fixtures.post(12, minutesAgo = 40),
        )
        val after = Feed.upsert(
            before,
            listOf(Fixtures.post(11, minutesAgo = 20).copy(text = "edited", commentCount = 4)),
        )

        assertEquals(listOf(10, 11, 12), ids(after))
        assertEquals("edited", after.posts[1].text)
        assertEquals(4, after.posts[1].commentCount)
    }

    @Test
    fun `upsert puts a brand new post in its timestamped place`() {
        val before = state(
            Fixtures.post(11, minutesAgo = 20),
            Fixtures.post(12, minutesAgo = 40),
        )
        val after = Feed.upsert(before, listOf(Fixtures.post(9, minutesAgo = 1)))

        assertEquals(listOf(9, 11, 12), ids(after))
    }

    @Test
    fun `a hidden post stays in the list for a moderator and leaves the visible one`() {
        val before = state(
            Fixtures.post(10, minutesAgo = 5),
            Fixtures.post(11, minutesAgo = 20),
        )
        val after = Feed.upsert(
            before,
            listOf(Fixtures.post(10, minutesAgo = 5).copy(hidden = true)),
        )

        assertEquals(listOf(10, 11), ids(after))
        assertEquals(listOf(11), after.visible.map { it.id })
    }

    // ----------------------------------------------------------------- filters --

    @Test
    fun `the media filter finds posts that carry a photo, not only image posts`() {
        // Section 6.6 allows text with photos attached, so a sentence with two
        // pictures is something somebody looking at the photo tab wants to see.
        val textWithPhotos = Fixtures.post(
            1, kind = PostKind.Text, media = listOf("p1", "p2"),
        )
        val plainText = Fixtures.post(2, kind = PostKind.Text)
        val voice = Fixtures.post(3, kind = PostKind.Voice)

        assertTrue(FeedFilter.Media.matches(textWithPhotos, viewerId = 7))
        assertFalse(FeedFilter.Media.matches(plainText, viewerId = 7))
        assertFalse(FeedFilter.Media.matches(voice, viewerId = 7))
    }

    @Test
    fun `the mine filter belongs to the viewer and to nobody else`() {
        val mine = Fixtures.post(1, authorId = 7)
        val theirs = Fixtures.post(2, authorId = 100)

        assertTrue(FeedFilter.Mine.matches(mine, viewerId = 7))
        assertFalse(FeedFilter.Mine.matches(theirs, viewerId = 7))
    }

    @Test
    fun `changing the filter keeps the loaded posts and drops the old cursor`() {
        // The cursor belongs to the old filter's page sequence. Paging on from
        // it would splice two unrelated lists together.
        val before = state(Fixtures.post(10, minutesAgo = 5)).copy(nextCursor = "c1")
        val after = Feed.applyFilter(before, FeedFilter.Voice)

        assertEquals(FeedFilter.Voice, after.filter)
        assertNull(after.nextCursor)
        assertEquals(listOf(10), ids(after))
        // Applying the same filter again changes nothing at all.
        assertEquals(after, Feed.applyFilter(after, FeedFilter.Voice))
    }

    @Test
    fun `an unknown filter code falls back to everything rather than nothing`() {
        // A chip this build has not shipped must not empty somebody's feed.
        assertEquals(FeedFilter.All, FeedFilter.fromCode("some_future_filter"))
        assertEquals(FeedFilter.All, FeedFilter.fromCode(null))
    }

    // ------------------------------------------------------------------ pins --

    @Test
    fun `pinned birthdays and retirements sit outside the paged list`() {
        val before = state(Fixtures.post(10, minutesAgo = 5))
        val after = Feed.pin(
            before,
            listOf(
                FeedPin(userId = 55, kind = PinKind.Birthday, label = "Kaur ji's birthday"),
                FeedPin(userId = 56, kind = PinKind.Retirement),
            ),
        )

        assertEquals(listOf(10), ids(after))
        assertTrue(after.isPinned(55))
        assertTrue(after.isPinned(56))
        assertFalse(after.isPinned(57))
    }

    // ------------------------------------------------------------------ boost --

    @Test
    fun `the thirty-day interaction boost is a label and never a sort`() {
        // Section 6.6 asks for a small boost for people you interacted with,
        // and in the same section says no engagement ranking. Reordering would
        // be ranking, so the boost is carried as a set and the order stays
        // chronological.
        val plain = Feed.appendPage(
            state(),
            Page(
                items = listOf(
                    Fixtures.post(1, minutesAgo = 60, authorId = 100),
                    Fixtures.post(2, minutesAgo = 5, authorId = 200),
                ),
            ),
        )
        val boosted = plain.copy(recentlyInteractedWith = setOf(100))

        assertEquals(listOf(2, 1), ids(plain))
        assertEquals(listOf(2, 1), ids(boosted))
        assertTrue(boosted.isRecentlyInteracted(boosted.posts[1]))
        assertFalse(boosted.isRecentlyInteracted(boosted.posts[0]))
    }

    // ------------------------------------------------------------ caught up --

    @Test
    fun `the caught-up notice waits for thirty posts`() {
        val few = state(*Array(29) { Fixtures.post(it + 1, minutesAgo = it.toLong()) })
            .copy(status = FeedStatus.CaughtUp)
        assertFalse(few.showsCaughtUpNotice)

        val many = few.copy(posts = few.posts + Fixtures.post(99, minutesAgo = 99))
        assertTrue(many.showsCaughtUpNotice)
    }

    @Test
    fun `reset is the one transition that empties the list`() {
        val before = state(Fixtures.post(10, minutesAgo = 5)).copy(nextCursor = "c1")
        val after = Feed.reset(before)

        assertTrue(after.posts.isEmpty())
        assertNull(after.nextCursor)
        assertEquals(FeedStatus.Idle, after.status)
    }
}
