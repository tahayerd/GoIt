package com.taha.goit.util

import com.taha.goit.data.model.LatLng
import kotlin.math.*

object GeoUtils {
    private const val EARTH_RADIUS = 6371000.0 // meters

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS * c
    }

    /**
     * Snaps a given coordinate to the closest point along a polyline.
     * If polyline is empty or point is too far (> 100m), original point is returned.
     */
    fun snapToPolyline(point: LatLng, polyline: List<LatLng>, maxSnapDistMeters: Double = 80.0): LatLng {
        if (polyline.size < 2) return point

        var minDistance = Double.MAX_VALUE
        var bestPoint = point

        for (i in 0 until polyline.size - 1) {
            val p1 = polyline[i]
            val p2 = polyline[i + 1]
            val projected = projectPointOnSegment(point, p1, p2)
            val dist = distanceMeters(point.latitude, point.longitude, projected.latitude, projected.longitude)
            if (dist < minDistance) {
                minDistance = dist
                bestPoint = projected
            }
        }

        return if (minDistance <= maxSnapDistMeters) bestPoint else point
    }

    private fun projectPointOnSegment(p: LatLng, p1: LatLng, p2: LatLng): LatLng {
        val dx = p2.longitude - p1.longitude
        val dy = p2.latitude - p1.latitude
        if (dx == 0.0 && dy == 0.0) return p1

        val t = ((p.longitude - p1.longitude) * dx + (p.latitude - p1.latitude) * dy) / (dx * dx + dy * dy)
        val clampedT = t.coerceIn(0.0, 1.0)
        return LatLng(
            p1.latitude + clampedT * dy,
            p1.longitude + clampedT * dx
        )
    }
}
