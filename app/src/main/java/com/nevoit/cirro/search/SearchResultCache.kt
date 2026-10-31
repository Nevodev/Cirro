package com.nevoit.cirro.search

internal class SearchResultCache {
    private data class SearchKey(val query: String, val submission: Int, val ownerId: Long)

    private var search: SearchKey? = null
    private val results = mutableMapOf<SearchCategory, SearchResult>()

    fun get(request: SearchRequest, ownerId: Long): SearchResult? {
        val key = key(request, ownerId)
        if (key != search) {
            search = key
            results.clear()
        }
        return results[request.category]?.copy(request = request)
    }

    fun put(result: SearchResult, ownerId: Long) {
        val key = key(result.request, ownerId) ?: return
        if (key != search || result.loading || result.message != null) return
        results[result.request.category] = result.copy(loadingMore = false)
    }

    fun invalidate(category: SearchCategory) {
        results.remove(category)
    }

    private fun key(request: SearchRequest, ownerId: Long): SearchKey? =
        if (request.active && request.submitted && request.scope == SearchScope.NETEASE && request.query.isNotBlank()) {
            SearchKey(request.query.trim(), request.submission, ownerId)
        } else null
}
