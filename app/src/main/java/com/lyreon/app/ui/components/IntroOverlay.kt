/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lyreon.app.R
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonMotion
import com.lyreon.app.ui.theme.lyreonTween
import com.lyreon.app.ui.theme.reduceMotionEnabled
import kotlinx.coroutines.delay

/**
 * Intro saat membuka aplikasi — logo LYREON membesar halus + strip loading
 * berwarna aksen tema, lalu memudar keluar. Murni Compose (stabil di semua API).
 * Ditampilkan singkat (~1,9 detik) agar tetap terasa instan ala iOS.
 */
@Composable
fun LyreonIntroOverlay() {
    var visible by remember { mutableStateOf(true) }
    var entered by remember { mutableStateOf(false) }

    // Reduce motion: intro dipersingkat dan tanpa efek membesar.
    val reduced = reduceMotionEnabled
    LaunchedEffect(Unit) {
        entered = true
        delay(if (reduced) 550L else 1900L)
        visible = false
    }

    val scale by animateFloatAsState(
        targetValue = if (entered || reduced) 1f else 0.72f,
        animationSpec = lyreonTween(LyreonMotion.deliberate),
        label = "logo_scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered || reduced) 1f else 0f,
        animationSpec = lyreonTween(LyreonMotion.normal),
        label = "logo_alpha",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(lyreonTween(LyreonMotion.normal)),
        exit = fadeOut(lyreonTween(LyreonMotion.normal)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LyreonBackground),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier
                        .size(96.dp)
                        .scale(scale)
                        .alpha(alpha)
                        .clip(RoundedCornerShape(26.dp)),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.app_name).uppercase(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = LyreonTextPrimary.copy(alpha = alpha),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.tagline),
                    style = MaterialTheme.typography.labelSmall,
                    color = LyreonTextMuted.copy(alpha = alpha),
                )
                Spacer(Modifier.height(26.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(120.dp)
                        .height(3.dp)
                        .alpha(alpha),
                    color = LyreonCrimson,
                    trackColor = LyreonSurface,
                )
            }
        }
    }
}
