/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonTextSecondary

/** Cover artwork via Coil (thumbnail YouTube resolusi tinggi) + fallback monogram. */
@Composable
fun Artwork(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    // null = isi mengikuti kendala induk (dipakai bila induk sudah menentukan
    // ukuran, mis. kotak 44dp di mini player).
    size: Dp? = 56.dp,
    cornerRadius: Dp = 0.dp,
    bordered: Boolean = false,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val base = (if (size == null) modifier.fillMaxSize() else modifier.size(size))
        .clip(shape)
        .background(LyreonSurface)
        .then(if (bordered) Modifier.border(1.dp, LyreonLine, shape) else Modifier)

    Box(base, contentAlignment = Alignment.Center) {
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = title.take(1).ifBlank { "L" },
                style = MaterialTheme.typography.headlineSmall,
                color = LyreonTextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
