package com.nevoit.cirro.data

import com.nevoit.cirro.data.model.MemoryPlaylist
import com.nevoit.cirro.data.model.MemoryPlaylists
import com.nevoit.cirro.data.model.MemoryPeriod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal const val MemoryPlaylistPosition = "PAGE_RECOMMEND_MONTH_YEAR_PLAYLIST"

internal class HomeFeedPage(raw: String) {
    private val response = Json.parseToJsonElement(raw) as JsonObject
    private val data = response["data"] as? JsonObject ?: error("首页数据缺失")

    init {
        check(response["code"]?.jsonPrimitive?.intOrNull == 200) { "首页请求未成功" }
        check(data["blocks"] is JsonArray) { "首页区块列表缺失" }
    }

    val blocks: List<JsonObject> = buildList {
        fun append(value: JsonElement?) {
            (value as? JsonArray)?.forEach { element ->
                val block = element as? JsonObject ?: return@forEach
                add(block)
                append((block["subHomeBlockInfo"] as? JsonObject)?.get("subBlockList"))
            }
        }
        append(data["blocks"])
    }
    val hasMore: Boolean get() = data["hasMore"]?.jsonPrimitive?.booleanOrNull == true
    val cursor: Int get() = data["cursor"]?.jsonPrimitive?.intOrNull ?: 0

    fun memories(): MemoryPlaylists? {
        val block = blocks.firstOrNull { it.text("positionCode") == MemoryPlaylistPosition } ?: return null
        val dsl = block["dslData"] as? JsonObject ?: error("回忆歌单内容缺失")
        // DSL 节点名包含服务端模板生成的后缀；按已核对的数据形状取，不绑定模板哈希。
        val resource = dsl.filterKeys { it.startsWith("rcmd_annual_and_monthly_playlist_list_module_") }
            .values.filterIsInstance<JsonObject>()
            .mapNotNull { it["blockResource"] as? JsonObject }
            .firstOrNull { it["items"] is JsonArray } ?: error("回忆歌单列表缺失")
        val playlists = (resource["items"] as JsonArray).mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            if (!item.text("resourceType").equals("playlist", ignoreCase = true)) return@mapNotNull null
            val id = item["resourceId"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return@mapNotNull null
            MemoryPlaylist(id, item.text("title"), item.text("coverImg"), item.text("coverTextImg"), item.text("subTitle"))
        }.distinctBy { it.id }
        return MemoryPlaylists(
            title = (resource["header"] as? JsonObject)?.text("title").orEmpty().ifBlank { "回忆歌单" },
            playlists = playlists,
        )
    }

    fun nextRequest(first: JsonObject, loaded: List<String>) = JsonObject(first + mapOf(
        "isFirstScreen" to JsonPrimitive(false),
        "cursor" to JsonPrimitive(cursor),
        "blockCodeOrderList" to (data["blockCodeOrderList"] ?: JsonNull),
        "callbackParameters" to (data["callbackParameters"] ?: JsonPrimitive("")),
        "loadedPositionCodes" to JsonPrimitive(Json.encodeToString(loaded)),
        "extJson" to JsonPrimitive("""{"refreshAction":"loadMore","firstRequestPerLaunch":false}"""),
    ))
}

internal fun homeFeedRequest(widthDp: Float, heightDp: Float): JsonObject = buildJsonObject {
    put("pageCode", "HOME_RECOMMEND_PAGE")
    put("isFirstScreen", true)
    put("cursor", 0)
    put("refresh", false)
    put("widthDp", widthDp)
    put("heightDp", heightDp)
    put("loadedPositionCodes", "")
    put("callbackParameters", "")
    put("extJson", "{}")
}

private fun JsonObject.text(key: String): String =
    get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.content.orEmpty()

private val AnnualMemoryTitle = Regex("([12][0-9]{3})年度")
private val MemoryYear = Regex("([12][0-9]{3})年")
private val MonthlyMemoryTitle = Regex("(?<![一二三四五六七八九十0-9])(十二|十一|十|[一二三四五六七八九]|1[0-2]|[1-9])月歌单")
private val MonthlyMemoryDescription = Regex("(?:^|\\s)(1[0-2]|[1-9])月听歌")

internal fun memoryPeriod(title: String, description: String): MemoryPeriod? {
    AnnualMemoryTitle.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return MemoryPeriod(it) }
    val year = MemoryYear.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: return null
    val monthName = MonthlyMemoryTitle.find(title)?.groupValues?.get(1)
    val month = monthName?.toIntOrNull()
        ?: listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
            .indexOf(monthName).takeIf { it >= 0 }?.plus(1)
        ?: MonthlyMemoryDescription.find(description)?.groupValues?.get(1)?.toIntOrNull()
        ?: return null
    return MemoryPeriod(year, month)
}
