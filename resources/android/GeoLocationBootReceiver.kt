package com.blutrixx.plugins.geolocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/** After a restart or an app update, resume recording if it was running. Android may refuse; the app then restarts it when opened. */
class GeoLocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (GeoStore.tracking(context) == null) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, GeoLocationService::class.java))
            Log.d("GeoLocation", "Recording resumed after ${intent.action}")
        } catch (e: Exception) {
            Log.w("GeoLocation", "Android did not allow the service to start after boot: ${e.message}")
            GeoStore.setStat(context, "lastError", "Could not resume after restart: ${e.message}")
        }
    }
}
