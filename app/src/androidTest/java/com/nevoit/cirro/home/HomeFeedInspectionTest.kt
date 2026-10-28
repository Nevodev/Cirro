package com.nevoit.cirro.home

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.nevoit.cirro.data.AccountRepository
import com.nevoit.cirro.data.NcmBridge
import com.nevoit.cirro.data.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import org.junit.Test
import org.koin.core.context.GlobalContext

class HomeFeedInspectionTest {
    @Test
    fun inspectCurrentHomeBlocks() = runBlocking {
        val account = GlobalContext.get().get<AccountRepository>()
        withTimeout(30_000) { account.session.first { it is Session.LoggedIn && it.profile.userId > 0 } }
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
        val params = buildJsonObject {
            put("pageCode", "HOME_RECOMMEND_PAGE")
            put("isFirstScreen", true)
            put("cursor", 0)
            put("refresh", false)
            put("widthDp", metrics.widthPixels / metrics.density)
            put("heightDp", metrics.heightPixels / metrics.density)
            put("loadedPositionCodes", "")
            put("callbackParameters", "")
            put("extJson", "{}")
        }
        val pages = mutableListOf<JsonObject>()
        pages += Json.parseToJsonElement(NcmBridge.homeFeed("""{"inspectConfig":true}""").getOrThrow()).jsonObject
        var request = params
        val loaded = mutableListOf<String>()
        for (page in 0 until 5) {
            val response = Json.parseToJsonElement(NcmBridge.homeFeed(request.toString()).getOrThrow()).jsonObject
            pages += response
            val data = response["data"]!!.jsonObject
            loaded += data["blocks"]!!.jsonArray.mapNotNull { it.jsonObject["positionCode"]?.jsonPrimitive?.content }
            if (data["hasMore"]?.jsonPrimitive?.booleanOrNull != true || response.toString().contains("回忆歌单")) break
            request = JsonObject(params + mapOf(
                "isFirstScreen" to JsonPrimitive(false),
                "cursor" to data.getValue("cursor"),
                "blockCodeOrderList" to data.getValue("blockCodeOrderList"),
                "callbackParameters" to data.getValue("callbackParameters"),
                "loadedPositionCodes" to JsonPrimitive(Json.encodeToString(loaded)),
                "extJson" to JsonPrimitive("""{"refreshAction":"loadMore","firstRequestPerLaunch":false}"""),
            ))
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "home-feed-inspection.json")
            .writeText(JsonArray(pages).toString(), Charsets.UTF_8)
    }
}
