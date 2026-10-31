package com.nevoit.cirro.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.model.SearchCandidates
import com.nevoit.cirro.data.model.SearchCandidate
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.forSession
import com.nevoit.cirro.data.model.appendResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SearchRequest(
    val query: String = "",
    val scope: SearchScope = SearchScope.NETEASE,
    val active: Boolean = false,
    val revision: Int = 0,
    val submitted: Boolean = false,
    val submission: Int = 0,
    val category: SearchCategory = SearchCategory.BEST,
)

internal data class SearchResult(
    val request: SearchRequest = SearchRequest(),
    val candidates: SearchCandidates = SearchCandidates(),
    val loading: Boolean = false,
    val message: String? = null,
    val nextOffsets: Map<String, Int> = emptyMap(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val moreMessage: String? = null,
) {
    val hasMore: Boolean get() = nextCursor != null || nextOffsets.isNotEmpty()
}

internal class SearchViewModel(
    private val account: AccountRepository,
    library: LibraryRepository,
    private val historyStore: SearchHistoryStore,
    collections: CollectionRepository,
) : ViewModel() {
    private val coverLoader = SearchCoverLoader(
        loadSongs = { ids -> withContext(Dispatchers.IO) { NcmBridge.songDetail(ids) } },
        loadCollection = { info ->
            if (info.kind == CollectionKind.ARTIST) {
                withContext(Dispatchers.IO) { NcmBridge.artistCover(info.id) }
            } else collections.info(info.kind, info.id).map { it.cover }
        },
    )
    private val request = MutableStateFlow(SearchRequest())
    private val resultCache = SearchResultCache()
    private var generation = 0L
    private var moreJob: Job? = null
    private val _result = MutableStateFlow(SearchResult())
    val result = _result.asStateFlow()
    private val owner = combine(account.session, account.profile) { session, profile ->
        if (session == Session.LoggedOut) 0L else profile?.userId ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Eagerly, currentOwner())

    val history = combine(request.map { it.scope }.distinctUntilChanged(), owner, historyStore.entries) {
            scope, ownerId, entries -> entries[searchHistoryKey(ownerId, scope)].orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { historyStore.load() }
        viewModelScope.launch {
            var index: LibrarySearchIndex? = null
            combine(request, library.content, account.session, owner) { request, content, session, ownerId ->
                Triple(request, if (request.scope == SearchScope.LIBRARY) content?.forSession(session) else null, ownerId)
            }.distinctUntilChanged().collectLatest { (request, content, ownerId) ->
                generation++
                moreJob?.cancel()
                val cached = resultCache.get(request, ownerId)
                _result.value = cached?.copy(candidates = coverLoader.cached(cached.candidates))
                    ?: SearchResult(request, loading = request.active && request.query.isNotBlank())
                if (!request.active || request.query.isBlank()) return@collectLatest
                if (!request.submitted) delay(if (request.scope == SearchScope.NETEASE) 250 else 60)
                try {
                    if (cached == null) {
                        var nextCursor: String? = null
                        val (candidates, message, offsets) = if (request.scope == SearchScope.NETEASE && request.submitted) {
                            val page = withContext(Dispatchers.IO) {
                                NcmBridge.searchResults(request.query.trim(), request.category.wireType).getOrThrow()
                            }
                            nextCursor = page.nextCursor
                            Triple(page.candidates, null, page.nextOffsets)
                        } else if (request.scope == SearchScope.NETEASE) {
                            val (found, partial) = withContext(Dispatchers.IO) {
                                NcmBridge.searchCandidates(request.query.trim()).getOrThrow()
                            }
                            Triple(found, if (partial) "部分候选暂不可用，点按重试" else null, emptyMap())
                        } else if (content == null) {
                            Triple(SearchCandidates(), if (account.session.value == Session.LoggedOut) {
                                "登录后搜索资料库"
                            } else "资料库正在准备，请稍候", emptyMap())
                        } else {
                            withContext(Dispatchers.Default) {
                                val current = index?.takeIf { it.source === content }
                                    ?: LibrarySearchIndex.build(content).also { index = it }
                                Triple(request.category.filter(current.find(request.query)), null, emptyMap())
                            }
                        }
                        // JNI is blocking: a cancelled request may finish after the query or account changes.
                        currentCoroutineContext().ensureActive()
                        publish(SearchResult(request, coverLoader.cached(candidates), message = message,
                            nextOffsets = offsets, nextCursor = nextCursor), ownerId)
                    }
                    if (request.scope == SearchScope.NETEASE) {
                        coverLoader.load(_result.value.candidates) {
                            publish(_result.value.copy(candidates = coverLoader.cached(_result.value.candidates)), ownerId)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("Search", "Search failed: scope=${request.scope}, category=${request.category}, submitted=${request.submitted}", error)
                    _result.value = SearchResult(request, message = if (request.submitted) "搜索未能完成，点按重试" else "候选未能加载，点按重试")
                }
            }
        }
    }

    fun search(
        query: String,
        scope: SearchScope,
        active: Boolean,
        submitted: Boolean,
        submission: Int,
        category: SearchCategory = SearchCategory.BEST,
    ) {
        request.value = SearchRequest(
            query, scope, active, submitted = submitted, submission = submission,
            category = if (submitted) category else SearchCategory.BEST,
        )
    }

    fun loadMore() {
        val before = _result.value
        if (!before.request.submitted || before.request.scope != SearchScope.NETEASE ||
            before.loading || before.loadingMore || !before.hasMore
        ) return
        val currentGeneration = generation
        val ownerId = currentOwner()
        _result.value = before.copy(loadingMore = true, moreMessage = null)
        moreJob = viewModelScope.launch {
            try {
                val pages = if (before.nextCursor != null) {
                    listOf("best" to withContext(Dispatchers.IO) {
                        NcmBridge.searchResults(before.request.query, cursor = before.nextCursor)
                    })
                } else coroutineScope {
                    before.nextOffsets.map { (type, offset) -> async(Dispatchers.IO) {
                        val wire = SearchCategory.entries.first { it.resultKey == type }.wireType
                        type to NcmBridge.searchResults(before.request.query, wire, offset)
                    } }.awaitAll()
                }
                currentCoroutineContext().ensureActive()
                if (currentGeneration != generation) return@launch
                var candidates = _result.value.candidates
                val offsets = before.nextOffsets.toMutableMap()
                var nextCursor = before.nextCursor
                var failed = false
                for ((type, result) in pages) {
                    result.fold(onSuccess = { page ->
                        candidates = candidates.appendResults(page.candidates)
                        offsets.remove(type)
                        offsets.putAll(page.nextOffsets)
                        if (type == "best") nextCursor = page.nextCursor
                    }, onFailure = { failed = true })
                }
                publish(_result.value.copy(
                    candidates = coverLoader.cached(candidates), nextOffsets = offsets, nextCursor = nextCursor, loadingMore = false,
                    moreMessage = if (failed) "部分结果未能加载，点按重试" else null,
                ), ownerId)
                coverLoader.load(candidates) {
                    if (currentGeneration == generation) {
                        publish(_result.value.copy(candidates = coverLoader.cached(_result.value.candidates)), ownerId)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentGeneration == generation) {
                    publish(_result.value.copy(loadingMore = false, moreMessage = "更多结果未能加载，点按重试"), ownerId)
                }
            }
        }
    }

    private fun publish(result: SearchResult, ownerId: Long) {
        resultCache.put(result, ownerId)
        _result.value = result
    }

    fun retry() {
        resultCache.invalidate(request.value.category)
        request.value = request.value.copy(revision = request.value.revision + 1)
    }

    fun rememberQuery(query: String) {
        rememberItem(SearchCandidate.Keyword(query))
    }

    fun rememberItem(item: SearchCandidate) {
        val ownerId = currentOwner()
        val scope = request.value.scope
        viewModelScope.launch { historyStore.record(ownerId, scope, item) }
    }

    fun clearHistory() {
        val ownerId = currentOwner()
        val scope = request.value.scope
        viewModelScope.launch { historyStore.clear(ownerId, scope) }
    }

    private fun currentOwner(): Long =
        if (account.session.value == Session.LoggedOut) 0L else account.profile.value?.userId ?: 0L
}
