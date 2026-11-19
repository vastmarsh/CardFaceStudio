package com.cardface.studio

import android.content.Context

object Prefs {
    private const val NAME = "cardface_prefs"
    private const val KEY_PKG = "target_pkg"
    private const val KEY_LABEL = "target_label"
    private const val KEY_DIR = "target_dir"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun pkg(c: Context): String? = sp(c).getString(KEY_PKG, null)?.takeIf { it.isNotBlank() }

    fun setPkg(c: Context, value: String) {
        sp(c).edit().putString(KEY_PKG, value).apply()
    }

    fun label(c: Context): String? = sp(c).getString(KEY_LABEL, null)?.takeIf { it.isNotBlank() }

    fun setLabel(c: Context, value: String?) {
        sp(c).edit().putString(KEY_LABEL, value).apply()
    }

    fun dir(c: Context): String? = sp(c).getString(KEY_DIR, null)?.takeIf { it.isNotBlank() }

    fun setDir(c: Context, value: String) {
        sp(c).edit().putString(KEY_DIR, value).apply()
    }
}
