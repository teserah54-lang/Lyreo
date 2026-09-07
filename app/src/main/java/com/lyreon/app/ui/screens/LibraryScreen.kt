/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyreon.app.R
import com.lyreon.app.data.db.PlaylistEntity
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.Artwork
import com.lyreon.app.ui.components.GroupedTrackList
import com.lyreon.app.ui.components.LibraryGroup
import com.lyreon.app.ui.components.LibraryGroupSelector
import com.lyreon.app.ui.components.SectionRule
import com.lyreon.app.ui.components.TrackRow
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.vm.LibraryTab
import com.lyreon.app.ui.vm.LibraryViewModel

@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    playerState: PlayerUiState,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showNewPlaylist by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<PlaylistEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importPlaylist(uri) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        LibraryTab.entries.getOrNull(initialTab)?.let(vm::selectTab)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LyreonBackground),
    ) {
        // ---- Header ----
        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            Text(text = stringResource(R.string.library_kicker), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.displaySmall,
                color = LyreonTextPrimary,
            )
            Spacer(Modifier.height(8.dp))
            Box(Modifier.width(40.dp).height(2.dp).background(LyreonCrimson))
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.library_stats, state.liked.size, state.playlists.size, state.history.size, state.downloads.size),
                style = MaterialTheme.typography.bodySmall,
                color = LyreonTextSecondary,
            )
            Spacer(Modifier.height(16.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryTab.entries.toList(), key = { it.name }) { tab ->
                    val selected = state.tab == tab
                    Box(
                        modifier = Modifier
                            .border(1.dp, if (selected) LyreonCrimson else LyreonLine)
                            .background(
                                if (selected) LyreonCrimson.copy(alpha = 0.18f)
                                else LyreonSurface.copy(alpha = 0.25f),
                            )
                            .clickable { vm.selectTab(tab) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(when (tab) {
                                LibraryTab.FAVORIT -> R.string.tab_favorite
                                LibraryTab.PLAYLIST -> R.string.tab_playlist
                                LibraryTab.RIWAYAT -> R.string.tab_history
                                LibraryTab.OFFLINE -> R.string.tab_offline
                                LibraryTab.LOCAL -> R.string.tab_local
                            }),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) LyreonTextPrimary else LyreonTextSecondary,
                        )
                    }
                }
            }
        }

        // ---- Isi tab ----
        if (state.tab == LibraryTab.LOCAL) {
            // Tab LOKAL punya LazyColumn sendiri (daftar panjang + gerbang izin)
            LocalMusicContent(
                playerState = playerState,
                onPlayQueue = onPlayQueue,
                onTrackMore = onTrackMore,
                onLike = onLike,
                likedIds = likedIds,
                downloadedIds = downloadedIds,
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        if (state.tab == LibraryTab.FAVORIT) {
            // Tab FAVORIT punya LazyColumn sendiri (lagu/album/artis)
            FavoriteContent(
                tracks = state.liked,
                playerState = playerState,
                onPlayQueue = onPlayQueue,
                onTrackMore = onTrackMore,
                onLike = onLike,
                likedIds = likedIds,
                downloadedIds = downloadedIds,
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            when (state.tab) {
                LibraryTab.LOCAL, LibraryTab.FAVORIT -> { /* tidak tercapai — ditangani di atas */ }

                LibraryTab.PLAYLIST -> {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                                .border(1.dp, LyreonCrimson)
                                .background(LyreonCrimson.copy(alpha = 0.15f))
                                .clickable { showNewPlaylist = true }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, tint = LyreonTextPrimary)
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.library_new_playlist), style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary)
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                                .border(1.dp, LyreonLine)
                                .background(LyreonSurface.copy(alpha = 0.3f))
                                .clickable(enabled = !state.importing) {
                                    importLauncher.launch(arrayOf("*/*"))
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.PlaylistAdd,
                                contentDescription = null,
                                tint = if (state.importing) LyreonTextMuted else LyreonTextPrimary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(
                                    if (state.importing) R.string.library_importing else R.string.library_import_playlist,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (state.importing) LyreonTextMuted else LyreonTextPrimary,
                            )
                        }
                    }
                    if (state.playlists.isEmpty()) {
                        item { EmptyNote(stringResource(R.string.library_pl_empty_title), stringResource(R.string.library_pl_empty_body)) }
                    } else {
                        items(state.playlists, key = { it.playlist.id }) { stats ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 6.dp)
                                    .border(1.dp, LyreonLine)
                                    .background(LyreonSurface.copy(alpha = 0.3f))
                                    .clickable { onOpenPlaylist(stats.playlist.id, stats.playlist.name) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Artwork(
                                    url = stats.coverUrl.orEmpty(),
                                    title = stats.playlist.name,
                                    size = 52.dp,
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stats.playlist.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = LyreonTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        stringResource(R.string.playlist_track_count, stats.itemCount),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LyreonTextSecondary,
                                    )
                                }
                                IconButton(onClick = { playlistToDelete = stats.playlist }) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = stringResource(R.string.action_delete),
                                        tint = LyreonTextMuted,
                                    )
                                }
                            }
                        }
                    }
                }

                LibraryTab.RIWAYAT -> {
                    if (state.history.isEmpty()) {
                        item { EmptyNote(stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body)) }
                    } else {
                        item {
                            PlayAllBar(
                                label = stringResource(R.string.history_replay),
                                secondary = stringResource(R.string.history_clear),
                                onClick = { onPlayQueue(state.history, 0) },
                                onSecondary = { vm.clearHistory() },
                            )
                        }
                        itemsIndexed(state.history, key = { _, t -> t.videoId }) { index, track ->
                            TrackRow(
                                track = track,
                                isActive = playerState.currentTrack?.videoId == track.videoId,
                                isPlaying = playerState.isPlaying,
                                isLiked = likedIds.contains(track.videoId),
                                isDownloaded = downloadedIds.contains(track.videoId),
                                index = index,
                                onPlay = { onPlayQueue(state.history, index) },
                                onLike = { onLike(track) },
                                onMore = { onTrackMore(track) },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                LibraryTab.OFFLINE -> {
                    item {
                        OfflineSummary(
                            downloads = state.downloads,
                            onPlayDownloads = {
                                val done = state.downloads
                                    .filter { it.state == "DONE" }
                                    .map { d -> LyreonTrack(d.videoId, d.title, d.artist, d.album, d.durationSec, d.thumbnailUrl) }
                                if (done.isNotEmpty()) onPlayQueue(done, 0)
                            },
                        )
                        SectionRule(label = stringResource(R.string.offline_queue_hint))
                    }
                }
            }
        }
    }

    if (showNewPlaylist) {
        NewPlaylistDialog(
            onCreate = {
                vm.createPlaylist(it)
                showNewPlaylist = false
            },
            onDismiss = { showNewPlaylist = false },
        )
    }

    playlistToDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = LyreonElevated,
            title = { Text(stringResource(R.string.delete_playlist_title), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) },
            text = {
                Text(
                    stringResource(R.string.delete_playlist_body, pl.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LyreonTextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePlaylist(pl)
                    playlistToDelete = null
                }) { Text(stringResource(R.string.action_delete), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
                }
            },
        )
    }

    state.importResult?.let { result ->
        AlertDialog(
            onDismissRequest = vm::clearImportResult,
            containerColor = LyreonElevated,
            title = { Text(stringResource(R.string.import_playlist_done), style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary) },
            text = {
                Text(
                    stringResource(R.string.import_playlist_summary, result.playlistName, result.imported.size, result.skipped),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LyreonTextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = vm::clearImportResult) {
                    Text(stringResource(R.string.action_close), style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary)
                }
            },
        )
    }

    if (state.importError) {
        AlertDialog(
            onDismissRequest = vm::clearImportError,
            containerColor = LyreonElevated,
            title = { Text(stringResource(R.string.import_playlist_failed_title), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) },
            text = {
                Text(
                    stringResource(R.string.import_playlist_failed_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LyreonTextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = vm::clearImportError) {
                    Text(stringResource(R.string.action_close), style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary)
                }
            },
        )
    }
}

@Composable
internal fun EmptyNote(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .border(1.dp, LyreonLine)
            .padding(20.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = LyreonTextMuted)
    }
}

@Composable
private fun FavoriteContent(
    tracks: List<LyreonTrack>,
    playerState: PlayerUiState,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            EmptyNote(
                stringResource(R.string.library_fav_empty_title),
                stringResource(R.string.library_fav_empty_body),
            )
        }
        return
    }

    var group by remember { mutableStateOf(LibraryGroup.SONGS) }

    Column(modifier = modifier.fillMaxSize()) {
        PlayAllBar(
            label = stringResource(R.string.library_play_all_fav),
            onClick = { onPlayQueue(tracks, 0) },
        )
        LibraryGroupSelector(group = group, onSelect = { group = it })
        Spacer(Modifier.height(4.dp))
        GroupedTrackList(
            tracks = tracks,
            group = group,
            playerState = playerState,
            onPlayQueue = onPlayQueue,
            onTrackMore = onTrackMore,
            onLike = onLike,
            likedIds = likedIds,
            downloadedIds = downloadedIds,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PlayAllBar(
    label: String,
    secondary: String? = null,
    onClick: () -> Unit,
    onSecondary: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .border(1.dp, LyreonCrimson)
                .background(LyreonCrimson.copy(alpha = 0.15f))
                .clickable(onClick = onClick)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary)
        }
        if (secondary != null) {
            Box(
                modifier = Modifier
                    .border(1.dp, LyreonLine)
                    .background(LyreonSurface.copy(alpha = 0.3f))
                    .clickable { onSecondary?.invoke() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(secondary, style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
            }
        }
    }
}

@Composable
private fun OfflineSummary(
    downloads: List<com.lyreon.app.data.db.DownloadEntity>,
    onPlayDownloads: () -> Unit,
) {
    val done = downloads.count { it.state == "DONE" }
    val active = downloads.count { it.state == "QUEUED" || it.state == "DOWNLOADING" }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .border(1.dp, LyreonLine)
            .background(LyreonSurface.copy(alpha = 0.3f))
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.offline_storage), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.offline_summary, done, active),
            style = MaterialTheme.typography.bodySmall,
            color = LyreonTextSecondary,
        )
        Spacer(Modifier.height(12.dp))
        if (done > 0) {
            Box(
                modifier = Modifier
                    .border(1.dp, LyreonCrimson)
                    .background(LyreonCrimson.copy(alpha = 0.15f))
                    .clickable(onClick = onPlayDownloads)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(stringResource(R.string.offline_play_mode), style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary)
            }
        }
    }
}

@Composable
internal fun NewPlaylistDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyreonElevated,
        title = { Text(stringResource(R.string.new_playlist_title), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = {
                    Text(stringResource(R.string.new_playlist_hint), style = MaterialTheme.typography.bodySmall, color = LyreonTextMuted)
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
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) {
                Text(stringResource(R.string.action_create), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
            }
        },
    )
}
