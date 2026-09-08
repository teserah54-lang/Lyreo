/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Sistem ekstraksi warna pemutar diadaptasi dari FrancescoGrazioso/Meld
 * (GPL-3.0): app/src/main/kotlin/com/metrolist/music/ui/theme/PlayerColorExtractor.kt
 * dan app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt (blok
 * LaunchedEffect yang membangun Palette 100×100 lalu memanggil
 * extractGradientColors). Metrolist Project (C) 2026 — lihat riwayat git
 * upstream untuk kontributor.
 *
 * Yang dipertahankan dari Meld:
 *  - bitmap 100×100 via ImageLoader (cache memori UI ikut terpakai);
 *  - `Palette.from(...).maximumColorCount(8).resizeBitmapArea(100*100)`;
 *  - bobot swatch = populasi × bonus kejenuhan × (saturasi+nilai)/2;
 *  - vividness dinaikkan: saturasi ×faktor, nilai dijepit 0.4..0.85.
 *
 * Penyesuaian Lyreon: OkHttp/Coil3 singleton (bukan Hilt), hasil dipakai
 * untuk aksen + gradien ambien Now Playing (alpha lembut di atas latar,
 * bukan gradasi pekat penuh), dan cache per-URL supaya satu lagu = satu
 * ekstraksi.
 */
package com.lyreon.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

import androidx.compose.ui.graphics.Brush

@Stable
data class DynamicArtworkPalette(
    val accent: Color,
    val ambientTop: Color,
    val ambientMiddle: Color,
    val ambientBottom: Color,
    val surfaceTint: Color,
    val ambientGradient: Brush,
)

/**
 * Hasil ekstraksi satu artwork: aksen utama + warna kedua untuk kedalaman
 * gradien (pola `extractGradientColors` Meld: [utama, gelap, hitam] —
 * di Lyreon versi keduanya dipakai sebagai stop tengah gradien ambien).
 */
data class ExtractedArtworkColors(
    val primary: Color,
    val secondary: Color,
)

/** Cache hasil ekstraksi per URL — satu lagu = satu ekstraksi. */
private val extractedCache = ConcurrentHashMap<String, ExtractedArtworkColors>()

/**
 * Ekstraktor warna dinamis dari thumbnail musik aktif — pola Meld.
 */
object PlayerColorExtractor {

    /** Ukuran bitmap ekstraksi (sama dengan Meld: 100×100, area 10.000 px). */
    private const val SAMPLE_SIZE = 100

    /**
     * Ambil warna dari artwork: Palette swatch berbobot, vividness dinaikkan.
     * Mengembalikan null bila artwork gagal dimuat / tidak ada warna layak.
     */
    suspend fun extract(context: Context, thumbnailUrl: String): ExtractedArtworkColors? =
        withContext(Dispatchers.IO) {
            extractedCache[thumbnailUrl]?.let { return@withContext it }
            val loader: ImageLoader = SingletonImageLoader.get(context)
            val request = ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(SAMPLE_SIZE, SAMPLE_SIZE)
                // Palette butuh bitmap software (getPixels) — hardware tidak bisa.
                .allowHardware(false)
                .build()
            val result = loader.execute(request)
            if (result !is SuccessResult) return@withContext null
            val bitmap = result.image.toBitmap()

            val colors = withContext(Dispatchers.Default) {
                // LyreonCrimsonDefault (konstanta), BUKAN LyreonCrimson —
                // token tema itu getter @Composable yang tak boleh dipanggil
                // dari fungsi suspend non-komposisi (CI 34171614841).
                extractFromBitmap(bitmap, LyreonCrimsonDefault)
            } ?: return@withContext null
            extractedCache[thumbnailUrl] = colors
            colors
        }

    /** Buang cache (dipanggil saat pengguna membersihkan cache aplikasi). */
    fun clearCache() = extractedCache.clear()

    /**
     * Inti algoritma Meld: semua swatch diberi bobot (dominansi + kejenuhan),
     * terbaik dipertajam; keduanya dikembalikan untuk gradien dua warna.
     */
    fun extractFromBitmap(
        bitmap: Bitmap,
        fallbackColor: Color,
    ): ExtractedArtworkColors? {
        val palette = Palette.from(bitmap)
            .maximumColorCount(8)
            .resizeBitmapArea(SAMPLE_SIZE * SAMPLE_SIZE)
            .generate()

        val candidates = listOfNotNull(
            palette.dominantSwatch,
            palette.vibrantSwatch,
            palette.darkVibrantSwatch,
            palette.lightVibrantSwatch,
            palette.mutedSwatch,
            palette.darkMutedSwatch,
            palette.lightMutedSwatch,
        )
        val fallbackDominant = palette.dominantSwatch?.rgb
            ?: palette.getDominantColor(fallbackColor.toArgb())

        val ranked = candidates
            .sortedByDescending { swatchWeight(it) }

        val best = ranked.getOrNull(0)
        val primary = if (best != null && isColorVibrant(Color(best.rgb))) {
            enhanceColorVividness(Color(best.rgb), VIBRANT_SATURATION_FACTOR)
        } else {
            enhanceColorVividness(Color(fallbackDominant), FALLBACK_SATURATION_FACTOR)
        }

        val secondSwatch = ranked.getOrNull(1)
        val secondary = if (secondSwatch != null) {
            // Versi lebih gelap dari warna kedua — stop tengah gradien
            darken(enhanceColorVividness(Color(secondSwatch.rgb), FALLBACK_SATURATION_FACTOR), 0.6f)
        } else {
            darken(primary, 0.6f)
        }
        return ExtractedArtworkColors(primary = primary, secondary = secondary)
    }

    /** Apakah warna cukup hidup untuk aksen? (ambang Meld) */
    private fun isColorVibrant(color: Color): Boolean {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        return hsv[1] > 0.25f && hsv[2] > 0.2f && hsv[2] < 0.9f
    }

    /**
     * Naikkan vividness: saturasi ×[saturationFactor], nilai ×0.9 lalu
     * dijepit 0.4..0.85 — konstanta Meld `PlayerColorExtractor.Config`.
     */
    fun enhanceColorVividness(color: Color, saturationFactor: Float): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color.toArgb(), hsv)
        hsv[1] = (hsv[1] * saturationFactor).coerceIn(0f, 1f)
        hsv[2] = (hsv[2] * BRIGHTNESS_MULTIPLIER).coerceIn(BRIGHTNESS_MIN, BRIGHTNESS_MAX)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** Bobot swatch: dominasi (populasi) × bonus kejenuhan — formula Meld. */
    private fun swatchWeight(swatch: Palette.Swatch): Float {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(swatch.rgb, hsv)
        val saturation = hsv[1]
        val brightness = hsv[2]
        val populationWeight = swatch.population.toFloat() * POPULATION_WEIGHT_MULTIPLIER
        val vibrancyBonus =
            if (saturation > 0.3f && brightness > 0.3f) VIBRANCY_BONUS else 1f
        return populationWeight * vibrancyBonus * (saturation + brightness) / 2f
    }

    private fun darken(color: Color, factor: Float): Color = Color(
        red = (color.red * factor).coerceIn(0f, 1f),
        green = (color.green * factor).coerceIn(0f, 1f),
        blue = (color.blue * factor).coerceIn(0f, 1f),
        alpha = color.alpha,
    )

    // Konstanta disalin dari Meld PlayerColorExtractor.Config (GPL-3.0).
    private const val VIBRANT_SATURATION_FACTOR = 1.3f
    private const val FALLBACK_SATURATION_FACTOR = 1.1f
    private const val BRIGHTNESS_MULTIPLIER = 0.9f
    private const val BRIGHTNESS_MIN = 0.4f
    private const val BRIGHTNESS_MAX = 0.85f
    private const val POPULATION_WEIGHT_MULTIPLIER = 2f
    private const val VIBRANCY_BONUS = 1.5f
}

/**
 * Palet dinamis Now Playing: aksen + gradien ambien dua warna dari sampul
 * (utama di atas, sekunder lebih gelap di tengah, latar di bawah).
 */
@Composable
fun rememberDynamicArtworkPalette(
    thumbnailUrl: String?,
    fallbackColor: Color = LyreonCrimson,
): DynamicArtworkPalette {
    val context = LocalContext.current
    val currentBg = LyreonBackground
    var extracted by remember(thumbnailUrl) {
        mutableStateOf<ExtractedArtworkColors?>(
            if (!thumbnailUrl.isNullOrBlank()) extractedCache[thumbnailUrl] else null,
        )
    }

    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl.isNullOrBlank()) {
            extracted = null
            return@LaunchedEffect
        }
        val colors = PlayerColorExtractor.extract(context, thumbnailUrl)
        if (colors != null) extracted = colors
    }

    val accentSource = extracted?.primary ?: fallbackColor
    val secondarySource = extracted?.secondary ?: darkenStatic(fallbackColor, 0.6f)

    // Transisi warna halus saat berganti lagu
    val animAccent by animateColorAsState(
        targetValue = accentSource,
        animationSpec = tween(durationMillis = 600),
        label = "playerAccent",
    )
    val animSecondary by animateColorAsState(
        targetValue = secondarySource,
        animationSpec = tween(durationMillis = 600),
        label = "playerSecondary",
    )

    val ambientTop by animateColorAsState(
        targetValue = animAccent.copy(alpha = 0.35f),
        animationSpec = tween(durationMillis = 600),
        label = "ambientTop",
    )
    val ambientMiddle by animateColorAsState(
        targetValue = animSecondary.copy(alpha = 0.14f),
        animationSpec = tween(durationMillis = 600),
        label = "ambientMiddle",
    )
    val ambientBottom by animateColorAsState(
        targetValue = Color.Transparent,
        animationSpec = tween(durationMillis = 600),
        label = "ambientBottom",
    )
    val surfaceTint by animateColorAsState(
        targetValue = animAccent.copy(alpha = 0.18f),
        animationSpec = tween(durationMillis = 600),
        label = "surfaceTint",
    )

    val ambientGradient = remember(ambientTop, ambientMiddle, currentBg) {
        Brush.verticalGradient(
            listOf(
                ambientTop,
                ambientMiddle,
                currentBg,
            ),
        )
    }

    return remember(animAccent, ambientTop, ambientMiddle, ambientBottom, surfaceTint, ambientGradient) {
        DynamicArtworkPalette(
            accent = animAccent,
            ambientTop = ambientTop,
            ambientMiddle = ambientMiddle,
            ambientBottom = ambientBottom,
            surfaceTint = surfaceTint,
            ambientGradient = ambientGradient,
        )
    }
}

private fun darkenStatic(color: Color, factor: Float): Color = Color(
    red = (color.red * factor).coerceIn(0f, 1f),
    green = (color.green * factor).coerceIn(0f, 1f),
    blue = (color.blue * factor).coerceIn(0f, 1f),
    alpha = color.alpha,
)
