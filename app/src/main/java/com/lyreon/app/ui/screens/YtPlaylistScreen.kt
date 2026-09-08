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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.Artwork
import com.lyreon.app.ui.components.TrackRow
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.vm.YtPlaylistViewModel

@Composable
fun YtPlaylistScreen(
    vm: YtPlaylistViewModel,
    playerState: PlayerUiState,
    onBack: () -> Unit,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    onAppendQueue: (List<LyreonTrack>) -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()

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
                Text(stringResource(R.string.yt_playlist_title), style = MaterialTheme.typography.labelSmall, color = LyreonTextMuted)
                Text(
                    state.name.ifBlank { stringResource(R.string.home_loading) }.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = LyreonTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Artwork(url = state.thumbnailUrl, title = state.name, size = 44.dp)
        }

        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LyreonCrimson, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.notif_loading_playlist), style = MaterialTheme.typography.labelSmall, color = LyreonTextMuted)
                }
            }
            return@Column
        }

        state.error?.let { err ->
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.downloads_failed), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
                Spacer(Modifier.height(6.dp))
                Text(err, style = MaterialTheme.typography.bodySmall, color = LyreonTextSecondary)
                Spacer(Modifier.height(14.dp))
                ActionButton(label = stringResource(R.string.action_retry), primary = true, onClick = vm::load)
            }
            return@Column
        }

        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(label = stringResource(R.string.play_count, state.tracks.size), primary = true, modifier = Modifier.weight(1f)) {
                if (state.tracks.isNotEmpty()) onPlayQueue(state.tracks, 0)
            }
            ActionButton(label = stringResource(R.string.action_append_queue), primary = false) {
                onAppendQueue(state.tracks)
            }
            ActionButton(
                label = stringResource(if (state.saved) R.string.action_saved else R.string.action_save),
                primary = false,
            ) {
                vm.saveAsLocal()
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            itemsIndexed(state.tracks, key = { _, t -> t.videoId }) { index, track ->
                TrackRow(
                    track = track,
                    isActive = playerState.currentTrack?.videoId == track.videoId,
                    isPlaying = playerState.isPlaying,
                    isLiked = likedIds.contains(track.videoId),
                    isDownloaded = downloadedIds.contains(track.videoId),
                    index = index,
                    onPlay = { onPlayQueue(state.tracks, index) },
                    onLike = { onLike(track) },
                    onMore = { onTrackMore(track) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }
}
