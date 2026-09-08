/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextBright
import com.lyreon.app.ui.theme.lyreonSpring
import kotlin.math.min

/**
 * Tombol play LYREON — lingkaran crimson solid dengan ikon putih
 * (morph play ⇄ pause via spring). Tanpa animasi abadi di background:
 * versi sebelumnya menjalankan infinite transition terus-menerus (= lag GPU).
 */
@Composable
fun LyreonPlayButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    accent: Color = LyreonCrimson,
    foreground: Color = LyreonTextBright,
    showOrbit: Boolean = false,
) {
    val morph = remember { Animatable(if (isPlaying) 1f else 0f) }
    // Spec diambil di luar LaunchedEffect: helper tema bersifat @Composable.
    val morphSpec = lyreonSpring<Float>(dampingRatio = 0.72f, stiffness = 420f)

    LaunchedEffect(isPlaying) {
        morph.animateTo(
            targetValue = if (isPlaying) 1f else 0f,
            animationSpec = morphSpec,
        )
    }

    val interaction = remember { MutableInteractionSource() }

    // Token palet dinamis — capture di composable scope sebelum lambda draw
    val orbitColor = LyreonTextBright.copy(alpha = 0.35f)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(accent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val minSide = min(this.size.width, this.size.height)
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            val t = morph.value

            val iconScale = minSide * 0.26f

            // Ikon play (memudar saat → pause)
            val playPath = Path().apply {
                val left = cx - iconScale * 0.4f
                val top = cy - iconScale * 0.62f
                moveTo(left, top)
                lineTo(left, top + iconScale * 1.24f)
                lineTo(left + iconScale * 1.05f, cy)
                close()
            }
            if (t < 0.5f) {
                drawPath(playPath, color = foreground.copy(alpha = 1f - t * 2f))
            }

            // Bar pause (muncul saat play)
            val barW = iconScale * 0.26f
            val barH = iconScale * 1.04f
            val gap = iconScale * 0.30f
            val barAlpha = ((t - 0.25f) / 0.75f).coerceIn(0f, 1f)
            if (barAlpha > 0f) {
                val topY = cy - barH / 2f
                drawRoundRect(
                    color = foreground.copy(alpha = barAlpha),
                    topLeft = Offset(cx - gap - barW, topY),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW * 0.35f),
                )
                drawRoundRect(
                    color = foreground.copy(alpha = barAlpha),
                    topLeft = Offset(cx + gap, topY),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(barW * 0.35f),
                )
            }

            // Ring statis tipis saat playing (tanpa animasi infinite)
            if (showOrbit && isPlaying) {
                drawCircle(
                    color = orbitColor,
                    radius = minSide * 0.46f,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.4f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
fun LyreonMiniPlay(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    LyreonPlayButton(
        isPlaying = isPlaying,
        onClick = onClick,
        modifier = modifier,
        size = size,
        showOrbit = false,
    )
}
