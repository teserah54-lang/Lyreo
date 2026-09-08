/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.os.ConfigurationCompat
import java.util.Locale

/**
 * Bahasa aplikasi — disimpan sinkron di SharedPreferences agar bisa dibaca
 * di attachBaseContext (DataStore async terlambat untuk tahap ini).
 * Bahasa UI yang sewaktu akan terlihat di perangkat dengan API per-app
 * locale (Android 13+) diiklankan via res/xml/locale_config.xml.
 */
object LocaleHelper {

    const val PREFS = "lyreon_locale"
    const val KEY_LANGUAGE = "app_language"

    /** Kosong = ikut sistem. */
    data class AppLanguage(val tag: String, val label: String)

    val SUPPORTED = listOf(
        AppLanguage("", "Sistem / System"),
        AppLanguage("in", "Indonesia"),
        AppLanguage("en", "English"),
        AppLanguage("zh", "中文"),
        AppLanguage("hi", "हिन्दी"),
        AppLanguage("ms", "Bahasa Melayu"),
        AppLanguage("ja", "日本語"),
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentTag(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "") ?: ""

    fun set(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, tag).apply()
    }

    /** Bungkus context dengan locale pilihan pengguna (dipanggil di attachBaseContext). */
    fun wrap(base: Context): Context {
        val tag = currentTag(base)
        if (tag.isBlank()) return base
        val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull() ?: return base
        Locale.setDefault(locale)
        val config = base.resources.configuration
        val newConfig = android.content.res.Configuration(config).apply {
            if (Build.VERSION.SDK_INT >= 24) {
                setLocale(locale)
                setLayoutDirection(locale)
            } else {
                @Suppress("DEPRECATION")
                this.locale = locale
            }
        }
        return base.createConfigurationContext(newConfig)
    }

    /** Nama bahasa aktif apa adanya (utama untuk ditampilkan di Settings). */
    fun currentLabel(context: Context): String {
        val tag = currentTag(context)
        return SUPPORTED.firstOrNull { it.tag == tag }?.label
            ?: ConfigurationCompat.getLocales(context.resources.configuration)
                .get(0)?.displayLanguage
            ?: "-"
    }
}
