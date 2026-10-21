package com.you.gamespace

import android.content.Context
import android.content.SharedPreferences

data class Profile(val mode: String, val scale: String, val fps: String) {
    fun encode() = "$mode|$scale|$fps"
}

val DEFAULT_PROFILE = Profile("performance", "disable", "disable")

fun parseProfile(s: String?): Profile? {
    val p = s?.split("|") ?: return null
    if (p.size != 3) return null
    if (p[0] !in ShizukuManager.GAME_MODES) return null
    if (p[1] !in ShizukuManager.SCALES) return null
    if (p[2] !in ShizukuManager.FPS) return null
    return Profile(p[0], p[1], p[2])
}

// Kho lưu trữ dùng chung cho Activity và OverlayService
object Store {
    private var p: SharedPreferences? = null

    fun init(c: Context) {
        if (p == null) p = c.applicationContext.getSharedPreferences("gamespace", Context.MODE_PRIVATE)
    }

    val prefs: SharedPreferences get() = p!!

    fun getSet(key: String): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
    fun putSet(key: String, v: Set<String>) { prefs.edit().putStringSet(key, HashSet(v)).apply() }

    fun defaultProfile(): Profile = parseProfile(prefs.getString("d_profile", null)) ?: DEFAULT_PROFILE
    fun profileOf(pkg: String): Profile = parseProfile(prefs.getString("p_$pkg", null)) ?: defaultProfile()
    fun saveProfile(pkg: String, pr: Profile) { prefs.edit().putString("p_$pkg", pr.encode()).apply() }
}
