package com.taha.goit.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.taha.goit.data.model.ScheduleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Normalizes route codes across Burulas APIs and static schedule data.
 * e.g., "31/A" -> "31A", "15/H" -> "15H", "B/38" -> "B38", "B/44-B" -> "B44B", "4/İ" -> "4İ"
 */
fun normalizeRouteCode(code: String): String {
    return code.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}

/**
 * Burulas ScheduleByRoute weekDay mapping:
 * Monday=1, Tuesday=2, Wednesday=3, Thursday=4, Friday=5, Saturday=6, Sunday=7.
 */
fun getBurulasWeekDay(calendar: Calendar = Calendar.getInstance()): Int {
    return when (calendar.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        Calendar.SATURDAY -> 6
        Calendar.SUNDAY -> 7
        else -> 1
    }
}

class ScheduleCache(private val context: Context) {

    private val prefs = context.getSharedPreferences("goit_schedule_prefs", Context.MODE_PRIVATE)
    private val scheduleDir = File(context.filesDir, "schedule")
    private val chunksDir = File(scheduleDir, "chunks")
    private val numBuckets = 128

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // Small in-memory LRU cache for bucket data: bucketIndex -> Map<StopIdString, Map<DayString, List<ScheduleEntry>>>
    private val lruCache = object : LinkedHashMap<Int, Map<String, Map<String, List<ScheduleEntry>>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<String, Map<String, List<ScheduleEntry>>>>?): Boolean {
            return size > 6
        }
    }
    private val cacheLock = Any()

    init {
        if (!chunksDir.exists()) {
            chunksDir.mkdirs()
        }
    }

    fun getLastUpdateTimestamp(): Long {
        return prefs.getLong("schedule_pkg_ts", 0L)
    }

    fun isAutoUpdateEnabled(): Boolean {
        return prefs.getBoolean("schedule_auto_update", true)
    }

    fun setAutoUpdateEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("schedule_auto_update", enabled).apply()
    }

    fun hasOfflineData(): Boolean {
        return getLastUpdateTimestamp() > 0 && (chunksDir.list()?.isNotEmpty() == true)
    }

    private fun isMeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered
    }

    /**
     * Checks if schedule package is older than 24h and updates if Wi-Fi / unmetered.
     */
    suspend fun checkAndUpdateSchedule(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val lastTs = getLastUpdateTimestamp()
        val now = System.currentTimeMillis()
        val oneDayMillis = 24 * 60 * 60 * 1000L

        if (!force) {
            if (!isAutoUpdateEnabled()) return@withContext false
            if (now - lastTs < oneDayMillis && hasOfflineData()) return@withContext false
            if (isMeteredNetwork()) {
                Log.d("ScheduleCache", "Deferring timetable update on metered network")
                return@withContext false
            }
        }

        try {
            val downloadUrl = "https://github.com/tahayerd/GoIt/releases/download/schedule-data/schedule-v1.json.gz"
            val req = Request.Builder()
                .url(downloadUrl)
                .addHeader("User-Agent", "GoItApp/1.6.0")
                .build()

            val tempGz = File(scheduleDir, "temp_schedule.json.gz")
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("ScheduleCache", "Failed to fetch schedule archive: ${resp.code}")
                    return@withContext false
                }
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(tempGz).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (!tempGz.exists() || tempGz.length() < 1000) {
                tempGz.delete()
                return@withContext false
            }

            // Unpack and partition into chunks
            val success = processAndChunkGzip(tempGz)
            tempGz.delete()

            if (success) {
                prefs.edit().putLong("schedule_pkg_ts", System.currentTimeMillis()).apply()
                synchronized(cacheLock) {
                    lruCache.clear()
                }
            }
            return@withContext success
        } catch (t: Throwable) {
            Log.e("ScheduleCache", "Error updating schedule data", t)
            return@withContext false
        }
    }

    private fun getBucketIndex(stopId: Int): Int {
        return (stopId.hashCode() and 0x7FFFFFFF) % numBuckets
    }

    private fun escapeJson(s: String, sb: java.lang.StringBuilder) {
        for (i in 0 until s.length) {
            val c = s[i]
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 32) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
    }

    private fun readStopObjectToJson(reader: android.util.JsonReader, sb: java.lang.StringBuilder) {
        sb.append("{")
        reader.beginObject()
        var firstDay = true
        while (reader.hasNext()) {
            val dayName = reader.nextName()
            if (!firstDay) sb.append(",")
            firstDay = false
            sb.append("\"").append(dayName).append("\":[")
            reader.beginArray()
            var firstEntry = true
            while (reader.hasNext()) {
                reader.beginArray()
                val rCode = if (reader.hasNext()) reader.nextString() else ""
                val sTime = if (reader.hasNext()) reader.nextString() else ""
                while (reader.hasNext()) {
                    reader.skipValue()
                }
                reader.endArray()
                if (!firstEntry) sb.append(",")
                firstEntry = false
                sb.append("[\"")
                escapeJson(rCode, sb)
                sb.append("\",\"")
                escapeJson(sTime, sb)
                sb.append("\"]")
            }
            reader.endArray()
            sb.append("]")
        }
        reader.endObject()
        sb.append("}")
    }

    private fun processAndChunkGzip(gzFile: File): Boolean {
        val tempChunksDir = File(scheduleDir, "chunks_temp")
        if (tempChunksDir.exists()) tempChunksDir.deleteRecursively()
        tempChunksDir.mkdirs()

        val writers = arrayOfNulls<java.io.BufferedWriter>(numBuckets)
        val isFirstEntry = BooleanArray(numBuckets) { true }
        val stopSb = java.lang.StringBuilder(8192)

        try {
            for (i in 0 until numBuckets) {
                val chunkFile = File(tempChunksDir, "chunk_$i.json")
                val bw = java.io.BufferedWriter(
                    java.io.OutputStreamWriter(java.io.FileOutputStream(chunkFile), Charsets.UTF_8),
                    32768
                )
                bw.write("{")
                writers[i] = bw
            }

            GZIPInputStream(FileInputStream(gzFile)).bufferedReader(Charsets.UTF_8).use { br ->
                val reader = android.util.JsonReader(br)
                reader.beginObject()
                while (reader.hasNext()) {
                    val rootKey = reader.nextName()
                    if (rootKey == "stops") {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val stopIdStr = reader.nextName()
                            val stopId = stopIdStr.toIntOrNull()
                            stopSb.setLength(0)
                            readStopObjectToJson(reader, stopSb)

                            if (stopId != null) {
                                val bIdx = getBucketIndex(stopId)
                                val w = writers[bIdx]
                                if (w != null) {
                                    if (!isFirstEntry[bIdx]) {
                                        w.write(",")
                                    }
                                    isFirstEntry[bIdx] = false
                                    w.write("\"")
                                    w.write(stopIdStr)
                                    w.write("\":")
                                    w.write(stopSb.toString())
                                }
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
            }

            // Close all bucket writers properly
            for (i in 0 until numBuckets) {
                writers[i]?.let { bw ->
                    bw.write("}")
                    bw.flush()
                    bw.close()
                }
                writers[i] = null
            }

            // Atomic swap: replace chunksDir with tempChunksDir
            if (chunksDir.exists()) chunksDir.deleteRecursively()
            val renamed = tempChunksDir.renameTo(chunksDir)
            if (!renamed) {
                tempChunksDir.copyRecursively(chunksDir, overwrite = true)
                tempChunksDir.deleteRecursively()
            }
            return true
        } catch (t: Throwable) {
            Log.e("ScheduleCache", "Failed to stream and chunk schedule data", t)
            tempChunksDir.deleteRecursively()
            return false
        } finally {
            for (w in writers) {
                runCatching {
                    w?.close()
                }
            }
        }
    }

    private fun loadBucket(bucketIndex: Int): Map<String, Map<String, List<ScheduleEntry>>>? {
        synchronized(cacheLock) {
            val cached = lruCache[bucketIndex]
            if (cached != null) return cached
        }

        val chunkFile = File(chunksDir, "chunk_$bucketIndex.json")
        if (!chunkFile.exists()) return null

        try {
            val content = chunkFile.readText()
            val root = json.parseToJsonElement(content).jsonObject
            val parsedBucket = mutableMapOf<String, MutableMap<String, List<ScheduleEntry>>>()

            for ((stopIdStr, daysElement) in root) {
                val daysMap = mutableMapOf<String, List<ScheduleEntry>>()
                val daysObj = daysElement.jsonObject
                for ((dayStr, entriesElement) in daysObj) {
                    val entryList = mutableListOf<ScheduleEntry>()
                    for (item in entriesElement.jsonArray) {
                        val arr = item.jsonArray
                        val rCode = arr.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: ""
                        val sTime = arr.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: ""
                        entryList.add(ScheduleEntry(routeCode = rCode, stopTime = sTime))
                    }
                    daysMap[dayStr] = entryList
                }
                parsedBucket[stopIdStr] = daysMap
            }

            synchronized(cacheLock) {
                lruCache[bucketIndex] = parsedBucket
            }
            return parsedBucket
        } catch (e: Exception) {
            Log.e("ScheduleCache", "Error reading chunk $bucketIndex", e)
            return null
        }
    }

    /**
     * Looks up timetable for a specific stop and weekDay from offline chunked storage.
     * Returns null if not cached so caller can fall back to network API.
     */
    fun getSchedule(stopId: Int, weekDay: Int): List<ScheduleEntry>? {
        val bIdx = getBucketIndex(stopId)
        val bucket = loadBucket(bIdx) ?: return null
        val stopData = bucket[stopId.toString()] ?: return null
        return stopData[weekDay.toString()]
    }
}
