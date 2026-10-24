package fr.selvania.cine

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Mises à jour automatiques : la dernière « release » du dépôt public de mises à jour
 * (BuildConfig.UPDATE_FEED) porte l'APK signé. Si elle est plus récente que l'appli, on propose
 * de l'installer ; l'APK est téléchargé puis confié à l'installeur d'Android.
 */
object Updater {

    /** Une recherche au plus toutes les 6 heures au démarrage (l'API GitHub limite les appels). */
    private const val INTERVAL = 6 * 3600_000L

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** APK téléchargé en attente de l'autorisation « installer des applis » (réglage Android). */
    private var pending: File? = null

    private data class Release(val version: String, val url: String, val notes: String)

    /** Au démarrage (silencieux), ou depuis Réglages › Rechercher une mise à jour (`manual`). */
    fun check(activity: AppCompatActivity, manual: Boolean = false) {
        val feed = BuildConfig.UPDATE_FEED
        if (feed.isBlank()) return
        val prefs = activity.getSharedPreferences("updates", AppCompatActivity.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && now - prefs.getLong("lastCheck", 0) < INTERVAL) return
        prefs.edit().putLong("lastCheck", now).apply()

        io.execute {
            val release = runCatching { latest(feed) }
            main.post {
                if (activity.isFinishing) return@post
                val r = release.getOrNull()
                when {
                    r != null && newer(r.version, BuildConfig.VERSION_NAME) -> offer(activity, r)
                    manual && release.isFailure ->
                        toast(activity, activity.getString(R.string.update_failed, release.exceptionOrNull()?.message ?: ""))
                    manual -> toast(activity, activity.getString(R.string.update_none, BuildConfig.VERSION_NAME))
                }
            }
        }
    }

    /** Retour des réglages Android : l'autorisation est peut-être accordée, on relance l'installation. */
    fun resume(activity: AppCompatActivity) {
        val apk = pending ?: return
        if (canInstall(activity)) {
            pending = null
            install(activity, apk)
        }
    }

    private fun latest(feed: String): Release {
        val json = JSONObject(get("https://api.github.com/repos/$feed/releases/latest"))
        val version = json.getString("tag_name").removePrefix("v")
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                return Release(version, a.getString("browser_download_url"), json.optString("body"))
            }
        }
        throw IllegalStateException("pas d'APK dans la version $version")
    }

    private fun offer(activity: AppCompatActivity, r: Release) {
        val text = buildString {
            append(activity.getString(R.string.update_text, r.version, BuildConfig.VERSION_NAME))
            if (r.notes.isNotBlank()) append("\n\n").append(r.notes.trim().take(600))
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(text)
            .setPositiveButton(R.string.update_install) { _, _ -> download(activity, r) }
            .setNegativeButton(R.string.update_later, null)
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun download(activity: AppCompatActivity, r: Release) {
        toast(activity, activity.getString(R.string.update_downloading))
        val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, "Selvania-Cine-${r.version}.apk")
        io.execute {
            val result = runCatching {
                val conn = URL(r.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "SelvaniaCine/${BuildConfig.VERSION_NAME}")
                try {
                    if (conn.responseCode !in 200..299) throw IllegalStateException("erreur ${conn.responseCode}")
                    conn.inputStream.use { input -> apk.outputStream().use { input.copyTo(it) } }
                } finally {
                    conn.disconnect()
                }
            }
            main.post {
                result.exceptionOrNull()?.let {
                    toast(activity, activity.getString(R.string.update_failed, it.message ?: ""))
                    return@post
                }
                if (canInstall(activity)) {
                    install(activity, apk)
                } else {
                    // Première fois : Android demande d'autoriser l'appli à installer des applis.
                    pending = apk
                    toast(activity, activity.getString(R.string.update_allow))
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                        )
                    }
                }
            }
        }
    }

    private fun canInstall(activity: AppCompatActivity) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun install(activity: AppCompatActivity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
            .onFailure { toast(activity, activity.getString(R.string.update_failed, it.message ?: "")) }
    }

    /** « 2.0.10 » est plus récent que « 2.0.9 » (comparaison nombre par nombre). */
    internal fun newer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "SelvaniaCine/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode == 404) throw IllegalStateException("aucune version publiée")
            if (conn.responseCode !in 200..299) throw IllegalStateException("erreur ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(activity: AppCompatActivity, text: String) =
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
}
