/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.model.formatDuration
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonRose
import com.lyreon.app.ui.theme.LyreonTextMuted

/** Baris track — tap = putar; tombol hati & overflow di kanan. */
@Composable
fun TrackRow(
    track: LyreonTrack,
    isActive: Boolean,
    isPlaying: Boolean,
    isLiked: Boolean,
    isDownloaded: Boolean,
    index: Int?,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Statis, tanpa animasi per-baris (daftar panjang = hemat GPU)
    val borderColor = if (isActive) LyreonCrimson.copy(alpha = 0.45f) else LyreonLine
    val bgColor = if (isActive) LyreonSurface.copy(alpha = 0.7f) else LyreonSurface.copy(alpha = 0.35f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor)
            .background(bgColor)
            .clickable(onClick = onPlay)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Text(
                text = "%02d".format(index + 1),
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) LyreonCrimson else LyreonTextMuted,
                modifier = Modifier.width(32.dp),
            )
        } else {
            Spacer(Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .size(56.dp)
                .clickable(onClick = onPlay),
        ) {
            Artwork(url = track.thumbnailUrl, title = track.title, size = 56.dp)
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(LyreonSurface.copy(alpha = if (isActive) 0.35f else 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                LyreonMiniPlay(
                    isPlaying = isActive && isPlaying,
                    onClick = onPlay,
                    size = 28.dp,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = LyreonTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.artist.ifBlank { track.album }.ifBlank { stringResource(R.string.common_youtube) },
                style = MaterialTheme.typography.bodySmall,
                color = LyreonTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isDownloaded) {
                Text(
                    text = stringResource(R.string.common_offline),
                    style = MaterialTheme.typography.labelSmall,
                    color = LyreonRose,
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatDuration(track.durationSec),
                style = MaterialTheme.typography.labelSmall,
                color = LyreonTextSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(R.string.np_like_add),
                        tint = if (isLiked) LyreonCrimson else LyreonTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onMore, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Lainnya",
                        tint = LyreonTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
