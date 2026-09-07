/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.lyreon.app.R
import com.lyreon.app.data.model.EditorialSection
import com.lyreon.app.data.model.LyreonArchive
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.Artwork
import com.lyreon.app.ui.components.BrutalFrame
import com.lyreon.app.ui.components.EventCountdownChip
import com.lyreon.app.ui.components.GenreReelSlider
import com.lyreon.app.ui.components.LyreonPlayButton
import com.lyreon.app.ui.components.SectionRule
import com.lyreon.app.ui.components.TrackRow
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.vm.HomeViewModel
import java.util.Calendar

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    playerState: PlayerUiState,
    onPlayQueue: (List<LyreonTrack>, Int) -> Unit,
    onTogglePlay: () -> Unit,
    onOpenTrack: () -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    onOpenEditorial: (EditorialSection) -> Unit,
    onSearchClick: () -> Unit,
    onOpenBrowse: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val home by vm.state.collectAsStateWithLifecycle()
    val locator = com.lyreon.app.ui.vm.LocalLyreon.current
    val settingsVm: com.lyreon.app.ui.vm.SettingsViewModel =
        com.lyreon.app.ui.vm.lyreonViewModel { com.lyreon.app.ui.vm.SettingsViewModel(it) }
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    var showNameDialog by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LyreonBackground),
    ) {
        val wide = maxWidth >= 600.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "greeting") {
                GreetingBlock(
                    displayName = settings.displayName.ifBlank { "Farizy" },
                    onEditName = { showNameDialog = true },
                )
            }

            // Bar pencarian — langsung loncat ke tab Explore
            item(key = "home_search") {
                HomeSearchBar(onClick = onSearchClick)
            }

            // ALGORITMA SELERA — "Untuk Kamu": lagu yang cocok dengan genre,
            // artis, gaya (speed up/reverb/Nightcore, dst) yang sering diputar,
            // plus chip vibe dari tagar kreator (#sadvibes, …)
            if (home.personaActive) {
                item(key = "persona_rule") {
                    Spacer(Modifier.height(20.dp))
                    SectionRule(label = stringResource(R.string.home_for_you))
                }
                item(key = "persona_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(home.personaTracks, key = { i, t -> "p_${t.videoId}_$i" }) { _, track ->
                            QuickCard(
                                track = track,
                                active = playerState.currentTrack?.videoId == track.videoId,
                                onClick = { onPlayQueue(home.personaTracks, home.personaTracks.indexOf(track)) },
                            )
                        }
                    }
                }
                if (home.personaChips.isNotEmpty()) {
                    item(key = "persona_chips") {
                        PersonaChips(
                            chips = home.personaChips,
                            onChip = { chip ->
                                locator.pendingSearchQuery.value = chip.removePrefix("#")
                                onSearchClick()
                            },
                        )
                    }
                }
            }

            // ARTIS POPULER PER BENUA (dipindah dari Search agar tab Search ringan)
            item(key = "artist_regions") {
                LaunchedEffect(Unit) { vm.ensureArtistSection() }
                ArtistRegionsBlock(
                    region = home.artistRegion,
                    artists = home.artists,
                    loading = home.artistsLoading,
                    onRegion = vm::selectArtistRegion,
                    onArtist = { artist ->
                        // Halaman artis InnerTube bila kanal dikenal; kalau tidak,
                        // jatuh kembali ke pencarian seperti sebelumnya.
                        if (artist.browseId.isNotBlank()) {
                            onOpenBrowse(artist.browseId, artist.name)
                        } else {
                            locator.pendingSearchQuery.value = artist.name
                            onSearchClick()
                        }
                    },
                )
            }

            // ---- TREN PER NEGARA: genre/mood + lagu yang sedang naik ----
            item(key = "trending_block") {
                LaunchedEffect(Unit) { vm.ensureTrendingSection() }
                TrendingCountryBlock(
                    country = home.trendingCountry,
                    genres = home.trendingGenres,
                    tracks = home.trendingTracks,
                    loading = home.trendingLoading,
                    playerState = playerState,
                    likedIds = likedIds,
                    downloadedIds = downloadedIds,
                    onGenre = { item -> onOpenBrowse(item.browseId, item.title) },
                    onPlayTracks = { list, index -> onPlayQueue(list, index) },
                    onLike = onLike,
                    onTrackMore = onTrackMore,
                )
            }

            // Sedang diputar — kartu ringkas (tap → Now Playing)
            if (playerState.currentTrack != null) {
                item(key = "nowplaying") {
                    NowPlayingStrip(
                        track = playerState.currentTrack,
                        isPlaying = playerState.isPlaying,
                        isBuffering = playerState.isBuffering,
                        onToggle = onTogglePlay,
                        onOpen = onOpenTrack,
                    )
                }
            }

            // Baru diputar — riwayat pengguna
            if (home.history.isNotEmpty()) {
                item(key = "history_rule") {
                    Spacer(Modifier.height(20.dp))
                    SectionRule(label = stringResource(R.string.home_history))
                }
                item(key = "history_row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(home.history, key = { _, t -> t.videoId }) { _, track ->
                            QuickCard(
                                track = track,
                                active = playerState.currentTrack?.videoId == track.videoId,
                                onClick = { onPlayQueue(home.history, home.history.indexOf(track)) },
                            )
                        }
                    }
                }
            }

            // Pilihan untukmu — personal sesuai lagu yang sering diputar
            item(key = "picks_rule") {
                Spacer(Modifier.height(24.dp))
                SectionRule(label = stringResource(if (home.personalized) R.string.home_picks_personal else R.string.home_picks_popular))
            }
            item(key = "picks") {
                if (home.loading && home.quickPicks.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = LyreonCrimson, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.home_preparing), style = MaterialTheme.typography.bodySmall, color = LyreonTextMuted)
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(home.quickPicks, key = { _, t -> t.videoId }) { index, track ->
                            QuickCard(
                                track = track,
                                active = playerState.currentTrack?.videoId == track.videoId,
                                onClick = { onPlayQueue(home.quickPicks, index) },
                            )
                        }
                    }
                }
            }

            // Jelajahi suasana — genre
            item(key = "genres_rule") {
                Spacer(Modifier.height(24.dp))
                SectionRule(label = stringResource(R.string.home_genres))
            }
            item(key = "genres") {
                GenreReelSlider(
                    genres = LyreonArchive.genres,
                    selectedId = home.selectedGenreId,
                    onGenreClick = { g ->
                        vm.selectGenre(if (home.selectedGenreId == g.id) null else g)
                    },
                    onPlayGenre = { g ->
                        vm.playGenre(g) { tracks -> onPlayQueue(tracks, 0) }
                    },
                )
            }

            // Trek dari genre terpilih
            val selectedGenre = LyreonArchive.genres.find { it.id == home.selectedGenreId }
            if (selectedGenre != null) {
                item(key = "genre_tracks_rule") {
                    Spacer(Modifier.height(20.dp))
                    SectionRule(label = stringResource(R.string.home_genre_for_you, selectedGenre.name))
                }
                if (home.movementLoading) {
                    item(key = "genre_loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LyreonCrimson, modifier = Modifier.size(32.dp))
                        }
                    }
                } else {
                    itemsIndexed(home.movementTracks.take(8), key = { _, t -> t.videoId }) { index, track ->
                        TrackRow(
                            track = track,
                            isActive = playerState.currentTrack?.videoId == track.videoId,
                            isPlaying = playerState.isPlaying,
                            isLiked = likedIds.contains(track.videoId),
                            isDownloaded = downloadedIds.contains(track.videoId),
                            index = index,
                            onPlay = { onPlayQueue(home.movementTracks.take(8), index) },
                            onLike = { onLike(track) },
                            onMore = { onTrackMore(track) },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            // Featured untukmu — kurasi editorial
            item(key = "featured_rule") {
                Spacer(Modifier.height(24.dp))
                SectionRule(label = stringResource(R.string.home_featured))
            }
            itemsIndexed(LyreonArchive.editorials.take(3), key = { _, e -> e.id }) { _, section ->
                EditorialCard(
                    section = section,
                    wide = wide,
                    onOpen = { onOpenEditorial(section) },
                    onPlay = { vm.playEditorial(section) { tracks -> onPlayQueue(tracks, 0) } },
                )
            }

            item(key = "footer") {
                Spacer(Modifier.height(24.dp))
                FooterBlock()
            }
        }
    }

    if (showNameDialog) {
        NameEditDialog(
            current = settings.displayName.ifBlank { "Farizy" },
            onSave = {
                settingsVm.setDisplayName(it)
                showNameDialog = false
            },
            onDismiss = { showNameDialog = false },
        )
    }
}

/** Sapaan sesuai waktu — ala "Good Evening, Farizy" + hitung mundur event. */
@Composable
private fun GreetingBlock(
    displayName: String,
    onEditName: () -> Unit,
) {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greetRes = when (hour) {
        in 4..10 -> R.string.greeting_morning
        in 11..14 -> R.string.greeting_noon
        in 15..18 -> R.string.greeting_afternoon
        else -> R.string.greeting_evening
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(greetRes), style = MaterialTheme.typography.titleMedium, color = LyreonTextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    displayName,
                    style = MaterialTheme.typography.displaySmall,
                    color = LyreonTextPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.name_edit_title),
                    tint = LyreonCrimson,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onEditName),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.home_quote),
                style = MaterialTheme.typography.bodyMedium,
                color = LyreonTextMuted,
            )
        }
        EventCountdownChip()
    }
}

/** Bar pencarian pill ala iOS — semu (tap membuka tab Explore). */
@Composable
private fun HomeSearchBar(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp)
            .clip(RoundedCornerShape(50))
            .background(LyreonSurface.copy(alpha = 0.7f))
            .border(1.dp, LyreonLine.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = stringResource(R.string.search_kicker),
            tint = LyreonCrimson,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.home_search_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = LyreonTextMuted,
        )
    }
}

/** Dialog ganti nama sapaan — tersimpan permanen di pengaturan. */
@Composable
private fun NameEditDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(current)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyreonElevated,
        title = {
            Text(
                stringResource(R.string.name_edit_title),
                style = MaterialTheme.typography.labelMedium,
                color = LyreonCrimson,
            )
        },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
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
            androidx.compose.material3.TextButton(onClick = { if (name.isNotBlank()) onSave(name.trim()) }) {
                Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
            }
        },
    )
}

/** Strip "sedang diputar" — artwork bulat kecil + kontrol simpel. */
@Composable
private fun NowPlayingStrip(
    track: LyreonTrack?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    if (track == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(LyreonElevated)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            url = track.thumbnailUrl,
            title = track.title,
            size = 52.dp,
            cornerRadius = 12.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                color = LyreonTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isBuffering) stringResource(R.string.home_loading) else track.artist.ifBlank { stringResource(R.string.common_youtube) },
                style = MaterialTheme.typography.bodySmall,
                color = LyreonTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        LyreonPlayButton(isPlaying = isPlaying, onClick = onToggle, size = 44.dp)
    }
}

@Composable
private fun QuickCard(
    track: LyreonTrack,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) LyreonElevated else LyreonSurface)
            .clickable(onClick = onClick)
            .padding(bottom = 12.dp),
    ) {
        Box(Modifier.size(150.dp, 150.dp)) {
            if (track.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(LyreonElevated, LyreonSurface))),
                )
            }
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(CircleShape)
                        .background(LyreonCrimson)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(stringResource(R.string.common_playing), style = MaterialTheme.typography.labelSmall, color = LyreonTextPrimary)
                }
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleSmall,
                color = LyreonTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifBlank { stringResource(R.string.common_youtube) },
                style = MaterialTheme.typography.bodySmall,
                color = LyreonTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun EditorialCard(
    section: EditorialSection,
    wide: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        BrutalFrame(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            Box(modifier = Modifier.fillMaxWidth().height(if (wide) 240.dp else 260.dp)) {
                Image(
                    painter = painterResource(section.backgroundRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(LyreonBackground.copy(alpha = 0.25f), LyreonBackground.copy(alpha = 0.9f)),
                        ),
                    ),
                )

                Column(
                    Modifier.fillMaxSize().padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(section.title, style = MaterialTheme.typography.headlineMedium, color = LyreonTextPrimary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            section.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LyreonTextSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LyreonPlayButton(isPlaying = false, onClick = onPlay, size = 44.dp, showOrbit = false)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.common_play), style = MaterialTheme.typography.titleSmall, color = LyreonTextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun FooterBlock() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(text = "LYREON", style = MaterialTheme.typography.headlineSmall, color = LyreonTextMuted)
        Text(
            text = stringResource(R.string.home_quote),
            style = MaterialTheme.typography.bodySmall,
            color = LyreonTextMuted,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** Chip vibe personality — ketuk = cari konteks itu (#sadvibes → "sadvibes"). */
@Composable
private fun PersonaChips(
    chips: List<String>,
    onChip: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.take(5).forEach { chip ->
            Text(
                text = chip,
                style = MaterialTheme.typography.labelMedium,
                color = LyreonTextSecondary,
                maxLines = 1,
                modifier = Modifier
                    .border(1.dp, LyreonLine, RoundedCornerShape(50))
                    .clickable { onChip(chip) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Kartu artis populer per benua — DIPINDAH dari tab Search ke Home.
 * Pengaman anti force close:
 *  - kunci Lazy unik per indeks (nama ganda dari charts tak pernah tabrakan),
 *  - fetch ditunda sampai seksi ini benar-benar dirender (cache 6 jam),
 *  - daftar kurasi cadangan bila charts wilayah tak tersedia.
 */
@Composable
private fun ArtistRegionsBlock(
    region: com.lyreon.app.yt.YouTubeRepository.ArtistRegion,
    artists: List<com.lyreon.app.yt.YouTubeRepository.ArtistCard>,
    loading: Boolean,
    onRegion: (com.lyreon.app.yt.YouTubeRepository.ArtistRegion) -> Unit,
    onArtist: (com.lyreon.app.yt.YouTubeRepository.ArtistCard) -> Unit,
) {
    val regions = com.lyreon.app.yt.YouTubeRepository.ArtistRegion.values()
    val regionLabels = mapOf(
        com.lyreon.app.yt.YouTubeRepository.ArtistRegion.EUROPE to stringResource(R.string.region_europe),
        com.lyreon.app.yt.YouTubeRepository.ArtistRegion.ASIA to stringResource(R.string.region_asia),
        com.lyreon.app.yt.YouTubeRepository.ArtistRegion.AMERICA to stringResource(R.string.region_america),
        com.lyreon.app.yt.YouTubeRepository.ArtistRegion.AFRICA to stringResource(R.string.region_africa),
        com.lyreon.app.yt.YouTubeRepository.ArtistRegion.ARAB to stringResource(R.string.region_arab),
    )

    Spacer(Modifier.height(22.dp))
    SectionRule(label = stringResource(R.string.search_top_artists))
    Spacer(Modifier.height(12.dp))

    // Pemilih benua
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(regions.toList(), key = { i, r -> "reg_${r.name}_$i" }) { _, r ->
            val selected = r == region
            Text(
                text = regionLabels[r].orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) LyreonCrimson else LyreonTextSecondary,
                modifier = Modifier
                    .border(
                        1.dp,
                        if (selected) LyreonCrimson else LyreonLine,
                        RoundedCornerShape(50),
                    )
                    .background(
                        if (selected) LyreonCrimson.copy(alpha = 0.12f) else LyreonSurface.copy(alpha = 0.25f),
                        RoundedCornerShape(50),
                    )
                    .clickable { onRegion(r) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }

    when {
        loading && artists.isEmpty() -> {
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = LyreonCrimson,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        artists.isNotEmpty() -> {
            Spacer(Modifier.height(14.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(artists, key = { i, a -> "art_${a.name}_$i" }) { _, artist ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(96.dp)
                            .clickable { onArtist(artist) },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(84.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .border(1.dp, LyreonLine, RoundedCornerShape(24.dp))
                                .background(LyreonSurface),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (artist.thumbUrl.isNotBlank()) {
                                AsyncImage(
                                    model = artist.thumbUrl,
                                    contentDescription = artist.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Text(
                                    text = artist.name.take(1).uppercase(),
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = LyreonCrimson,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = LyreonTextPrimary,
                            maxLines = 2,
                            textAlign = TextAlign.Center,
                        )
                        if (artist.subtitle.isNotBlank()) {
                            Text(
                                text = artist.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = LyreonTextMuted,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Blok "Tren di <negara>": rak genre/mood lokal + daftar lagu tren.
 *
 * Dimuat malas (hanya saat blok ini benar-benar dirender) supaya frame pertama
 * Beranda tidak menunggu jaringan — sama seperti blok artis per benua.
 */
@Composable
private fun TrendingCountryBlock(
    country: String,
    genres: List<com.lyreon.app.yt.BrowseItem>,
    tracks: List<LyreonTrack>,
    loading: Boolean,
    playerState: PlayerUiState,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    onGenre: (com.lyreon.app.yt.BrowseItem) -> Unit,
    onPlayTracks: (List<LyreonTrack>, Int) -> Unit,
    onLike: (LyreonTrack) -> Unit,
    onTrackMore: (LyreonTrack) -> Unit,
) {
    if (!loading && country.isBlank() && genres.isEmpty() && tracks.isEmpty()) return

    Spacer(Modifier.height(20.dp))
    SectionRule(
        label = if (country.isBlank()) {
            stringResource(R.string.home_trending)
        } else {
            stringResource(R.string.home_trending_in, country)
        },
    )

    if (genres.isNotEmpty()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(genres, key = { _, g -> g.browseId }) { _, genre ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(LyreonRadius.pill))
                        .background(LyreonSurface.copy(alpha = 0.5f))
                        .border(1.dp, LyreonLine, RoundedCornerShape(LyreonRadius.pill))
                        .clickable { onGenre(genre) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        genre.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = LyreonTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    when {
        loading && tracks.isEmpty() -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(color = LyreonCrimson, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.home_trending_loading),
                style = MaterialTheme.typography.labelSmall,
                color = LyreonTextMuted,
            )
        }

        tracks.isNotEmpty() -> Column {
            tracks.take(10).forEachIndexed { index, track ->
                TrackRow(
                    track = track,
                    isActive = playerState.currentTrack?.videoId == track.videoId,
                    isPlaying = playerState.isPlaying,
                    isLiked = likedIds.contains(track.videoId),
                    isDownloaded = downloadedIds.contains(track.videoId),
                    index = index,
                    onPlay = { onPlayTracks(tracks, index) },
                    onLike = { onLike(track) },
                    onMore = { onTrackMore(track) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
        }
    }
}
