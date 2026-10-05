package com.nevoit.cirro.login

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.QrCheckStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface QrLoginState {
    data object Loading : QrLoginState
    data class Waiting(val remainingSeconds: Int) : QrLoginState
    data object Scanned : QrLoginState
    data object Expired : QrLoginState
    data object Confirmed : QrLoginState
    data class Failed(val message: String) : QrLoginState
}

/**
 * 扫码登录的唯一真相源。
 *
 * 一个 unikey 从生成起只有约 3 分钟寿命，所以 [waitForScan] 里的轮询窗口和倒计时用同一个
 * 常量，别只改一处。
 *
 * bridge 的 JNI 调用在 Rust 侧是 `block_on` 阻塞等待，必须挪到 IO 线程，
 * 否则轮询每 2 秒卡一次主线程。
 */
class QrLoginViewModel(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    /** 当前二维码内容，过期后仍保留，方便界面上盖一层遮罩。 */
    var qrUrl by mutableStateOf<String?>(null)
        private set

    var state by mutableStateOf<QrLoginState>(QrLoginState.Loading)
        private set

    private var pollJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch { login() }
    }

    fun refresh() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch { login() }
    }

    private suspend fun login() {
        state = QrLoginState.Loading
        qrUrl = null

        val key = withContext(Dispatchers.IO) { NcmBridge.qrKey() }.getOrElse { error ->
            state = QrLoginState.Failed(error.message ?: "获取二维码失败")
            return
        }
        val url = withContext(Dispatchers.IO) { NcmBridge.qrCreate(key) }.getOrElse { error ->
            state = QrLoginState.Failed(error.message ?: "生成二维码失败")
            return
        }
        qrUrl = url
        state = QrLoginState.Waiting(LIFETIME_SECONDS)

        waitForScan(key)
    }

    private suspend fun waitForScan(key: String) {
        var remaining = LIFETIME_SECONDS

        while (remaining > 0) {
            delay(POLL_INTERVAL_MILLIS)
            remaining -= POLL_INTERVAL_SECONDS

            val status = withContext(Dispatchers.IO) { NcmBridge.qrCheck(key) }.getOrElse { error ->
                state = QrLoginState.Failed(error.message ?: "二维码状态查询失败")
                return
            }

            when (status.code) {
                QrCheckStatus.CONFIRMED -> {
                    // 会话落地交给仓库（跑在 App 级作用域里），本 ViewModel 随 Activity 一起死也没关系
                    accountRepository.onLoginSucceeded(status.cookie)
                    state = QrLoginState.Confirmed
                    return
                }

                QrCheckStatus.EXPIRED -> {
                    state = QrLoginState.Expired
                    return
                }

                QrCheckStatus.SCANNED -> state = QrLoginState.Scanned

                else -> state = QrLoginState.Waiting(remaining.coerceAtLeast(0))
            }
        }

        state = QrLoginState.Expired
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L
        const val POLL_INTERVAL_SECONDS = 2
        const val LIFETIME_SECONDS = 180
    }
}
