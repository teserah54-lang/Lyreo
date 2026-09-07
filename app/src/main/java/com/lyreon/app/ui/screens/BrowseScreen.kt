/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.Artwork
import com.lyreon.app.ui.components.TrackRow
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.vm.BrowseViewModel
import com.lyreon.app.yt.BrowseItem
import com.lyreon.app.yt.BrowseItemKind
import com.lyreon.app.yt.BrowseSection

/**
 * Halaman hasil browse InnerTube: artis, album, playlist besar, serta
 * genre/mood dan kategorinya (wishlist #20, pola Meld/Metrolist).
 *
 * Satu layar untuk semua bentuk halaman karena respons InnerTube sudah
 * dinormalisasi [BrowseParser] menjadi rak berjudul. Rak berisi lagu dirender
 * sebagai baris (bisa langsung diputar), rak berisi album/playlist/artis
 * dirender sebagai kartu horizontal — persis tata letak YouTube Music.
 */
@Composable
fun BrowseScreen(
    vm: BrowseViewModel,
    fallbackTitle: String,
    playerState: PlayerUiState,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    onBack: () -> Unit,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
    onOpenBrowse: (String, String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onStartRadio: (LyreonTrack) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sections = state.sections
    val allTracks = sections.flatMap { section -> section.items.filter { it.isPlayableTrack() }.map { it.toTrack() } }
    val header = state.header

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
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = LyreonTextPrimary,
                )
            }
            Column(Modifier.padding(start = 8.dp)) {
                Text(
                    stringResource(R.string.browse_kicker).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = LyreonCrimson,
                )
                Text(
                    state.title.ifBlank { fallbackTitle }.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = LyreonTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LyreonCrimson, strokeWidth = 2.dp)
            }

            sections.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyNote(
                    stringResource(R.string.browse_empty_title),
                    stringResource(R.string.browse_empty_body),
                )
                Spacer(Modifier.height(16.dp))
                ActionButton(
                    label = stringResource(R.string.action_retry),
                    primary = true,
                    onClick = vm::load,
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // ---- Kepala halaman artis/album (pola ArtistScreen Metrolist) ----
                // Sampul besar + ringkasan + tiga aksi: putar, radio, acak.
                // Radio memakai benih nyata (lagu teratas atau `RDAMVM<videoId>`
                // dari header) sehingga antrean langsung terisi lagu terkait.
                if (!header.isEmpty) {
                    item(key = "browse_hero", contentType = "hero") {
                        val seed = allTracks.firstOrNull()
                            ?: header.seedVideoId.takeIf { it.isNotBlank() }?.let { id ->
                                LyreonTrack(
                                    videoId = id,
                                    title = header.title,
                                    artist = "",
                                    thumbnailUrl = header.thumbUrl,
                                )
                            }
                        BrowseHero(
                            header = header,
                            trackCount = allTracks.size,
                            onPlay = { if (allTracks.isNotEmpty()) onPlayQueue(allTracks, 0) },
                            onRadio = { seed?.let(onStartRadio) },
                            onShuffle = {
                                if (allTracks.isNotEmpty()) onPlayQueue(allTracks.shuffled(), 0)
                            },
                            radioEnabled = seed != null,
                            playEnabled = allTracks.isNotEmpty(),
                        )
                    }
                }

                if (allTracks.isNotEmpty() && header.isEmpty) {
                    item(key = "browse_actions") {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ActionButton(
                                label = stringResource(R.string.play_count, allTracks.size),
                                primary = true,
                                modifier = Modifier.weight(1f),
                                onClick = { onPlayQueue(allTracks, 0) },
                            )
                        }
                    }
                }

                items(sections, key = { section -> section.title + section.items.size }) { section ->
                    if (section.items.any { it.isPlayableTrack() } && section.items.all { it.isPlayableTrack() }) {
                        TrackShelf(
                            section = section,
                            playerState = playerState,
                            likedIds = likedIds,
                            downloadedIds = downloadedIds,
                            allTracks = allTracks,
                            onPlayQueue = onPlayQueue,
                            onLike = onLike,
                            onTrackMore = onTrackMore,
                        )
                    } else {
                        CardShelf(
                            section = section,
                            onOpenBrowse = onOpenBrowse,
                            onOpenPlaylist = onOpenPlaylist,
                            onPlayTrack = { item ->
                                val tracks = section.items.filter { it.isPlayableTrack() }.map { it.toTrack() }
                                val start = tracks.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
                                onPlayQueue(if (tracks.isEmpty()) listOf(item.toTrack()) else tracks, start)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Rak lagu: baris penuh dengan tombol suka/aksi, sama seperti layar playlist. */
@Composable
private fun TrackShelf(
    section: BrowseSection,
    playerState: PlayerUiState,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    allTracks: List<LyreonTrack>,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
) {
    val tracks = section.items.map { it.toTrack() }
    Column {
        ShelfTitle(section.title)
        // BUKAN LazyColumn: rak ini berada di dalam item LazyColumn induk, yang
        // mengukur anaknya dengan tinggi tak terbatas — scrollable bersarang
        // searah akan crash. Baris dirender langsung saja.
        Column {
            tracks.forEachIndexed { index, track ->
                val globalIndex = allTracks.indexOfFirst { it.videoId == track.videoId }
                TrackRow(
                    track = track,
                    isActive = playerState.currentTrack?.videoId == track.videoId,
                    isPlaying = playerState.isPlaying,
                    isLiked = likedIds.contains(track.videoId),
                    isDownloaded = downloadedIds.contains(track.videoId),
                    index = if (globalIndex >= 0) globalIndex else index,
                    onPlay = { onPlayQueue(allTracks.ifEmpty { tracks }, if (globalIndex >= 0) globalIndex else index) },
                    onLike = { onLike(track) },
                    onMore = { onTrackMore(track) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** Rak kartu: album, playlist, artis, atau kategori genre/mood. */
@Composable
private fun CardShelf(
    section: BrowseSection,
    onOpenBrowse: (String, String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onPlayTrack: (BrowseItem) -> Unit,
) {
    Column {
        ShelfTitle(section.title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(section.items, key = { item -> item.title + item.browseId + item.playlistId }) { item ->
                BrowseCard(
                    item = item,
                    onClick = {
                        when {
                            item.kind == BrowseItemKind.TRACK && item.videoId.isNotBlank() -> onPlayTrack(item)
                            item.playlistId.isNotBlank() -> onOpenPlaylist(item.playlistId)
                            item.browseId.isNotBlank() -> onOpenBrowse(item.browseId, item.title)
                            item.videoId.isNotBlank() -> onPlayTrack(item)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ShelfTitle(title: String) {
    if (title.isBlank()) return
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = LyreonTextPrimary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun BrowseCard(item: BrowseItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(148.dp)
            .clip(RoundedCornerShape(LyreonRadius.md))
            .background(LyreonSurface)
            .clickable { onClick() }
            .padding(10.dp),
    ) {
        Artwork(
            url = item.thumbUrl,
            title = item.title,
            size = 128.dp,
            modifier = Modifier.clip(
                RoundedCornerShape(
                    if (item.kind == BrowseItemKind.ARTIST) LyreonRadius.pill else LyreonRadius.sm,
                ),
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            color = LyreonTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                item.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = LyreonTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun BrowseItem.isPlayableTrack(): Boolean =
    videoId.isNotBlank() && (kind == BrowseItemKind.TRACK || kind == BrowseItemKind.OTHER)

private fun BrowseItem.toTrack(): LyreonTrack = LyreonTrack(
    videoId = videoId,
    title = title,
    artist = subtitle,
    durationSec = durationSec.toLong(),
    thumbnailUrl = thumbUrl,
)

/**
 * Kepala halaman artis/album.
 *
 * Latar memakai sampul yang diredupkan + disaturasi rendah + gradien scrim —
 * BUKAN `Modifier.blur`: lapisan buram nyata di dalam item yang ikut menggulir
 * dibayar ulang tiap frame (lihat notes/03 §4). Efeknya setara, biayanya jauh
 * lebih kecil.
 */
@Composable
private fun BrowseHero(
    header: com.lyreon.app.yt.BrowseHeader,
    trackCount: Int,
    onPlay: () -> Unit,
    onRadio: () -> Unit,
    onShuffle: () -> Unit,
    radioEnabled: Boolean,
    playEnabled: Boolean,
) {
    val background = LyreonBackground
    val scrim = remember(background) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            colors = listOf(
                background.copy(alpha = 0.30f),
                background.copy(alpha = 0.86f),
                background,
            ),
        )
    }
    val desaturate = remember {
        androidx.compose.ui.graphics.ColorFilter.colorMatrix(
            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0.42f) },
        )
    }
    var descriptionExpanded by remember { androidx.compose.runtime.mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(background),
    ) {
        if (header.thumbUrl.isNotBlank()) {
            coil3.compose.AsyncImage(
                model = header.thumbUrl,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                colorFilter = desaturate,
                alpha = 0.5f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(230.dp)
                .drawBehind { drawRect(scrim) },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(
                    url = header.thumbUrl,
                    title = header.title,
                    size = 96.dp,
                    // Kanal artis dirender bulat; album/playlist tetap persegi.
                    cornerRadius = if (header.shufflePlaylistId.startsWith("OLAK")) LyreonRadius.sm else 48.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        header.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = LyreonTextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (header.meta.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            header.meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = LyreonTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (trackCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.browse_track_count, trackCount),
                            style = MaterialTheme.typography.labelSmall,
                            color = LyreonTextMuted,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    label = stringResource(R.string.browse_play),
                    primary = true,
                    enabled = playEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onPlay,
                )
                ActionButton(
                    label = stringResource(R.string.browse_radio),
                    primary = false,
                    enabled = radioEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onRadio,
                )
                ActionButton(
                    label = stringResource(R.string.browse_shuffle),
                    primary = false,
                    enabled = playEnabled,
                    modifier = Modifier.weight(1f),
                    onClick = onShuffle,
                )
            }

            if (header.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    header.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = LyreonTextSecondary,
                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { descriptionExpanded = !descriptionExpanded },
                )
            }
        }
    }
}
