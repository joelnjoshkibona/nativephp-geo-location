package com.blutrixx.plugins.geolocation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bridge functions the PHP side calls (see nativephp.json). */
object GeoLocationFunctions {
    private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun hasForeground(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasBackground(ctx: Context) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) else hasForeground(ctx)

    private fun locationOn(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun toJson(l: Location): JSONObject {
        val o = JSONObject().put("lat", l.latitude).put("lng", l.longitude).put("t", l.time).put("provider", l.provider ?: "")
        if (l.hasAccuracy()) o.put("accuracy", l.accuracy.toDouble())
        if (l.hasSpeed()) o.put("speed", l.speed.toDouble())
        if (l.hasAltitude()) o.put("altitude", l.altitude)
        if (l.hasBearing()) o.put("heading", l.bearing.toDouble())
        return o
    }

    class CheckPermissions(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val t = GeoStore.tracking(context)
            return BridgeResponse.success(
                mapOf(
                    "foreground" to hasForeground(context),
                    "fine" to granted(context, Manifest.permission.ACCESS_FINE_LOCATION),
                    "background" to hasBackground(context),
                    "notifications" to NotificationManagerCompat.from(context).areNotificationsEnabled(),
                    "locationOn" to locationOn(context),
                    "tracking" to (t != null),
                    "sdk" to Build.VERSION.SDK_INT,
                ),
            )
        }
    }

    class RequestPermissions(private val activity: Activity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val wantBackground = parameters["background"] as? Boolean ?: false
            // Android asks in two steps: location first, "all the time" afterwards (and on Android 11+ in Settings).
            val first = ArrayList<String>()
            if (!hasForeground(activity)) {
                first.add(Manifest.permission.ACCESS_FINE_LOCATION)
                first.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(activity, Manifest.permission.POST_NOTIFICATIONS)) {
                first.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            var asked = "none"
            if (first.isNotEmpty()) {
                asked = "foreground"
                activity.runOnUiThread { ActivityCompat.requestPermissions(activity, first.toTypedArray(), 7401) }
            } else if (wantBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackground(activity)) {
                asked = "background"
                activity.runOnUiThread { ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 7402) }
            }
            return BridgeResponse.success(mapOf("asked" to asked))
        }
    }

    class GetCurrentPosition(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!hasForeground(context)) return BridgeResponse.error("NO_PERMISSION", "Location permission is not granted")
            val maxAgeMs = ((parameters["maxAgeSeconds"] as? Number)?.toLong() ?: 60L) * 1000L
            val timeoutMs = ((parameters["timeoutSeconds"] as? Number)?.toLong() ?: 15L) * 1000L
            val fine = parameters["fine"] as? Boolean ?: false
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)

            try {
                val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                if (last != null && System.currentTimeMillis() - last.time <= maxAgeMs) {
                    return BridgeResponse.success(mapOf("fix" to toJson(last).toString(), "fresh" to false))
                }
                val provider = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && providers.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                    fine && providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    else -> null
                } ?: return BridgeResponse.error("NO_PROVIDER", "No location provider is switched on")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val latch = CountDownLatch(1)
                    var result: Location? = null
                    val signal = CancellationSignal()
                    val executor = Executors.newSingleThreadExecutor()
                    lm.getCurrentLocation(provider, signal, executor) { loc -> result = loc; latch.countDown() }
                    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) signal.cancel()
                    executor.shutdown()
                    val r = result ?: last
                    return if (r != null) BridgeResponse.success(mapOf("fix" to toJson(r).toString(), "fresh" to (r !== last)))
                    else BridgeResponse.error("TIMEOUT", "No position within the time limit")
                }
                return if (last != null) BridgeResponse.success(mapOf("fix" to toJson(last).toString(), "fresh" to false))
                else BridgeResponse.error("NO_FIX", "No known position")
            } catch (e: SecurityException) {
                return BridgeResponse.error("NO_PERMISSION", "Location permission is not granted")
            }
        }
    }

    class StartTracking(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            if (!hasForeground(context)) return BridgeResponse.error("NO_PERMISSION", "Location permission is not granted")
            val t = Tracking(
                id = (parameters["id"] as? String)?.takeIf { it.isNotBlank() } ?: "default",
                intervalSeconds = ((parameters["intervalSeconds"] as? Number)?.toInt() ?: 60).coerceAtLeast(1),
                minDistanceMeters = ((parameters["minDistanceMeters"] as? Number)?.toInt() ?: 25).coerceAtLeast(0),
                highAccuracy = parameters["highAccuracy"] as? Boolean ?: false,
                maxAccuracyMeters = (parameters["maxAccuracyMeters"] as? Number)?.toInt() ?: 0,
                title = (parameters["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Location is being recorded",
                text = parameters["text"] as? String ?: "",
            )
            GeoStore.saveTracking(context, t)
            GeoStore.setStat(context, "startedAt", System.currentTimeMillis())
            GeoStore.setStat(context, "lastError", null)
            return try {
                ContextCompat.startForegroundService(context, Intent(context, GeoLocationService::class.java))
                BridgeResponse.success(mapOf("started" to true, "id" to t.id, "backgroundAllowed" to hasBackground(context)))
            } catch (e: Exception) {
                GeoStore.saveTracking(context, null)
                BridgeResponse.error("START_REFUSED", e.message ?: "Android did not allow the service to start")
            }
        }
    }

    class StopTracking(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val t = GeoStore.tracking(context)
            GeoStore.saveTracking(context, null)
            context.stopService(Intent(context, GeoLocationService::class.java))
            val clear = parameters["clearBuffer"] as? Boolean ?: false
            val id = (parameters["id"] as? String) ?: t?.id
            if (clear && id != null) GeoStore.clearBuffer(context, id)
            return BridgeResponse.success(mapOf("stopped" to true))
        }
    }

    class Drain(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = (parameters["id"] as? String)?.takeIf { it.isNotBlank() } ?: "default"
            val cursor = (parameters["cursor"] as? Number)?.toLong() ?: 0L
            val limit = (parameters["limit"] as? Number)?.toInt() ?: 500
            val (lines, next) = GeoStore.drain(context, id, cursor, limit)
            return BridgeResponse.success(
                mapOf("count" to lines.size, "cursor" to next, "size" to GeoStore.bufferSize(context, id), "fixes" to lines.joinToString(",", "[", "]")),
            )
        }
    }

    class Trim(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val id = (parameters["id"] as? String)?.takeIf { it.isNotBlank() } ?: "default"
            GeoStore.trim(context, id, (parameters["cursor"] as? Number)?.toLong() ?: 0L)
            return BridgeResponse.success(mapOf("size" to GeoStore.bufferSize(context, id)))
        }
    }

    class Status(private val context: Context) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val t = GeoStore.tracking(context)
            val id = (parameters["id"] as? String) ?: t?.id ?: "default"
            val out = HashMap<String, Any>()
            out["tracking"] = t != null
            out["id"] = id
            out["startedAt"] = GeoStore.statLong(context, "startedAt") / 1000
            out["lastFixAt"] = GeoStore.statLong(context, "lastFixAt") / 1000
            out["fixes"] = GeoStore.statLong(context, "fixes")
            out["bufferBytes"] = GeoStore.bufferSize(context, id)
            GeoStore.statString(context, "providers")?.let { out["providers"] = it }
            GeoStore.statString(context, "lastError")?.let { out["lastError"] = it }
            return BridgeResponse.success(out)
        }
    }

    class OpenSettings(private val activity: Activity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val page = parameters["page"] as? String ?: "app"
            val intent = if (page == "location") Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            activity.runOnUiThread { activity.startActivity(intent) }
            return BridgeResponse.success(mapOf("opened" to page))
        }
    }
}
