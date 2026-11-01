package it.prencipe.volumeboost

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var knob: KnobView
    private lateinit var versionLabel: TextView

    // Aggiornamento in attesa del permesso "installa app sconosciute"
    private var pendingUrl: String? = null
    private var downloading = false
    private var progressDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        knob = findViewById(R.id.knob)
        knob.onValueChange = { db -> applyGain(db) }

        versionLabel = findViewById(R.id.version)
        versionLabel.text = "v${BuildConfig.VERSION_NAME} · cerca aggiornamenti"
        versionLabel.setOnClickListener { checkUpdates(silent = false) }

        pendingUrl = savedInstanceState?.getString(KEY_PENDING)

        // Controllo automatico all'apertura: avvisa solo se c'è una versione nuova
        if (savedInstanceState == null) checkUpdates(silent = true)
    }

    override fun onResume() {
        super.onResume()
        // Ricarica valore e stato (il boost può essere stato spento dal riquadro rapido)
        knob.value = ForegroundService.savedGain(this)
        knob.active = ForegroundService.isEnabled(this)

        // Di ritorno dalle impostazioni: se il permesso ora c'è, riprende l'aggiornamento
        val url = pendingUrl
        if (url != null && Updater.puoInstallare(this)) {
            pendingUrl = null
            startDownload(url)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING, pendingUrl)
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        super.onDestroy()
    }

    private fun applyGain(db: Float) {
        ForegroundService.saveGain(this, db)
        if (db <= 0f) {
            // Manopola a 0 dB = boost spento
            ForegroundService.disable(this)
        } else {
            // Girare la manopola riaccende il boost con il nuovo valore
            ForegroundService.enable(this)
        }
        knob.active = ForegroundService.isEnabled(this)
    }

    // ---------- Aggiornamenti ----------

    private fun alive() = !isFinishing && !isDestroyed

    private fun checkUpdates(silent: Boolean) {
        if (downloading) return
        if (!silent) versionLabel.text = "Controllo in corso…"
        Updater.check { info, errore ->
            if (!alive()) return@check
            versionLabel.text = "v${BuildConfig.VERSION_NAME} · cerca aggiornamenti"
            when {
                info != null -> showUpdateDialog(info)
                silent -> Unit // all'avvio non disturba se non c'è nulla o manca la rete
                errore != null -> toast("Impossibile controllare: $errore")
                else -> toast("Hai già l'ultima versione")
            }
        }
    }

    private fun showUpdateDialog(info: Updater.Info) {
        val note = info.notes.trim().ifEmpty { "Nessuna nota per questa versione." }
        AlertDialog.Builder(this)
            .setTitle("Nuova versione ${info.version}")
            .setMessage("Installata: ${BuildConfig.VERSION_NAME}\n\n$note")
            .setPositiveButton("Aggiorna") { _, _ -> requestInstall(info.url) }
            .setNegativeButton("Più tardi", null)
            .show()
    }

    private fun requestInstall(url: String) {
        if (Updater.puoInstallare(this)) {
            startDownload(url)
            return
        }
        // Serve una volta sola: autorizzare quest'app a installare aggiornamenti
        AlertDialog.Builder(this)
            .setTitle("Serve un permesso")
            .setMessage(
                "Per installare l'aggiornamento, Android deve consentire a Volume Boost " +
                    "di installare app.\n\nNella schermata che si apre attiva l'interruttore, " +
                    "poi torna indietro: il download partirà da solo."
            )
            .setPositiveButton("Apri impostazioni") { _, _ ->
                pendingUrl = url
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun startDownload(url: String) {
        if (downloading) return
        downloading = true

        val pad = (24 * resources.displayMetrics.density).toInt()
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val label = TextView(this).apply { text = "Download in corso…" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label)
            addView(bar)
        }
        progressDialog = AlertDialog.Builder(this)
            .setTitle("Aggiornamento")
            .setView(box)
            .setCancelable(false)
            .show()

        Updater.download(this, url,
            onProgress = { p ->
                if (p >= 0) {
                    bar.isIndeterminate = false
                    bar.progress = p
                    label.text = "Download in corso… $p%"
                }
            },
            onDone = { errore ->
                downloading = false
                progressDialog?.dismiss()
                progressDialog = null
                if (errore != null && alive()) {
                    AlertDialog.Builder(this)
                        .setTitle("Aggiornamento non riuscito")
                        .setMessage("Motivo: $errore")
                        .setPositiveButton("OK", null)
                        .show()
                }
                // Se è andato tutto bene si apre l'installatore di Android
            }
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_PENDING = "pending_update_url"
    }
}
