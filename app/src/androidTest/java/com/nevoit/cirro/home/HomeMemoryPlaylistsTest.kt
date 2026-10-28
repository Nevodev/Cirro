package com.nevoit.cirro.home

import com.nevoit.cirro.data.HomeFeedPage
import com.nevoit.cirro.data.MemoryPlaylistPosition
import com.nevoit.cirro.data.homeFeedRequest
import com.nevoit.cirro.data.memoryPeriod
import com.nevoit.cirro.data.model.MemoryPeriod
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeMemoryPlaylistsTest {
    @Test
    fun customDateLabelsComeFromOfficialDetailAndSeparateAnnualFromMonthly() {
        assertEquals(MemoryPeriod(2026, 9), memoryPeriod("2026年 | 九月歌单", "9月听歌2时27分"))
        assertEquals(MemoryPeriod(2024), memoryPeriod("2024年度报告歌单", "2024年度最爱歌曲重温"))
        assertEquals("'24", MemoryPeriod(2024).number)
        assertEquals("9", MemoryPeriod(2026, 9).number)
        for ((name, month) in listOf("十" to 10, "十一" to 11, "十二" to 12, "12" to 12)) {
            assertEquals(MemoryPeriod(2025, month), memoryPeriod("2025年 | ${name}月歌单", ""))
        }
        assertNull(memoryPeriod("你的足迹", "9月听歌2时27分"))
        assertNull(memoryPeriod("2026年回忆", ""))
        assertNull(memoryPeriod("2026年 | 十三月歌单", ""))
    }

    @Test
    fun usesVerifiedPositionAndServerOrderWithoutGuessingDatesOrTemplateSuffix() {
        val page = page(MemoryPlaylistPosition)
        val memories = requireNotNull(page.memories())
        assertEquals("回忆歌单", memories.title)
        assertEquals(listOf(42L, 7L), memories.playlists.map { it.id })
        assertEquals(listOf("month.png", "year.png"), memories.playlists.map { it.coverText })
        assertEquals("你的足迹", memories.playlists.first().title)
        assertEquals("歌手甲、歌手乙", memories.playlists.first().subtitle)
        assertNull(page("PAGE_RECOMMEND_MY_SHEET").memories())
    }

    @Test
    fun continuationRetainsServerTokensAndIsNoLongerFirstScreen() {
        val page = page(MemoryPlaylistPosition)
        val next = page.nextRequest(homeFeedRequest(400f, 800f), listOf("PAGE_RECOMMEND_RADAR"))
        assertEquals("7", next["cursor"]!!.jsonPrimitive.content)
        assertEquals("false", next["isFirstScreen"]!!.jsonPrimitive.content)
        assertEquals("opaque", next["callbackParameters"]!!.jsonPrimitive.content)
        assertEquals("[\"A\",\"B\"]", next["blockCodeOrderList"]!!.jsonPrimitive.content)
        assertEquals("[\"PAGE_RECOMMEND_RADAR\"]", next["loadedPositionCodes"]!!.jsonPrimitive.content)
        assertTrue(page.hasMore)
    }

    @Test
    fun serverAbsenceIsAnEmptySectionAndFailuresAreNotEmptySuccess() {
        val page = HomeFeedPage("""{"code":200,"data":{"blocks":[],"hasMore":false}}""")
        assertFalse(page.hasMore)
        assertNull(page.memories())
        assertTrue(runCatching { HomeFeedPage("""{"code":403,"data":{"blocks":[]}}""") }.isFailure)
        assertTrue(runCatching { HomeFeedPage("""{"code":200}""") }.isFailure)
        assertTrue(runCatching { HomeFeedPage("""{"code":200,"data":{}}""") }.isFailure)
    }

    private fun page(position: String): HomeFeedPage {
        val response = buildJsonObject {
            put("code", 200)
            put("data", buildJsonObject {
                put("cursor", 7)
                put("hasMore", true)
                put("callbackParameters", "opaque")
                put("blockCodeOrderList", "[\"A\",\"B\"]")
                put("blocks", buildJsonArray {
                    add(buildJsonObject {
                        put("positionCode", position)
                        put("dslData", buildJsonObject {
                            put("rcmd_annual_and_monthly_playlist_list_module_new_suffix", buildJsonObject {
                                put("blockResource", buildJsonObject {
                                    put("header", buildJsonObject { put("title", "回忆歌单") })
                                    put("items", buildJsonArray {
                                        for ((id, dateImage) in listOf(42L to "month.png", 7L to "year.png", 42L to "duplicate.png")) {
                                            add(buildJsonObject {
                                                put("resourceId", id.toString())
                                                put("resourceType", "playlist")
                                                put("title", "你的足迹")
                                                put("subTitle", "歌手甲、歌手乙")
                                                put("coverImg", "cover.jpg")
                                                put("coverTextImg", dateImage)
                                            })
                                        }
                                        add(buildJsonObject { put("resourceId", "0"); put("resourceType", "playlist") })
                                        add(buildJsonObject { put("resourceId", "99"); put("resourceType", "song") })
                                    })
                                })
                            })
                        })
                    })
                })
            })
        }
        return HomeFeedPage(response.toString())
    }
}
