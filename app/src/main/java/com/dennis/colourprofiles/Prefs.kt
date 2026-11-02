package com.dennis.colourprofiles

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val LIGHTROOM = "com.adobe.lrmobile"
    private const val RULE = "rule:"

    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)

    fun seed(c: Context) {
        val p = sp(c)
        if (!p.getBoolean("seeded", false)) {
            p.edit()
                .putInt(RULE + LIGHTROOM, DisplayMode.BASIC.id)
                .putBoolean("seeded", true)
                .apply()
        }
    }

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("enabled", on).apply()

    fun useMdnie(c: Context) = sp(c).getBoolean("mdnie", true)
    fun setUseMdnie(c: Context, on: Boolean) = sp(c).edit().putBoolean("mdnie", on).apply()

    fun defaultMode(c: Context) = sp(c).getInt("default", DisplayMode.VIVID.id)
    fun setDefaultMode(c: Context, id: Int) = sp(c).edit().putInt("default", id).apply()

    fun rule(c: Context, pkg: String): Int? {
        val v = sp(c).getInt(RULE + pkg, -1)
        return if (v >= 0) v else null
    }

    fun setRule(c: Context, pkg: String, id: Int) = sp(c).edit().putInt(RULE + pkg, id).apply()
    fun removeRule(c: Context, pkg: String) = sp(c).edit().remove(RULE + pkg).apply()

    fun rules(c: Context): Map<String, Int> = sp(c).all
        .filterKeys { it.startsWith(RULE) }
        .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(RULE) to it } }
        .toMap()
}
