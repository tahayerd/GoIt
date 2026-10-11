package com.taha.goit.data.api

import com.taha.goit.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TransitRepository {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val baseUrl = "https://bursakartapi.abys-web.com"
    private val originHeader = "https://www.bursakart.com.tr"
    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    suspend fun searchRouteAndStation(keyword: String): List<SearchItem> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject { put("keyword", keyword) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/routeandstation")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<SearchItem>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getRealtimeBuses(keyword: String): List<BusRealtimeLocation> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject { put("keyword", keyword) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/realtimedata")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<BusRealtimeLocation>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getRouteStops(routeCode: Int): List<RouteStop> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject { put("routeCode", routeCode) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/routestat")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<RouteStop>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getRouteCoordinates(routeCode: String): List<RouteCoordinate> = withContext(Dispatchers.IO) {
        try {
            val bodyObj = buildJsonObject { put("keyword", routeCode) }
            val request = Request.Builder()
                .url("$baseUrl/api/static/routecoordinate")
                .addHeader("Origin", originHeader)
                .addHeader("Accept", "application/json")
                .post(bodyObj.toString().toRequestBody(mediaTypeJson))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val str = resp.body?.string() ?: return@withContext emptyList()
                val parsed = json.decodeFromString<ApiResponse<List<RouteCoordinate>>>(str)
                return@withContext parsed.result ?: emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
