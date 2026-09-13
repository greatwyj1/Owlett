package com.example.birdingsoundmvp.notify

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class VibrationNotifier(context: Context) {
    private val appContext = context.applicationContext

    fun vibrate() {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator ?: return
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java) ?: return
        }

        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 180, 80, 180),
                    intArrayOf(0, 180, 0, 220),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 180, 80, 180), -1)
        }
    }
}
