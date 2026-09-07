/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyreon.app.R
import com.lyreon.app.data.db.DownloadEntity
import com.lyreon.app.data.db.DownloadState
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.Artwork
import com.lyreon.app.ui.components.LyreonMiniPlay
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonRose
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.vm.DownloadsViewModel

@Composable
fun DownloadsScreen(
    vm: DownloadsViewModel,
    playerState: PlayerUiState,
    onBack: () -> Unit,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val done = downloads.filter { it.state == DownloadState.DONE }
    val active = downloads.filter { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING }
    val failed = downloads.filter { it.state == DownloadState.ERROR || it.state == DownloadState.CANCELED }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LyreonBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = LyreonTextPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.labelSmall, color = LyreonTextMuted)
                Text(
                    stringResource(R.string.dl_kicker).uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = LyreonTextPrimary,
                )
            }
            Text(
                "${done.size} SIAP",
                style = MaterialTheme.typography.labelSmall,
                color = LyreonCrimson,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (done.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                            .border(1.dp, LyreonCrimson)
                            .background(LyreonCrimson.copy(alpha = 0.15f))
                            .clickable {
                                onPlayQueue(done.map { it.toTrack() }, 0)
                            }
                            .padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.dl_play_all_offline, done.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = LyreonTextPrimary,
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.dl_storage_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = LyreonTextMuted,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(10.dp))
            }

            if (downloads.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp)
                            .border(1.dp, LyreonLine)
                            .padding(20.dp),
                    ) {
                        Text(stringResource(R.string.downloads_empty), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Ketuk menu Lainnya pada lagu, lalu pilih UNDUH (OFFLINE). Lagu tersimpan sebagai audio-only dan otomatis diputar offline saat sinyal buruk.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LyreonTextMuted,
                        )
                    }
                }
            }

            if (active.isNotEmpty()) {
                item { DownloadHeader("SEDANG DIPROSES · ${active.size}") }
                items(active, key = { it.videoId }) { entry ->
                    DownloadRow(
                        entry = entry,
                        onCancel = { vm.cancel(entry.videoId) },
                        onDelete = { vm.remove(entry.videoId) },
                        onRetry = { vm.retry(entry.videoId) },
                        onPlay = null,
                    )
                }
            }

            if (done.isNotEmpty()) {
                item { DownloadHeader("TERSIMPAN · ${done.size}") }
                items(done, key = { it.videoId }) { entry ->
                    val activePlaying = playerState.currentTrack?.videoId == entry.videoId
                    DownloadRow(
                        entry = entry,
                        onCancel = null,
                        onDelete = { vm.remove(entry.videoId) },
                        onRetry = null,
                        onPlay = { onPlayQueue(done.map { it.toTrack() }, done.indexOf(entry)) },
                        activePlaying = activePlaying,
                        isPlaying = playerState.isPlaying,
                    )
                }
            }

            if (failed.isNotEmpty()) {
                item { DownloadHeader("PERLU TINDAKAN · ${failed.size}") }
                items(failed, key = { it.videoId }) { entry ->
                    DownloadRow(
                        entry = entry,
                        onCancel = null,
                        onDelete = { vm.remove(entry.videoId) },
                        onRetry = { vm.retry(entry.videoId) },
                        onPlay = null,
                    )
                }
            }
        }
    }
}

private fun DownloadEntity.toTrack() =
    LyreonTrack(videoId, title, artist, album, durationSec, thumbnailUrl)

@Composable
private fun DownloadHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
        Box(Modifier.weight(1f).height(1.dp).background(LyreonLine))
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntity,
    onCancel: (() -> Unit)?,
    onDelete: () -> Unit,
    onRetry: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    activePlaying: Boolean = false,
    isPlaying: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .border(1.dp, if (activePlaying) LyreonCrimson else LyreonLine)
            .background(LyreonSurface.copy(alpha = 0.3f))
            .clickable(enabled = onPlay != null) { onPlay?.invoke() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(url = entry.thumbnailUrl, title = entry.title, size = 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = LyreonTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.artist.ifBlank { stringResource(R.string.common_youtube) },
                    style = MaterialTheme.typography.labelSmall,
                    color = LyreonTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (entry.state) {
                        DownloadState.DONE -> "OFFLINE · ${(entry.bytesTotal / 1024 / 1024)} MB"
                        DownloadState.DOWNLOADING -> stringResource(R.string.dl_downloading)
                        DownloadState.QUEUED -> stringResource(R.string.dl_queued)
                        DownloadState.ERROR -> "GAGAL · ${entry.errorMessage ?: ""}"
                        else -> stringResource(R.string.dl_canceled)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (entry.state) {
                        DownloadState.DONE -> LyreonRose
                        DownloadState.ERROR -> LyreonCrimson
                        else -> LyreonTextMuted
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (onPlay != null) {
                LyreonMiniPlay(isPlaying = activePlaying && isPlaying, onClick = onPlay, size = 36.dp)
            }
            if (onCancel != null) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel), tint = LyreonTextSecondary)
                }
            }
            if (onRetry != null) {
                IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_retry), tint = LyreonTextSecondary)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.action_delete), tint = LyreonTextMuted)
            }
        }

        if (entry.state == DownloadState.DOWNLOADING || entry.state == DownloadState.QUEUED) {
            Spacer(Modifier.height(8.dp))
            if (entry.bytesTotal > 0) {
                val p = (entry.bytesDone.toFloat() / entry.bytesTotal.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = LyreonCrimson,
                    trackColor = LyreonSurface,
                    strokeCap = StrokeCap.Butt,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = LyreonCrimson,
                    trackColor = LyreonSurface,
                )
            }
        }
    }
}
