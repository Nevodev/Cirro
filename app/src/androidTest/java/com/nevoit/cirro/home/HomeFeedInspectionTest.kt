package com.nevoit.cirro.home

import androidx.test.platform.app.InstrumentationRegistry
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.HomeRepository
import com.nevoit.cirro.data.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koin.core.context.GlobalContext

class HomeFeedInspectionTest {
    @Test
    fun inspectCurrentHomeBlocks() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectHomeFeed") == "true")
        val account = GlobalContext.get().get<AccountRepository>()
        withTimeout(30_000) { account.session.first { it is Session.LoggedIn && it.profile.userId > 0 } }
        val result = GlobalContext.get().get<HomeRepository>().memoryPlaylists().getOrThrow()
        assertTrue(result.playlists.isNotEmpty())
        assertTrue(result.playlists.all { it.id > 0 && it.cover.isNotBlank() && it.coverText.isNotBlank() })
        assertTrue(result.playlists.all { it.period != null })
    }
}
