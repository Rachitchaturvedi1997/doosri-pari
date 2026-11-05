package `in`.doosripari.network

import `in`.doosripari.core.model.Page
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cursor paging, which plan section 6.6 asks for by name: "a post arriving while
 * the user scrolls cannot make them skip a row".
 */
class PaginationTest {

    /** Serializable only because it has to cross the wire to be decoded. */
    @Serializable
    private data class Row(val id: Int, val text: String)

    private fun pages(vararg page: Page<Row>): (String?) -> Page<Row> {
        var index = 0
        return { _ ->
            page.getOrElse(index) { Page.last() }.also { index++ }
        }
    }

    @Test
    fun `pages load in order and follow the cursor`() = runTest {
        val feed = PagedFeed(
            loadPage = pages(
                Page(listOf(Row(3, "c"), Row(2, "b")), nextCursor = "c2"),
                Page(listOf(Row(1, "a")), nextCursor = null),
            ),
            keyOf = { it.id },
        )

        assertEquals(2, feed.loadMore())
        assertEquals(listOf(3, 2), feed.snapshot.map { it.id })
        assertTrue(feed.hasMore)

        assertEquals(1, feed.loadMore())
        assertEquals(listOf(3, 2, 1), feed.snapshot.map { it.id })
        assertTrue(feed.isExhausted)
        assertFalse(feed.hasMore)
    }

    @Test
    fun `a post that lands in two pages is shown once`() = runTest {
        // The exact case the cursor exists for: id 2 is the last row of page one
        // and, because somebody posted while the user was scrolling, also the
        // first row of page two.
        val feed = PagedFeed(
            loadPage = pages(
                Page(listOf(Row(3, "c"), Row(2, "b")), nextCursor = "c2"),
                Page(listOf(Row(2, "b"), Row(1, "a")), nextCursor = null),
            ),
            keyOf = { it.id },
        )

        assertEquals(2, feed.loadMore())
        assertEquals(1, feed.loadMore())
        assertEquals(listOf(3, 2, 1), feed.snapshot.map { it.id })
    }

    @Test
    fun `an entirely-duplicate page still advances the cursor`() = runTest {
        // Otherwise the feed asks for the same cursor for ever and the user
        // watches a spinner that never produces anything.
        val feed = PagedFeed(
            loadPage = pages(
                Page(listOf(Row(2, "b"), Row(1, "a")), nextCursor = "c2"),
                Page(listOf(Row(2, "b"), Row(1, "a")), nextCursor = null),
            ),
            keyOf = { it.id },
        )

        assertEquals(2, feed.loadMore())
        assertEquals(0, feed.loadMore(), "nothing new, but the cursor moved on")
        assertTrue(feed.isExhausted)
    }

    @Test
    fun `the feed stops at its limit rather than growing without bound`() = runTest {
        val many = (1..100).map { Row(it, "row $it") }
        val feed = PagedFeed(
            loadPage = pages(Page(many, nextCursor = "more")),
            keyOf = { it.id },
            maxItems = 10,
        )

        feed.loadMore()

        assertEquals(10, feed.snapshot.size)
        assertTrue(feed.isAtLimit)
        assertFalse(feed.hasMore)
        assertEquals(0, feed.loadMore())
    }

    @Test
    fun `loadMore on an exhausted feed does nothing`() = runTest {
        var calls = 0
        val feed = PagedFeed<Row, Int>(
            loadPage = {
                calls++
                Page.last()
            },
            keyOf = { it.id },
        )

        assertEquals(0, feed.loadMore())
        assertEquals(1, calls)
        assertTrue(feed.isExhausted)
    }

    @Test
    fun `reset starts again from a null cursor`() = runTest {
        val cursors = mutableListOf<String?>()
        var index = 0
        val canned = listOf(
            Page(listOf(Row(2, "b")), nextCursor = "c2"),
            Page.last(),
        )
        val feed = PagedFeed(
            loadPage = { cursor ->
                cursors += cursor
                canned.getOrElse(index) { Page.last() }.also { index++ }
            },
            keyOf = { it.id },
        )

        feed.loadMore()
        assertEquals(listOf(null), cursors)

        feed.reset()
        feed.loadMore()
        assertEquals(listOf(null, null), cursors, "a refresh is a new query, not a continuation")
        assertEquals(listOf(2), feed.snapshot.map { it.id })
    }

    @Test
    fun `an empty first page with no cursor ends the feed`() = runTest {
        val feed = PagedFeed<Row, Int>(
            loadPage = { Page.last() },
            keyOf = { it.id },
        )

        assertEquals(0, feed.loadMore())
        assertTrue(feed.isExhausted)
        assertNull(feed.nextCursor)
    }

    // --------------------------------------------------------- the wire shape --

    @Test
    fun `the wire page decodes into the domain page`() {
        val body = """{"items":[{"id":1,"text":"Namaste"}],"next_cursor":"abc123"}"""

        val page: Page<Row> = WIRE_JSON.decodeFromString<PageDto<Row>>(body).toPage()

        assertEquals(1, page.items.size)
        assertEquals(Row(1, "Namaste"), page.items.single())
        assertEquals("abc123", page.nextCursor)
        assertTrue(page.hasMore)
    }

    @Test
    fun `a missing next_cursor means the last page`() {
        val page: Page<Row> =
            WIRE_JSON.decodeFromString<PageDto<Row>>("""{"items":[]}""").toPage()

        assertNull(page.nextCursor)
        assertFalse(page.hasMore)
    }

    @Test
    fun `the client asks for the next page with an encoded cursor query`() = runTest {
        val transport = FakeTransport(
            WireResponse(
                status = 200,
                body = """{"items":[{"id":1,"text":"Namaste"}],"next_cursor":"a b/c"}""",
            ),
        )
        val client = ApiClient(
            config = ApiConfig("https://example.test"),
            transport = transport,
            sleeper = { },
        )

        val page: Page<Row> = client.getPage("/circles/7/posts", cursor = "a b/c")

        assertEquals(1, page.items.size)
        assertEquals(
            "?cursor=a%20b%2Fc",
            transport.sent.single().url.substringAfter("posts"),
            "a cursor is opaque server text and must be percent-encoded",
        )
    }

    @Test
    fun `the first page asks for no cursor at all`() = runTest {
        val transport = FakeTransport(WireResponse(status = 200, body = """{"items":[]}"""))
        val client = ApiClient(
            config = ApiConfig("https://example.test/"),
            transport = transport,
            sleeper = { },
        )

        client.getPage<Row>("/circles/7/posts")

        assertEquals("https://example.test/circles/7/posts", transport.sent.single().url)
    }
}
