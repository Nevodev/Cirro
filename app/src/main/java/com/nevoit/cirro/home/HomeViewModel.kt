package com.nevoit.cirro.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.HomeRepository
import com.nevoit.cirro.data.RadioOpening
import com.nevoit.cirro.data.RadioRepository
import com.nevoit.cirro.data.contentDay
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.data.model.HomeBlock
import com.nevoit.cirro.data.model.MemoryPlaylists
import com.nevoit.cirro.data.model.Playlist
import com.nevoit.cirro.data.model.RecentAlbum
import com.nevoit.cirro.data.model.Session
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.albumDisplayTitle
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** 从首页下发的区块里挑出要用的两块。名字变了就在这儿改。 */
private const val BlockRadar = "HOMEPAGE_BLOCK_MGC_PLAYLIST"
private const val BlockStyle = "HOMEPAGE_BLOCK_STYLE_RCMD"

/** 后台轮询的节拍：最近播放查变更、跨天检查，都按这个间隔跳。 */
private const val RefreshIntervalMs = 60_000L

/**
 * 竖卡要找的那条歌单，名字**精确**匹配这个。
 *
 * 🔴 别拿它去 homepage 的雷达组里挑（`contains` 也不行）：那 6 条是运营雷达，
 * 里面没有这条纯「私人雷达」，模糊匹配会挑中「华语私人雷达」——竖卡的封面和署名就换了。
 */
private const val RadarName = "私人雷达"

/** 「私人雷达」竖卡取多少首曲目来凑署名。署名最多两行，二十首够挑，也免得把整单拉回来。 */
private const val CreditSample = 20

/**
 * 「私人雷达」那条歌单的 id。
 *
 * 🔴 点竖卡进详情页要用它导航（详情接口按 id 取）。它跟 [RadarName] 是同一条：
 * 竖卡显示走名字精确匹配，跳转走这个写死的 id——两处必须指同一条，改一处就得出事故。
 */
internal const val RadarPlaylistId = 3136952023L

/**
 * 一个入口取几首曲目来凑**封面候选**。
 *
 * 网易云的专辑图**不保证还在 CDN 上**：实测「时光雷达」首曲（LIFETIMES）那张直接 404，
 * 而接口照样把 URL 下发下来。所以封面不是"有 URL 就能用"，判空字符串救不回来，
 * 只能多带几张备用、让界面在**加载真的失败**时顺延。5 张足够，再多只是把上游那次
 * `song/detail` 的返回撑大。
 */
private const val CoverCandidates = 5

/** 曲风推荐的区标题，接口没给名字时的兜底。 */
private const val StyleShelfTitle = "曲风推荐"

/** 雷达歌单的名字都以这两个字收尾（华语私人雷达 / 时光雷达 / 新歌雷达 …）。 */
private const val RadarSuffix = "雷达"

/**
 * 名字里没写「雷达」的兜底：标题提到这两个字的就是新歌雷达。
 *
 * 这条目前只服务一条 —— 「刘柏辛Lexie喊你来听新歌」（= 新歌雷达，个性化成了"某位艺人喊你来听"，
 * 名字里压根没有「雷达」）。其余的雷达都能从标题直接切出来，走不到这一步。
 */
private const val NewsongHint = "新歌"
private const val NewsongRadarName = "新歌雷达"

/**
 * 首页单个模块的加载状态。
 *
 * 每个模块各自一份：接口互不依赖，一块挂了不该把另一块也拖成错误态，谁先回来谁先显示。
 */
sealed interface Section<out T> {
    data object Loading : Section<Nothing>
    data class Ready<T>(val data: T) : Section<T>
    data class Failed(val message: String) : Section<Nothing>
}

/** 取出 [Section.Ready] 里的数据，其余状态返回 null。 */
@Suppress("UNCHECKED_CAST")
val <T> Section<T>.dataOrNull: T?
    get() = (this as? Section.Ready<T>)?.data

/**
 * 卡片上**随接口变化**的部分：封面和署名。
 *
 * [covers] 是**候选封面**，按序试，第一张是主图。做成列表是因为首曲的专辑图可能已经不在
 * CDN 上了（见 [CoverCandidates]）——那种图接口会给 URL 但加载 404，只能等失败再顺延。
 *
 * [artists] 存的是**拆开的名字列表**，不是拼好的字符串 —— 「用分隔符连、最多几行、末尾挂个等」
 * 是排版，界面才知道能用的宽度和字号，所以拼接和截断都留在界面那一侧。数据层只管给名字。
 */
data class EntryDetail(
    val covers: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
)

/**
 * 一张入口卡片。
 *
 * [title] 是固定的，不依赖任何接口——所以接口还没回来卡片就已经是完整形状了，
 * 只有封面和署名在等。这样卡片不会在几种状态之间换布局。
 *
 * [kind] / [id] 是点击时要去的**详情页目的地**：每日推荐没有实体 id（传 0 占位），
 * 私人雷达是一条真歌单（见 [RadarPlaylistId]）。
 */
data class HomeEntry(
    val title: String,
    val kind: CollectionKind,
    val id: Long,
    val detail: Section<EntryDetail>,
)

/**
 * 首页顶部横滑区的一张卡。
 *
 * 🔴 这一层就是「按列表自动匹配卡片与内容」的地方：界面只 `when` 一次分发，
 * 不再关心"第一张是什么、第二张是什么"。加一种新卡（比如新歌速递）只需要
 * 多一个子类型 + 在 [HomeUiState.cards] 里挑个位置，界面那一行不用动。
 *
 * [key] 是 LazyRow 的 item 身份。**不能只用标题**（两张卡可能同名），
 * 也不能用下标（电台卡时有时无，会把后面的卡的身份全打乱）。
 * [covers] 给首页统一预取用。
 */
sealed interface HomeCard {
    val key: String
    val covers: List<String>

    /** 有实体目的地的入口卡：点它进详情页。 */
    data class Entry(val entry: HomeEntry) : HomeCard {
        override val key: String get() = "entry:${entry.kind}:${entry.id}"
        override val covers: List<String> get() = entry.detail.dataOrNull?.covers.orEmpty()
    }

    /** 电台卡：点一下**直接开播**，不进任何页面。 */
    data class Radio(val opening: RadioOpening) : HomeCard {
        override val key: String get() = "radio"
        override val covers: List<String> get() = opening.covers
    }
}

/**
 * 横滑带里的一张卡：展示名 + 封面候选 + **可选**的一行小字。
 *
 * 封面跟 [EntryDetail.covers] 一样是**候选表**：正常用歌单里第一首的专辑图，
 * 那张挂了就顺延下一首，最后一张兜底是运营给歌单配的宣传图（有张图也好过一片空白）。
 *
 * [subtitle] 在最近播放和回忆歌单中显示歌手；雷达歌单留 null ——
 * 卡片会因此短一行，这是有意的，不是漏了。
 *
 * [id] 是卡片的**稳定身份**（歌单 id / 专辑 id）：`LazyRow` 的 `items(key=)` 靠它识别 item，
 * 数据刷新时不会整排重组，也不会因为两张卡标题相同撞 key 崩掉（key 必须唯一，标题做不到）。
 * 将来点卡片跳歌单页/专辑页，用的也是它。
 */
data class ShelfCard(
    val id: Long,
    val title: String,
    val covers: List<String> = emptyList(),
    val subtitle: String? = null,
)

/** 曲风推荐：区标题 + 按列排好的歌。列是上游给的，不自己切。 */
data class StylePicks(
    val title: String,
    val columns: List<List<Song>>,
) {
    /** 展开成一维，供点某一首后整块入队播放。 */
    val songs: List<Song> get() = columns.flatten()
}

data class HomeUiState(
    val daily: Section<EntryDetail> = Section.Loading,
    val radar: Section<EntryDetail> = Section.Loading,
    val shelf: Section<List<ShelfCard>> = Section.Loading,
    val style: Section<StylePicks> = Section.Loading,
    val recent: Section<List<ShelfCard>> = Section.Loading,
    val memories: Section<MemoryPlaylists> = Section.Loading,
    /**
     * 电台卡。
     *
     * 它跟别的卡不是一类：点了**直接开播**，不进详情页。加载失败就整张不出现 ——
     * 一张点不动的电台卡比没有更让人困惑。
     */
    val radio: Section<RadioOpening> = Section.Loading,
) {
    /**
     * 顶部卡片，**顺序即展示顺序**：每日推荐 → 电台 → 私人雷达。
     *
     * 电台排第二是定死的；它没数据（还在加载 / 失败 / 未登录）时列表里干脆没有这一项，
     * 后面那张自然前移 —— 不留一张点不动的空卡占位。
     */
    val cards: List<HomeCard>
        get() = buildList {
            add(HomeCard.Entry(HomeEntry("每日推荐", CollectionKind.DAILY, 0L, daily)))
            add(
                HomeCard.Entry(
                    HomeEntry(
                        "私人雷达",
                        CollectionKind.PLAYLIST,
                        RadarPlaylistId,
                        radar
                    )
                )
            )
            radio.dataOrNull?.let { add(HomeCard.Radio(it)) }
        }

    /**
     * 「最近播放」要不要占位置。
     *
     * 🔴 **有内容才显示**：接口给空列表是"这段时间没听"（正常空态，不是出错），
     * 摆一条空带子比整块收起来难看——App 里这块本来就是时有时无的。
     * 请求失败照常显示，好让那句"点按重试"有地方落。
     */
    val showRecent: Boolean
        get() = recent is Section.Failed || !recent.dataOrNull.isNullOrEmpty()

    val showMemories: Boolean
        get() = memories is Section.Failed || !memories.dataOrNull?.playlists.isNullOrEmpty()
}

/**
 * 首页。
 *
 * 只负责拉数据、拼出界面要的形状，界面上不放任何网络调用——桥接层是阻塞的，
 * 边界收在这里比散在 Compose 里安全。
 */
class HomeViewModel(
    private val repository: HomeRepository,
    private val account: AccountRepository,
    private val radio: RadioRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** 「最近播放」上次画到界面上的内容——轮询只在这个**真的变了**时才动界面。 */
    private var lastRecent: List<RecentAlbum>? = null

    /**
     * 上次成功重拉时所属的内容日（[contentDay]）：三块首页内容**全部拉齐成功**才推进到它。
     * 当前内容日跟它不一致时触发重拉（进程活着熬过日切、或真跨了天）；有一块失败就留着
     * 旧值，下一分钟 ticker 再试——不然那一趟恰好网络不通，就得拿旧内容撑到下次冷启动。
     * 平时缓存不作数：每次冷启动本来就必真拉一遍（见 HomeRepository 类文档）。
     */
    private var loadedDay = ""

    /** 重拉去重：上一趟还没回来时，这一分钟的 tick 直接跳过（见 [attemptDailyReload]）。 */
    private var reloadInFlight = false

    /**
     * 上次为**哪个登录态**拉过网络。[Session] 是值对象，相等就不重复拉——
     * 登录流程里先抛占位的 `LoggedIn(空资料)`、再抛真资料，那种连续两次只作一次算。
     */
    private var pulledFor: Session? = null

    init {
        // 🔴 首帧只画快照，**不发请求**：登录态还没查明时原生层可能还没拿到 cookie，
        // 而记录类端点在未登录时返 code=200 + 空数组（不是失败），一发就把刚垫上去的
        // 「最近播放」清成空。等登录态确定再拉，见下面的观察。
        paintSnapshot()
        startTicker()
        viewModelScope.launch {
            account.session.collect { session ->
                if (session == Session.Unknown || session == pulledFor) return@collect
                pulledFor = session
                if (session != Session.LoggedOut) refresh()
            }
        }
    }

    /**
     * 刷新首页。
     *
     * 🔴 先**同步**把缓存快照画上去（进程内缓存 + 磁盘快照，冷启动也有得画），再异步拉新数据。
     * 没有这一步，每次进来 state 先被重置成全 Loading——哪怕仓库零延迟返回，界面也要闪一下
     * 加载态，「秒加载」就无从谈起。
     *
     * 快照可能是跨天的旧数据：先垫底，新数据回来自然顶掉；哪块连快照都没有（首次使用）才真的 Loading。
     */
    fun refresh() {
        paintSnapshot()
        viewModelScope.launch { attemptDailyReload(contentDay()) }
        loadRecent()
        loadRadio()
    }

    /** 只读快照、只动界面：[refresh] 的同步那一半，也用作登录态未定时的首帧。 */
    private fun paintSnapshot() {
        val blocks = repository.homeBlocksSnapshot()
        lastRecent = repository.recentAlbumsSnapshot()

        _state.value = HomeUiState(
            daily = repository.dailySongsSnapshot()?.let(::dailyDetail).toSectionOrNull(),
            radar = radarDetailSnapshot().toSectionOrNull(),
            style = blocks?.block(BlockStyle)?.let {
                StylePicks(it.title.ifEmpty { StyleShelfTitle }, it.columns)
            }.toSectionOrNull(),
            shelf = blocks?.block(BlockRadar)?.playlists?.let(::radarShelfSync).toSectionOrNull(),
            recent = lastRecent
                ?.let { recentAlbumsShelf(it) { id -> repository.albumTypeSnapshot(id) } }
                .toSectionOrNull(),
            memories = repository.memoryPlaylistsSnapshot().toSectionOrNull(),
        )
    }

    /** 能不能发请求：登录态说明 cookie 已经进原生层了（见 [init]）。 */
    private fun canFetch(): Boolean {
        val session = account.session.value
        return session != Session.Unknown && session != Session.LoggedOut
    }

    /**
     * 一分钟一跳的后台节拍，干两件事：
     * 1. **内容日变了**（日切点过了，或真跨了天）→ 三块首页内容重拉；
     * 2. **最近播放**轮询——服务端有传播延迟，播完的歌得等一会儿才记账，一分钟一查足够勤快。
     */
    private fun startTicker() {
        viewModelScope.launch {
            while (true) {
                delay(RefreshIntervalMs.milliseconds)
                // 登录态还没查明/确定没登录时这一拍跳过：cookie 可能没进原生层，
                // 拉回来的是"未登录的空数组"，会把垫在界面上的快照清掉（见 [init]）
                if (!canFetch()) continue

                val day = contentDay()
                if (day != loadedDay) {
                    attemptDailyReload(day)
                }

                repository.recentAlbums().onSuccess { fresh ->
                    lastRecent = fresh
                    _state.update { it.copy(recent = Section.Ready(recentShelf(fresh))) }
                }
            }
        }
    }

    /**
     * 拉齐三块首页内容（每日推荐、私人雷达竖卡、首页区块），**全部成功**才把 [day]
     * 认到 [loadedDay] 名下；有一块失败就留给下一分钟的 tick 重试。仓库每次都真拉，
     * 拉回来的内容跟界面上一样时，StateFlow 的相等性判断会让这次 update 等于没发生。
     */
    private suspend fun attemptDailyReload(day: String) {
        if (reloadInFlight) return
        reloadInFlight = true
        try {
            val ok = coroutineScope {
                listOf(
                    async { loadDaily() },
                    async { loadRadar() },
                    async { loadBlocks() },
                    async { loadMemories() },
                ).awaitAll().all { it }
            }
            if (ok) loadedDay = day
        } finally {
            reloadInFlight = false
        }
    }

    /** 「每日推荐」——单独一个接口，跟别的模块互不拖累。返回这次有没有真拿到数据。 */
    private suspend fun loadDaily(): Boolean {
        val result = repository.dailySongs().map(::dailyDetail)
        _state.update { it.copy(daily = mergeFailure(result.toSection(), it.daily)) }
        return result.isSuccess
    }

    private suspend fun loadMemories(): Boolean {
        val result = repository.memoryPlaylists()
        _state.update { it.copy(memories = mergeFailure(result.toSection(), it.memories)) }
        return result.isSuccess
    }

    /**
     * 竖卡「私人雷达」。
     *
     * 🔴 它**不走** homepage 的雷达组。那 6 条是运营雷达（华语/欧美私人雷达、时光/新歌/乐迷/
     * 宝藏雷达），里面**没有**那条纯「私人雷达」；照名字模糊匹配会挑中「华语私人雷达」，
     * 竖卡的封面和署名就整个换成另一个歌单了（出过一次）。所以这里仍旧走 `recommend_resource`，
     * 它的第一条就叫这个名字。
     */
    private suspend fun loadRadar(): Boolean {
        val result = radarDetail()
        _state.update { it.copy(radar = mergeFailure(result.toSection(), it.radar)) }
        return result.isSuccess
    }

    /**
     * 雷达歌单区和曲风推荐区来自**同一次** `homepage/block/page`，所以这两块同进同退。
     *
     * 但下发分两趟：雷达区的封面还要再补一轮请求（见 [radarShelf]），比曲风区晚回来，
     * 挤在一起会让曲风区白等那一轮。
     */
    private suspend fun loadBlocks(): Boolean {
        val blocks = repository.homeBlocks()
        val failure = blocks.exceptionOrNull()?.message ?: "加载失败"
        val list = blocks.getOrNull().orEmpty()

        val style = list.block(BlockStyle)
            ?.let { block ->
                StylePicks(
                    block.title.ifEmpty { StyleShelfTitle },
                    block.columns
                )
            }
            .toSection(failure)
        _state.update { it.copy(style = mergeFailure(style, it.style)) }

        val radar = list.block(BlockRadar)?.playlists?.map { it.radarDisplayName() }
        val shelf = radar?.let { radarShelf(it) }.toSection(failure)
        _state.update { it.copy(shelf = mergeFailure(shelf, it.shelf)) }

        return blocks.isSuccess
    }

    /**
     * 电台卡：种子歌（近七天播放最多那首）+ 相似歌曲开路。
     *
     * 它**不参与** [attemptDailyReload] 那个"三块内容全成功才认日"的账：电台失败只是少一张卡，
     * 不该把每日推荐/雷达一起判成没加载出来。也不缓存 —— 种子跟着听歌记录走，
     * 每次进首页重算一遍才有意义。
     */
    private fun loadRadio() {
        val userId = (account.session.value as? Session.LoggedIn)?.profile?.userId
        if (userId == null || userId <= 0) return

        viewModelScope.launch {
            radio.opening(userId)
                .onSuccess { opening -> _state.update { it.copy(radio = Section.Ready(opening)) } }
                .onFailure { error ->
                    // 已经有内容就别把失败盖上去（跟「最近播放」同一个处理）。
                    _state.update {
                        if (it.radio is Section.Ready) it
                        else it.copy(radio = Section.Failed(error.message ?: "电台加载失败"))
                    }
                }
        }
    }

    /**
     * 「最近播放」。
     *
     * 跟前面几块都不同：它**不来自首页区块**，是单独一个端点——数据是最近听过的**单曲**，
     * 仓库侧按专辑归并成卡片（见 [HomeRepository.recentAlbums]，为什么不用专辑维度端点
     * 写在那边）。卡片要封面、专辑名、歌手三样，折成 [ShelfCard] 时把歌手填进
     * `subtitle`，跟雷达歌单那排共用一套卡片。
     *
     * 单曲专辑的标题补 " - Single"（见 [recentShelf]）。空列表照常进 [Section.Ready]，
     * 由界面决定整块收不收起（见 [HomeUiState.showRecent]）。
     */
    private fun loadRecent() {
        viewModelScope.launch {
            repository.recentAlbums().onSuccess { fresh ->
                lastRecent = fresh
                // StateFlow 对相等值自动去重：没变化时这次 update 等于什么都没发生
                _state.update { it.copy(recent = Section.Ready(recentShelf(fresh))) }
            }.onFailure { e ->
                // 快照垫过底的别把失败盖上去——界面维持旧数据比闪"加载失败"体面
                _state.update {
                    if (it.recent is Section.Ready) {
                        it
                    } else {
                        it.copy(recent = Section.Failed(e.message ?: "加载失败"))
                    }
                }
            }
        }
    }

    /**
     * 「最近播放」折卡 + 单曲专辑的标题补 " - Single"。
     *
     * 类型来自仓库的**静态缓存**（[HomeRepository.albumType]，一个专辑一辈子查一次），
     * 所以这条 suspending 函数常态下零请求；首次遇到没查过的专辑才并发补几条详情，
     * 查完界面自然换上带后缀的标题。
     */
    private suspend fun recentShelf(albums: List<RecentAlbum>): List<ShelfCard> = coroutineScope {
        val types = albums.map { it.id to async { repository.albumType(it.id) } }
            .associate { (id, deferred) -> id to deferred.await() }
        recentAlbumsShelf(albums) { types[it] }
    }

    /**
     * 雷达歌单区那排卡：封面换成歌单里**头几首**的专辑图。
     *
     * 运营给歌单配的封面是一张固定宣传图，跟这张雷达"今天在推什么"没关系；首曲的专辑图才是。
     * 但首曲那张**不一定还加载得出来**（见 [CoverCandidates]），所以多取几首一起交给
     * [covers] 排成候选表。6 条并发取，谁一首都拿不到就退回谁的原封面 ——
     * 图对不上不值得让整排歌单消失。
     *
     * `limit` 从 1 提到 [CoverCandidates] 只是把上游 `song/detail` 那一步的返回撑大一点，
     * 请求数不变（第一步照样会拉完整 trackIds）。6 个请求换来一次封面替换，
     * 所以只有这一处这么干，别扩散到推荐歌单那类列表。
     */
    private suspend fun radarShelf(playlists: List<Playlist>): List<ShelfCard> = coroutineScope {
        playlists.map { playlist ->
            async {
                val tracks = repository.playlistTracks(playlist.id, limit = CoverCandidates)
                    .getOrNull()
                    .orEmpty()
                ShelfCard(
                    id = playlist.id,
                    title = playlist.name,
                    covers = covers(tracks, playlist.cover),
                )
            }
        }.awaitAll()
    }

    /**
     * 竖卡「私人雷达」的封面与署名。
     *
     * 歌单自己那张图是运营生成的，跟今天的推荐没关系，所以跟「每日推荐」一个路子：
     * 用曲目的专辑图当封面，顺手用这批曲目的歌手凑署名。
     * 这一步失败**不算卡片失败**——退回歌单封面就行，卡片照样能用。
     */
    private suspend fun radarDetail(): Result<EntryDetail> = runCatching {
        val playlists = repository.dailyPlaylists().getOrThrow()
        val radar = playlists.firstOrNull { it.name == RadarName } ?: playlists.firstOrNull()
        ?: return@runCatching EntryDetail()

        // 这一趟本来就为署名取 20 首，封面候选顺路一起收，不额外请求
        val tracks = repository.playlistTracks(radar.id, limit = CreditSample).getOrNull().orEmpty()

        EntryDetail(
            covers = covers(tracks, radar.cover),
            artists = tracks.artists(),
        )
    }

    /**
     * 竖卡「私人雷达」的**快照版**：全从缓存拼，不碰网络、不挂起。
     * 拼不完整（歌单或曲目快照缺哪个）就返回 null，让 [radarDetail] 异步去补。
     */
    private fun radarDetailSnapshot(): EntryDetail? {
        val playlists = repository.dailyPlaylistsSnapshot() ?: return null
        val radar = playlists.firstOrNull { it.name == RadarName } ?: playlists.firstOrNull()
        ?: return null
        val tracks = repository.tracksSnapshot(radar.id, CreditSample) ?: return null
        return EntryDetail(covers = covers(tracks, radar.cover), artists = tracks.artists())
    }

    /**
     * 雷达歌单区的**快照版**：曲目列表从当天缓存里拿（缓存条数不够就少几张候选，
     * 界面自己会顺延），不碰网络。配合 [refresh] 的同步垫底用。
     */
    private fun radarShelfSync(playlists: List<Playlist>): List<ShelfCard> = playlists.map {
        val cleaned = it.radarDisplayName()
        val tracks = repository.tracksSnapshot(it.id, CoverCandidates).orEmpty()
        ShelfCard(
            id = it.id,
            title = cleaned.name,
            covers = covers(tracks, it.cover),
        )
    }
}

private fun List<HomeBlock>.block(code: String): HomeBlock? = firstOrNull { it.code == code }

/**
 * 雷达歌单的显示名：只留「xx雷达」，把推荐语丢掉。
 *
 * 🔴 网易云把推荐语和歌单名**拼在同一个字段**里下发，歌单自己的 `name` 实测也是这一串
 * （`playlist_detail` 取回来一模一样），没有干净的来源。而且拼法**不统一**：
 * ```
 * 普通朋友和更多好听的|华语私人雷达              → 华语私人雷达
 * 还在听《LIFETIMES》吗|时光雷达                 → 时光雷达
 * 欧美私人雷达 | 最懂你的欧美推荐 每日更新35首    → 欧美私人雷达   ← 这条前后反着
 * 刘柏辛Lexie喊你来听新歌                        → 新歌雷达       ← 名字里没有「雷达」
 * ```
 * 所以按分隔符切开，取**以「雷达」收尾**的那一段；一段都不符合时才走「新歌」兜底
 * （见 [NewsongHint]），再不符合就原样保留（宁可长一点，也别猜出一个错的雷达名）。
 *
 * 「以雷达收尾」这个条件本身就把误伤挡住了：推荐歌单里也有带 `|` 的正常名字
 * （`Colour Bass ｜ 新时代异军突起…`），但两边都不以「雷达」收尾，实测 12 条零改动。
 * 不过这条规则**语义上只属于雷达区块**，别拿去处理别的列表。
 */
private fun Playlist.radarDisplayName(): Playlist {
    val parts = name.split('|', '｜')
        .map { it.trim { c -> c.isWhitespace() || c == '\u00A0' } }
        .filter { it.isNotEmpty() }

    val radar = parts.firstOrNull { it.endsWith(RadarSuffix) }
        ?: NewsongRadarName.takeIf { name.contains(NewsongHint) }
        ?: return this

    return if (radar == name) this else copy(name = radar)
}

/**
 * 「每日推荐」卡片。
 *
 * 封面借用当天头几首推荐歌的专辑图——接口给的是歌曲列表，没有歌单封面，
 * 歌曲封面是唯一能反映"今天推了什么"的图。署名同理，就是这几十首的歌手。
 */
private fun dailyDetail(songs: List<Song>): EntryDetail = EntryDetail(
    covers = covers(songs),
    artists = songs.artists(),
)

/**
 * 一批曲目的封面候选：按序取，去掉空的和重复的，末尾兜底 [fallback]。
 *
 * 顺序就是优先级，界面从第一张往后试。取 [CoverCandidates] 张是因为**接口给了 URL 不等于
 * 图还在**——首曲的专辑图可能已经从 CDN 撤下、加载 404（见 [CoverCandidates]），
 * 多几张才有可能顺延到一张能用的。
 *
 * [fallback] 一般是歌单自己那张运营宣传图：曲目封面全废时，有张图也好过一片空白。
 */
private fun covers(tracks: List<Song>, fallback: String = ""): List<String> =
    (tracks.take(CoverCandidates).map { it.cover } + fallback)
        .filter { it.isNotEmpty() }
        .distinct()

/**
 * 按出现顺序收集整批歌的歌手并去重。
 *
 * 同一位歌手在一张推荐里反复出现是常态（一张专辑好几首、合作曲更是），署名里重复列出来很怪。
 */
private fun List<Song>.artists(): List<String> =
    flatMap { it.artists }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * 「最近播放」的条目折成卡片：独图当候选表传，封面为空的直接不给（卡片露出一块底色）。
 *
 * `subtitle` 用小字放**歌手**——这张卡就靠它跟雷达歌单那排区分开。歌手名一个都没有时
 * 留 null，卡片就只显示专辑名，而不是硬塞一行空白。
 *
 * [albumType] 给了且是 "Single" 时，标题补 " - Single"（官方客户端的叫法）。
 * 上游封面是单张（不像歌单那样给一串备选），所以这里就一张；加载不出来时卡片露底色。
 */
private fun recentAlbumsShelf(
    items: List<RecentAlbum>,
    albumType: (Long) -> String? = { null },
): List<ShelfCard> = items.map { item ->
    ShelfCard(
        id = item.id,
        title = albumDisplayTitle(item.title, albumType(item.id)),
        covers = listOfNotNull(item.cover.takeIf { it.isNotEmpty() }),
        subtitle = item.artistText.takeIf { it.isNotEmpty() },
    )
}

private fun <T> Result<T>.toSection(): Section<T> = fold(
    onSuccess = { Section.Ready(it) },
    onFailure = { Section.Failed(it.message ?: "加载失败") },
)

/** 快照的落位：有数据就是 Ready，没有退回 Loading 等异步那趟。 */
private fun <T> T?.toSectionOrNull(): Section<T> =
    this?.let { Section.Ready(it) } ?: Section.Loading

/**
 * 失败不打掉已经画出来的内容：有缓存垫底的区块请求挂了就维持原样——
 * 界面显示上一次的数据，比闪一下"加载失败"体面，下次轮询还会再试。
 */
private fun <T> mergeFailure(next: Section<T>, current: Section<T>): Section<T> =
    if (next is Section.Failed && current is Section.Ready) current else next

/**
 * 为 null 说明来源那次请求本身失败了（区块缺失和请求失败在界面上是同一种"这块没有内容"），
 * 所以统一进 [Section.Failed]，把真正的失败原因带过去。
 */
private fun <T> T?.toSection(failure: String): Section<T> =
    if (this == null) Section.Failed(failure) else Section.Ready(this)
