package fr.selvania.cine

import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewStub
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Selvania Ciné : l'interface façon Netflix (intégrée à l'appli, serveur Jellyfin au choix),
 * le lecteur natif (ExoPlayer) pour les vidéos et la diffusion Chromecast sur téléphone.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var splash: View
    private lateinit var offline: View
    private var failed = false
    private var playing = false
    private var castReady = false

    /** Télé (Android TV / Google TV) : navigation à la télécommande, pas de diffusion vers un Chromecast. */
    private val isTv: Boolean by lazy {
        (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    // L'interface est servie depuis l'appli sous une adresse https (stockage et cookies comme un site).
    private val assets by lazy {
        WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        splash = findViewById(R.id.splash)
        offline = findViewById(R.id.offline)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        castReady = !isTv && Caster.init(this)
        if (castReady) findViewById<ViewStub>(R.id.cast_mini).inflate()

        setupWebView()
        findViewById<Button>(R.id.retry).setOnClickListener { load() }

        // Retour (télécommande ou téléphone) : l'interface ferme d'abord ses menus / revient à l'écran précédent.
        onBackPressedDispatcher.addCallback(this) {
            if (offline.visibility == View.VISIBLE) {
                finish()
                return@addCallback
            }
            web.evaluateJavascript("(window.__selvaniaBack ? window.__selvaniaBack() : false)") { handled ->
                if (handled != "true") finish()
            }
        }
        load()
        Updater.check(this)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Serveurs Jellyfin de la maison en http:// depuis l'interface en https://.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // « SelvaniaTV » : l'interface passe en mode télécommande. « SelvaniaCine » : téléphone.
            userAgentString = "$userAgentString ${if (isTv) "SelvaniaTV" else "SelvaniaCine"}/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.setBackgroundColor(getColor(R.color.graphite))
        web.addJavascriptInterface(Bridge(), "SelvaniaTV")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == HOST) return false
                // Bande-annonce, liens externes : ouverts dans l'appli adaptée.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                failed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                CookieManager.getInstance().flush()
                if (!failed) {
                    splash.visibility = View.GONE
                    offline.visibility = View.GONE
                    web.requestFocus()
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline(error.description?.toString())
            }
        }
    }

    private fun load() {
        offline.visibility = View.GONE
        splash.visibility = View.VISIBLE
        web.loadUrl(HOME)
    }

    private fun showOffline(detail: String?) {
        failed = true
        splash.visibility = View.GONE
        offline.visibility = View.VISIBLE
        findViewById<TextView>(R.id.offline_text).text = getString(R.string.offline_text) + (detail?.let { "\n($it)" } ?: "")
        findViewById<Button>(R.id.retry).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Retour des réglages Android (autorisation d'installer) : la mise à jour reprend.
        Updater.resume(this)
        // Retour du lecteur : l'interface relit la progression (reprise, « vu »).
        if (playing) {
            playing = false
            web.evaluateJavascript("window.dispatchEvent(new Event('selvania:resume'))", null)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    /** Appelé par l'interface web : `SelvaniaTV.play(json)`, `SelvaniaTV.cast(json)`… */
    inner class Bridge {
        @JavascriptInterface
        fun play(json: String) {
            runOnUiThread {
                playing = true
                startActivity(Intent(this@MainActivity, PlayerActivity::class.java).putExtra("request", json))
            }
        }

        @JavascriptInterface
        fun canCast(): Boolean = castReady

        @JavascriptInterface
        fun cast(json: String) {
            runOnUiThread {
                runCatching { Caster.start(this@MainActivity, JSONObject(json)) }.onFailure {
                    Log.e("SelvaniaCine", "Diffusion impossible", it)
                    Toast.makeText(this@MainActivity, getString(R.string.cast_error, it.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }

        @JavascriptInterface
        fun version(): String = BuildConfig.VERSION_NAME

        /** Réglages › Rechercher une mise à jour. */
        @JavascriptInterface
        fun checkUpdate() {
            runOnUiThread { Updater.check(this@MainActivity, manual = true) }
        }

        @JavascriptInterface
        fun canUpdate(): Boolean = BuildConfig.UPDATE_FEED.isNotBlank()
    }

    private companion object {
        const val HOST = "appassets.androidplatform.net"
        const val HOME = "https://$HOST/assets/cine/index.html"
    }
}
