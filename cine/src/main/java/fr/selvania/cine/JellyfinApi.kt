package fr.selvania.cine

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Appels Jellyfin du lecteur natif et de la diffusion (même session que l'interface web). */
class JellyfinApi(
    val server: String,
    val token: String,
    val userId: String,
    private val deviceId: String,
) {
    private val auth = "MediaBrowser Client=\"Selvania Cine\", Device=\"${Build.MODEL.replace("\"", "")}\", " +
        "DeviceId=\"$deviceId\", Version=\"${BuildConfig.VERSION_NAME}\", Token=\"$token\""

    fun request(method: String, path: String, query: Map<String, Any?> = emptyMap(), body: JSONObject? = null): String {
        val qs = query.filterValues { it != null }.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v.toString(), "UTF-8")}"
        }
        val conn = URL("$server$path${if (qs.isNotEmpty()) "?$qs" else ""}").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", auth)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("Erreur $code ${text.take(120)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    fun item(id: String) = JSONObject(request("GET", "/Items/$id", mapOf("userId" to userId)))

    fun playbackInfo(
        id: String,
        startTicks: Long,
        allowDirect: Boolean,
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
        profile: JSONObject = DeviceProfile.build(),
        maxBitrate: Int = 120_000_000,
    ): JSONObject {
        val body = JSONObject()
            .put("UserId", userId)
            .put("DeviceProfile", profile)
            .put("MaxStreamingBitrate", maxBitrate)
            .put("StartTimeTicks", startTicks)
            .put("EnableDirectPlay", allowDirect)
            .put("EnableDirectStream", true)
            .put("EnableTranscoding", true)
            .put("AutoOpenLiveStream", true)
        if (audioIndex != null) body.put("AudioStreamIndex", audioIndex)
        if (subtitleIndex != null) body.put("SubtitleStreamIndex", subtitleIndex)
        return JSONObject(request("POST", "/Items/$id/PlaybackInfo", mapOf("userId" to userId), body))
    }

    /** Épisode qui suit, dans l'ordre de la série. */
    fun nextEpisode(item: JSONObject): JSONObject? {
        if (item.optString("Type") != "Episode") return null
        val series = item.optString("SeriesId").ifEmpty { return null }
        val list = JSONObject(
            request("GET", "/Shows/$series/Episodes", mapOf("userId" to userId, "startItemId" to item.getString("Id"), "limit" to 2, "fields" to "Overview")),
        ).optJSONArray("Items") ?: return null
        for (i in 0 until list.length()) {
            val e = list.getJSONObject(i)
            if (e.getString("Id") != item.getString("Id")) return e
        }
        return null
    }

    /** L'épisode donné puis ceux qui le suivent dans la série (file d'attente de la diffusion). */
    fun episodesFrom(seriesId: String, startItemId: String, limit: Int): List<JSONObject> {
        val list = JSONObject(
            request("GET", "/Shows/$seriesId/Episodes", mapOf("userId" to userId, "startItemId" to startItemId, "limit" to limit, "fields" to "Overview")),
        ).optJSONArray("Items") ?: return emptyList()
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    /** Vignette d'un épisode (ou image de la série / du film), pour la carte « Épisode suivant » et le Chromecast. */
    fun imageUrl(item: JSONObject, width: Int): String? {
        val tags = item.optJSONObject("ImageTags")
        tags?.optString("Primary")?.takeIf { it.isNotEmpty() && item.optString("Type") == "Episode" }?.let {
            return "$server/Items/${item.getString("Id")}/Images/Primary?maxWidth=$width&tag=$it"
        }
        item.optJSONArray("BackdropImageTags")?.optString(0)?.takeIf { it.isNotEmpty() }?.let {
            return "$server/Items/${item.getString("Id")}/Images/Backdrop/0?maxWidth=$width&tag=$it"
        }
        item.optJSONArray("ParentBackdropImageTags")?.optString(0)?.takeIf { it.isNotEmpty() }?.let {
            return "$server/Items/${item.optString("ParentBackdropItemId")}/Images/Backdrop/0?maxWidth=$width&tag=$it"
        }
        tags?.optString("Primary")?.takeIf { it.isNotEmpty() }?.let {
            return "$server/Items/${item.getString("Id")}/Images/Primary?maxWidth=$width&tag=$it"
        }
        return null
    }

    fun segments(id: String): JSONArray =
        runCatching { JSONObject(request("GET", "/MediaSegments/$id")).optJSONArray("Items") }.getOrNull() ?: JSONArray()

    fun report(kind: String, info: JSONObject) {
        val path = when (kind) {
            "start" -> "/Sessions/Playing"
            "progress" -> "/Sessions/Playing/Progress"
            else -> "/Sessions/Playing/Stopped"
        }
        runCatching { request("POST", path, body = info) }
    }

    fun directUrl(id: String, source: JSONObject, playSessionId: String): String {
        val q = mutableMapOf<String, Any?>(
            "static" to "true",
            "mediaSourceId" to source.getString("Id"),
            "deviceId" to deviceId,
            "ApiKey" to token,
            "playSessionId" to playSessionId,
        )
        source.optString("ETag").takeIf { it.isNotEmpty() }?.let { q["Tag"] = it }
        val qs = q.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v.toString(), "UTF-8")}" }
        return "$server/Videos/$id/stream?$qs"
    }
}
