/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.screens

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.using
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.model.formatDuration
import com.lyreon.app.data.model.formatMs
import com.lyreon.app.player.PlayerPosition
import com.lyreon.app.player.PlayerManager
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.Artwork
import com.lyreon.app.ui.components.LyricsContent
import com.lyreon.app.ui.components.LyreonPlayButton
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonRose
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonMotion
import com.lyreon.app.ui.theme.lyreonTween
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.LyreonScrimSheet
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonHairline
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import com.lyreon.app.data.settings.LyreonSettings
import com.lyreon.app.ui.vm.LocalLyreon
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

import com.lyreon.app.ui.theme.rememberDynamicArtworkPalette
import com.lyreon.app.ui.theme.DynamicArtworkPalette
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.DeleteSweep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playerState: PlayerUiState,
    player: PlayerManager,
    isLiked: Boolean,
    isDownloaded: Boolean,
    videoMode: Boolean,
    controller: Player?,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onCycleRepeat: () -> Unit,
    onLike: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onSleepTimer: () -> Unit,
    onShare: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onSetVideoMode: (Boolean) -> Unit,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = playerState.currentTrack
    val dynamicPalette = rememberDynamicArtworkPalette(track?.thumbnailUrl)

    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }

    // Posisi/durasi dari flow khusus (ticker 500ms)
    val pos by player.position.collectAsStateWithLifecycle()

    // Foto kanal uploader lagu berjalan (lazy per lagu; null → monogram huruf)
    val locator = com.lyreon.app.ui.vm.LocalLyreon.current
    val artistAvatarUrl by androidx.compose.runtime.produceState<String?>(
        initialValue = null,
        track?.videoId,
    ) {
        value = null
        val t = track
        if (t != null && !t.isLocal) {
            val byVideo = runCatching { locator.youtube.uploaderAvatar(t.videoId) }.getOrNull()
            value = byVideo?.takeIf { it.isNotBlank() } ?: runCatching {
                locator.youtube.channelProfile(t.artist)?.first
            }.getOrNull()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(LyreonBackground),
    ) {
        val wide = maxWidth >= 700.dp

        // Latar dinamis: gradien adaptif dari palet warna sampul musik
        Box(
            Modifier
                .fillMaxSize()
                .background(dynamicPalette.ambientGradient),
        )

        if (track == null) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.np_empty_title), style = MaterialTheme.typography.titleMedium, color = LyreonTextSecondary)
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.np_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = LyreonTextMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    stringResource(R.string.action_close),
                    style = MaterialTheme.typography.labelMedium,
                    color = dynamicPalette.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, dynamicPalette.accent.copy(alpha = 0.6f), RoundedCornerShape(50))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
            return@BoxWithConstraints
        }

        // ---------------------------------------------------------------
        // Struktur tata letak MELD (ui/player/Player.kt, mode potret):
        //   [bilah atas — SELALU terlihat: tutup · status · lirik · bagikan]
        //   [AREA UTAMA Box(weight 1f) — artwork ⇄ lirik full-bleed: hanya
        //    ISI yang berganti lewat AnimatedContent, UKURAN BOX TIDAK
        //    PERNAH berubah]
        //   [kontrol — SELALU terlihat di bawah]
        //
        // Dulu (bug fatal "thumbnail membesar / terjebak"): seluruh kolom
        // pemutar ditukar dengan halaman lirik lewat Crossfade sehingga kotak
        // thumbnail×lirik BERUBAH UKURAN setiap kali lirik dibuka/ditutup,
        // dan artwork bisa diketuk tanpa sengaja membuka lirik. Meld tidak
        // pernah menukar ukuran: lirik mengganti ISI area utama, kontrol dan
        // tombol kembali tetap diam di tempatnya. Lihat notes/01 §B7.
        // ---------------------------------------------------------------
        if (wide) {
            Column(Modifier.fillMaxSize()) {
                PlayerTopBar(
                    isLocal = track.isLocal,
                    isBuffering = playerState.isBuffering,
                    videoMode = videoMode,
                    showLyrics = showLyrics,
                    accent = dynamicPalette.accent,
                    onBack = onBack,
                    onShare = onShare,
                    onToggleLyrics = { showLyrics = !showLyrics },
                )
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    PlayerMainArea(
                        track = track,
                        isDownloaded = isDownloaded,
                        showLyrics = showLyrics,
                        videoMode = videoMode,
                        controller = controller,
                        positionMs = pos.positionMs,
                        onSeekMs = onSeekMs,
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight()
                            .padding(horizontal = 12.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(0.48f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                    ) {
                        PlayerControls(
                            track = track,
                            wide = wide,
                            isLiked = isLiked,
                            isDownloaded = isDownloaded,
                            showLyrics = showLyrics,
                            videoMode = videoMode,
                            playerState = playerState,
                            pos = pos,
                            accent = dynamicPalette.accent,
                            artistAvatarUrl = artistAvatarUrl,
                            onSetVideoMode = onSetVideoMode,
                            onToggleLyrics = { showLyrics = !showLyrics },
                            onLike = onLike,
                            onSeekFraction = onSeekFraction,
                            onShuffle = onShuffle,
                            onToggle = onToggle,
                            onPrev = onPrev,
                            onNext = onNext,
                            onCycleRepeat = onCycleRepeat,
                            onDownload = onDownload,
                            onAddToPlaylist = onAddToPlaylist,
                            onSleepTimer = onSleepTimer,
                            onOpenQueue = { showQueue = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PlayerTopBar(
                    isLocal = track.isLocal,
                    isBuffering = playerState.isBuffering,
                    videoMode = videoMode,
                    showLyrics = showLyrics,
                    accent = dynamicPalette.accent,
                    onBack = onBack,
                    onShare = onShare,
                    onToggleLyrics = { showLyrics = !showLyrics },
                )
                PlayerMainArea(
                    track = track,
                    isDownloaded = isDownloaded,
                    showLyrics = showLyrics,
                    videoMode = videoMode,
                    controller = controller,
                    positionMs = pos.positionMs,
                    onSeekMs = onSeekMs,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                )
                PlayerControls(
                    track = track,
                    wide = wide,
                    isLiked = isLiked,
                    isDownloaded = isDownloaded,
                    showLyrics = showLyrics,
                    videoMode = videoMode,
                    playerState = playerState,
                    pos = pos,
                    accent = dynamicPalette.accent,
                    artistAvatarUrl = artistAvatarUrl,
                    onSetVideoMode = onSetVideoMode,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    onLike = onLike,
                    onSeekFraction = onSeekFraction,
                    onShuffle = onShuffle,
                    onToggle = onToggle,
                    onPrev = onPrev,
                    onNext = onNext,
                    onCycleRepeat = onCycleRepeat,
                    onDownload = onDownload,
                    onAddToPlaylist = onAddToPlaylist,
                    onSleepTimer = onSleepTimer,
                    onOpenQueue = { showQueue = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // ---- Lembar Antrean & Riwayat Putar (pola Meld Queue.kt) ----
    if (showQueue) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val historyTracks by locator.library.history.collectAsStateWithLifecycle(initialValue = emptyList())
        var queueTab by remember { mutableStateOf(0) } // 0 = Antrean, 1 = Riwayat
        val queueScope = rememberCoroutineScope()
        val queueSnackbar = remember { SnackbarHostState() }

        // ---- Salinan antrean lokal dengan UID stabil (pola Meld: ----
        // `mutableQueueWindows`). Drag menggerakkan salinan lokal secara
        // langsung; pemutar baru dikomit saat jari diangkat. UID dipertahankan
        // untuk item yang sama sehingga animasi drag tidak "loncat" dan kunci
        // LazyColumn tidak pernah tabrakan walau ada lagu kembar.
        var localQueue by remember { mutableStateOf<List<Pair<String, LyreonTrack>>>(emptyList()) }
        var uidCounter by remember { mutableStateOf(0L) }
        LaunchedEffect(playerState.queue) {
            val newQueue = playerState.queue
            val oldById = localQueue.groupBy { it.second.videoId }
            val used = HashSet<String>()
            val next = ArrayList<Pair<String, LyreonTrack>>(newQueue.size)
            newQueue.forEach { t ->
                val uid = oldById[t.videoId].orEmpty().firstOrNull { it.first !in used }?.first
                    ?: "u" + uidCounter++
                used.add(uid)
                next += uid to t
            }
            localQueue = next
        }

        val queueListState = rememberLazyListState()
        var dragFromTo by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        val reorderState = rememberReorderableLazyListState(
            lazyListState = queueListState,
        ) { from, to ->
            val f = from.index.coerceIn(0, localQueue.lastIndex)
            val t = to.index.coerceIn(0, localQueue.lastIndex)
            if (f != t) {
                localQueue = localQueue.toMutableList().apply { add(t, removeAt(f)) }
                dragFromTo = (dragFromTo?.first ?: f) to t
            }
            true
        }
        // Komit urutan baru ke pemutar saat jari diangkat (pola Meld).
        LaunchedEffect(reorderState.isAnyItemDragging) {
            if (!reorderState.isAnyItemDragging) {
                dragFromTo?.let { (from, to) -> onMoveQueueItem(from, to) }
                dragFromTo = null
            }
        }
        // Gulir otomatis ke lagu aktif saat lembar dibuka / lagu berganti.
        LaunchedEffect(showQueue, playerState.currentIndex, queueTab) {
            val idx = playerState.currentIndex
            if (queueTab == 0 && idx in playerState.queue.indices) {
                runCatching { queueListState.animateScrollToItem(idx) }
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
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
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(LyreonSurface),
                )
            },
        ) {
            // Lembar antrean memenuhi sebagian besar layar (pola Meld: antrean
            // adalah lembar tinggi dengan daftar panjang).
            Column(Modifier.fillMaxHeight(0.88f).padding(bottom = 24.dp)) {
                // Header Segmented Tab: Antrean vs Riwayat
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(LyreonSurface.copy(alpha = 0.5f))
                            .padding(3.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (queueTab == 0) dynamicPalette.accent else Color.Transparent)
                                .clickable { queueTab = 0 }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                stringResource(R.string.queue_title, playerState.queue.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (queueTab == 0) Color.White else LyreonTextSecondary,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (queueTab == 1) dynamicPalette.accent else Color.Transparent)
                                .clickable { queueTab = 1 }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                stringResource(R.string.history_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (queueTab == 1) Color.White else LyreonTextSecondary,
                            )
                        }
                    }

                    if (queueTab == 0 && playerState.queue.size > 1) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Acak antrean (pola Meld: aksi acak tersedia langsung
                            // di lembar antrean, bukan hanya di kontrol pemutar).
                            IconButton(
                                onClick = { onShuffle(!playerState.shuffleEnabled) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Shuffle,
                                    contentDescription = stringResource(R.string.common_shuffle),
                                    tint = if (playerState.shuffleEnabled) dynamicPalette.accent else LyreonTextSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Text(
                                stringResource(R.string.history_clear),
                                style = MaterialTheme.typography.labelSmall,
                                color = dynamicPalette.accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        // Bersihkan antrean selain lagu yang sedang diputar
                                        val currentIdx = playerState.currentIndex
                                        for (i in playerState.queue.indices.reversed()) {
                                            if (i != currentIdx) onRemoveQueueItem(i)
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }

                if (queueTab == 0) {

                    // Radio: bukan lagi sekadar badge — tombol nyata yang memulai
                    // radio dari lagu berjalan (pola Metrolist: antrean langsung
                    // terisi lagu terkait, bukan menunggu lagu habis).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Radio,
                            contentDescription = null,
                            tint = dynamicPalette.accent,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.radio_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = LyreonTextMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, dynamicPalette.accent, RoundedCornerShape(50))
                                .background(dynamicPalette.accent.copy(alpha = 0.16f), RoundedCornerShape(50))
                                .clickable { track?.let(player::startRadio) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(
                                stringResource(R.string.radio_start),
                                style = MaterialTheme.typography.labelMedium,
                                color = dynamicPalette.accent,
                            )
                        }
                    }

                    // ---- Daftar antrean: drag-reorder + geser-hapus + urungkan ----
                    // (pola Meld Queue.kt: ReorderableItem + draggableHandle,
                    // SwipeToDismissBox dua arah, snackbar URUNGKAN)
                    LazyColumn(
                        state = queueListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        itemsIndexed(localQueue, key = { _, entry -> entry.first }) { index, entry ->
                            val uid = entry.first
                            val item = entry.second
                            val active = index == playerState.currentIndex
                            ReorderableItem(
                                state = reorderState,
                                key = uid,
                            ) {
                                val dismissState = rememberSwipeToDismissBoxState(
                                    positionalThreshold = { totalDistance -> totalDistance },
                                )
                                var processedDismiss by remember { mutableStateOf(false) }
                                val removedMsg = stringResource(R.string.queue_removed_fmt, item.title)
                                val undoStr = stringResource(R.string.queue_undo)
                                LaunchedEffect(dismissState.currentValue) {
                                    val dv = dismissState.currentValue
                                    if (!processedDismiss && (
                                            dv == SwipeToDismissBoxValue.StartToEnd ||
                                                dv == SwipeToDismissBoxValue.EndToStart
                                            )
                                        ) {
                                        processedDismiss = true
                                        onRemoveQueueItem(index)
                                        queueScope.launch {
                                            val result = queueSnackbar.showSnackbar(
                                                message = removedMsg,
                                                actionLabel = undoStr,
                                                duration = SnackbarDuration.Short,
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                player.insertAt(index, item)
                                            }
                                        }
                                    }
                                    if (dv == SwipeToDismissBoxValue.Settled) processedDismiss = false
                                }
                                SwipeToDismissBox(
                                    state = dismissState,
                                    backgroundContent = {
                                        Box(
                                            Modifier
                                                .fillMaxSize()
                                                .padding(vertical = 2.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .background(LyreonRose.copy(alpha = 0.85f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = stringResource(R.string.queue_remove),
                                                tint = LyreonBackground,
                                            )
                                        }
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (active) LyreonSurface.copy(alpha = 0.5f) else LyreonElevated)
                                            .clickable {
                                                // Ketuk lagu aktif = jeda/main; lainnya = putar (pola Meld)
                                                if (active) onToggle() else onPlayAt(index)
                                            }
                                            .padding(horizontal = 20.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Artwork(
                                            url = item.thumbnailUrl,
                                            title = item.title,
                                            size = 44.dp,
                                            cornerRadius = 8.dp,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                item.title,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = if (active) dynamicPalette.accent else LyreonTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                item.artist.ifBlank { stringResource(R.string.common_youtube) } +
                                                    "  \u00b7  " + formatDuration(item.durationSec),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = LyreonTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (active) {
                                            Icon(
                                                Icons.Filled.GraphicEq,
                                                contentDescription = stringResource(R.string.common_playing),
                                                tint = dynamicPalette.accent,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                        // Pegangan seret (pola Meld: drag_handle + draggableHandle)
                                        IconButton(
                                            onClick = { },
                                            modifier = Modifier.draggableHandle(),
                                        ) {
                                            Icon(
                                                Icons.Filled.DragHandle,
                                                contentDescription = stringResource(R.string.queue_drag_handle),
                                                tint = LyreonTextMuted,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Tab Riwayat Putar
                    if (historyTracks.isNotEmpty()) {
                        // Riwayat → antrean: satu ketukan mengisi antrean dengan
                        // lagu yang baru diputar (jalur otomatisnya di
                        // PlayerManager.maybeExtendQueue saat radio kehabisan lagu).
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.history_queue_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = LyreonTextMuted,
                                modifier = Modifier.weight(1f),
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .border(1.dp, dynamicPalette.accent, RoundedCornerShape(50))
                                    .background(dynamicPalette.accent.copy(alpha = 0.16f), RoundedCornerShape(50))
                                    .clickable { player.enqueueHistory() }
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    stringResource(R.string.history_queue_action),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = dynamicPalette.accent,
                                )
                            }
                        }
                    }
                    if (historyTracks.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.history_empty_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = LyreonTextMuted,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(bottom = 16.dp),
                        ) {
                            itemsIndexed(historyTracks, key = { i, hTrack -> "${hTrack.videoId}_$i" }) { index, hTrack ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            player.playQueue(historyTracks, index)
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Artwork(url = hTrack.thumbnailUrl, title = hTrack.title, size = 42.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            hTrack.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = LyreonTextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            hTrack.artist.ifBlank { stringResource(R.string.common_youtube) },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LyreonTextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = { player.addToQueue(hTrack) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.PlaylistAdd,
                                            contentDescription = stringResource(R.string.action_add_to_queue),
                                            tint = dynamicPalette.accent,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // Snackbar "URUNGKAN" hapus antrean (pola Meld)
                SnackbarHost(queueSnackbar)
            }
        }
    }
}

/** Pill ala segmented control iOS. */
@Composable
private fun ModePill(
    label: String,
    selected: Boolean,
    accentColor: Color = LyreonCrimson,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accentColor else LyreonSurface.copy(alpha = 0f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) LyreonTextPrimary else LyreonTextSecondary,
        )
    }
}

@Composable
private fun SecondaryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    accentColor: Color = LyreonCrimson,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) accentColor else LyreonTextPrimary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) accentColor else LyreonTextSecondary,
        )
    }
}

/**
 * Bilah atas pemutar — SELALU terlihat di kedua mode (artwork maupun lirik),
 * pola chrome Meld Player.kt. Inilah jaminan "tidak pernah terjebak": tombol
 * tutup tidak pernah hilang digantikan apa pun. Tombol LIRIK ada di sini
 * (bukan gestur tersembunyi pada artwork).
 */
@Composable
private fun PlayerTopBar(
    isLocal: Boolean,
    isBuffering: Boolean,
    videoMode: Boolean,
    showLyrics: Boolean,
    accent: Color,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onToggleLyrics: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.action_close),
                tint = LyreonTextPrimary,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.np_kicker),
                style = MaterialTheme.typography.labelMedium,
                color = LyreonTextSecondary,
            )
            Text(
                when {
                    isBuffering -> stringResource(R.string.np_loading_stream)
                    isLocal -> stringResource(R.string.np_local_file)
                    videoMode -> stringResource(R.string.np_video)
                    else -> stringResource(R.string.np_audio_only)
                },
                style = MaterialTheme.typography.labelSmall,
                color = LyreonTextMuted,
            )
        }
        Row {
            if (!isLocal) {
                IconButton(onClick = onToggleLyrics) {
                    Icon(
                        Icons.Filled.Lyrics,
                        contentDescription = stringResource(R.string.np_lyrics),
                        tint = if (showLyrics) accent else LyreonTextPrimary,
                    )
                }
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(R.string.np_share),
                        tint = LyreonTextPrimary,
                    )
                }
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
    }
}

/**
 * AREA UTAMA pemutar — pola Meld Player.kt (Box weight(1f) + AnimatedContent
 * `showInlineLyrics`): artwork dan lirik berbagi AREA YANG SAMA; hanya ISI
 * yang berganti (fade). Ukuran area — dan dengan itu ukuran "kotak
 * thumbnail" — tidak pernah berubah, sehingga mustahil thumbnail "membesar"
 * atau menelan layar. Artwork TIDAK lagi diketuk untuk membuka lirik.
 */
@Composable
private fun PlayerMainArea(
    track: LyreonTrack,
    isDownloaded: Boolean,
    showLyrics: Boolean,
    videoMode: Boolean,
    controller: Player?,
    positionMs: Long,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (videoMode && controller != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(LyreonSurface),
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = true
                            keepScreenOn = true
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    update = { view -> view.player = controller },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            AnimatedContent(
                targetState = showLyrics,
                // tween mentah: transitionSpec berjalan di luar konteks
                // komposisi (lyreonTween adalah @Composable — CI 34174868041).
                //
                // .using(null) MEMATIKAN SizeTransform bawaan AnimatedContent.
                // Tanpa ini, AnimatedContent SELALU membawa SizeTransform
                // default (spring) walau tidak diminta — dan default itu akan
                // menganimasikan ukurannya sendiri bila kedua cabang (artwork/
                // lirik) sampai punya intrinsic size berbeda, persis pola bug
                // "kotak membesar" di notes/01 §B7. Kedua cabang di bawah SUDAH
                // fillMaxSize (ukuran seharusnya identik), tapi itu asumsi yang
                // gampang rusak oleh perubahan berikutnya — .using(null) membuat
                // "ukuran box tidak pernah berubah" jadi INVARIAN yang dipaksa
                // API, bukan cuma kebetulan dari modifier yang kebetulan sama.
                transitionSpec = {
                    fadeIn(tween(LyreonMotion.deliberate))
                        .togetherWith(fadeOut(tween(LyreonMotion.deliberate)))
                        .using(null)
                },
                label = "np_main_area",
            ) { lyrics ->
                if (lyrics) {
                    LyricsMainArea(
                        track = track,
                        positionMs = positionMs,
                        onSeekMs = onSeekMs,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ArtworkCanvas(
                            track = track,
                            isDownloaded = isDownloaded,
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 560.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Blok kontrol pemutar (segmented mode · meta · seek · kontrol utama · aksi)
 * — SELALU terlihat, termasuk saat lirik terbuka (pola Meld: hanya area
 * utama yang berganti isi; kontrol tetap di bawahnya).
 */
@Composable
private fun PlayerControls(
    track: LyreonTrack,
    wide: Boolean,
    isLiked: Boolean,
    isDownloaded: Boolean,
    showLyrics: Boolean,
    videoMode: Boolean,
    playerState: PlayerUiState,
    pos: PlayerPosition,
    accent: Color,
    artistAvatarUrl: String?,
    onSetVideoMode: (Boolean) -> Unit,
    onToggleLyrics: () -> Unit,
    onLike: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onShuffle: (Boolean) -> Unit,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onSleepTimer: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var showPlaybackParams by remember { mutableStateOf(false) }
    val progress = if (pos.durationMs > 0) {
        pos.positionMs.toFloat() / pos.durationMs.toFloat()
    } else 0f

    Column(modifier) {
        // Segmented MUSIK | VIDEO ala iOS
        if (!track.isLocal) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(50))
                    .background(LyreonSurface.copy(alpha = 0.6f))
                    .border(1.dp, LyreonLine.copy(alpha = 0.3f), RoundedCornerShape(50))
                    .padding(4.dp),
            ) {
                ModePill(
                    label = stringResource(R.string.np_music),
                    selected = !videoMode,
                    accentColor = accent,
                    onClick = { onSetVideoMode(false) },
                )
                Spacer(Modifier.width(4.dp))
                ModePill(
                    label = stringResource(R.string.np_video),
                    selected = videoMode,
                    accentColor = accent,
                    onClick = { onSetVideoMode(true) },
                )
            }
        }

        Spacer(Modifier.height(if (wide) 16.dp else 8.dp))

        // Meta: judul + artis di kiri, hati di kanan
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = LyreonTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                val artistName = track.artist.ifBlank { stringResource(R.string.common_youtube_music) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtistAvatar(name = artistName, url = artistAvatarUrl)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        artistName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LyreonTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onLike) {
                Icon(
                    if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(if (isLiked) R.string.np_like_remove else R.string.np_like_add),
                    tint = if (isLiked) accent else LyreonTextSecondary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            stringResource(R.string.np_audio_queue, playerState.currentIndex + 1, playerState.queue.size),
            style = MaterialTheme.typography.labelSmall,
            color = LyreonTextMuted,
        )

        Spacer(Modifier.height(12.dp))

        // Seek
        Slider(
            value = if (dragging) sliderValue else progress.coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                sliderValue = it
            },
            onValueChangeFinished = {
                onSeekFraction(sliderValue)
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = LyreonTextPrimary,
                activeTrackColor = accent,
                inactiveTrackColor = LyreonSurface.copy(alpha = 0.5f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatMs(if (dragging) (sliderValue * pos.durationMs).toLong() else pos.positionMs),
                style = MaterialTheme.typography.labelSmall,
                color = LyreonTextSecondary,
            )
            Text(formatMs(pos.durationMs), style = MaterialTheme.typography.labelSmall, color = LyreonTextSecondary)
        }

        Spacer(Modifier.height(10.dp))

        // Kontrol utama
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { onShuffle(!playerState.shuffleEnabled) }) {
                Icon(
                    Icons.Filled.Shuffle,
                    contentDescription = stringResource(R.string.common_shuffle),
                    tint = if (playerState.shuffleEnabled) accent else LyreonTextSecondary,
                )
            }
            IconButton(onClick = onPrev, modifier = Modifier.size(52.dp)) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.np_previous),
                    tint = LyreonTextPrimary,
                    modifier = Modifier.size(38.dp),
                )
            }
            LyreonPlayButton(
                isPlaying = playerState.isPlaying,
                onClick = onToggle,
                size = 84.dp,
            )
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.np_next),
                    tint = LyreonTextPrimary,
                    modifier = Modifier.size(38.dp),
                )
            }
            IconButton(onClick = onCycleRepeat) {
                Icon(
                    when (playerState.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                        else -> Icons.Filled.Repeat
                    },
                    contentDescription = stringResource(R.string.np_repeat),
                    tint = if (playerState.repeatMode != Player.REPEAT_MODE_OFF) accent else LyreonTextSecondary,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        // Aksi sekunder: lirik · unduh · playlist · timer · antrean · kecepatan
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryAction(
                icon = Icons.Filled.Lyrics,
                label = stringResource(R.string.np_lyrics),
                active = showLyrics,
                accentColor = accent,
                onClick = onToggleLyrics,
            )
            if (!track.isLocal) {
                SecondaryAction(
                    icon = Icons.Filled.Download,
                    label = stringResource(R.string.np_download),
                    active = isDownloaded,
                    accentColor = accent,
                    onClick = onDownload,
                )
            }
            SecondaryAction(
                icon = Icons.Filled.PlaylistAdd,
                label = stringResource(R.string.common_playlist_caps),
                active = false,
                accentColor = accent,
                onClick = onAddToPlaylist,
            )
            SecondaryAction(
                icon = Icons.Filled.Bedtime,
                label = if (playerState.sleepDeadlineMs != null) {
                    val mins = ((playerState.sleepDeadlineMs - System.currentTimeMillis()) / 60000L).coerceAtLeast(0)
                    stringResource(R.string.np_timer_left, mins)
                } else stringResource(R.string.np_timer),
                active = playerState.sleepDeadlineMs != null,
                accentColor = accent,
                onClick = onSleepTimer,
            )
            SecondaryAction(
                icon = Icons.Filled.QueueMusic,
                label = stringResource(R.string.np_queue),
                active = false,
                accentColor = accent,
                onClick = onOpenQueue,
            )
            val audioPrefs = rememberPlaybackPrefs()
            val audioTweaked = audioPrefs.playbackSpeed != 1f || audioPrefs.pitchSemitones != 0
            SecondaryAction(
                icon = Icons.Filled.Speed,
                label = if (audioTweaked) {
                    stringResource(R.string.np_speed_active_fmt, audioPrefs.playbackSpeed)
                } else {
                    stringResource(R.string.np_speed)
                },
                active = audioTweaked,
                accentColor = accent,
                onClick = { showPlaybackParams = true },
            )
        }

        if (showPlaybackParams) {
            PlaybackParamsDialog(onDismiss = { showPlaybackParams = false })
        }
    }
}

/**
 * Kanvas sampul: bingkai persegi sedikit memanjang (~1:1,06), gambar MENGISI
 * penuh areanya (crop tengah), kualitas via rantai varian ytimg. Tidak lagi
 * diketuk untuk membuka lirik — pembuka lirik adalah tombol eksplisit.
 */
@Composable
private fun ArtworkCanvas(
    track: LyreonTrack,
    isDownloaded: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            // Persegi nyaris 1:1 — dipasang SETELAH fillMaxSize dari pemanggil
            // sehingga otomatis menyesuaikan area yang tersedia (area utama
            // bisa memendek di layar kecil) tanpa pernah meluber.
            .aspectRatio(0.94f)
            .clip(RoundedCornerShape(20.dp))
            .background(LyreonSurface),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (track.thumbnailUrl.isNotBlank()) {
                RichArtwork(
                    url = track.thumbnailUrl,
                    contentDescription = track.title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(LyreonSurface))
            }
            if (isDownloaded) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(LyreonRose)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.common_offline), style = MaterialTheme.typography.labelSmall, color = LyreonBackground)
                }
            }
        }
    }
}

/**
 * LIRIK FULL-BLEED di area utama (bukan lagi halaman terpisah, bukan kotak):
 * artwork yang sama jadi latar (blur + skrim gradasi), lirik mengisi seluruh
 * area di atas kontrol — inilah "lirik full page" ala Meld (`InlineLyricsView`
 * + `LyricsBackgroundStyle`). Kontrol dan bilah atas tetap terlihat.
 */
@Composable
private fun LyricsMainArea(
    track: LyreonTrack,
    positionMs: Long,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (track.thumbnailUrl.isNotBlank()) {
            RichArtwork(
                url = track.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            Modifier.blur(40.dp)
                        } else {
                            Modifier.alpha(0.18f)
                        },
                    ),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.55f),
                        LyreonBackground.copy(alpha = 0.82f),
                        Color.Black.copy(alpha = 0.78f),
                    ),
                ),
            ),
        )
        LyricsContent(
            track = track,
            positionMs = positionMs,
            onSeekMs = onSeekMs,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Gambar artwork dengan rantai kualitas ytimg (maxres 1280×720 → sd 640×480 →
 * hq 480×360) — maxres tidak tersedia di semua video, jadi turun otomatis saat
 * 404. Non-ytimg (file lokal, dsb) ditampilkan apa adanya. Selalu ContentScale.Crop.
 */
@Composable
private fun RichArtwork(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val candidates = remember(url) { ytimgCandidates(url) }
    var index by remember(url) { androidx.compose.runtime.mutableIntStateOf(0) }
    AsyncImage(
        model = candidates[index.coerceAtMost(candidates.lastIndex)],
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        onError = {
            // Varian tak tersedia (404) → turun ke kualitas berikutnya
            if (index < candidates.lastIndex) index++
        },
        modifier = modifier,
    )
}

/** Rantai URL varian ytimg terbesar → terkecil untuk videoId dari URL gambar. */
private fun ytimgCandidates(url: String): List<String> =
    if (url.contains("i.ytimg.com") && url.contains("/vi/")) {
        val vid = url.substringAfter("/vi/").substringBefore("/")
        listOf(
            "https://i.ytimg.com/vi/$vid/maxresdefault.jpg",
            "https://i.ytimg.com/vi/$vid/sddefault.jpg",
            "https://i.ytimg.com/vi/$vid/hqdefault.jpg",
            url, // jaga-jaga: sumber asli (mungkin varian lain)
        )
    } else {
        listOf(url)
    }

/** Foto kanal artis (bulat) — fallback monogram huruf pertama. */
@Composable
private fun ArtistAvatar(
    name: String,
    url: String?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(LyreonSurface),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = LyreonCrimson,
            )
        }
    }
}

/**
 * Preferensi audio (kecepatan & nada) dibaca langsung dari Pengaturan — sumber
 * kebenaran tunggalnya di sana; PlaybackService yang menerapkannya ke ExoPlayer.
 */
@Composable
private fun rememberPlaybackPrefs(): LyreonSettings {
    val locator = LocalLyreon.current
    val settings by locator.settings.settings
        .collectAsStateWithLifecycle(initialValue = LyreonSettings())
    return settings
}

/**
 * Dialog kecepatan putar & geser nada. Nilai disimpan saat gestur selesai
 * (bukan tiap langkah geser) supaya DataStore tidak dibanjiri tulisan.
 */
@Composable
private fun PlaybackParamsDialog(onDismiss: () -> Unit) {
    val locator = LocalLyreon.current
    val scope = rememberCoroutineScope()
    val settings = rememberPlaybackPrefs()

    var speedDrag by remember { mutableStateOf<Float?>(null) }
    var pitchDrag by remember { mutableStateOf<Float?>(null) }
    val speed = speedDrag ?: settings.playbackSpeed
    val pitch = pitchDrag ?: settings.pitchSemitones.toFloat()
    val tweaked = settings.playbackSpeed != 1f || settings.pitchSemitones != 0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyreonElevated,
        title = {
            Text(
                stringResource(R.string.speed_dialog_title),
                style = MaterialTheme.typography.titleMedium,
                color = LyreonTextPrimary,
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.speed_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = LyreonTextSecondary,
                )
                Text(
                    stringResource(R.string.speed_value_fmt, speed),
                    style = MaterialTheme.typography.titleMedium,
                    color = LyreonCrimson,
                )
                Slider(
                    value = speed,
                    onValueChange = { speedDrag = it },
                    onValueChangeFinished = {
                        speedDrag?.let { v -> scope.launch { locator.settings.setPlaybackSpeed(v) } }
                        speedDrag = null
                    },
                    valueRange = 0.5f..2f,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.pitch_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = LyreonTextSecondary,
                )
                Text(
                    stringResource(R.string.pitch_value_fmt, pitch.roundToInt()),
                    style = MaterialTheme.typography.titleMedium,
                    color = LyreonCrimson,
                )
                Slider(
                    value = pitch,
                    onValueChange = { pitchDrag = it },
                    onValueChangeFinished = {
                        pitchDrag?.let { v ->
                            scope.launch { locator.settings.setPitchSemitones(v.roundToInt()) }
                        }
                        pitchDrag = null
                    },
                    valueRange = -12f..12f,
                    steps = 23,
                )
                if (tweaked) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                locator.settings.setPlaybackSpeed(1f)
                                locator.settings.setPitchSemitones(0)
                            }
                        },
                    ) {
                        Text(
                            stringResource(R.string.speed_reset),
                            style = MaterialTheme.typography.labelLarge,
                            color = LyreonCrimson,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.common_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = LyreonTextSecondary,
                )
            }
        },
    )
}
