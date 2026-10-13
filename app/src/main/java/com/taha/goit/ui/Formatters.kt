package com.taha.goit.ui

/** "00:07:00" biçimini "7 dk" olarak gösterir; 1 saatten uzunları "1s 5dk" yapar. */
fun formatEtaTime(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "-") return "Bekleniyor"
    return try {
        val parts = raw.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        when {
            h > 0 && m > 0 -> "${h}s ${m}dk"
            h > 0 -> "${h}s"
            else -> "$m dk"
        }
    } catch (_: Exception) {
        raw
    }
}

/** "00:07:00" biçimini saniyeye çevirir (sıralama için). */
fun parseEtaSeconds(raw: String?): Int {
    if (raw.isNullOrBlank()) return Int.MAX_VALUE
    return try {
        val parts = raw.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        h * 3600 + m * 60
    } catch (_: Exception) {
        Int.MAX_VALUE
    }
}

/** Ücret metni: "56.5" -> "56,5₺" gibi. */
fun formatPriceText(price: Double?): String? {
    if (price == null) return null
    val s = if (price % 1.0 == 0.0) price.toInt().toString() else String.format(java.util.Locale("tr", "TR"), "%.2f", price).trimEnd('0').trimEnd('.', ',')
    return "$s₺"
}

/** Mesafe metni: 850 m / 1,2 km. */
fun formatDistance(meters: Double): String {
    return if (meters >= 1000) {
        String.format(java.util.Locale("tr", "TR"), "%.1f km", meters / 1000.0)
    } else {
        "${meters.toInt()} m"
    }
}
