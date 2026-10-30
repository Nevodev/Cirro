package com.nevoit.cirro.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.SongLikeRepository
import com.nevoit.cirro.data.model.Playlist
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SongMenuViewModel(
    private val account: AccountRepository,
    private val repository: CollectionRepository,
    private val likes: SongLikeRepository,
) : ViewModel() {
    data class PendingLike(val songId: Long, val userId: Long, val liked: Boolean)

    data class UiState(
        val pendingLike: PendingLike? = null,
        val playlists: List<Playlist>? = null,
        val loadingPlaylists: Boolean = false,
        val playlistError: String? = null,
        val addingPlaylistId: Long? = null,
    )

    val session = account.session
    val likeSnapshot = likes.snapshot
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private var playlistsJob: Job? = null
    private var playlistGeneration = 0
    private var pendingLike: PendingLike? = null
    private var addingRequest: Any? = null
    private var reducingSongId: Long? = null

    init {
        likes.ensureLoaded()
        viewModelScope.launch {
            session.map { it.userIdOrNull() }.distinctUntilChanged().collect {
                playlistGeneration++
                playlistsJob?.cancel()
                pendingLike = null
                addingRequest = null
                _state.value = UiState()
            }
        }
    }

    fun toggleLike(songId: Long) {
        val uid = requireUserId() ?: return
        if (pendingLike != null) return
        val liked = likes.isLiked(songId, uid) ?: run {
            likes.ensureLoaded()
            _messages.trySend("喜爱状态加载中，请稍后重试")
            return
        }
        val request = PendingLike(songId, uid, !liked)
        pendingLike = request
        _state.update { it.copy(pendingLike = request) }
        viewModelScope.launch {
            try {
                likes.setLiked(songId, uid, request.liked).onFailure {
                    if (session.value.userIdOrNull() == uid) {
                        _messages.trySend(if (request.liked) "喜爱歌曲失败，请稍后重试" else "取消喜爱失败，请稍后重试")
                    }
                }
            } finally {
                if (pendingLike === request) {
                    pendingLike = null
                    _state.update { it.copy(pendingLike = null) }
                }
            }
        }
    }

    suspend fun navigationSong(song: Song): Song =
        repository.songMetadata(song.id).getOrNull() ?: song

    fun reduceRecommendation(songId: Long) {
        val uid = requireUserId() ?: return
        if (reducingSongId != null) return
        reducingSongId = songId
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { NcmBridge.reduceRecommendation(songId) }
                if (session.value.userIdOrNull() != uid) return@launch
                _messages.trySend(if (result.isSuccess) "已减少推荐" else "操作失败，请稍后重试")
            } finally {
                reducingSongId = null
            }
        }
    }

    fun loadPlaylists() {
        val uid = requireUserId() ?: return
        if (_state.value.loadingPlaylists) return
        val generation = ++playlistGeneration
        _state.update { it.copy(loadingPlaylists = true, playlistError = null, playlists = null) }
        playlistsJob = viewModelScope.launch {
            if (generation != playlistGeneration || session.value.userIdOrNull() != uid) return@launch
            val result = repository.ownedPlaylists(uid)
            if (generation != playlistGeneration || session.value.userIdOrNull() != uid) return@launch
            _state.update {
                it.copy(
                    loadingPlaylists = false,
                    playlists = result.getOrNull(),
                    playlistError = result.exceptionOrNull()?.message ?: if (result.isFailure) "加载播放列表失败" else null,
                )
            }
        }
    }

    fun addToPlaylist(songId: Long, playlist: Playlist, onAdded: () -> Unit) {
        val uid = requireUserId() ?: return
        if (addingRequest != null || _state.value.playlists?.any { it.id == playlist.id } != true) return
        val request = Any()
        addingRequest = request
        _state.update { it.copy(addingPlaylistId = playlist.id) }
        viewModelScope.launch {
            try {
                if (addingRequest !== request || session.value.userIdOrNull() != uid) return@launch
                val result = repository.addSongToPlaylist(songId, playlist.id)
                if (addingRequest !== request || session.value.userIdOrNull() != uid) return@launch
                result.onSuccess {
                    _messages.trySend("已添加到「${playlist.name}」")
                    onAdded()
                }.onFailure {
                    _messages.trySend(it.message ?: "添加到播放列表失败，请稍后重试")
                }
            } finally {
                if (addingRequest === request) {
                    addingRequest = null
                    _state.update { it.copy(addingPlaylistId = null) }
                }
            }
        }
    }

    private fun requireUserId(): Long? {
        val session = session.value
        val uid = session.userIdOrNull()
        if (uid == null) {
            _messages.trySend(when (session) {
                Session.LoggedOut -> "请先登录网易云音乐"
                Session.Offline -> "暂时无法确认登录状态，请恢复网络后重试"
                else -> "登录状态加载中，请稍后重试"
            })
        }
        return uid
    }

    private fun Session.userIdOrNull(): Long? =
        (this as? Session.LoggedIn)?.profile?.userId?.takeIf { it > 0 }
}
