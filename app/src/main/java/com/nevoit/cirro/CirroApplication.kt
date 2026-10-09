package com.nevoit.cirro

import android.app.Application
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.di.appModule
import com.nevoit.cirro.lyrics.data.AppleMusicAccountRepository
import com.nevoit.cirro.lyrics.data.LyricsBridge
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.io.File

class CirroApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val koin = startKoin {
            androidContext(this@CirroApplication)
            modules(appModule)
        }.koin

        // 歌词引擎的可写目录。原生层拿不到 Context，这个路径只能由上层给一次 ——
        // 它用来落盘缓存 Apple 的开发者 token（抓一次能用几小时，省掉冷启动那趟网页 + JS）。
        // 刻意用 filesDir 下的独立子目录，而不是 cacheDir（系统会清），也不跟 LyricsStore 的
        // `lyrics/` 混在一起（那个目录整个会被 deleteRecursively）。
        LyricsBridge.setAppleCacheDir(File(filesDir, "lyrics-engine").absolutePath)

        // 冷启动把磁盘上的登录态恢复到原生层。放这里而不是 MainActivity：
        // 恢复要在任何界面看到登录态之前就开始。
        koin.get<AccountRepository>().restore()

        // Apple Music 那份凭据也要回灌给歌词引擎（另一个 .so，自己持一份）。
        // 它没按时推，取词链第 1 级会一直以为自己没配 token。
        koin.get<AppleMusicAccountRepository>().restore()
    }
}
