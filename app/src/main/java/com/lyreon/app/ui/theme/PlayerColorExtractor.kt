/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
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
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
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

private val colorCache = ConcurrentHashMap<String, Color>()

/**
 * Ekstraktor warna dinamis dari thumbnail musik aktif.
 * Mengambil warna dominan dengan saturasi & kontras optimal untuk tema pemutar.
 */
@Composable
fun rememberDynamicArtworkPalette(
    thumbnailUrl: String?,
    fallbackColor: Color = LyreonCrimson,
): DynamicArtworkPalette {
    val context = LocalContext.current
    val currentBg = LyreonBackground
    var extractedColor by remember(thumbnailUrl) {
        mutableStateOf(
            if (!thumbnailUrl.isNullOrBlank()) colorCache[thumbnailUrl] ?: fallbackColor
            else fallbackColor,
        )
    }

    LaunchedEffect(thumbnailUrl) {
        if (thumbnailUrl.isNullOrBlank()) {
            extractedColor = fallbackColor
            return@LaunchedEffect
        }
        val cached = colorCache[thumbnailUrl]
        if (cached != null) {
            extractedColor = cached
            return@LaunchedEffect
        }

        val color = withContext(Dispatchers.IO) {
            extractDominantColor(context, thumbnailUrl) ?: fallbackColor
        }
        colorCache[thumbnailUrl] = color
        extractedColor = color
    }

    // Transisi warna halus saat berganti lagu
    val animAccent by animateColorAsState(
        targetValue = extractedColor,
        animationSpec = tween(durationMillis = 600),
        label = "playerAccent",
    )

    val ambientTop by animateColorAsState(
        targetValue = animAccent.copy(alpha = 0.35f),
        animationSpec = tween(durationMillis = 600),
        label = "ambientTop",
    )
    val ambientMiddle by animateColorAsState(
        targetValue = animAccent.copy(alpha = 0.12f),
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

private suspend fun extractDominantColor(context: Context, url: String): Color? = runCatching {
    val loader = ImageLoader(context)
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(64, 64)
        .build()

    val result = loader.execute(request)
    if (result !is SuccessResult) return null

    val bitmap = result.image.toBitmap()
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    // Analisis histogram warna (menghindari hitam/putih ekstrem, prioritaskan vibran)
    var bestColor: Color? = null
    var bestScore = -1f

    val hsv = FloatArray(3)
    for (pixel in pixels) {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        android.graphics.Color.RGBToHSV(r, g, b, hsv)

        val saturation = hsv[1]
        val value = hsv[2]

        // Filter warna yang terlalu gelap atau terlalu pudar
        if (value < 0.2f || value > 0.95f || saturation < 0.25f) continue

        // Skor vibran = keseimbangan antara saturasi & kecerahan
        val score = saturation * 0.7f + (1f - kotlin.math.abs(value - 0.65f)) * 0.3f
        if (score > bestScore) {
            bestScore = score
            // Clamp value untuk keterbacaan
            val clampedValue = value.coerceIn(0.5f, 0.85f)
            val clampedSat = saturation.coerceIn(0.45f, 0.95f)
            val finalColorInt = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], clampedSat, clampedValue))
            bestColor = Color(finalColorInt)
        }
    }

    bestColor
}.getOrNull()
