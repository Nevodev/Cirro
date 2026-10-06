package com.nevoit.cirro.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.model.CollectionInfo
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.home.Section
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 统一详情页（歌单 / 专辑 / 每日推荐）。
 *
 * 三种来源共用一份状态形状：头部信息一份 [Section]、曲目一份 [Section]。
 * [kind] / [id] / [titleHint] 由导航目的地带进来，进 ViewModel 是为了让
 * 收藏这类**有副作用的操作**不散落在组合里——桥接层是阻塞调用，边界收在这里。
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
        load()
    }

    /** 加载（重载同入口）。头部先到先显示，曲目随后；两步谁挂了只影响自己那一块。 */
    fun load() {
        viewModelScope.launch {
            val info = repository.info(kind, id)
            _state.update { it.copy(info = info.toSection(keep = it.info)) }

            // 专辑的歌跟着详情那次响应回来，必须等 info 落地才能白拿缓存，别并发
            val songs = repository.tracks(kind, id)
            _state.update { it.copy(songs = songs.toSection(keep = it.songs)) }
        }
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

    /** 失败不打掉已画出来的内容：有旧数据垫底就维持旧数据（跟首页一个约定）。 */
    private fun <T> Result<T>.toSection(keep: Section<T>): Section<T> = fold(
        onSuccess = { Section.Ready(it) },
        onFailure = { keep as? Section.Ready ?: Section.Failed(it.message ?: "加载失败") },
    )
}
