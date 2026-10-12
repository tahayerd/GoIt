package com.taha.goit.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.taha.goit.data.model.ScheduleEntry
import com.taha.goit.util.ScheduleUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Günlük indirilen bursakart tarife paketini (schedule-v1.json.gz) yönetir.
 * RAM tasarrufu için indirilen paket durak bazlı parçalanarak diskte tutulur ve LRU önbellek kullanılır.
 */
class ScheduleCache(private val context: Context) {
    private val prefs = context.getSharedPreferences("goit_schedule_cache", Context.MODE_PRIVATE)
    private val scheduleDir = File(context.filesDir, "schedule")
    private val chunksDir = File(scheduleDir, "chunks")

    // Bellekte son kullanılan 8 durağın tarifesini tutan basit LRU cache
    private val memoryLru = object : java.util.LinkedHashMap<String, Map<String, List<ScheduleEntry>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, List<ScheduleEntry>>>?): Boolean {
            return size > 16
        }
    }

    init {
        chunksDir.mkdirs()
    }

    fun getLastUpdateTimestamp(): Long {
        return prefs.getLong("schedule_pkg_ts", 0L)
    }

    private fun setLastUpdateTimestamp(ts: Long) {
        prefs.edit().putLong("schedule_pkg_ts", ts).apply()
    }

    fun isMeteredNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.isActiveNetworkMetered
    }

    /**
     * Belirli bir durak ve haftanın günü için tarifeyi yerel cache'ten döndürür.
     */
    suspend fun getScheduleForStop(stopId: Int, weekDay: Int): List<ScheduleEntry>? = withContext(Dispatchers.IO) {
        val sKey = stopId.toString()
        val dayKey = weekDay.toString()

        synchronized(memoryLru) {
            val cached = memoryLru[sKey]
            if (cached != null) {
                return@withContext cached[dayKey]
            }
        }

        val stopFile = File(chunksDir, "stop_$sKey.json")
        if (!stopFile.exists()) return@withContext null

        try {
            val content = stopFile.readText()
            val jsonElement = Json.parseToJsonElement(content).jsonObject
            val dayMap = mutableMapOf<String, List<ScheduleEntry>>()
            for ((d, arr) in jsonElement) {
                val entries = arr.jsonArray.mapNotNull { item ->
                    val itemArr = item.jsonArray
                    val rCode = itemArr.getOrNull(0)?.jsonPrimitive?.content ?: return@mapNotNull null
                    val sTime = itemArr.getOrNull(1)?.jsonPrimitive?.content ?: return@mapNotNull null
                    ScheduleEntry(
                        routeId = true,
                        routeCode = rCode,
                        stopTime = sTime
                    )
                }
                dayMap[d] = entries
            }
            synchronized(memoryLru) {
                memoryLru[sKey] = dayMap
            }
            return@withContext dayMap[dayKey]
        } catch (_: Exception) {
            null
        }
    }

    /**
     * GitHub release'inden yeni tarife paketini indirir ve ayrıştırır.
     * force: true ise metered ağ kontrolünü yoksayar (kullanıcı Ayarlar'dan bastığında).
     */
    suspend fun checkAndUpdate(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!force && isMeteredNetwork()) {
                return@withContext false
            }

            val lastTs = getLastUpdateTimestamp()
            val now = System.currentTimeMillis()
            if (!force && (now - lastTs < 24 * 60 * 60 * 1000L)) {
                return@withContext false
            }

            val downloadUrl = "https://github.com/tahayerd/GoIt/releases/download/schedule-data/schedule-v1.json.gz"
            val gzFile = File(scheduleDir, "download.tmp.gz")

            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "GoIt-Android")

            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext false
            }

            conn.inputStream.use { input ->
                FileOutputStream(gzFile).use { output ->
                    input.copyTo(output)
                }
            }
            conn.disconnect()

            if (!gzFile.exists() || gzFile.length() == 0L) {
                gzFile.delete()
                return@withContext false
            }

            // Aç ve ayrıştırıp durak dosyalarına yaz
            GZIPInputStream(FileInputStream(gzFile)).bufferedReader(Charsets.UTF_8).use { reader ->
                val fullText = reader.readText()
                val root = Json.parseToJsonElement(fullText).jsonObject
                val generated = root["generated"]?.jsonPrimitive?.content?.toLongOrNull() ?: System.currentTimeMillis()
                val stopsObj = root["stops"]?.jsonObject ?: return@withContext false

                chunksDir.mkdirs()
                for ((sId, daysObj) in stopsObj) {
                    val stopFile = File(chunksDir, "stop_$sId.json")
                    stopFile.writeText(daysObj.toString())
                }

                setLastUpdateTimestamp(now)
                synchronized(memoryLru) {
                    memoryLru.clear()
                }
            }

            gzFile.delete()
            true
        } catch (_: Exception) {
            false
        }
    }
}
