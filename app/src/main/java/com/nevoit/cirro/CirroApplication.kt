package com.nevoit.cirro

import android.app.Application
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class CirroApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val koin = startKoin {
            androidContext(this@CirroApplication)
            modules(appModule)
        }.koin

        // 冷启动把磁盘上的登录态恢复到原生层。放这里而不是 MainActivity：
        // 恢复要在任何界面看到登录态之前就开始。
        koin.get<AccountRepository>().restore()
    }
}
