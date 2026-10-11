package com.taha.goit.data.local

import android.content.Context

/**
 * Son kullanılan hatların kalıcı listesi (en son kullanılan en üstte).
 * Hatlar sekmesinde listenin en üstünde gösterilir.
 */
class RouteHistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("goit_route_history", Context.MODE_PRIVATE)

    fun getRecent(): List<String> {
        val raw = prefs.getString("recent", null) ?: return emptyList()
        return raw.split('\u0001').filter { it.isNotBlank() }
    }

    fun add(code: String) {
        if (code.isBlank()) return
        val list = getRecent().toMutableList()
        list.remove(code)
        list.add(0, code)
        while (list.size > 15) list.removeAt(list.size - 1)
        prefs.edit().putString("recent", list.joinToString("\u0001")).apply()
    }

    fun clear() {
        prefs.edit().remove("recent").apply()
    }
}
