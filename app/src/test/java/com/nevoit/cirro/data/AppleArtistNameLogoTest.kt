package com.nevoit.cirro.data

import com.nevoit.cirro.lyrics.data.AppleArtistHeader
import com.nevoit.cirro.lyrics.data.AppleArtistNameLogo
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppleArtistNameLogoTest {
    @Test
    fun oversizedLogoRequestsOnlyDisplayPixels() {
        val logo = AppleArtistNameLogo("https://example.com/{w}x{h}{c}.{f}", 30000, 5000)
        assertEquals(
            AppleArtistNameLogo("https://example.com/900x150bb.png", 900, 150),
            logo.thumbnail(900, 240),
        )
    }

    @Test
    fun tallLogoRespectsHeightAndKeepsAspectRatio() {
        val logo = AppleArtistNameLogo("https://example.com/{w}x{h}.png", 4000, 16000)
        assertEquals(AppleArtistNameLogo("https://example.com/60x240.png", 60, 240), logo.thumbnail(900, 240))
    }

    @Test
    fun appleJpegTemplateRequestsPngToPreserveTransparency() {
        val logo = AppleArtistNameLogo("https://is1-ssl.mzstatic.com/image/thumb/asset/{w}x{h}bb.jpg", 4826, 1264)
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/asset/916x240bb.png",
            logo.thumbnail(1024, 240)?.url,
        )
    }

    @Test
    fun smallLogoIsNotUpscaled() {
        val logo = AppleArtistNameLogo("https://example.com/{w}x{h}.png", 120, 30)
        assertEquals(AppleArtistNameLogo("https://example.com/120x30.png", 120, 30), logo.thumbnail(900, 240))
    }

    @Test
    fun invalidArtworkAndUnboundedOriginalUrlsFallBackToText() {
        val logo = AppleArtistNameLogo("https://example.com/{w}x{h}.png", 30000, 5000)
        assertNull(logo.copy(width = 0).thumbnail(900, 240))
        assertNull(logo.thumbnail(0, 240))
        assertNull(logo.copy(url = "https://example.com/original.png").thumbnail(900, 240))
    }

    @Test
    fun metadataAllowsLogoWithoutVideoOrAnEmptyHeader() {
        assertEquals(AppleArtistHeader(), Json.decodeFromString<AppleArtistHeader>("{}"))
        val header = Json.decodeFromString<AppleArtistHeader>(
            """{"videoUrl":null,"nameLogo":{"url":"https://example.com/{w}x{h}.png","width":30000,"height":5000}}""",
        )
        assertNull(header.videoUrl)
        assertEquals(30000, header.nameLogo?.width)
    }
}
