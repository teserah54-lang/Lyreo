/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Diadaptasi dari FrancescoGrazioso/Meld (GPL-3.0):
 *   app/src/main/kotlin/com/metrolist/music/ui/utils/FadingEdge.kt
 * Metrolist Project (C) 2026 — lihat riwayat git upstream untuk kontributor.
 */
package com.lyreon.app.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

/**
 * Tepian memudar (fade) di sisi container — dipakai daftar lirik agar baris yang
 * keluar dari area baca meleleh, bukan terpotong keras.
 *
 * Catatan performa: ini hanya dua `drawRect` dengan `BlendMode.DstIn` pada satu
 * lapisan (`graphicsLayer(alpha = 0.99f)` memaksa lapisan tersendiri). Murah,
 * tetapi jangan dipakai per item di dalam LazyColumn — satu kali per daftar.
 */
fun Modifier.fadingEdge(
    left: Dp? = null,
    top: Dp? = null,
    right: Dp? = null,
    bottom: Dp? = null,
): Modifier = graphicsLayer(alpha = 0.99f)
    .drawWithContent {
        drawContent()
        if (top != null) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = top.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (bottom != null) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - bottom.toPx(),
                    endY = size.height,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (left != null) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = 0f,
                    endX = left.toPx(),
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (right != null) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = size.width - right.toPx(),
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** Bentuk ringkas: fade seragam di kedua ujung sumbu. */
fun Modifier.fadingEdge(
    horizontal: Dp? = null,
    vertical: Dp? = null,
): Modifier = fadingEdge(
    left = horizontal,
    right = horizontal,
    top = vertical,
    bottom = vertical,
)
