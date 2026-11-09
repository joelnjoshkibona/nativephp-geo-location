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
    private var lastGpsAt = 0L   // when the last satellite fix arrived (wall clock)
    private var motion: MotionMonitor? = null

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
            val fused = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)
            if (fused) providers.add(LocationManager.FUSED_PROVIDER)
            if ((cfg.highAccuracy || providers.isEmpty()) && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
            // Before Android 12 there is no fused provider, and GPS alone gives almost nothing indoors or in a pocket (measured on an Infinix with
            // Android 10: 7 fixes in 2 hours 15 minutes at a 2 minute interval). The network provider (Wi-Fi and cell towers) fills the gaps.
            if ((!fused || providers.isEmpty()) && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)
            if (providers.isEmpty()) {
                fail("No location provider is switched on")
                return
            }
            for (p in providers) {
                val l = Listener(cfg)
                listeners.add(l)
                lm.requestLocationUpdates(p, cfg.intervalSeconds * 1000L, cfg.minDistanceMeters.toFloat(), l, Looper.getMainLooper())
            }
            motion?.stop()
            motion = MotionMonitor(this) { AppLogBridge.w('I', "motion sensed: $it") }.also { it.start() }
            GeoStore.setStat(this, "lastError", null)
            GeoStore.setStat(this, "providers", providers.joinToString(","))
            Log.d(TAG, "Recording with ${providers.joinToString(",")} every ${cfg.intervalSeconds}s / ${cfg.minDistanceMeters}m, motion sensors: ${motion?.names?.ifEmpty { "none" }}")
            AppLogBridge.w('I', "location service started: ${providers.joinToString(",")} every ${cfg.intervalSeconds}s, motion sensors: ${motion?.names?.ifEmpty { "none" }}")
        } catch (e: SecurityException) {
            fail("Location permission is missing")
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        GeoStore.setStat(this, "lastError", message)
        AppLogBridge.w('E', "location service cannot record: $message")
        stopUpdates()
    }

    private fun stopUpdates() {
        motion?.stop()
        motion = null
        for (l in listeners) try { manager?.removeUpdates(l) } catch (e: Exception) { }
        listeners.clear()
    }

    private inner class Listener(private val cfg: Tracking) : LocationListener {
        override fun onLocationChanged(loc: Location) {
            if (loc.time <= lastTime) return
            // a satellite fix is better than a network one: while GPS is delivering, the network fix of the same round is left out
            if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsAt = System.currentTimeMillis()
            else if (loc.provider == LocationManager.NETWORK_PROVIDER && System.currentTimeMillis() - lastGpsAt < cfg.intervalSeconds * 1500L) return
            if (cfg.maxAccuracyMeters > 0 && loc.hasAccuracy() && loc.accuracy > cfg.maxAccuracyMeters) return
            lastTime = loc.time
            val fix = JSONObject().put("lat", loc.latitude).put("lng", loc.longitude).put("t", loc.time)
                .put("provider", loc.provider ?: "")
            if (loc.hasAccuracy()) fix.put("accuracy", loc.accuracy.toDouble())
            if (loc.hasSpeed()) fix.put("speed", loc.speed.toDouble())
            if (loc.hasAltitude()) fix.put("altitude", loc.altitude)
            if (loc.hasBearing()) fix.put("heading", loc.bearing.toDouble())
            motion?.flush()   // what the sensor chip holds arrives a moment later, for the next fix
            motion?.still()?.let { fix.put("mv", if (it) 0 else 1) }   // 0: the phone has not moved for a few minutes, 1: it has; absent: no motion sensor
            StatusBridge.snapshot(this@GeoLocationService)?.let { fix.put("st", it) }   // the phone's state at this moment (battery, network, signal), when device-utils is in the app
            GeoStore.append(this@GeoLocationService, cfg.id, fix)
            if (AppLogBridge.detailOn()) AppLogBridge.w('D', "fix ${loc.provider} accuracy=${if (loc.hasAccuracy()) loc.accuracy.toInt().toString() + "m" else "?"} total=${GeoStore.statLong(this@GeoLocationService, "fixes")}")
        }

        override fun onProviderEnabled(provider: String) { AppLogBridge.w('I', "location provider $provider switched on") }
        override fun onProviderDisabled(provider: String) { AppLogBridge.w('W', "location provider $provider switched off") }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    override fun onDestroy() {
        AppLogBridge.w('I', "location service stopped")
        stopUpdates()
        super.onDestroy()
    }
}

/**
 * The app's log, if the device-utils plugin is in the app (by reflection: no dependency on it). Never write coordinates.
 */
private object AppLogBridge {
    private val write = try { Class.forName("com.blutrixx.device.utils.AppLog").getMethod("write", Char::class.javaPrimitiveType, String::class.java, String::class.java) } catch (e: Throwable) { null }
    private val detail = try { Class.forName("com.blutrixx.device.utils.AppLog").getMethod("detail") } catch (e: Throwable) { null }
    fun w(level: Char, message: String) { try { write?.invoke(null, level, "places", message) } catch (e: Throwable) { } }
    fun detailOn(): Boolean = try { (detail?.invoke(null) as? Boolean) ?: false } catch (e: Throwable) { false }
}

/**
 * The phone's state (battery, network, signal), if the device-utils plugin is in the app (by reflection: no dependency on it).
 * A short JSON object such as {"b":85,"c":1,"n":"w","s":3}; nothing that identifies the person or the place.
 */
private object StatusBridge {
    private val snapshot = try { Class.forName("com.blutrixx.device.utils.DeviceStatus").getMethod("snapshot", android.content.Context::class.java) } catch (e: Throwable) { null }
    fun snapshot(context: android.content.Context): JSONObject? = try { (snapshot?.invoke(null, context) as? String)?.let { JSONObject(it) } } catch (e: Throwable) { null }
}
