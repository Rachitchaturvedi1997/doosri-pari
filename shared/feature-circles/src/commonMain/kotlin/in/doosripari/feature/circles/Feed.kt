package `in`.doosripari.feature.circles

import `in`.doosripari.core.model.ApiError
import `in`.doosripari.core.model.Page
import `in`.doosripari.core.model.Post
import `in`.doosripari.core.model.PostKind
import kotlinx.datetime.Instant

/**
 * The circle feed. Plan section 6.6.
 *
 * Two rules from that section shape everything here, and they pull against
 * each other:
 *
 *  - **newest first, no engagement ranking.** Nothing is promoted because it
 *    attracted replies. A post is worth reading because it is recent or it is
 *    from somebody you know, and the second of those is a *label*, not a sort.
 *  - **a cursor, not a page number**, so a post arriving while the user
 *    scrolls cannot make them skip a row.
 *
 * The consequence of a cursor is that pages are appended, and appending is
 * where feeds go wrong: a post already on screen reappears, or the list is
 * re-sorted and everything the user is looking at jumps. [Feed.appendPage] is
 * therefore a *stable merge* — a post that is already present is never moved
 * and never duplicated, and a new post slots into the one position its
 * timestamp earns without disturbing anything above it.
 *
 * Nothing in this file is `@Serializable`. The wire mirror with
 * `@SerialName("snake_case")` belongs in the platform data layer, as
 * `core-model`'s file header sets out.
 */

/**
 * The five things a user can narrow the feed to.
 *
 * [Media] is "has a photo", not "kind is [PostKind.Image]": section 6.6 allows
 * text with up to six photos attached, so a post can be a sentence *and* carry
 * pictures. Filtering on the kind would hide every one of those from the photo
 * tab, which is the tab people open when they want to see faces.
 */
enum class FeedFilter(val code: String) {
    All("all"),
    Text("text"),
    Voice("voice"),
    Media("media"),
    /** "Mine" needs the viewer, so [matches] takes their id. */
    Mine("mine");

    fun matches(post: Post, viewerId: Int): Boolean = when (this) {
        All -> true
        Text -> post.kind == PostKind.Text
        Voice -> post.kind == PostKind.Voice
        Media -> post.media.isNotEmpty()
        Mine -> post.authorId == viewerId
    }

    companion object {
        /**
         * Falls back to [All] rather than throwing: a filter chip this app has
         * not shipped yet must not empty somebody's feed.
         */
        fun fromCode(code: String?): FeedFilter =
            entries.firstOrNull { it.code == code } ?: All
    }
}

/** What the feed is doing. A sealed type so no screen has to guess a boolean. */
sealed interface FeedStatus {
    /** Nothing in flight. */
    data object Idle : FeedStatus

    /** The first page is coming. The current posts stay on screen meanwhile. */
    data object Loading : FeedStatus

    /** A further page is coming, below the fold. */
    data object LoadingMore : FeedStatus

    /**
     * The server has nothing more. This is the state plan section 6.6 wants to
     * say "You're all caught up. See you tomorrow." at, once the reader has
     * had [CAUGHT_UP_AFTER] posts — a scrollbar that keeps asking for more is
     * exactly the infinite-scroll habit the section is trying not to build.
     */
    data object CaughtUp : FeedStatus

    /**
     * [isFirstPage] distinguishes the two failures a user experiences very
     * differently: a failed first page is an empty screen with a retry, and a
     * failed second page is a perfectly good list with a "try again" under it.
     */
    data class Failed(val error: ApiError, val isFirstPage: Boolean) : FeedStatus
}

/**
 * A pinned row for the day. Plan section 6.6 pins birthdays and retirements.
 *
 * It is a separate list from [FeedState.posts] because it is not part of the
 * paged feed, is the same for everybody in the circle, and is not a post at
 * all — putting it in the paged list would mean the server has to interleave it
 * on every page and the merge would have to special-case it.
 */
data class FeedPin(
    val userId: Int,
    val kind: PinKind,
    /** Pre-rendered in the user's language by the caller, or left to the UI. */
    val label: String? = null,
)

enum class PinKind(val code: String) {
    Birthday("birthday"),
    Retirement("retirement");

    companion object {
        fun fromCode(code: String?): PinKind =
            entries.firstOrNull { it.code == code } ?: Birthday
    }
}

/**
 * The feed, as one immutable value.
 *
 * [posts] is the *merged, ordered* list and is the only thing the list UI
 * should render rows from, with a stable key of the post id. Rendering from a
 * separately filtered copy is what causes a list to jump.
 */
data class FeedState(
    val circleId: Int,
    val viewerId: Int,
    val posts: List<Post> = emptyList(),
    val pinned: List<FeedPin> = emptyList(),
    val filter: FeedFilter = FeedFilter.All,
    val status: FeedStatus = FeedStatus.Idle,
    val nextCursor: String? = null,
    /**
     * Author ids this user interacted with in the last 30 days.
     *
     * Plan section 6.6 asks for "a small boost for people you interacted with".
     * It is carried as a *marker* and never as a sort key, on purpose. A boost
     * that reorders the list is engagement ranking by another name, and the
     * same section forbids that outright. The UI shows it as a line of text
     * ("you both commented last week") and the order stays chronological.
     */
    val recentlyInteractedWith: Set<Int> = emptySet(),
) {
    /**
     * What the list should actually draw: not hidden, and matching the filter.
     *
     * A hidden post is kept in [posts] rather than dropped, because a moderator
     * looking at the same feed still has to be able to see what was held —
     * `core-model`'s [Post.isVisible] exists for exactly that split.
     */
    val visible: List<Post>
        get() = posts.filter { it.isVisible && filter.matches(it, viewerId) }

    val hasMore: Boolean get() = nextCursor != null

    /** False while a page is already in flight, so a scroll cannot queue three. */
    val canLoadMore: Boolean
        get() = nextCursor != null &&
            status != FeedStatus.Loading &&
            status != FeedStatus.LoadingMore

    val isCaughtUp: Boolean get() = status == FeedStatus.CaughtUp

    /** The plan's threshold for saying "You're all caught up." */
    val showsCaughtUpNotice: Boolean
        get() = isCaughtUp && visible.size >= Feed.CAUGHT_UP_AFTER

    fun isPinned(userId: Int): Boolean = pinned.any { it.userId == userId }

    /** See [recentlyInteractedWith] for why this is a label and not a sort. */
    fun isRecentlyInteracted(post: Post): Boolean =
        post.authorId in recentlyInteractedWith
}

/**
 * The feed transitions, as pure functions over [FeedState].
 *
 * Pure for the same reason `EscalationEngine` is: the merge rule is the part
 * most likely to be got wrong, and a pure function is the part that can be
 * tested with a fake clock and no server.
 */
object Feed {

    /**
     * Plan section 6.6: after thirty posts, stop and say so.
     *
     * Thirty is a lot of reading for this audience and the line is the whole
     * point of the section — a feed that never ends is the habit the product
     * has decided not to build.
     */
    const val CAUGHT_UP_AFTER: Int = 30

    /**
     * Begin loading the first page.
     *
     * The existing posts are kept. Clearing them would blank the screen a
     * pensioner is halfway through reading, and a refresh that loses your
     * place is the fastest way to teach somebody not to refresh.
     */
    fun startLoading(state: FeedState): FeedState =
        state.copy(status = FeedStatus.Loading)

    /**
     * Start loading the *next* page. A no-op when one is already in flight or
     * the cursor is spent, so a bouncing scrollbar cannot queue five requests.
     */
    fun startLoadingMore(state: FeedState): FeedState =
        if (state.canLoadMore) state.copy(status = FeedStatus.LoadingMore) else state

    /**
     * Replace everything — a new circle, a new filter, or a cold start.
     *
     * This one *does* replace rather than merges, because there is nothing
     * worth preserving. It is the only path that may reorder.
     */
    fun reset(state: FeedState): FeedState = state.copy(
        posts = emptyList(),
        status = FeedStatus.Idle,
        nextCursor = null,
    )

    /**
     * The first page, which is authoritative and replaces what was there.
     *
     * Sorted here because on a cold feed there is no "already shown" to
     * preserve — this is the one place where ordering from scratch is correct.
     */
    fun firstPage(state: FeedState, page: Page<Post>): FeedState {
        val ordered = page.items
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Post> { it.createdAt }.thenByDescending { it.id })
        return state.copy(
            posts = ordered,
            status = if (page.hasMore) FeedStatus.Idle else FeedStatus.CaughtUp,
            nextCursor = page.nextCursor,
        )
    }

    /**
     * Append the next page. The rule the whole file exists for.
     *
     * Guarantees, in the order a user would notice a breach of them:
     *
     *  1. a post already in [FeedState.posts] does not appear twice;
     *  2. a post already in the list does not move — the relative order of
     *     everything on screen is identical before and after;
     *  3. a new post lands at the position its [Post.createdAt] earns, so a
     *     server that returns a page slightly out of order still produces a
     *     correctly ordered list.
     *
     * A duplicate is ignored rather than replaced. A page of history carries
     * the post as it was *then*; a live edit arrives through [upsert] or a
     * push, and letting a slow page overwrite a fresher row is how a comment
     * count goes backwards under somebody's thumb.
     */
    fun appendPage(state: FeedState, page: Page<Post>): FeedState {
        val merged = mergeNewestFirst(state.posts, page.items)
        return state.copy(
            posts = merged,
            status = if (page.hasMore) FeedStatus.Idle else FeedStatus.CaughtUp,
            nextCursor = page.nextCursor,
        )
    }

    /**
     * Apply changes that arrived while the user was reading — a new post, an
     * edited one, a comment count that moved.
     *
     * Content is updated **in place**: the row keeps its position, so an edit
     * to a post at the top of the screen does not renumber everything under
     * it. Genuinely new posts go through the same stable insert as
     * [appendPage].
     */
    fun upsert(state: FeedState, updates: List<Post>): FeedState {
        if (updates.isEmpty()) return state
        val byId = updates.associateBy { it.id }
        val replaced = state.posts.map { post -> byId[post.id] ?: post }
        val existing = state.posts.mapTo(HashSet()) { it.id }
        val brandNew = updates.filterNot { it.id in existing }
        return state.copy(posts = mergeNewestFirst(replaced, brandNew))
    }

    /** The page failed. Which kind of failure decides which retry the UI shows. */
    fun failed(state: FeedState, error: ApiError): FeedState = state.copy(
        status = FeedStatus.Failed(error, isFirstPage = state.posts.isEmpty()),
    )

    /**
     * Switch the chip.
     *
     * The loaded posts are kept so the screen does not blank while the new
     * filter's first page comes back, and the cursor is dropped because it
     * belongs to the *old* filter's page sequence — paging on from it would
     * splice two unrelated lists together.
     */
    fun applyFilter(state: FeedState, filter: FeedFilter): FeedState {
        if (filter == state.filter) return state
        return state.copy(
            filter = filter,
            nextCursor = null,
            status = FeedStatus.Idle,
        )
    }

    /** Plan section 6.6's pinned rows, replaced wholesale for the day. */
    fun pin(state: FeedState, pins: List<FeedPin>): FeedState =
        state.copy(pinned = pins)

    /**
     * The stable merge.
     *
     * [incoming] is assumed to be a page of history — mostly older than what
     * is on screen — so the common case is a single append at the end. The
     * scan is linear rather than a binary search because it also has to be
     * correct when the server interleaves, and because a page is tens of rows:
     * the cost is invisible next to the network call.
     */
    internal fun mergeNewestFirst(existing: List<Post>, incoming: List<Post>): List<Post> {
        if (incoming.isEmpty()) return existing
        val out = existing.toMutableList()
        val seen = existing.mapTo(HashSet()) { it.id }
        for (post in incoming) {
            // Guard 1: never twice.
            if (!seen.add(post.id)) continue
            // Guard 2: insert before the first row that is strictly newer, which
            // is the only position that is not a reordering of what is above it.
            val at = out.indexOfFirst { isNewer(it, post) }
            if (at < 0) out.add(post) else out.add(at, post)
        }
        return out
    }
}

/**
 * Total order on posts: newest first, higher id wins a tie.
 *
 * The tie-break is not cosmetic. Two posts written in the same second are
 * common when a list is imported or a circle is seeded, and without a second
 * key their relative order would depend on the sort's stability, which differs
 * between Kotlin/Native and Kotlin/JVM. The same feed must not read in a
 * different order on an iPhone than on an Android phone.
 */
internal fun isNewer(a: Post, b: Post): Boolean =
    a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.id > b.id)
