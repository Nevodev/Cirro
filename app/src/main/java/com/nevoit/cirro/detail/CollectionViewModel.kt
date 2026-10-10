package com.nevoit.cirro.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.home.Section
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 详情页那两次**只读**请求的自动重试次数。
 *
 * 网络抖一下不该让用户看见「加载失败 · 点按重试」。写操作（收藏）不在此列——重试会重复提交。
 * 退避按次线性放大：0 / [LoadRetryBaseDelayMs] / 2 × [LoadRetryBaseDelayMs]。
 */
private const val LoadAttempts = 3
private const val LoadRetryBaseDelayMs = 800L

/**
 * 统一详情页（歌单 / 专辑 / 每日推荐）。
 *
 * 三种来源共用一份状态形状：头部信息一份 [Section]、曲目一份 [Section]。
 * [kind] / [id] / [titleHint] 由导航目的地带进来，进 ViewModel 是为了让
 * 收藏这类**有副作用的操作**不散落在组合里——桥接层是阻塞调用，边界收在这里。
 *
 * 加载节奏：头部和曲目**同时**发请求，但曲目取回来先攒着，等界面在这页进场动画**看着到位**后
 * 喊 [revealTracks] 才提交（见 [pendingTracks]）。
 */
class CollectionViewModel(
    private val repository: CollectionRepository,
    val kind: CollectionKind,
    val id: Long,
    private val titleHint: String,
) : ViewModel() {

    data class UiState(
        val info: Section<CollectionInfo> = Section.Loading,
        val songs: Section<List<Song>> = Section.Loading,
        /** 收藏切换在途：按钮置灰防连点。 */
        val subBusy: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = Channel<String>(capacity = Channel.BUFFERED)

    /** 一次性的人话提示（收藏失败之类），界面拿去弹 Toast。 */
    val messages = _messages.receiveAsFlow()

    /** 头部那一次请求。曲目要等它落地——专辑的歌跟着详情一起回来（见 `CollectionRepository.tracks`）。 */
    private var infoJob: Job? = null

    /** 曲目预取那次请求。重试时要能掐掉它（可能正卡在退避里）。 */
    private var tracksJob: Job? = null

    /**
     * 预取到、但**还没亮出去**的曲目。
     *
     * 曲目跟进场动画同时开跑，结果先攒在这儿；等界面喊 [revealTracks] 才写进 [UiState.songs]。
     * 提前提交会让一整屏列表（外加每行封面请求）在滑动中落地，把导航那条位移弹簧顶掉帧。
     */
    private var pendingTracks: Section<List<Song>>? = null

    /** 曲目区是否已经亮过。亮过之后（重试 / 重建）直接提交，不再等动画。 */
    private var tracksRevealed = false

    /**
     * 界面显示的标题。
     *
     * 雷达类歌单接口里的 `name` 是「推荐语|雷达名」拼死在一起的一串（首页已经切过一次），
     * 导航带来的 [titleHint] 是干净的显示名，优先用它；没有 hint（比如直接查详情）
     * 才退回接口的原始标题。
     */
    val displayTitle: String
        get() = titleHint.ifEmpty {
            (_state.value.info as? Section.Ready)?.data?.title.orEmpty()
        }

    init {
        // 两张**一起发**：曲目在进场动画这 350~500ms 里就飞在路上，动画一结束就能亮出来，
        // 不用再干等一次网络往返。提交时机交给界面（[revealTracks]）。
        loadInfo()
        fetchTracks()
    }

    /**
     * 整页重来：头部 + 曲目（头部失败 / 曲目失败共用这个重试入口）。
     *
     * 重试时页面早在台上，曲目直接提交，不用再等进场动画。
     */
    fun load() {
        infoJob?.cancel()
        tracksJob?.cancel()
        pendingTracks = null
        tracksRevealed = true
        loadInfo()
        fetchTracks()
    }

    /** 头部信息。先到先显示，挂了只影响自己那一块（[toSection] 会留住旧数据）。 */
    private fun loadInfo() {
        infoJob = viewModelScope.launch {
            val info = retrying { repository.info(kind, id) }
            _state.update { it.copy(info = info.toSection(keep = it.info)) }
        }
    }

    /**
     * 曲目**预取**，只在 [init] 和重试 [load] 里发。
     *
     * 结果不直接提交——攒进 [pendingTracks] 等 [revealTracks]。
     */
    private fun fetchTracks() {
        tracksJob = viewModelScope.launch {
            // 🔴 必须在头部之后发：专辑的歌来自详情那次响应，早于它就只能再打一次详情接口。
            infoJob?.join()
            val section = retrying { repository.tracks(kind, id) }
                .toSection(keep = _state.value.songs)
            if (tracksRevealed) _state.update { it.copy(songs = section) }
            else pendingTracks = section
        }
    }

    /**
     * 界面在这页**进场动画看着到位**之后喊：把预取到的曲目一次性亮出来。
     *
     * 数据还没到就只留个标记，到了立刻提交——所以「动画一结束就有列表」只在预取真的先落地时
     * 成立；网络慢的时候照样是先转圈、再出现，不会两头都等。
     */
    fun revealTracks() {
        tracksRevealed = true
        val pending = pendingTracks ?: return
        pendingTracks = null
        _state.update { it.copy(songs = pending) }
    }

    /** 收藏 / 取消收藏。本地状态先翻面（乐观），失败再弹回并给一句人话。 */
    fun toggleSubscribed() {
        val info = (_state.value.info as? Section.Ready)?.data ?: return
        if (_state.value.subBusy) return
        if (kind == CollectionKind.DAILY) return

        val next = !info.subscribed
        viewModelScope.launch {
            _state.update {
                it.copy(
                    subBusy = true,
                    info = Section.Ready(info.copy(subscribed = next))
                )
            }
            repository.setSubscribed(kind, id, next)
                .onFailure { e ->
                    _state.update { it.copy(info = Section.Ready(info), subBusy = false) }
                    _messages.send(e.message?.ifEmpty { null } ?: "操作失败")
                }
                .onSuccess {
                    _state.update { it.copy(subBusy = false) }
                }
        }
    }

    /**
     * 只读请求的自动重试：失败就等一会儿再来，[LoadAttempts] 次用完才把失败交出去。
     *
     * 退避期间界面维持 [Section.Loading]（旧数据还在就维持旧数据），所以用户看不到
     * 「失败 → 又好了」的闪烁。写操作绝不走这里。
     */
    private suspend fun <T> retrying(block: suspend () -> Result<T>): Result<T> {
        var result = block()
        var attempt = 1
        while (result.isFailure && attempt < LoadAttempts) {
            delay(LoadRetryBaseDelayMs * attempt)
            attempt++
            result = block()
        }
        return result
    }

    /** 失败不打掉已画出来的内容：有旧数据垫底就维持旧数据（跟首页一个约定）。 */
    private fun <T> Result<T>.toSection(keep: Section<T>): Section<T> = fold(
        onSuccess = { Section.Ready(it) },
        onFailure = { keep as? Section.Ready ?: Section.Failed(it.message ?: "加载失败") },
    )
}
