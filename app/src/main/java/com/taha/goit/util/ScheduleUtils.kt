package com.taha.goit.util

import java.util.Calendar
import java.util.Locale

object ScheduleUtils {
    /**
     * Hat kodlarını karşılaştırma için normalize eder.
     * "31/A" -> "31A", "15/H" -> "15H", "B/38" -> "B38", "4/İ" -> "4İ", "B/44-B" -> "B44B"
     */
    fun normalizeRouteCode(code: String?): String {
        if (code.isNullOrBlank()) return ""
        return code.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }

    /**
     * Calendar.DAY_OF_WEEK (Pazar=1, Pzt=2... Cmt=7) değerini
     * Bursakart API standart gününe çevirir (Pzt=1, Salı=2... Cmt=6, Pazar=7).
     */
    fun getCurrentApiWeekDay(): Int {
        val cal = Calendar.getInstance()
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        return when (dow) {
            Calendar.SUNDAY -> 7
            else -> dow - 1
        }
    }
}
