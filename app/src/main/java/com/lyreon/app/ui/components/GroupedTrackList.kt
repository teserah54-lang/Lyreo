/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonTextSecondary

/**
 * Pengelompokan daftar track di Pustaka: lagu (datar), album, atau artis.
 * Urutan asli [tracks] dipertahankan sebagai urutan antrean — indeks baris
 * mengacu ke posisi di [tracks], bukan posisi visual dalam kelompok.
 */
enum class LibraryGroup { SONGS, ALBUMS, ARTISTS }

private data class TrackGroup(
    val label: String,
    val items: List<Pair<Int, LyreonTrack>>,
)

@Composable
fun GroupedTrackList(
    tracks: List<LyreonTrack>,
    group: LibraryGroup,
    playerState: PlayerUiState,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    modifier: Modifier = Modifier,
) {
    val unknownAlbum = stringResource(R.string.unknown_album)
    val unknownArtist = stringResource(R.string.unknown_artist)

    val groups = remember(tracks, group, unknownAlbum, unknownArtist) {
        when (group) {
            LibraryGroup.SONGS ->
                listOf(TrackGroup("", tracks.mapIndexed { i, t -> i to t }))
            LibraryGroup.ALBUMS ->
                groupByLabel(tracks, unknownAlbum) { it.album }
            LibraryGroup.ARTISTS ->
                groupByLabel(tracks, unknownArtist) { it.artist }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        groups.forEach { trackGroup ->
            if (trackGroup.label.isNotEmpty()) {
                item(key = "header:${trackGroup.label}") {
                    SectionRule(label = trackGroup.label)
                }
            }
            items(trackGroup.items, key = { (index, t) -> "${group.name}:$index:${t.videoId}" }) { (index, track) ->
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
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun groupByLabel(
    tracks: List<LyreonTrack>,
    fallback: String,
    keyOf: (LyreonTrack) -> String,
): List<TrackGroup> =
    tracks.mapIndexed { i, t -> i to t }
        .groupBy { (_, t) -> keyOf(t).trim().ifEmpty { fallback } }
        .entries
        .sortedBy { it.key.lowercase() }
        .map { (label, items) -> TrackGroup(label, items) }

@Composable
fun LibraryGroupSelector(
    group: LibraryGroup,
    onSelect: (LibraryGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(LibraryGroup.entries.toList(), key = { it.name }) { option ->
            val selected = option == group
            Box(
                modifier = Modifier
                    .border(1.dp, if (selected) LyreonCrimson else LyreonLine)
                    .background(
                        if (selected) LyreonCrimson.copy(alpha = 0.18f)
                        else LyreonSurface.copy(alpha = 0.25f),
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(
                        when (option) {
                            LibraryGroup.SONGS -> R.string.filter_songs
                            LibraryGroup.ALBUMS -> R.string.filter_albums
                            LibraryGroup.ARTISTS -> R.string.filter_artists
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) LyreonTextPrimary else LyreonTextSecondary,
                )
            }
        }
    }
}
