package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SongLikeRepository(
    private val scope: CoroutineScope,
    private val session: StateFlow<Session>,
    private val read: (Long) -> Result<Set<Long>> = { NcmBridge.likedSongIds(it) },
    private val write: (Long, Long, Boolean) -> Result<Unit> = { id, uid, liked ->
        NcmBridge.setSongLiked(id, uid, liked)
    },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val onFailure: (Throwable) -> Unit = {},
) {
    data class Snapshot(val userId: Long? = null, val likedIds: Set<Long>? = null)

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot = _snapshot.asStateFlow()
    private val mutex = Mutex()
    private var loadedAtMs: Long? = null
    private var retryAtMs: Long? = null
    private var retryDelayMs = InitialRetryDelayMs
    private var generation = 0
    private var syncJob: Job? = null

    init {
        scope.launch {
            session.map { it.userIdOrNull() }.distinctUntilChanged().collect { userId ->
                mutex.withLock {
                    if (userId != session.value.userIdOrNull()) return@withLock
                    if (_snapshot.value.userId != userId) changeAccount(userId)
                }
            }
        }
    }

    fun isLiked(id: Long, userId: Long): Boolean? =
        _snapshot.value.takeIf { it.userId == userId && session.value.userIdOrNull() == userId }
            ?.likedIds?.contains(id)

    fun ensureLoaded() = requestSync(onlyMissing = true)

    fun onForeground() = requestSync(onlyMissing = false)

    suspend fun refreshNow(userId: Long): Result<Set<Long>> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (session.value.userIdOrNull() != userId) {
                return@withLock Result.failure(IllegalStateException("账号已切换"))
            }
            if (_snapshot.value.userId != userId) changeAccount(userId)
            val result = read(userId)
            if (session.value.userIdOrNull() != userId) {
                return@withLock Result.failure(IllegalStateException("账号已切换"))
            }
            result.onSuccess { ids ->
                syncJob?.cancel()
                syncJob = null
                loadedAtMs = nowMs()
                retryAtMs = null
                retryDelayMs = InitialRetryDelayMs
                _snapshot.value = Snapshot(userId, ids.toSet())
            }
        }
    }

    private fun requestSync(onlyMissing: Boolean): Job =
        scope.launch {
            mutex.withLock {
                val userId = session.value.userIdOrNull()
                if (_snapshot.value.userId != userId) changeAccount(userId)
                if (userId == null) return@withLock
                val loadedAt = loadedAtMs
                if (loadedAt == null || (!onlyMissing && nowMs() - loadedAt >= RefreshIntervalMs)) {
                    startSync(userId)
                }
            }
        }

    private fun changeAccount(userId: Long?) {
        generation++
        syncJob?.cancel()
        syncJob = null
        _snapshot.value = Snapshot(userId)
        loadedAtMs = null
        retryAtMs = null
        retryDelayMs = InitialRetryDelayMs
        if (userId != null) startSync(userId)
    }

    private fun startSync(userId: Long) {
        if (syncJob?.isActive == true) return
        val request = generation
        syncJob = scope.launch(start = CoroutineStart.LAZY) {
            val worker = currentCoroutineContext()[Job]
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val (result, retryInMs) = mutex.withLock {
                        if (!isCurrent(request, userId)) return@launch
                        val remaining = retryAtMs?.minus(nowMs()) ?: 0L
                        if (remaining > 0) return@withLock null to remaining
                        val result = withContext(Dispatchers.IO) { read(userId) }
                        if (!isCurrent(request, userId)) return@launch
                        if (result.isSuccess) {
                            syncJob = null
                            loadedAtMs = nowMs()
                            retryAtMs = null
                            retryDelayMs = InitialRetryDelayMs
                            _snapshot.value = Snapshot(userId, result.getOrThrow().toSet())
                        } else {
                            retryAtMs = nowMs() + retryDelayMs
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(MaxRetryDelayMs)
                        }
                        result to (retryAtMs?.minus(nowMs()) ?: 0L).coerceAtLeast(1L)
                    }
                    if (result?.isSuccess == true) return@launch
                    result?.exceptionOrNull()?.let(onFailure)
                    // 退避在锁外等待，不能让后台重试挡住用户的喜欢 / 取消喜欢操作。
                    wait(retryInMs)
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (syncJob === worker) syncJob = null
                    }
                }
            }
        }.also { it.start() }
    }

    private fun isCurrent(request: Int, userId: Long): Boolean =
        request == generation && _snapshot.value.userId == userId &&
            session.value.userIdOrNull() == userId

    // JNI 写请求无法取消，同步读取必须等写入完成，才不会用旧列表覆盖新状态。
    suspend fun setLiked(id: Long, userId: Long, liked: Boolean): Result<Unit> =
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                if (_snapshot.value.userId != userId || session.value.userIdOrNull() != userId) {
                    return@withLock Result.failure(IllegalStateException("登录账号已变化"))
                }
                val result = write(id, userId, liked)
                if (result.isSuccess) {
                    _snapshot.value.likedIds?.let { ids ->
                        _snapshot.value = Snapshot(userId, if (liked) ids + id else ids - id)
                    }
                } else {
                    // 超时不能证明服务端没写入。保留已知状态，后台重新确认完整列表。
                    loadedAtMs = null
                    startSync(userId)
                }
                result
            }
        }

    private fun Session.userIdOrNull(): Long? =
        (this as? Session.LoggedIn)?.profile?.userId?.takeIf { it > 0 }

    private companion object {
        const val RefreshIntervalMs = 5 * 60 * 1000L
        const val InitialRetryDelayMs = 1000L
        const val MaxRetryDelayMs = 30_000L
    }
}
