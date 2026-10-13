package com.taha.goit.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * O-23: Titreşim işlemlerini yöneten merkezi yardımcı sınıf.
 */
object VibrationHelper {
    val ALERT_WAVEFORM = longArrayOf(0, 500, 200, 500, 200, 500)
    val SHORT_WAVEFORM = longArrayOf(0, 250, 150, 250)

    fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun vibrateAlert(context: Context) {
        val vibrator = getVibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(ALERT_WAVEFORM, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(ALERT_WAVEFORM, -1)
        }
    }

    fun vibrateShort(context: Context) {
        val vibrator = getVibrator(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(SHORT_WAVEFORM, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(SHORT_WAVEFORM, -1)
        }
    }
}
