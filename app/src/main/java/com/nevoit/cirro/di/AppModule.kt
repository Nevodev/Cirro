package com.nevoit.cirro.di

import android.util.Log
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.AppleAlbumRepository
import com.nevoit.cirro.data.AppleAlbumStore
import com.nevoit.cirro.data.AppleArtistRepository
import com.nevoit.cirro.data.AppleArtistStore
import com.nevoit.cirro.data.AudioQualityStore
import com.nevoit.cirro.data.CollectionRepository
import com.nevoit.cirro.data.EncryptedSecretStore
import com.nevoit.cirro.data.HomeRepository
import com.nevoit.cirro.data.IntelligenceRepository
import com.nevoit.cirro.data.LibraryRepository
import com.nevoit.cirro.data.PlayQueueStore
import com.nevoit.cirro.data.PlaybackStore
import com.nevoit.cirro.data.RadioRepository
import com.nevoit.cirro.data.SessionStore
import com.nevoit.cirro.data.SongLikeRepository
import com.nevoit.cirro.ui.SongMenuViewModel
import com.nevoit.cirro.ui.SongCreditsViewModel
import com.nevoit.cirro.data.model.Song
import com.nevoit.cirro.data.model.CollectionKind
import com.nevoit.cirro.detail.ArtistShelfSection
import com.nevoit.cirro.detail.ArtistShelfViewModel
import com.nevoit.cirro.detail.CollectionViewModel
import com.nevoit.cirro.home.HomeViewModel
import com.nevoit.cirro.library.LibraryViewModel
import com.nevoit.cirro.login.QrLoginViewModel
import com.nevoit.cirro.lyrics.data.AppleMusicAccountRepository
import com.nevoit.cirro.lyrics.data.AppleMusicTokenStore
import com.nevoit.cirro.lyrics.data.LyricsRepository
import com.nevoit.cirro.lyrics.data.LyricsStore
import com.nevoit.cirro.palette.CoverPalette
import com.nevoit.cirro.playback.AudioAnalysisStore
import com.nevoit.cirro.playback.PlaybackConnection
import com.nevoit.cirro.playback.PlaybackReportRepository
import com.nevoit.cirro.playback.PlaybackAudioCache
import com.nevoit.cirro.playback.PlayerLikeViewModel
import com.nevoit.cirro.playback.PlayerUiController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * App 级作用域。
 *
 * 仓库的写操作必须活过发起它的那个界面：登录成功后 LoginActivity 会立刻 finish，
 * 跟着它的 scope 一起被取消的话 cookie 就存不下去了。
 */
private val ApplicationScope = named("applicationScope")

val appModule = module {

    single(ApplicationScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single { EncryptedSecretStore(androidContext()) }

    single { SessionStore(get()) }

    single { AudioQualityStore(androidContext()) }
    single { PlaybackAudioCache(androidContext(), get(ApplicationScope), get(), get()) }

    // 播放队列的真相源：PlaybackConnection 写（它拿着 MediaController），
    // PlaybackService 读（打卡要 sourceId）。
    single { PlaybackStore(androidContext(), get(ApplicationScope)) }
    single { PlayQueueStore(get<PlaybackStore>()::save) }

    single { AccountRepository(get(), get(ApplicationScope)) }
    single { PlaybackReportRepository(androidContext(), get(ApplicationScope), get<AccountRepository>().session) }

    single { IntelligenceRepository() }
    single { RadioRepository() }
    single { PlaybackConnection(androidContext(), get(ApplicationScope), get(), get(), get(), get(), get(), get()) }
    single { AudioAnalysisStore() }

    // 播放页的展开进度。迷你条和播放页分属两层，要由同一个值驱动，所以由 App 持有。
    // 它的动画得跑在组合的帧时钟上，由界面侧启动（见 PlayerUiController.animate）
    single { PlayerUiController() }

    single { HomeRepository(androidContext(), get(ApplicationScope), get()) }

    single { CollectionRepository(get(), get()) }

    single { AppleArtistStore(androidContext()) }
    single { AppleAlbumStore(androidContext()) }
    single {
        val store = get<AppleAlbumStore>()
        AppleAlbumRepository(get(ApplicationScope), store::read, store::write, onFailure = {
            Log.w("AppleAlbum", "专辑匹配或映射保存失败，下次打开页面时重试", it)
        })
    }
    single {
        val store = get<AppleArtistStore>()
        AppleArtistRepository(get(ApplicationScope), store::read, store::write, onFailure = {
            Log.w("AppleArtist", "艺人匹配或映射保存失败，下次打开页面时重试", it)
        })
    }

    single {
        SongLikeRepository(get(ApplicationScope), get<AccountRepository>().session, onFailure = {
            Log.w("SongLike", "读取喜欢状态失败，将静默重试: ${it.message}")
        })
    }

    single { CoverPalette(androidContext(), get(ApplicationScope)) }
    single { LibraryRepository(androidContext(), get(ApplicationScope), get(), get(), get()) }

    // 歌词：独立的一个原生库（libcirro_lyrics.so）。快照落盘 + 四级取词链，
    // 切歌时由 PlaybackService 预取。
    single { LyricsStore(androidContext()) }

    // Apple Music 登录态（歌词第 1 级的凭据，与网易账号无关）。冷启动由 Application 调 restore()。
    single { AppleMusicTokenStore(get()) }

    single { AppleMusicAccountRepository(get(), get(ApplicationScope)) }

    single { LyricsRepository(get(), get(ApplicationScope), get()) }

    viewModelOf(::QrLoginViewModel)

    viewModelOf(::HomeViewModel)
    viewModelOf(::LibraryViewModel)

    viewModelOf(::PlayerLikeViewModel)

    viewModelOf(::SongMenuViewModel)
    viewModel { (song: Song) -> SongCreditsViewModel(get(), song) }

    // 统一详情页：一个页面架构服务三种来源，参数区分是哪一种
    viewModel { (kind: CollectionKind, id: Long, titleHint: String) ->
        CollectionViewModel(get(), kind, id, titleHint)
    }

    // 艺人页某一块的「全部」页
    viewModel { (artistId: Long, section: ArtistShelfSection) ->
        ArtistShelfViewModel(get(), artistId, section)
    }
}
