package it.prencipe.volumeboost

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import java.util.Locale

/**
 * Riquadro "Volume Boost" nelle Impostazioni rapide (come Wi-Fi, Bluetooth, Rotazione).
 * Tocco = acceso/spento. Da spento si riaccende al valore impostato con la manopola.
 */
class BoostTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        when {
            ForegroundService.isEnabled(this) -> ForegroundService.disable(this)

            // Nessun valore impostato: apri l'app per regolare la manopola
            ForegroundService.savedGain(this) <= 0f -> {
                openApp()
                return
            }

            else -> try {
                ForegroundService.enable(this)
            } catch (e: Exception) {
                // Se Android impedisce l'avvio da qui, apriamo l'app
                Log.e("VolumeBoost", "Avvio dal riquadro non consentito", e)
                openApp()
                return
            }
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = ForegroundService.isEnabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Volume Boost"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) {
                String.format(Locale.ITALY, "+%.1f dB", ForegroundService.savedGain(this))
            } else "Off"
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
