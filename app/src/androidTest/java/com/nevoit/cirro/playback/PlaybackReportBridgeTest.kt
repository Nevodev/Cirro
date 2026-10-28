package com.nevoit.cirro.playback

import com.nevoit.cirro.data.NcmBridge
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackReportBridgeTest {
    @Test
    fun packagedPrepareAndSendRejectAnUnboundOwnerBeforeNetwork() {
        val payload = """{"action":"_pld","json":{"id":42,"type":"song","_logTime":1000}}"""
        val prepared = NcmBridge.prepareScrobbleEvent(-1, payload)
        val sent = NcmBridge.scrobbleEvent(-1, payload)
        assertTrue(prepared.isFailure)
        assertTrue(sent.isFailure)
        assertTrue(prepared.exceptionOrNull()?.message.orEmpty().contains("账号"))
        assertTrue(sent.exceptionOrNull()?.message.orEmpty().contains("账号"))
    }
}
