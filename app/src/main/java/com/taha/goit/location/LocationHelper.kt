package com.taha.goit.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Konum yardımcıları: son bilinen konum (hızlı) ve tek seferlik taze konum.
 * Sürekli arka plan takibi yapmaz; yalnızca gerektiğinde tek seferlik konum sağlar.
 */
object LocationHelper {

    fun hasPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /** Son bilinen konum (en tazesi). */
    fun getLastKnownLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (p in providers) {
            try {
                val loc = lm.getLastKnownLocation(p) ?: continue
                if (best == null || loc.time > best.time) best = loc
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
        return best
    }

    /**
     * Taze konum almayı dener; 6 saniye içinde gelmezse son bilinen konuma düşer.
     * GPS'i kısa süreliğine dinler, sonra dinlemeyi bırakır (pil dostu).
     */
    suspend fun getFreshLocation(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val fresh = withTimeoutOrNull(6000) {
            suspendCancellableCoroutine { cont ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        lm.getCurrentLocation(
                            LocationManager.GPS_PROVIDER,
                            null,
                            context.mainExecutor
                        ) { loc ->
                            if (cont.isActive && loc != null) cont.resume(loc)
                        }
                    } catch (_: Exception) {
                        cont.resume(null)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    try {
                        @Suppress("DEPRECATION")
                        lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null)
                        cont.invokeOnCancellation {
                            runCatching { lm.removeUpdates(listener) }
                        }
                    } catch (_: Exception) {
                        cont.resume(null)
                    }
                }
            }
        }
        return fresh ?: getLastKnownLocation(context)
    }

    /**
     * Ön plandayken düzenli konum dinleme (~5 sn / 10 m).
     * Dönen lambda dinlemeyi durdurur (onStop / onDispose çağrısı için).
     */
    fun startContinuousLocationUpdates(
        context: Context,
        minTimeMs: Long = 5000L,
        minDistanceM: Float = 10f,
        onLocation: (Location) -> Unit
    ): () -> Unit {
        if (!hasPermission(context)) return {}
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return {}

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location)
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, listener)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMs, minDistanceM, listener)
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {}

        return {
            runCatching { lm.removeUpdates(listener) }
        }
    }
}
