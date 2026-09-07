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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.Artwork
import com.lyreon.app.ui.components.TrackRow
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.vm.PlaylistViewModel

@Composable
fun PlaylistDetailScreen(
    vm: PlaylistViewModel,
    playlistName: String,
    playerState: PlayerUiState,
    onBack: () -> Unit,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    onSetShuffle: (Boolean) -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    val tracks by vm.tracks.collectAsStateWithLifecycle()
    var showRename by remember { mutableStateOf(false) }
    var nameState by remember(playlistName) { mutableStateOf(playlistName) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LyreonBackground),
    ) {
        // ---- Header ----
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
                Text(stringResource(R.string.common_playlist_caps), style = MaterialTheme.typography.labelSmall, color = LyreonTextMuted)
                Text(
                    nameState.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = LyreonTextPrimary,
                    maxLines = 1,
                )
            }
            IconButton(onClick = { showRename = true }) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_rename), tint = LyreonTextSecondary)
            }
        }

        // ---- Aksi utama ----
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(label = stringResource(R.string.play_count, tracks.size), primary = true, modifier = Modifier.weight(1f)) {
                if (tracks.isNotEmpty()) {
                    onSetShuffle(false)
                    onPlayQueue(tracks, 0)
                }
            }
            ActionButton(label = stringResource(R.string.common_shuffle).uppercase(), primary = false) {
                if (tracks.isNotEmpty()) {
                    onPlayQueue(tracks.shuffled(), 0)
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            if (tracks.isEmpty()) {
                item {
                    EmptyNote(
                        stringResource(R.string.pl_empty_title),
                        stringResource(R.string.pl_empty_body),
                    )
                }
            } else {
                itemsIndexed(tracks, key = { _, t -> t.videoId }) { index, track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.padding(start = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            IconButton(
                                onClick = { if (index > 0) vm.move(index, index - 1) },
                                enabled = index > 0,
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowUp,
                                    contentDescription = stringResource(R.string.move_up),
                                    tint = if (index > 0) LyreonTextSecondary else LyreonSurface,
                                )
                            }
                            IconButton(
                                onClick = { if (index < tracks.lastIndex) vm.move(index, index + 1) },
                                enabled = index < tracks.lastIndex,
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.move_down),
                                    tint = if (index < tracks.lastIndex) LyreonTextSecondary else LyreonSurface,
                                )
                            }
                        }
                        TrackRow(
                            track = track,
                            isActive = playerState.currentTrack?.videoId == track.videoId,
                            isPlaying = playerState.isPlaying,
                            isLiked = likedIds.contains(track.videoId),
                            isDownloaded = downloadedIds.contains(track.videoId),
                            index = index,
                            onPlay = { onPlayQueue(tracks, index) },
                            onLike = { onLike(track) },
                            onMore = { onTrackMore(track) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 20.dp, top = 4.dp, bottom = 4.dp),
                        )
                        IconButton(onClick = { vm.remove(track.videoId) }) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = stringResource(R.string.remove_from_playlist),
                                tint = LyreonTextMuted,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRename) {
        RenameDialog(
            current = nameState,
            onRename = {
                vm.rename(it)
                nameState = it
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }
}

@Composable
internal fun ActionButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // Nonaktif = redup + tidak bisa diketuk. Target sentuh tetap dipertahankan
    // supaya tata letak tidak bergeser saat aksi belum tersedia.
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.42f)
            .then(
                if (primary) {
                    Modifier
                        .border(1.dp, LyreonCrimson)
                        .background(LyreonCrimson.copy(alpha = 0.15f))
                } else {
                    Modifier
                        .border(1.dp, LyreonLine)
                        .background(LyreonSurface.copy(alpha = 0.3f))
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (primary) LyreonTextPrimary else LyreonTextSecondary,
        )
    }
}

@Composable
private fun RenameDialog(
    current: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(current) { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.lyreon.app.ui.theme.LyreonElevated,
        title = { Text(stringResource(R.string.action_rename), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LyreonCrimson,
                    unfocusedBorderColor = LyreonLine,
                    cursorColor = LyreonTextPrimary,
                    focusedTextColor = LyreonTextPrimary,
                    unfocusedTextColor = LyreonTextPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                if (name.isNotBlank()) onRename(name.trim())
            }) { Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
            }
        },
    )
}
