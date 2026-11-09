package com.blutrixx.plugins.geolocation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * Tells whether the phone has been still. Indoors the network and GPS fixes of a phone lying on a table wander by hundreds of metres or
 * more, and without this the stop logic cannot tell that from a real trip. Two sources, either one counts as motion, no permission:
 *  - the accelerometer, read in batches (the sensor chip keeps the readings and hands them over when the app is awake anyway, so it does
 *    not keep the phone awake): a few seconds in which the strength of the pull varies by more than a lying phone's noise is motion;
 *  - the low-power motion triggers ("significant motion", the maker's "motion detect") where the phone has them. On the Infinix X692 the
 *    motion-detect one did not fire when the phone was carried around, which is why the accelerometer is the main source.
 *
 * `still()` is true when a sensor is there and no motion was reported for [STILL_AFTER_MS]; null when the phone has no such sensor (then
 * nothing is known and the fix carries no flag). Never claims "still" in the first minutes after it starts watching.
 */
internal class MotionMonitor(ctx: Context, private val onMotion: ((String) -> Unit)? = null) {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    // The motion sensors exist only as "wake-up" sensors, and getDefaultSensor(type) hands out the non-wake-up kind for most types: on the
    // Infinix X692 it answered null for the sensor that is in the phone. So ask for the wake-up one first.
    private fun find(type: Int): Sensor? = sm.getDefaultSensor(type, true) ?: sm.getDefaultSensor(type)
    private val triggers: List<Sensor> = listOfNotNull(find(Sensor.TYPE_SIGNIFICANT_MOTION), find(Sensor.TYPE_MOTION_DETECT)).distinctBy { it.name }
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val sensors: List<Sensor> = listOfNotNull(accel) + triggers
    @Volatile private var lastMotion = SystemClock.elapsedRealtime()
    private var lastReported = 0L
    private val listener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            moved(SystemClock.elapsedRealtime(), event.sensor.stringType.substringAfterLast('.'))
            arm(event.sensor)   // one-shot sensors: ask again for the next motion
        }
    }

    // accelerometer blocks of about five seconds: the spread (standard deviation) of the pull's strength, by the sensor's own clock
    private var blockStart = 0L
    private var n = 0
    private var sum = 0.0
    private var sumSq = 0.0
    private val accListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val at = e.timestamp / 1_000_000L   // elapsed-realtime milliseconds, also for readings that were batched
            val m = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
            if (n == 0) blockStart = at
            n++; sum += m; sumSq += m * m
            if (at - blockStart >= BLOCK_MS) {
                val mean = sum / n
                val sd = sqrt(maxOf(0.0, sumSq / n - mean * mean))
                if (n >= 8 && sd > MOVE_SD) moved(at, "accelerometer")
                n = 0; sum = 0.0; sumSq = 0.0
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun moved(at: Long, what: String) {
        if (at > lastMotion) lastMotion = at
        val now = SystemClock.elapsedRealtime()
        // for the app log: that a sensor works, at most once in two minutes (it fires again and again while walking)
        if (onMotion != null && now - lastReported >= 120_000L) { lastReported = now; onMotion.invoke(what) }
    }

    /** Which sensors are used, for the app log. */
    val names: String get() = sensors.joinToString("+") { it.stringType.substringAfterLast('.') }

    fun start() {
        lastMotion = SystemClock.elapsedRealtime()
        triggers.forEach { arm(it) }
        try {
            // readings are held by the sensor chip for up to 90 s and delivered together; the app is awake at every fix anyway
            if (accel != null) sm.registerListener(accListener, accel, SensorManager.SENSOR_DELAY_NORMAL, 90_000_000)
        } catch (e: Exception) { Log.w(TAG, "Accelerometer could not be read: ${e.message}") }
    }

    /** Ask the sensor chip to hand over what it holds now (so a fix taken right after sees the latest motion). */
    fun flush() { try { if (accel != null) sm.flush(accListener) } catch (e: Exception) { /* not supported */ } }

    private fun arm(s: Sensor) {
        try {
            if (!sm.requestTriggerSensor(listener, s)) { Log.w(TAG, "Motion sensor ${s.name} refused"); onMotion?.invoke("${s.stringType.substringAfterLast('.')} refused") }
        } catch (e: Exception) { Log.w(TAG, "Motion sensor ${s.name} could not be armed: ${e.message}") }
    }

    fun stop() {
        try { sm.unregisterListener(accListener) } catch (e: Exception) { /* not registered */ }
        triggers.forEach { try { sm.cancelTriggerSensor(listener, it) } catch (e: Exception) { /* not armed */ } }
    }

    fun still(): Boolean? = if (sensors.isEmpty()) null else SystemClock.elapsedRealtime() - lastMotion >= STILL_AFTER_MS

    companion object {
        private const val TAG = "GeoLocation"
        /** No motion for this long counts as still: a little more than one fix interval. */
        private const val STILL_AFTER_MS = 4 * 60_000L
        /** A block of readings is this long, and counts as motion when the pull's strength varies by more than this (m/s²): a phone lying on a table varies by about 0.02, one carried by a walker by 1 or more. */
        private const val BLOCK_MS = 5_000L
        private const val MOVE_SD = 0.12
    }
}
