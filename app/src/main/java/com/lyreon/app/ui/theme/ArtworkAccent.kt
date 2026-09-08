/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Pemilihan swatch (bobot populasi × kejenuhan, vividness dinaikkan) mengikuti
 * FrancescoGrazioso/Meld (GPL-3.0):
 *   app/src/main/kotlin/com/metrolist/music/ui/theme/PlayerColorExtractor.kt
 * Metrolist Project (C) 2026.
 */
package com.lyreon.app.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// ------------------------------------------------------------------
// Aksen global dari sampul lagu (wishlist: "tema warna berubah mengikuti
// palet thumbnail di setiap musik").
//
// Kenapa object tunggal, bukan state komposisi: tema hidup di ATAS pohon UI
// (LyreonTheme membungkus seluruh app), sedangkan lagu aktif hidup di dalam.
// Satu-satunya cara mengalirkan warna sampul ke tema tanpa menyusun ulang
// seluruh app adalah lewat satu sumber kebenaran di luar komposisi.
//
// Biaya dijaga ketat:
//  - bitmap 100×100 (bukan resolusi penuh) lewat PlayerColorExtractor
//    (ImageLoader SINGLETON Coil — biasanya sudah ada di cache memori);
//  - hasil di-cache per URL (ConcurrentHashMap) — satu lagu = satu ekstraksi;
//  - ekstraksi baru membatalkan yang sedang berjalan (tidak menumpuk).
//
// Algoritma pemilihan warna: swatch Palette ala Meld (dominan/vibrant/muted
// diberi bobot, terbaik dipertajam vividness-nya) — sebelumnya histogram HSV
// buatan sendiri yang sering memilih warna "hampir benar".
// ------------------------------------------------------------------

/** Sentinel "tidak ada aksen dari sampul" — konsisten dengan `accentArgb` di setelan. */
const val NO_ARTWORK_ACCENT = -1

private val accentCache = ConcurrentHashMap<String, Int>()

private val accentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private val _accentArgb = MutableStateFlow(NO_ARTWORK_ACCENT)

private var activeJob: Job? = null

private var activeUrl: String? = null

/** Aksen ARGB dari sampul lagu terakhir, atau [NO_ARTWORK_ACCENT]. */
val artworkAccentArgb: StateFlow<Int> = _accentArgb.asStateFlow()

/**
 * Amati satu sampul. Aman dipanggil berulang dengan URL yang sama: bila URL
 * tidak berubah, tidak ada pekerjaan baru yang dijadwalkan.
 */
fun updateArtworkAccent(context: Context, thumbnailUrl: String?) {
    if (thumbnailUrl.isNullOrBlank()) {
        resetArtworkAccent()
        return
    }
    if (thumbnailUrl == activeUrl) return
    activeUrl = thumbnailUrl

    accentCache[thumbnailUrl]?.let {
        activeJob?.cancel()
        activeJob = null
        _accentArgb.value = it
        return
    }

    val appContext = context.applicationContext
    activeJob?.cancel()
    activeJob = accentScope.launch {
        val colors = runCatching {
            PlayerColorExtractor.extract(appContext, thumbnailUrl)
        }.getOrNull()
        // Sampul monokrom/abu-abu: aksen kelabu tidak menambah apa pun —
        // biarkan tema memakai aksen bawaan/pengguna (perilaku lama Lyreon;
        // Meld memaksa warna dominan apa adanya).
        val usable = colors?.takeIf { isUsableAccent(it.primary) }
        if (usable != null) accentCache[thumbnailUrl] = usable.primary.toArgb()
        // Ekstraksi bisa selesai setelah lagu berganti lagi — jangan menimpa
        // warna lagu yang lebih baru.
        if (activeUrl == thumbnailUrl) {
            _accentArgb.value = usable?.primary?.toArgb() ?: NO_ARTWORK_ACCENT
        }
    }
}

/** Warna cukup berwarna untuk menjadi aksen? (ambang kejenuhan HSV 0.14) */
private fun isUsableAccent(color: Color): Boolean {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    return hsv[1] >= 0.14f
}

/** Kembali ke aksen bawaan (mis. tidak ada lagu yang diputar). */
fun resetArtworkAccent() {
    activeUrl = null
    activeJob?.cancel()
    activeJob = null
    _accentArgb.value = NO_ARTWORK_ACCENT
}

/** Buang cache (dipanggil saat pengguna membersihkan cache aplikasi). */
fun clearArtworkAccentCache() {
    accentCache.clear()
    PlayerColorExtractor.clearCache()
}
