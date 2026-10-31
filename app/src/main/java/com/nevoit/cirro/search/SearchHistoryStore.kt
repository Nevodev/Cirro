package com.nevoit.cirro.search

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.nevoit.cirro.data.model.SearchCandidate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal fun updatedSearchHistory(previous: List<SearchCandidate>, item: SearchCandidate): List<SearchCandidate> {
    val entry = when (item) {
        is SearchCandidate.Keyword -> item.copy(text = item.text.trim()).takeIf { it.text.isNotEmpty() }
        is SearchCandidate.Track -> item.copy(songIndex = 0).takeIf { it.song.id > 0 }
        is SearchCandidate.Collection -> item.takeIf { it.info.id > 0 }
        is SearchCandidate.Video -> item.takeIf { it.video.id > 0 }
    } ?: return previous
    fun identity(item: SearchCandidate): String = when (item) {
        is SearchCandidate.Keyword -> "keyword:${item.text.lowercase(Locale.ROOT)}"
        else -> item.key
    }
    val key = identity(entry)
    return (listOf(entry) + previous.filterNot { identity(it) == key }).take(20)
}

internal fun decodeSearchHistory(raw: String): Map<String, List<SearchCandidate>> =
    Json.parseToJsonElement(raw).jsonObject.mapValues { (_, entries) ->
        entries.jsonArray.map { item ->
            // Existing histories contain strings; retain them as keyword entries.
            if (item is JsonPrimitive && item.isString) SearchCandidate.Keyword(item.content)
            else Json.decodeFromJsonElement<SearchCandidate>(item)
        }
    }

internal fun searchHistoryKey(owner: Long, scope: SearchScope): String = "$owner:${scope.name}"

internal class SearchHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences("cirro_search", Context.MODE_PRIVATE) }
    private val mutex = Mutex()
    private var loaded = false
    private val _entries = MutableStateFlow<Map<String, List<SearchCandidate>>>(emptyMap())
    val entries = _entries.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }

    suspend fun record(owner: Long, scope: SearchScope, item: SearchCandidate) = change(owner, scope) {
        updatedSearchHistory(it, item)
    }

    suspend fun clear(owner: Long, scope: SearchScope) = change(owner, scope) { emptyList() }

    private suspend fun change(owner: Long, scope: SearchScope, update: (List<SearchCandidate>) -> List<SearchCandidate>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                loadLocked()
                val key = searchHistoryKey(owner, scope)
                val entries = _entries.value + (key to update(_entries.value[key].orEmpty()))
                _entries.value = entries
                preferences.edit { putString("history", Json.encodeToString(entries)) }
            }
        }

    private fun loadLocked() {
        if (loaded) return
        _entries.value = try {
            preferences.getString("history", null)?.let {
                decodeSearchHistory(it)
            }.orEmpty()
        } catch (error: Exception) {
            Log.w("SearchHistory", "搜索历史读取失败", error)
            emptyMap()
        }
        loaded = true
    }
}
