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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.local.LocalMusicRepository
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.GroupedTrackList
import com.lyreon.app.ui.components.LibraryGroup
import com.lyreon.app.ui.components.LibraryGroupSelector
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.vm.LocalMusicViewModel
import com.lyreon.app.ui.vm.lyreonViewModel

/**
 * Tab LOKAL — musik dari penyimpanan perangkat (mp3, m4a, ogg, …).
 * Dua sumber: indeks MediaStore + folder kustom pilihan user (SAF,
 * menembus folder ber-.nomedia seperti WhatsApp/Telegram).
 */
@Composable
fun LocalMusicContent(
    playerState: PlayerUiState,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    val vm: LocalMusicViewModel = lyreonViewModel { LocalMusicViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var group by remember { mutableStateOf(LibraryGroup.SONGS) }

    // Cek ulang izin setiap kembali ke layar (user bisa ubah dari Settings Android)
    LifecycleResumeEffect(Unit) {
        vm.refreshPermission()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> vm.onPermissionResult(granted) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) vm.addTree(uri) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LyreonBackground),
    ) {
        if (!state.permission) {
            // ---- Gerbang izin ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .border(1.dp, LyreonLine)
                    .background(LyreonSurface.copy(alpha = 0.3f))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = LyreonCrimson,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.local_permission_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = LyreonTextPrimary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.local_permission_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = LyreonTextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .border(1.dp, LyreonCrimson)
                        .background(LyreonCrimson.copy(alpha = 0.15f))
                        .clickable {
                            permissionLauncher.launch(LocalMusicRepository.requiredPermission())
                        }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(
                        stringResource(R.string.local_permission_button),
                        style = MaterialTheme.typography.labelMedium,
                        color = LyreonTextPrimary,
                    )
                }
            }
            return@Column
        }

        // ---- Bar kontrol: SELALU tampil setelah izin ada ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(1.dp, LyreonCrimson)
                    .background(LyreonCrimson.copy(alpha = 0.15f))
                    .clickable(enabled = state.tracks.isNotEmpty()) { onPlayQueue(state.tracks, 0) }
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.local_play_all, state.tracks.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = LyreonTextPrimary,
                )
            }
            // Pilih folder musik sendiri (menembus folder tak terindeks)
            IconButton(
                onClick = { folderLauncher.launch(null) },
                modifier = Modifier
                    .border(1.dp, LyreonCrimson)
                    .background(LyreonCrimson.copy(alpha = 0.12f)),
            ) {
                Icon(
                    Icons.Filled.CreateNewFolder,
                    contentDescription = stringResource(R.string.local_add_folder),
                    tint = LyreonCrimson,
                )
            }
            IconButton(
                onClick = vm::scan,
                modifier = Modifier
                    .border(1.dp, LyreonLine)
                    .background(LyreonSurface.copy(alpha = 0.3f)),
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.local_rescan),
                    tint = LyreonTextSecondary,
                )
            }
        }

        // ---- Chips folder kustom yang sudah dipilih ----
        if (state.trees.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.trees, key = { it }) { tree ->
                    Row(
                        modifier = Modifier
                            .border(1.dp, LyreonLine)
                            .background(LyreonSurface.copy(alpha = 0.3f))
                            .padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            vm.treeLabel(tree),
                            style = MaterialTheme.typography.labelSmall,
                            color = LyreonTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { vm.removeTree(tree) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.local_remove_folder),
                                tint = LyreonTextMuted,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // ---- Isi ----
        when {
            state.scanning && state.tracks.isEmpty() -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.local_scanning),
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextMuted,
                    )
                }
            }

            state.scannedOnce && state.tracks.isEmpty() -> {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                        .border(1.dp, LyreonLine)
                        .padding(20.dp),
                ) {
                    Text(stringResource(R.string.local_empty_title), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.local_empty_body), style = MaterialTheme.typography.bodySmall, color = LyreonTextMuted)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.local_add_folder),
                        style = MaterialTheme.typography.labelMedium,
                        color = LyreonCrimson,
                        modifier = Modifier
                            .border(1.dp, LyreonCrimson)
                            .clickable { folderLauncher.launch(null) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            else -> {
                Column(Modifier.fillMaxSize()) {
                    LibraryGroupSelector(group = group, onSelect = { group = it })
                    Spacer(Modifier.height(4.dp))
                    GroupedTrackList(
                        tracks = state.tracks,
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
        }
    }
}
