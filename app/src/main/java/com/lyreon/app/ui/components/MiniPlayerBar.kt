/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.player.PlayerManager
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextMuted
import androidx.compose.foundation.border
import com.lyreon.app.ui.theme.LyreonHairline
import com.lyreon.app.ui.theme.LyreonMotion
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.lyreonChromeEnter
import com.lyreon.app.ui.theme.lyreonChromeExit
import com.lyreon.app.ui.theme.lyreonTween

/** Sudut mini player: sedang, selaras kartu (skala radius tema). */
private val LyreonMiniShape = RoundedCornerShape(LyreonRadius.md)

@Composable
fun MiniPlayerBar(
    track: LyreonTrack?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    player: PlayerManager,
    visible: Boolean,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && track != null,
        enter = lyreonChromeEnter(),
        exit = lyreonChromeExit(),
        modifier = modifier,
    ) {
        if (track == null) return@AnimatedVisibility

        // Progress dikoleksi LOKAL di widget ini — hanya bar kecil ini yang
        // recompose tiap 500ms, bukan seluruh layar di belakangnya.
        val pos by player.position.collectAsStateWithLifecycle()
        val progress = if (pos.durationMs > 0) pos.positionMs.toFloat() / pos.durationMs else 0f
        val p by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = lyreonTween(LyreonMotion.deliberate),
            label = "mini_prog",
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(LyreonMiniShape)
                .background(LyreonElevated)
                .border(1.dp, LyreonHairline, LyreonMiniShape)
                .clickable(onClick = onOpen),
        ) {
            if (isBuffering) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = LyreonCrimson,
                    trackColor = LyreonSurface,
                )
            } else {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = LyreonCrimson,
                    trackColor = LyreonSurface,
                    strokeCap = StrokeCap.Butt,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(url = track.thumbnailUrl, title = track.title, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = LyreonTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = track.artist.ifBlank { stringResource(R.string.common_youtube_music) },
                        style = MaterialTheme.typography.labelSmall,
                        color = LyreonTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                LyreonMiniPlay(isPlaying = isPlaying, onClick = onToggle, size = 40.dp)
                IconButton(onClick = onNext) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.np_next),
                        tint = LyreonTextPrimary,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LyreonTextMuted.copy(alpha = 0.25f)),
            )
        }
    }
}
