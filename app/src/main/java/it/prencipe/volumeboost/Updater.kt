package it.prencipe.volumeboost

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Aggiornamenti dall'app (stesso schema di TrainUP): legge l'ultima Release pubblica
 * su GitHub, scarica l'APK e lo passa all'installatore di Android.
 * Non serve nessun account GitHub: il repository è pubblico.
 */
object Updater {
    const val REPO = "NutriEvolution/VolumeBoost"

    class Info(val version: String, val notes: String, val url: String)

    private val main = Handler(Looper.getMainLooper())

    // Da dove si può scaricare: solo le release di questo repository (e i server di GitHub per i file)
    private fun urlAmmesso(u: URL, primo: Boolean): Boolean {
        if (u.protocol != "https") return false
        val h = u.host.lowercase()
        return if (primo) {
            h == "github.com" && u.path.lowercase().startsWith("/${REPO.lowercase()}/releases/download/")
        } else {
            h == "github.com" || h.endsWith(".githubusercontent.com")
        }
    }

    // Impronte SHA-256 dei certificati di firma (dell'app installata o di un APK scaricato)
    @Suppress("DEPRECATION")
    private fun impronte(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.let { si ->
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            }
        } else info.signatures
        return sigs?.map { sg ->
            MessageDigest.getInstance("SHA-256").digest(sg.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }?.toSet() ?: emptySet()
    }

    @Suppress("DEPRECATION")
    private fun flagFirme() =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun codiceVersione(p: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    /** Controlla che l'APK scaricato sia Volume Boost, più recente e firmato con la stessa chiave. */
    private fun apkValido(act: Activity, f: File): String? {
        val pm = act.packageManager
        val nuovo = pm.getPackageArchiveInfo(f.absolutePath, flagFirme()) ?: return "file non valido"
        if (nuovo.packageName != act.packageName) return "il file non è Volume Boost"
        val mio = pm.getPackageInfo(act.packageName, flagFirme())
        if (codiceVersione(nuovo) <= codiceVersione(mio)) return "non è più recente di quella installata"
        val a = impronte(nuovo)
        val b = impronte(mio)
        // Se Android non restituisce le firme dell'archivio decide comunque l'installatore,
        // che rifiuta firme diverse.
        if (a.isNotEmpty() && b.isNotEmpty() && a.intersect(b).isEmpty()) return "firma non riconosciuta"
        return null
    }

    private fun newer(remote: String, local: String): Boolean {
        val r = remote.trimStart('v', 'V').split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val l = local.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /**
     * Cerca una versione più recente. Risposta sul thread principale:
     * (info, null) = aggiornamento disponibile; (null, null) = già aggiornata; (null, errore) = problema.
     */
    fun check(onResult: (Info?, String?) -> Unit) = Thread {
        var info: Info? = null
        var errore: String? = null
        try {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest")
                .openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 10000
            c.readTimeout = 15000
            if (c.responseCode != 200) throw Exception("GitHub ha risposto ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name")
            val assets = j.optJSONArray("assets")
            var url = ""
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url"); break
                }
            }
            if (url.isNotEmpty() && newer(tag, BuildConfig.VERSION_NAME)) {
                info = Info(tag.trimStart('v', 'V'), j.optString("body"), url)
            }
        } catch (e: Exception) {
            errore = e.message ?: "errore di connessione"
        }
        main.post { onResult(info, errore) }
    }.start()

    /** Il permesso "installa app sconosciute" per questa app è già concesso? */
    fun puoInstallare(act: Activity): Boolean = act.packageManager.canRequestPackageInstalls()

    /**
     * Scarica l'APK, lo verifica e apre l'installatore.
     * onProgress riceve 0-100 (o -1 se la dimensione non è nota); onDone riceve null se tutto ok.
     */
    fun download(act: Activity, url: String, onProgress: (Int) -> Unit, onDone: (String?) -> Unit) = Thread {
        try {
            val dir = File(act.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val f = File(dir, "update.apk")
            var u = URL(url)
            if (!urlAmmesso(u, true)) throw SecurityException("indirizzo non ammesso")
            var c: HttpURLConnection
            var hops = 0
            while (true) { // segue i reindirizzamenti di GitHub verso i suoi server di file
                c = u.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000
                c.readTimeout = 30000
                val code = c.responseCode
                if (code in 300..399 && hops++ < 5) {
                    u = URL(u, c.getHeaderField("Location"))
                    if (!urlAmmesso(u, false)) throw SecurityException("reindirizzamento non ammesso")
                    continue
                }
                if (code != 200) throw Exception("download fallito (${code})")
                break
            }
            val total = c.contentLengthLong
            var done = 0L
            var last = -2
            c.inputStream.use { inp ->
                f.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        val p = if (total > 0) (done * 100 / total).toInt() else -1
                        if (p != last) {
                            last = p
                            main.post { onProgress(p) }
                        }
                    }
                }
            }
            val errore = apkValido(act, f)
            if (errore != null) {
                f.delete()
                main.post { onDone(errore) }
                return@Thread
            }
            val uri = FileProvider.getUriForFile(act, "${act.packageName}.files", f)
            main.post {
                act.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                onDone(null)
            }
        } catch (e: Exception) {
            main.post { onDone(e.message ?: "errore di connessione") }
        }
    }.start()
}
