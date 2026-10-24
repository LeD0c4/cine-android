package fr.selvania.cine

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.graphics.Outline
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Lecteur natif : lit le fichier d'origine (MKV, HEVC, Dolby…) sans conversion par le serveur,
 * enregistre la progression dans Jellyfin et enchaîne les épisodes.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    private lateinit var api: JellyfinApi
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var skipButton: Button
    private lateinit var nextButton: Button
    private lateinit var top: View
    private lateinit var upNext: View
    private lateinit var upNextPlay: View
    private lateinit var upNextFill: View
    private lateinit var upNextLabel: TextView
    private lateinit var upNextHit: View

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var itemId = ""
    private var item: JSONObject? = null
    private var source: JSONObject? = null
    private var playSessionId = ""
    private var method = "DirectPlay"
    private var triedTranscode = false
    private var next: JSONObject? = null
    private var segments = JSONArray()
    private var reportedStop = true

    // Réglages choisis dans Ciné (Réglages › Épisode suivant / Intro).
    private var upNextDelayMs = 5_000L
    private var autoSkipIntro = false
    private var upNextShown = false
    private var upNextDismissed = false
    private var upNextLeftMs = 0L
    private var upNextLastTick = 0L
    private var autoSkippedTo = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_player)

        view = findViewById(R.id.player)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        skipButton = findViewById(R.id.skip)
        nextButton = findViewById(R.id.next)
        top = findViewById(R.id.top)
        upNext = findViewById(R.id.upnext)
        upNextPlay = findViewById(R.id.upnext_play)
        upNextFill = findViewById(R.id.upnext_fill)
        upNextLabel = findViewById(R.id.upnext_label)
        upNextHit = findViewById(R.id.upnext_hit)
        upNextHit.setOnClickListener { cancelUpNext() }
        upNextFill.pivotX = 0f
        upNextPlay.outlineProvider = ViewOutlineProvider.BACKGROUND
        upNextPlay.clipToOutline = true

        val req = JSONObject(intent.getStringExtra("request") ?: "{}")
        upNextDelayMs = req.optInt("upNextDelay", 5).coerceAtLeast(0) * 1000L
        autoSkipIntro = req.optBoolean("autoSkipIntro", false)
        api = JellyfinApi(req.getString("server"), req.getString("token"), req.getString("userId"), req.getString("deviceId"))
        titleView.text = req.optString("title")
        subtitleView.text = req.optString("subtitle")

        val selector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setPreferredAudioLanguages("fre", "fra", "fr").setPreferredTextLanguage(null))
        }
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setTrackSelector(selector)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        view.player = player
        view.setShowNextButton(false)
        view.setShowPreviousButton(false)
        view.subtitleView?.apply {
            setStyle(CaptionStyleCompat(0xFFFFFFFF.toInt(), 0x00000000, 0x00000000, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, 0xFF000000.toInt(), null))
            setFractionalTextSize(0.058f)
            setBottomPaddingFraction(0.08f)
        }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> top.visibility = visibility })

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (source != null) report("progress")
            }

            override fun onPlayerError(error: PlaybackException) {
                // Format refusé par la télé : on redemande au serveur une version convertie.
                if (!triedTranscode) {
                    triedTranscode = true
                    load(itemId, player.currentPosition * 10_000L, allowDirect = false)
                } else {
                    Toast.makeText(this@PlayerActivity, getString(R.string.play_error, error.errorCodeName), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        })

        skipButton.setOnClickListener { skipSegment() }
        nextButton.setOnClickListener { playNext() }
        upNextPlay.setOnClickListener { playNext() }
        findViewById<Button>(R.id.upnext_credits).setOnClickListener { cancelUpNext() }

        load(req.getString("itemId"), req.optLong("startTicks", 0), allowDirect = true)
        main.postDelayed(ticker, 500)
    }

    private fun load(id: String, startTicks: Long, allowDirect: Boolean) {
        if (source != null) report("stopped")
        itemId = id
        io.execute {
            try {
                val it = api.item(id)
                val info = api.playbackInfo(id, startTicks, allowDirect)
                val src = info.getJSONArray("MediaSources").getJSONObject(0)
                val nx = runCatching { api.nextEpisode(it) }.getOrNull()
                val segs = api.segments(id)
                main.post { start(it, info.getString("PlaySessionId"), src, startTicks, allowDirect, nx, segs) }
            } catch (e: Exception) {
                main.post {
                    Toast.makeText(this, getString(R.string.play_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun start(it: JSONObject, session: String, src: JSONObject, startTicks: Long, allowDirect: Boolean, nx: JSONObject?, segs: JSONArray) {
        item = it
        source = src
        playSessionId = session
        next = nx
        segments = segs
        nextButton.visibility = if (nx != null) View.VISIBLE else View.GONE
        upNextDismissed = false
        autoSkippedTo = -1
        hideUpNext(animate = false)
        nx?.let { loadThumb(it) }

        if (it.optString("Type") == "Episode") {
            titleView.text = it.optString("SeriesName")
            subtitleView.text = "S${it.optInt("ParentIndexNumber")} · É${it.optInt("IndexNumber")} — ${it.optString("Name")}"
        } else {
            titleView.text = it.optString("Name")
            subtitleView.text = listOfNotNull(it.optInt("ProductionYear").takeIf { y -> y > 0 }?.toString()).joinToString()
        }

        val builder = MediaItem.Builder()
            .setMediaMetadata(MediaMetadata.Builder().setTitle(titleView.text).setSubtitle(subtitleView.text).build())
        if (allowDirect && src.optBoolean("SupportsDirectPlay")) {
            method = "DirectPlay"
            builder.setUri(api.directUrl(itemId, src, session))
        } else {
            val url = src.optString("TranscodingUrl")
            if (url.isEmpty()) {
                Toast.makeText(this, getString(R.string.play_error, "format non pris en charge"), Toast.LENGTH_LONG).show()
                finish()
                return
            }
            method = "Transcode"
            builder.setUri(api.server + url).setMimeType(MimeTypes.APPLICATION_M3U8)
        }

        // Sous-titres en fichiers séparés (.srt à côté du film) : convertis en WebVTT par Jellyfin.
        val subs = mutableListOf<MediaItem.SubtitleConfiguration>()
        val streams = src.optJSONArray("MediaStreams") ?: JSONArray()
        val defaultSub = src.optInt("DefaultSubtitleStreamIndex", -1)
        for (i in 0 until streams.length()) {
            val s = streams.getJSONObject(i)
            if (s.optString("Type") != "Subtitle" || s.optString("DeliveryMethod") != "External") continue
            val delivery = s.optString("DeliveryUrl")
            if (delivery.isEmpty()) continue
            subs += MediaItem.SubtitleConfiguration.Builder(Uri.parse(if (delivery.startsWith("http")) delivery else api.server + delivery))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(s.optString("Language").ifEmpty { null })
                .setLabel(s.optString("DisplayTitle"))
                .setSelectionFlags(if (s.optInt("Index") == defaultSub) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        builder.setSubtitleConfigurations(subs)

        player.setMediaItem(builder.build(), startTicks / 10_000L)
        player.prepare()
        player.playWhenReady = true
        reportedStop = false
        report("start")
    }

    // ---------- Suivi de lecture ----------

    private fun info() = JSONObject()
        .put("ItemId", itemId)
        .put("MediaSourceId", source?.optString("Id"))
        .put("PlaySessionId", playSessionId)
        .put("PositionTicks", player.currentPosition * 10_000L)
        .put("IsPaused", !player.isPlaying)
        .put("CanSeek", true)
        .put("PlayMethod", method)

    private fun report(kind: String) {
        if (kind == "stopped") {
            if (reportedStop) return
            reportedStop = true
        }
        val payload = info()
        io.execute { api.report(kind, payload) }
    }

    private var lastProgress = 0L
    private val ticker = object : Runnable {
        override fun run() {
            updateSkip()
            updateUpNext()
            val now = System.currentTimeMillis()
            if (player.isPlaying && now - lastProgress > 10_000) {
                lastProgress = now
                report("progress")
            }
            main.postDelayed(this, 250)
        }
    }

    // ---------- Intro et épisode suivant ----------

    private fun currentSegment(): JSONObject? {
        val t = player.currentPosition * 10_000L
        for (i in 0 until segments.length()) {
            val s = segments.getJSONObject(i)
            val type = s.optString("Type")
            if ((type == "Intro" || type == "Recap") && t >= s.optLong("StartTicks") && t < s.optLong("EndTicks") - 20_000_000L) return s
        }
        return null
    }

    private fun updateSkip() {
        val seg = currentSegment()
        // Option « Passer automatiquement » : une seule fois par passage dans l'intro.
        if (seg != null && autoSkipIntro && autoSkippedTo != seg.optLong("EndTicks")) {
            autoSkippedTo = seg.optLong("EndTicks")
            player.seekTo(autoSkippedTo / 10_000L)
            return
        }
        if (seg != null && skipButton.visibility != View.VISIBLE) {
            skipButton.text = getString(if (seg.optString("Type") == "Recap") R.string.skip_recap else R.string.skip_intro)
            skipButton.visibility = View.VISIBLE
            if (!view.isControllerFullyVisible) skipButton.requestFocus()
        } else if (seg == null && skipButton.visibility == View.VISIBLE) {
            skipButton.visibility = View.GONE
        }
    }

    private fun skipSegment() {
        currentSegment()?.let { player.seekTo(it.optLong("EndTicks") / 10_000L) }
        skipButton.visibility = View.GONE
    }

    private fun playNext() {
        val nx = next ?: return
        // Plus de « suivant » tant que le nouvel épisode charge (évite un double lancement).
        next = null
        hideUpNext(animate = false)
        triedTranscode = false
        load(nx.getString("Id"), 0, allowDirect = true)
    }

    // ---------- Carte « Épisode suivant » au générique ----------

    /** Début du générique : repéré par le serveur, sinon les 25 dernières secondes. */
    private fun outroAtMs(): Long {
        for (i in 0 until segments.length()) {
            val s = segments.getJSONObject(i)
            if (s.optString("Type") == "Outro") return s.optLong("StartTicks") / 10_000L
        }
        val duration = player.duration
        return if (duration != C.TIME_UNSET && duration > 300_000) duration - 25_000 else Long.MAX_VALUE
    }

    private fun updateUpNext() {
        val nx = next ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        if (player.currentPosition < outroAtMs()) {
            // Retour en arrière avant le générique : la carte pourra réapparaître.
            upNextDismissed = false
            if (upNextShown) hideUpNext(animate = true)
            return
        }
        if (upNextDismissed) return
        val now = SystemClock.elapsedRealtime()
        if (!upNextShown) {
            upNextShown = true
            upNextLeftMs = upNextDelayMs
            upNextLastTick = now
            findViewById<TextView>(R.id.upnext_title).text =
                "S${nx.optInt("ParentIndexNumber")} · É${nx.optInt("IndexNumber")} — ${nx.optString("Name")}"
            findViewById<TextView>(R.id.upnext_overview).apply {
                text = nx.optString("Overview")
                visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            upNextFill.animate().cancel()
            upNextFill.scaleX = 0f
            showPostPlay()
        }
        if (upNextDelayMs <= 0) {
            upNextLabel.text = getString(R.string.up_next)
            return
        }
        // Le compte à rebours s'arrête quand la vidéo est en pause.
        if (player.isPlaying) upNextLeftMs -= now - upNextLastTick
        upNextLastTick = now
        upNextLabel.text = getString(R.string.up_next_in, ceil(upNextLeftMs.coerceAtLeast(0) / 1000.0).toInt())
        upNextFill.animate()
            .scaleX((1f - upNextLeftMs.toFloat() / upNextDelayMs).coerceIn(0f, 1f))
            .setDuration(250)
            .setInterpolator(LinearInterpolator())
            .start()
        if (upNextLeftMs <= 0) playNext()
    }

    private fun dp(v: Int) = v * resources.displayMetrics.density

    /** La vidéo se réduit à gauche (elle continue), l'épisode suivant apparaît à droite. */
    private fun showPostPlay() {
        view.useController = false
        view.hideController()
        top.visibility = View.GONE
        skipButton.visibility = View.GONE

        val root = view.parent as View
        val panelWidth = (root.width * 0.42f).toInt()
        upNext.layoutParams = upNext.layoutParams.apply { width = panelWidth }
        val thumb = findViewById<ImageView>(R.id.upnext_thumb)
        // Vignette limitée en hauteur : tout le panneau doit tenir sur un téléphone à l'horizontale.
        val thumbHeight = minOf(panelWidth * 9 / 16, (root.height * 0.34f).toInt())
        thumb.layoutParams = thumb.layoutParams.apply {
            width = thumbHeight * 16 / 9
            height = thumbHeight
        }

        view.pivotX = 0f
        view.pivotY = view.height / 2f
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, dp(40))
        }
        view.clipToOutline = true
        view.animate().cancel()
        view.animate()
            .scaleX(SMALL).scaleY(SMALL).translationX(dp(48))
            .setDuration(700).setInterpolator(DecelerateInterpolator(2f))
            .start()

        upNext.alpha = 0f
        upNext.translationX = dp(32)
        upNext.visibility = View.VISIBLE
        upNext.animate().alpha(1f).translationX(0f).setStartDelay(200).setDuration(500).start()
        upNextHit.visibility = View.VISIBLE
        upNextPlay.requestFocus()
    }

    /** « Annuler » : la vidéo reprend tout l'écran, on regarde le générique jusqu'au bout. */
    private fun cancelUpNext() {
        upNextDismissed = true
        hideUpNext(animate = true)
    }

    private fun hideUpNext(animate: Boolean) {
        if (!upNextShown && upNext.visibility != View.VISIBLE) return
        upNextShown = false
        upNext.animate().cancel()
        upNext.visibility = View.GONE
        upNextHit.visibility = View.GONE
        view.animate().cancel()
        val restore = {
            view.clipToOutline = false
            view.useController = true
        }
        if (animate) {
            view.animate().scaleX(1f).scaleY(1f).translationX(0f)
                .setDuration(500).setInterpolator(DecelerateInterpolator(2f))
                .withEndAction(restore).start()
        } else {
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            restore()
        }
    }

    private fun loadThumb(nx: JSONObject) {
        val thumb = findViewById<ImageView>(R.id.upnext_thumb)
        thumb.setImageDrawable(null)
        val url = api.imageUrl(nx, 480) ?: return
        io.execute {
            val bmp = runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bmp != null) main.post { if (next === nx) thumb.setImageBitmap(bmp) }
        }
    }

    private fun onEnded() {
        report("stopped")
        when {
            next == null || upNextDismissed -> finish()
            upNextDelayMs > 0 -> playNext()
            // Lecture automatique désactivée : la carte reste affichée jusqu'à un appui.
            else -> updateUpNext()
        }
    }

    // ---------- Télécommande ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Retour pendant l'écran de fin : on revient au générique au lieu de quitter.
        if (upNextShown && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) cancelUpNext()
            return true
        }
        if (upNextShown) return super.dispatchKeyEvent(event)
        // Commandes masquées : gauche / droite avancent ou reculent de 10 s sans ouvrir le menu.
        if (event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible && skipButton.visibility != View.VISIBLE && upNext.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { player.seekBack(); view.showController(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekForward(); view.showController(); return true }
            }
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private companion object {
        /** Taille de la vidéo pendant l'écran de fin. */
        const val SMALL = 0.46f
    }

    override fun onStop() {
        super.onStop()
        if (source != null) report("stopped")
        player.pause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        player.release()
        io.shutdown()
        super.onDestroy()
    }
}
