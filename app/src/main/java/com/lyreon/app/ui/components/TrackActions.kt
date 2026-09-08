/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.model.formatDuration
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.LyreonScrimSheet

data class TrackActions(
    val onPlayNext: () -> Unit,
    val onAddToQueue: () -> Unit,
    val onAddToPlaylist: () -> Unit,
    val onDownload: () -> Unit,
    val onToggleLike: () -> Unit,
    val onShare: () -> Unit,
    val isLiked: Boolean,
    val isDownloaded: Boolean,
)

/** Lembar aksi utuh untuk satu track — sudut rounded ala iOS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackActionsSheet(
    track: LyreonTrack,
    actions: TrackActions,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LyreonElevated,
        scrimColor = LyreonScrimSheet,
        contentColor = LyreonTextPrimary,
        shape = LyreonRadius.top(),
        dragHandle = {
            Spacer(
                Modifier
                    .padding(top = 10.dp)
                    .width(44.dp)
                    .height(2.dp)
                    .background(LyreonSurface),
            )
        },
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(url = track.thumbnailUrl, title = track.title, size = 64.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = LyreonTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${track.artist.ifBlank { stringResource(R.string.common_youtube) }}  ·  ${formatDuration(track.durationSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            ActionRow(Icons.Filled.SkipNext, stringResource(R.string.act_play_next)) { actions.onPlayNext(); onDismiss() }
            ActionRow(Icons.Filled.QueueMusic, stringResource(R.string.act_add_queue)) { actions.onAddToQueue(); onDismiss() }
            ActionRow(Icons.Filled.PlaylistAdd, stringResource(R.string.act_save_playlist)) { actions.onAddToPlaylist(); onDismiss() }
            ActionRow(
                if (actions.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                stringResource(if (actions.isLiked) R.string.act_like_remove else R.string.act_like_add),
            ) { actions.onToggleLike(); onDismiss() }
            if (!track.isLocal) {
                // File lokal sudah tersimpan di perangkat & tidak punya link YouTube
                ActionRow(
                    Icons.Filled.Download,
                    stringResource(R.string.act_download),
                ) { actions.onDownload(); onDismiss() }
                ActionRow(Icons.Filled.Share, stringResource(R.string.act_share)) { actions.onShare(); onDismiss() }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = LyreonTextPrimary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(18.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary)
    }
}

data class PlaylistOption(val id: Long, val name: String, val count: Int)

/** Dialog pilih playlist / buat baru. */
@Composable
fun AddToPlaylistDialog(
    track: LyreonTrack,
    playlists: List<PlaylistOption>,
    onSelect: (Long) -> Unit,
    onCreateNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyreonElevated,
        shape = RoundedCornerShape(24.dp),
        titleContentColor = LyreonTextPrimary,
        textContentColor = LyreonTextSecondary,
        title = {
            Text(stringResource(R.string.act_save_playlist), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
        },
        text = {
            Column {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LyreonTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                if (playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.a2p_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextMuted,
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, LyreonLine, RoundedCornerShape(12.dp)),
                    ) {
                        playlists.take(6).forEach { pl ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(pl.id); onDismiss() }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    pl.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = LyreonTextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("${pl.count}", style = MaterialTheme.typography.labelSmall, color = LyreonTextMuted)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    placeholder = {
                        Text(stringResource(R.string.a2p_create_hint), style = MaterialTheme.typography.bodySmall, color = LyreonTextMuted)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LyreonCrimson,
                        unfocusedBorderColor = LyreonLine,
                        cursorColor = LyreonTextPrimary,
                        focusedTextColor = LyreonTextPrimary,
                        unfocusedTextColor = LyreonTextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isNotBlank()) {
                        onCreateNew(newName.trim())
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.a2p_create_save), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
            }
        },
    )
}

/** Sleep timer. */
@Composable
fun SleepTimerDialog(
    activeDeadline: Long?,
    onSelect: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(5, 10, 15, 30, 45, 60, 90)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyreonElevated,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(stringResource(R.string.sleep_title), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
        },
        text = {
            Column {
                if (activeDeadline != null) {
                    val remain = ((activeDeadline - System.currentTimeMillis()) / 60000L).coerceAtLeast(0L)
                    Text(
                        stringResource(R.string.sleep_active, remain),
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                options.forEach { min ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(min); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("%02d".format(min), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(R.string.sleep_minutes, min), style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary)
                    }
                }
            }
        },
        confirmButton = {
            if (activeDeadline != null) {
                TextButton(onClick = { onCancel(); onDismiss() }) {
                    Text(stringResource(R.string.sleep_off), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
            }
        },
    )
}
