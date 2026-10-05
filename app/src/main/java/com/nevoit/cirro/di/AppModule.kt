package com.nevoit.cirro.di

import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.SessionStore
import com.nevoit.cirro.login.QrLoginViewModel
import com.nevoit.cirro.playback.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
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

    single { SessionStore(androidContext()) }

    single { AccountRepository(get(), get(ApplicationScope)) }

    single { PlaybackConnection(androidContext(), get(ApplicationScope)) }

    viewModelOf(::QrLoginViewModel)
}
