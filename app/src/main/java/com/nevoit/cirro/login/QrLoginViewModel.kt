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
 *
 * 单次请求失败一律先原地重试：切后台再回来那一下网络常常还没就绪，一次失败就把整次登录判死是错的。
 */
class QrLoginViewModel(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    /** 当前二维码内容，过期后仍保留，方便界面上盖一层遮罩。 */
    var qrUrl by mutableStateOf<String?>(null)
        private set

    var state by mutableStateOf<QrLoginState>(QrLoginState.Loading)
        private set

    /** 上一个请求失败、正在原地重试。界面只拿它换一行提示，不参与状态机。 */
    var retrying by mutableStateOf(false)
        private set

    private var pollJob: Job? = null

    /** 当前二维码对应的 unikey。网络断了之后回前台要接着用它轮询，不能重新申请一个。 */
    private var unikey: String? = null

    /** 本地倒计时。重试期间不走，续跑时接着上次的数字算。 */
    private var remainingSeconds = LIFETIME_SECONDS

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch { login() }
    }

    /** 二维码确实过期了才走这里：必须换一个新 unikey。 */
    fun refresh() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch { login() }
    }

    /** 网络恢复后重试：unikey 还活着就接着轮询，别把用户已经扫过的码换掉。 */
    fun retry() {
        pollJob?.cancel()
        val pending = if (qrUrl != null && remainingSeconds > 0) unikey else null
        if (pending == null) {
            pollJob = viewModelScope.launch { login() }
            return
        }
        retrying = false
        state = QrLoginState.Waiting(remainingSeconds)
        pollJob = viewModelScope.launch { waitForScan(pending) }
    }

    /** 回到前台：后台期间可能已经因为没网被判失败，这里自动接上。 */
    fun onForeground() {
        if (pollJob?.isActive == true) return
        if (state !is QrLoginState.Failed) return
        retry()
    }

    private suspend fun login() {
        state = QrLoginState.Loading
        qrUrl = null
        retrying = false
        unikey = null
        remainingSeconds = LIFETIME_SECONDS

        val key = request(INIT_RETRY_ATTEMPTS) { NcmBridge.qrKey() }.getOrElse { error ->
            state = QrLoginState.Failed(error.message ?: "获取二维码失败")
            return
        }
        val url = request(INIT_RETRY_ATTEMPTS) { NcmBridge.qrCreate(key) }.getOrElse { error ->
            state = QrLoginState.Failed(error.message ?: "生成二维码失败")
            return
        }
        unikey = key
        qrUrl = url
        state = QrLoginState.Waiting(remainingSeconds)

        waitForScan(key)
    }

    private suspend fun waitForScan(key: String) {
        while (remainingSeconds > 0) {
            delay(POLL_INTERVAL_MILLIS)

            val status = request(CHECK_RETRY_ATTEMPTS) { NcmBridge.qrCheck(key) }.getOrElse { error ->
                // 重试额度用完还没网：判失败但不作废 unikey，回前台或者点重试都能接着查
                state = QrLoginState.Failed(error.message ?: "二维码状态查询失败")
                return
            }

            remainingSeconds -= POLL_INTERVAL_SECONDS

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

                else -> state = QrLoginState.Waiting(remainingSeconds.coerceAtLeast(0))
            }
        }

        state = QrLoginState.Expired
    }

    /**
     * 单个请求最多试 [attempts] 次，间隔 [RETRY_INTERVAL_MILLIS]。
     *
     * 只吞"没发出去 / 没回来"这类瞬时失败，额度用完了还是失败就把最后一次的错误交回调用方。
     */
    private suspend fun <T> request(
        attempts: Int,
        block: suspend () -> Result<T>,
    ): Result<T> {
        var last: Result<T> = Result.failure(IllegalStateException("请求失败"))
        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                retrying = true
                delay(RETRY_INTERVAL_MILLIS)
            }
            last = withContext(Dispatchers.IO) { block() }
            if (last.isSuccess) {
                retrying = false
                return last
            }
        }
        retrying = false
        return last
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L
        const val POLL_INTERVAL_SECONDS = 2
        const val LIFETIME_SECONDS = 180

        const val RETRY_INTERVAL_MILLIS = 1_000L
        const val INIT_RETRY_ATTEMPTS = 6
        const val CHECK_RETRY_ATTEMPTS = 20
    }
}
