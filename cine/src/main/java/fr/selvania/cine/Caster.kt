package fr.selvania.cine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteChooserDialog
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueData
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Diffusion sur un Chromecast avec le kit Google Cast. Le Chromecast lit la vidéo lui-même
 * (récepteur multimédia par défaut) avec les épisodes suivants en file d'attente : il les enchaîne
 * même si le téléphone est en veille. Tant que l'appli tourne, la progression est enregistrée dans
 * Jellyfin et l'épisode suivant démarre au générique, comme dans le lecteur.
 */
object Caster {

    /** Épisodes envoyés d'un coup au Chromecast (celui choisi + les suivants). */
    private const val QUEUE_SIZE = 8

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var cast: CastContext? = null
    private var pending: JSONObject? = null
    private var reporter: CastReporter? = null
    private var appContext: Context? = null

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            pending?.let { load(session, it) }
            pending = null
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            pending?.let { load(session, it) }
            pending = null
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pending = null
        }

        override fun onSessionEnding(session: CastSession) {
            reporter?.stop()
            reporter = null
        }

        override fun onSessionEnded(session: CastSession, error: Int) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    /** À appeler au démarrage (fil principal). Faux sans les services Google (certaines box, émulateurs). */
    fun init(context: Context): Boolean {
        if (cast != null) return true
        return try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) return false
            @Suppress("DEPRECATION")
            val ctx = CastContext.getSharedInstance(context)
            ctx.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
            cast = ctx
            appContext = context.applicationContext
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Demande de l'interface : choisir le Chromecast (si besoin) puis y lancer la vidéo. */
    fun start(activity: AppCompatActivity, req: JSONObject) {
        val ctx = cast ?: return
        val session = ctx.sessionManager.currentCastSession
        if (session?.isConnected == true) {
            load(session, req)
            return
        }
        pending = req
        val selector = ctx.mergedSelector ?: MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
        MediaRouteChooserDialog(activity, R.style.Theme_Cine).apply {
            routeSelector = selector
            show()
            // Le titre ne peut être changé qu'une fois la fenêtre créée.
            setTitle(R.string.cast_title)
        }
    }

    private fun load(session: CastSession, req: JSONObject) {
        val client = session.remoteMediaClient ?: return
        val context = appContext ?: return
        val device = session.castDevice?.friendlyName ?: "Chromecast"
        Toast.makeText(context, context.getString(R.string.cast_loading, device), Toast.LENGTH_SHORT).show()
        val api = JellyfinApi(req.getString("server"), req.getString("token"), req.getString("userId"), req.getString("deviceId"))
        val startTicks = req.optLong("startTicks", 0)
        io.execute {
            try {
                val first = api.item(req.getString("itemId"))
                val series = first.optString("SeriesId")
                val items = if (first.optString("Type") == "Episode" && series.isNotEmpty()) {
                    runCatching { api.episodesFrom(series, first.getString("Id"), QUEUE_SIZE) }.getOrNull()
                        ?.takeIf { it.firstOrNull()?.optString("Id") == first.getString("Id") } ?: listOf(first)
                } else {
                    listOf(first)
                }
                val queue = items.mapIndexed { i, it -> queueItem(api, it, if (i == 0) startTicks else 0) }
                main.post { send(context, client, api, req, queue, startTicks) }
            } catch (e: Exception) {
                main.post { Toast.makeText(context, context.getString(R.string.cast_error, e.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun send(context: Context, client: RemoteMediaClient, api: JellyfinApi, req: JSONObject, queue: List<MediaQueueItem>, startTicks: Long) {
        val request = MediaLoadRequestData.Builder()
            .setQueueData(
                MediaQueueData.Builder()
                    .setItems(queue)
                    .setStartIndex(0)
                    .setRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_OFF)
                    .build(),
            )
            .setAutoplay(true)
            .setCurrentTime(startTicks / 10_000L)
            .build()
        client.load(request).setResultCallback { result ->
            if (!result.status.isSuccess) {
                Toast.makeText(context, context.getString(R.string.cast_error, result.status.statusMessage ?: "le Chromecast a refusé la vidéo"), Toast.LENGTH_LONG).show()
                return@setResultCallback
            }
            reporter?.stop()
            reporter = CastReporter(api, client, req.optInt("upNextDelay", 5), req.optBoolean("autoSkipIntro", false))
            context.startActivity(Intent(context, CastControllerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Une vidéo de la file : adresse lisible par le Chromecast (directe ou convertie), titres et image. */
    private fun queueItem(api: JellyfinApi, item: JSONObject, startTicks: Long): MediaQueueItem {
        val id = item.getString("Id")
        val info = api.playbackInfo(id, startTicks, allowDirect = true, profile = DeviceProfile.cast(), maxBitrate = 20_000_000)
        val src = info.getJSONArray("MediaSources").getJSONObject(0)
        val session = info.getString("PlaySessionId")

        val url: String
        val type: String
        val method: String
        if (src.optBoolean("SupportsDirectPlay")) {
            url = api.directUrl(id, src, session)
            type = if (src.optString("Container").contains("webm")) "video/webm" else "video/mp4"
            method = "DirectPlay"
        } else {
            val transcode = src.optString("TranscodingUrl").ifEmpty { throw IllegalStateException("format non pris en charge") }
            url = api.server + transcode
            type = "application/x-mpegURL"
            method = "Transcode"
        }

        val episode = item.optString("Type") == "Episode"
        val meta = MediaMetadata(if (episode) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.optString("Name"))
            if (episode) {
                putString(MediaMetadata.KEY_SERIES_TITLE, item.optString("SeriesName"))
                putString(MediaMetadata.KEY_SUBTITLE, "${item.optString("SeriesName")} · S${item.optInt("ParentIndexNumber")} É${item.optInt("IndexNumber")}")
                putInt(MediaMetadata.KEY_SEASON_NUMBER, item.optInt("ParentIndexNumber"))
                putInt(MediaMetadata.KEY_EPISODE_NUMBER, item.optInt("IndexNumber"))
            } else {
                item.optInt("ProductionYear").takeIf { it > 0 }?.let { putString(MediaMetadata.KEY_SUBTITLE, it.toString()) }
            }
            api.imageUrl(item, 1280)?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val custom = JSONObject()
            .put("itemId", id)
            .put("mediaSourceId", src.optString("Id"))
            .put("playSessionId", session)
            .put("method", method)
            .put("runTimeTicks", item.optLong("RunTimeTicks"))
        val media = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(type)
            .setMetadata(meta)
            .apply { item.optLong("RunTimeTicks").takeIf { it > 0 }?.let { setStreamDuration(it / 10_000L) } }
            .setCustomData(custom)
            .build()
        return MediaQueueItem.Builder(media).setAutoplay(true).setPreloadTime(20.0).setCustomData(custom).build()
    }
}

/**
 * Suit la lecture sur le Chromecast : progression enregistrée dans Jellyfin (reprise, « vu »),
 * intro passée si demandé, et épisode suivant lancé au générique après le délai choisi.
 */
private class CastReporter(
    private val api: JellyfinApi,
    private val client: RemoteMediaClient,
    private val upNextDelay: Int,
    private val autoSkipIntro: Boolean,
) : RemoteMediaClient.Callback(), RemoteMediaClient.ProgressListener {

    private val io = Executors.newSingleThreadExecutor()
    private var current: JSONObject? = null
    private var positionMs = 0L
    private var lastReport = 0L
    @Volatile private var segments = JSONArray()
    private var creditsSince = 0L
    private var skippedTo = -1L
    private var advanced = false

    init {
        client.registerCallback(this)
        client.addProgressListener(this, 1000)
        onStatusUpdated()
    }

    private fun info(c: JSONObject) = JSONObject()
        .put("ItemId", c.optString("itemId"))
        .put("MediaSourceId", c.optString("mediaSourceId"))
        .put("PlaySessionId", c.optString("playSessionId"))
        .put("PositionTicks", positionMs * 10_000L)
        .put("IsPaused", client.isPaused)
        .put("CanSeek", true)
        .put("PlayMethod", c.optString("method"))

    private fun report(kind: String, c: JSONObject) {
        val payload = info(c)
        io.execute { api.report(kind, payload) }
    }

    override fun onStatusUpdated() {
        val custom = client.currentItem?.customData ?: client.mediaInfo?.customData
        val id = custom?.optString("itemId")
        if (id != null && id != current?.optString("itemId")) {
            current?.let { report("stopped", it) }
            current = custom
            positionMs = client.approximateStreamPosition
            segments = JSONArray()
            creditsSince = 0
            skippedTo = -1
            advanced = false
            report("start", custom)
            io.execute { segments = api.segments(id) }
        }
        val status = client.mediaStatus
        if (status?.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_FINISHED && !client.hasNext()) {
            current?.let { report("stopped", it) }
            current = null
        }
    }

    override fun onProgressUpdated(progressMs: Long, durationMs: Long) {
        val c = current ?: return
        positionMs = progressMs
        val now = System.currentTimeMillis()
        if (now - lastReport > 10_000) {
            lastReport = now
            report("progress", c)
        }
        val ticks = progressMs * 10_000L
        if (autoSkipIntro) {
            for (i in 0 until segments.length()) {
                val s = segments.getJSONObject(i)
                val type = s.optString("Type")
                val end = s.optLong("EndTicks")
                if ((type == "Intro" || type == "Recap") && ticks >= s.optLong("StartTicks") && ticks < end - 20_000_000L && skippedTo != end) {
                    skippedTo = end
                    client.seek(com.google.android.gms.cast.MediaSeekOptions.Builder().setPosition(end / 10_000L).build())
                }
            }
        }
        // Générique de fin : épisode suivant après le délai (comme la carte du lecteur).
        if (upNextDelay <= 0 || advanced || !client.hasNext()) return
        val duration = if (durationMs > 0) durationMs else c.optLong("runTimeTicks") / 10_000L
        var outro = Long.MAX_VALUE
        for (i in 0 until segments.length()) {
            val s = segments.getJSONObject(i)
            if (s.optString("Type") == "Outro") outro = s.optLong("StartTicks") / 10_000L
        }
        if (outro == Long.MAX_VALUE && duration > 300_000) outro = duration - 25_000
        if (progressMs < outro || client.isPaused) {
            if (progressMs < outro) creditsSince = 0
            return
        }
        if (creditsSince == 0L) creditsSince = now
        if (now - creditsSince >= upNextDelay * 1000L) {
            advanced = true
            client.queueNext(null)
        }
    }

    private fun RemoteMediaClient.hasNext(): Boolean {
        val status = mediaStatus ?: return false
        val items = status.queueItems
        val index = items.indexOfFirst { it.itemId == status.currentItemId }
        return index >= 0 && index < items.size - 1
    }

    fun stop() {
        current?.let { report("stopped", it) }
        current = null
        client.unregisterCallback(this)
        client.removeProgressListener(this)
        io.shutdown()
    }
}
