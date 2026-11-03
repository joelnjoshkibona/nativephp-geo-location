package com.blutrixx.plugins.geolocation

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** What a tracking session was asked to do. Kept so the service can resume after Android restarts it. */
data class Tracking(
    val id: String,
    val intervalSeconds: Int,
    val minDistanceMeters: Int,
    val highAccuracy: Boolean,
    val maxAccuracyMeters: Int,
    val title: String,
    val text: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("intervalSeconds", intervalSeconds)
        .put("minDistanceMeters", minDistanceMeters).put("highAccuracy", highAccuracy)
        .put("maxAccuracyMeters", maxAccuracyMeters).put("title", title).put("text", text)

    companion object {
        fun fromJson(o: JSONObject) = Tracking(
            o.getString("id"), o.optInt("intervalSeconds", 60), o.optInt("minDistanceMeters", 25),
            o.optBoolean("highAccuracy", false), o.optInt("maxAccuracyMeters", 0),
            o.optString("title", "Location is being recorded"), o.optString("text", ""),
        )
    }
}

/**
 * The state lives in SharedPreferences and the fixes in an append-only file (one JSON object per line), so the
 * service and the bridge functions share it with no PHP running. Cursors are byte offsets into that file.
 */
object GeoStore {
    private const val PREFS = "blutrixx_geo"
    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun bufferFile(ctx: Context, id: String): File {
        val dir = File(ctx.applicationContext.filesDir, "blutrixx_geo").apply { mkdirs() }
        return File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jsonl")
    }

    fun saveTracking(ctx: Context, t: Tracking?) = synchronized(lock) {
        val e = prefs(ctx).edit()
        if (t == null) e.remove("tracking") else e.putString("tracking", t.toJson().toString())
        e.commit(); Unit
    }

    fun tracking(ctx: Context): Tracking? = synchronized(lock) {
        val raw = prefs(ctx).getString("tracking", null) ?: return null
        try { Tracking.fromJson(JSONObject(raw)) } catch (e: Exception) { null }
    }

    fun setStat(ctx: Context, key: String, value: Any?) = synchronized(lock) {
        val e = prefs(ctx).edit()
        when (value) {
            null -> e.remove(key)
            is Long -> e.putLong(key, value)
            is Int -> e.putInt(key, value)
            is String -> e.putString(key, value)
            is Boolean -> e.putBoolean(key, value)
        }
        e.commit(); Unit
    }

    fun statLong(ctx: Context, key: String): Long = prefs(ctx).getLong(key, 0L)
    fun statString(ctx: Context, key: String): String? = prefs(ctx).getString(key, null)

    fun append(ctx: Context, id: String, fix: JSONObject) = synchronized(lock) {
        bufferFile(ctx, id).appendText(fix.toString() + "\n")
        setStat(ctx, "fixes", statLong(ctx, "fixes") + 1)
        setStat(ctx, "lastFixAt", fix.optLong("t"))
    }

    /** Reads whole lines from `cursor`, at most `limit` of them and about 512 KB. Returns the lines and the next cursor. */
    fun drain(ctx: Context, id: String, cursor: Long, limit: Int): Pair<List<String>, Long> = synchronized(lock) {
        val f = bufferFile(ctx, id)
        if (!f.exists() || cursor >= f.length()) return Pair(emptyList(), minOf(cursor, f.length()))
        RandomAccessFile(f, "r").use { raf ->
            raf.seek(cursor)
            val want = minOf(f.length() - cursor, 512L * 1024L).toInt()
            val bytes = ByteArray(want)
            raf.readFully(bytes)
            val lines = ArrayList<String>()
            var start = 0
            var consumed = 0
            for (i in bytes.indices) {
                if (bytes[i] == '\n'.code.toByte()) {
                    if (i > start) lines.add(String(bytes, start, i - start, Charsets.UTF_8))
                    start = i + 1
                    consumed = start
                    if (lines.size >= limit) break
                }
            }
            Pair(lines, cursor + consumed)
        }
    }

    /** Remove everything before `cursor`. Offsets start again from 0 afterwards. */
    fun trim(ctx: Context, id: String, cursor: Long) = synchronized(lock) {
        val f = bufferFile(ctx, id)
        if (!f.exists() || cursor <= 0) return
        if (cursor >= f.length()) { f.writeBytes(ByteArray(0)); return }
        val rest = RandomAccessFile(f, "r").use { raf -> raf.seek(cursor); ByteArray((f.length() - cursor).toInt()).also { raf.readFully(it) } }
        f.writeBytes(rest)
    }

    fun clearBuffer(ctx: Context, id: String) = synchronized(lock) {
        bufferFile(ctx, id).delete()
        setStat(ctx, "fixes", 0L)
        Unit
    }

    fun bufferSize(ctx: Context, id: String): Long = bufferFile(ctx, id).let { if (it.exists()) it.length() else 0L }
}
