package com.blutrixx.plugins.geolocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * Foreground service that keeps recording location while the app is closed.
 * Android requires the ongoing notification; it is what lets the service keep running.
 */
class GeoLocationService : Service() {
    private var manager: LocationManager? = null
    private val listeners = ArrayList<LocationListener>()
    private var lastTime = 0L

    companion object {
        private const val TAG = "GeoLocation"
        const val CHANNEL = "blutrixx_geo_tracking"
        const val NOTIFICATION_ID = 7402
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android may restart the service with no intent after killing it: the settings are read from the store.
        val cfg = GeoStore.tracking(this)
        if (cfg == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val n = notification(cfg)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, n)
        startUpdates(cfg)
        return START_STICKY
    }

    private fun notification(cfg: Tracking): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Location recording", NotificationManager.IMPORTANCE_LOW))
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val tap = open?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(cfg.title)
            .setContentText(cfg.text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tap)
            .build()
    }

    private fun startUpdates(cfg: Tracking) {
        stopUpdates()
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        manager = lm
        val providers = ArrayList<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) providers.add(LocationManager.FUSED_PROVIDER)
            if ((cfg.highAccuracy || providers.isEmpty()) && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
            if (providers.isEmpty() && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)
            if (providers.isEmpty()) {
                fail("No location provider is switched on")
                return
            }
            for (p in providers) {
                val l = Listener(cfg)
                listeners.add(l)
                lm.requestLocationUpdates(p, cfg.intervalSeconds * 1000L, cfg.minDistanceMeters.toFloat(), l, Looper.getMainLooper())
            }
            GeoStore.setStat(this, "lastError", null)
            GeoStore.setStat(this, "providers", providers.joinToString(","))
            Log.d(TAG, "Recording with ${providers.joinToString(",")} every ${cfg.intervalSeconds}s / ${cfg.minDistanceMeters}m")
        } catch (e: SecurityException) {
            fail("Location permission is missing")
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        GeoStore.setStat(this, "lastError", message)
        stopUpdates()
    }

    private fun stopUpdates() {
        for (l in listeners) try { manager?.removeUpdates(l) } catch (e: Exception) { }
        listeners.clear()
    }

    private inner class Listener(private val cfg: Tracking) : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (loc.time <= lastTime) return
            if (cfg.maxAccuracyMeters > 0 && loc.hasAccuracy() && loc.accuracy > cfg.maxAccuracyMeters) return
            lastTime = loc.time
            val fix = JSONObject().put("lat", loc.latitude).put("lng", loc.longitude).put("t", loc.time)
                .put("provider", loc.provider ?: "")
            if (loc.hasAccuracy()) fix.put("accuracy", loc.accuracy.toDouble())
            if (loc.hasSpeed()) fix.put("speed", loc.speed.toDouble())
            if (loc.hasAltitude()) fix.put("altitude", loc.altitude)
            if (loc.hasBearing()) fix.put("heading", loc.bearing.toDouble())
            GeoStore.append(this@GeoLocationService, cfg.id, fix)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    override fun onDestroy() {
        stopUpdates()
        super.onDestroy()
    }
}
