/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
//  - bitmap 48x48 (bukan resolusi penuh) di thread IO;
//  - memakai ImageLoader SINGLETON Coil, jadi biasanya sudah ada di cache
//    memori karena sampul yang sama baru saja dirender MiniPlayer/Home;
//  - hasil di-cache per URL (ConcurrentHashMap) — satu lagu = satu ekstraksi;
//  - ekstraksi baru membatalkan yang sedang berjalan (tidak menumpuk).
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
        val argb = runCatching { extractAccentArgb(appContext, thumbnailUrl) }.getOrNull()
        if (argb != null) accentCache[thumbnailUrl] = argb
        // Ekstraksi bisa selesai setelah lagu berganti lagi — jangan menimpa
        // warna lagu yang lebih baru.
        if (activeUrl == thumbnailUrl) {
            _accentArgb.value = argb ?: NO_ARTWORK_ACCENT
        }
    }
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
}

/**
 * Warna aksen yang layak dipakai sebagai warna penekanan: cukup jenuh untuk
 * terlihat, cukup terang untuk teks putih di atasnya, dan tidak pernah
 * terlalu dekat dengan latar gelap/terang.
 *
 * Implementasi mengikuti pola `PlayerColorExtractor` (histogram HSV 48x48),
 * tetapi memakai ImageLoader singleton Coil supaya cache memori UI ikut
 * terpakai — ekstraksi jadi nyaris gratis setelah sampul dirender sekali.
 */
private suspend fun extractAccentArgb(context: Context, url: String): Int? = withContext(Dispatchers.IO) {
    val loader: ImageLoader = SingletonImageLoader.get(context)
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(48, 48)
        .build()

    val result = loader.execute(request)
    if (result !is SuccessResult) return@withContext null

    val bitmap = result.image.toBitmap()
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return@withContext null

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val hsv = FloatArray(3)
    var bestScore = -1f
    var best: Color? = null

    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        android.graphics.Color.RGBToHSV(r, g, b, hsv)
        val saturation = hsv[1]
        val value = hsv[2]

        // Buang warna mati: terlalu gelap (tak terlihat di dark), terlalu
        // terang (tak terlihat di light), atau terlalu pudar (abu-abu).
        if (value < 0.22f || value > 0.95f || saturation < 0.22f) continue

        val score = saturation * 0.7f + (1f - kotlin.math.abs(value - 0.68f)) * 0.3f
        if (score > bestScore) {
            bestScore = score
            val clampedValue = value.coerceIn(0.48f, 0.82f)
            val clampedSat = saturation.coerceIn(0.42f, 0.92f)
            best = Color(
                android.graphics.Color.HSVToColor(
                    floatArrayOf(hsv[0], clampedSat, clampedValue),
                ),
            )
        }
    }

    // Sampul monokrom/abu-abu: tidak ada warna layak → jangan memaksa.
    if (best == null || bestScore < 0.34f) return@withContext null
    best?.toArgb()
}
