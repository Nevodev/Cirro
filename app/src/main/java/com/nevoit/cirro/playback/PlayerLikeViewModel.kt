package com.nevoit.cirro.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.SongLikeRepository
import com.nevoit.cirro.data.model.Session
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class PlayerLikeViewModel(
    private val repository: SongLikeRepository,
    private val account: AccountRepository,
    private val connection: PlaybackConnection,
) : ViewModel() {
    data class UiState(
        val songId: Long? = null,
        val liked: Boolean? = null,
        val busy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private data class PendingWrite(val songId: Long, val userId: Long, val liked: Boolean)

    private val pendingWrite = MutableStateFlow<PendingWrite?>(null)

    init {
        repository.ensureLoaded()
        viewModelScope.launch {
            combine(
                connection.state.map { it.song?.id }.distinctUntilChanged(),
                account.session.map { (it as? Session.LoggedIn)?.profile?.userId?.takeIf { id -> id > 0 } }
                    .distinctUntilChanged(),
                repository.snapshot,
                pendingWrite,
            ) { id, uid, snapshot, pending ->
                val liked = snapshot.takeIf { it.userId == uid }?.likedIds?.let { id in it }
                val write = pending?.takeIf { it.songId == id && it.userId == uid }
                UiState(
                    songId = id,
                    liked = write?.liked ?: liked,
                    busy = write != null || (id != null && id > 0 && uid != null && liked == null),
                )
            }.collect { _state.value = it }
        }
    }

    fun toggle() {
        val before = _state.value
        val id = before.songId ?: return
        if (before.busy || id <= 0 || connection.state.value.song?.id != id) return
        val session = account.session.value
        val uid = (session as? Session.LoggedIn)?.profile?.userId
        if (uid == null || uid <= 0) {
            _messages.trySend(when (session) {
                Session.LoggedOut -> "请先登录网易云音乐"
                Session.Offline -> "暂时无法确认登录状态，请恢复网络后重试"
                else -> "登录状态加载中，请稍后重试"
            })
            return
        }
        if (pendingWrite.value?.let { it.songId == id && it.userId == uid } == true) return
        val previous = repository.isLiked(id, uid) ?: return
        val request = PendingWrite(id, uid, !previous)
        pendingWrite.value = request
        _state.value = before.copy(liked = request.liked, busy = true)
        viewModelScope.launch {
            try {
                val result = repository.setLiked(id, uid, request.liked)
                if (connection.state.value.song?.id == id &&
                    (account.session.value as? Session.LoggedIn)?.profile?.userId == uid) {
                    result.onFailure {
                        _messages.trySend(if (request.liked) "喜欢歌曲失败，请稍后重试" else "取消喜欢失败，请稍后重试")
                    }
                }
            } finally {
                if (pendingWrite.value === request) pendingWrite.value = null
            }
        }
    }
}
