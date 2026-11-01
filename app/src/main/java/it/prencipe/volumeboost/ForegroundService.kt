package it.prencipe.volumeboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ForegroundService : Service() {

    companion object {
        private const val ACTION_APPLY = "it.prencipe.volumeboost.APPLY"

        private const val CHANNEL_ID = "boost_silent"
        private const val NOTIFICATION_ID = 1

        private const val PREFS = "boost_prefs"
        private const val KEY_GAIN = "gain_db"
        private const val KEY_ENABLED = "enabled"
        private const val TAG = "VolumeBoost"

        // Sessione audio 0 = mix di uscita globale (tutte le app)
        private const val GLOBAL_SESSION = 0

        private fun prefs(ctx: Context) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** Valore impostato con la manopola (resta salvato anche a boost spento) */
        fun savedGain(ctx: Context): Float = prefs(ctx).getFloat(KEY_GAIN, 0f)

        fun saveGain(ctx: Context, db: Float) {
            prefs(ctx).edit().putFloat(KEY_GAIN, db).apply()
        }

        fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

        private fun setEnabled(ctx: Context, enabled: Boolean) {
            prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        /**
         * Accende il boost al valore salvato. Può lanciare un'eccezione se Android
         * non permette di avviare il servizio in quel momento: chi chiama la gestisce.
         */
        fun enable(ctx: Context) {
            if (savedGain(ctx) <= 0f) return
            val intent = Intent(ctx, ForegroundService::class.java).setAction(ACTION_APPLY)
            ContextCompat.startForegroundService(ctx, intent)
            setEnabled(ctx, true)
            refreshTile(ctx)
        }

        /** Spegne il boost ma conserva il valore della manopola */
        fun disable(ctx: Context) {
            setEnabled(ctx, false)
            ctx.stopService(Intent(ctx, ForegroundService::class.java))
            refreshTile(ctx)
        }

        /** Chiede al sistema di aggiornare il riquadro nelle Impostazioni rapide */
        fun refreshTile(ctx: Context) {
            TileService.requestListeningState(
                ctx, ComponentName(ctx, BoostTileService::class.java)
            )
        }
    }

    private var enhancer: LoudnessEnhancer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val gain = savedGain(this)

        // startForeground va chiamato subito dopo startForegroundService
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)

        if (!isEnabled(this) || gain <= 0f) {
            stopSelf()
            return START_NOT_STICKY
        }

        applyGain(gain)
        // Se il sistema chiude il servizio, lo riavvia e riapplica il valore salvato
        return START_STICKY
    }

    private fun applyGain(db: Float) {
        try {
            val le = enhancer ?: LoudnessEnhancer(GLOBAL_SESSION).also { enhancer = it }
            le.setTargetGain((db * 100).toInt()) // il valore va in millibel (1 dB = 100 mB)
            le.enabled = true
        } catch (e: Exception) {
            // Alcuni dispositivi non permettono effetti sulla sessione globale
            Log.e(TAG, "LoudnessEnhancer non disponibile su questo dispositivo", e)
            enhancer?.release()
            enhancer = null
        }
    }

    /**
     * Notifica obbligatoria per un servizio in primo piano, resa il più invisibile possibile:
     * canale con importanza minima, nessun suono, nessun pulsante.
     * Su Android 13+ senza permesso notifiche non compare nella tendina.
     */
    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_boost)
            .setContentTitle("Volume Boost")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, "Volume Boost (servizio)", NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Necessaria ad Android per tenere attivo il boost"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        enhancer?.let {
            try {
                it.enabled = false
            } catch (_: Exception) { }
            it.release()
        }
        enhancer = null
        super.onDestroy()
    }
}
