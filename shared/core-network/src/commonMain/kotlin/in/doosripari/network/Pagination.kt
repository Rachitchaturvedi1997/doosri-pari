package `in`.doosripari.network

import `in`.doosripari.core.model.Page
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cursor paging over `core-model`'s [Page]. Plan section 6.6.
 *
 * ## Cursor, not page number
 *
 * Plan section 6.6 pages the feed with a cursor "so a post arriving while the
 * user scrolls cannot make them skip a row". With `?page=2`, a post that lands
 * between the user's first and second request shifts everything down by one and
 * a row is never shown — silently, which is worse than a visible gap. A cursor
 * points at the last row actually seen, so a new post appears at the top of the
 * next page and nothing is skipped.
 *
 * ## The feed is calm on purpose
 *
 * Plan section 6.6 also asks for newest first, no engagement ranking, and a
 * "You're all caught up. See you tomorrow." after about thirty posts.
 * [PagedFeed] is where that is enforced on the client: it appends,
 * de-duplicates by a caller-supplied key, and refuses to load past [maxItems].
 * A feed that can grow without bound is a feed that becomes a habit rather than
 * a check — which is the opposite of what plan section 1 asks the app to be.
 *
 * ## The wire shape — assumed, and stated once
 *
 * `{"items": [...], "next_cursor": "..."}`, and `GET <path>?cursor=<value>` for
 * the page after. **The plan does not specify either.** They are written down
 * here and in [ApiClient.getPage] so that a server which disagrees needs a
 * change in exactly two lines rather than a hunt. `core-model`'s [Page] is a
 * plain data class and stays that way; the serializable version is [PageDto],
 * which is `internal` to this module.
 */

/**
 * The wire shape of a page. `internal`: no feature module may name it.
 *
 * `@PublishedApi` only because [ApiClient.getPage] is an inline function whose
 * body decodes into it. The type stays invisible outside this module.
 */
@PublishedApi
@Serializable
internal data class PageDto<T>(
    @SerialName("items") val items: List<T> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** [PageDto] to the domain [Page]. */
@PublishedApi
internal fun <T> PageDto<T>.toPage(): Page<T> = Page(items = items, nextCursor = nextCursor)

/**
 * A feed assembled from cursor pages, with de-duplication.
 *
 * Mutable state plus a loader function, so it is testable with a list of canned
 * pages and no server. [keyOf] is what makes the de-duplication work: without
 * it, a post that lands at the end of page 2 and also at the top of page 3 —
 * which happens whenever somebody posts while the user is scrolling — is
 * rendered twice.
 */
class PagedFeed<T, K>(
    private val loadPage: suspend (cursor: String?) -> Page<T>,
    private val keyOf: (T) -> K,
    /** Plan section 6.6: stop around here rather than becoming a habit. */
    private val maxItems: Int = DEFAULT_MAX_ITEMS,
) {
    private val items = mutableListOf<T>()
    private val seen = mutableSetOf<K>()

    /** Cursor for the next request; null once the server said there is no more. */
    var nextCursor: String? = null
        private set

    /** True once the server returned a page with no `next_cursor`. */
    var isExhausted: Boolean = false
        private set

    /** True once [maxItems] have been collected. */
    val isAtLimit: Boolean get() = items.size >= maxItems

    /** True when there is more to fetch and we are still allowed to fetch it. */
    val hasMore: Boolean get() = !isExhausted && !isAtLimit

    /** Everything gathered so far, newest first as the server ordered it. */
    val snapshot: List<T> get() = items.toList()

    /** How many items the last [loadMore] actually added. Zero means stop. */
    suspend fun loadMore(): Int {
        if (!hasMore) return 0
        val page = loadPage(nextCursor)

        var added = 0
        for (item in page.items) {
            if (isAtLimit) break
            if (seen.add(keyOf(item))) {
                items += item
                added++
            }
        }

        // Advance the cursor even when nothing new was added. A page of
        // entirely-already-seen rows still moved the server's window forward,
        // and refusing to advance would loop on the same cursor for ever.
        nextCursor = page.nextCursor
        if (page.nextCursor == null) isExhausted = true
        return added
    }

    /**
     * Drop everything and start again.
     *
     * Needed after a pull-to-refresh: the feed is newest-first, so a refresh is
     * not "prepend" — it is a new query from a null cursor.
     */
    fun reset() {
        items.clear()
        seen.clear()
        nextCursor = null
        isExhausted = false
    }

    companion object {
        /**
         * Plan section 6.6 shows thirty posts and then "You're all caught up."
         * Slightly over, because a page boundary will not land on thirty.
         */
        const val DEFAULT_MAX_ITEMS: Int = 40
    }
}
