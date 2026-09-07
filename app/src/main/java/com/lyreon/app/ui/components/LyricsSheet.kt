/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Perilaku lirik hidup (auto-scroll ke tengah, jeda saat pengguna menggulir,
 * ketuk baris untuk seek, konstanta durasi scroll) diadaptasi dari
 * FrancescoGrazioso/Meld (GPL-3.0):
 *   app/src/main/kotlin/com/metrolist/music/ui/component/OriginalLyrics.kt
 *   app/src/main/kotlin/com/metrolist/music/lyrics/LyricsUtils.kt (findCurrentLineIndex)
 * Metrolist Project (C) 2026 — lihat riwayat git upstream untuk kontributor.
 *
 * Penyesuaian Lyreon: tanpa terjemahan AI/romanisasi/ekspor gambar, posisi dibaca
 * dari PlayerManager.positionNow() tiap 50 ms, semua durasi animasi lewat helper
 * tema (menghormati reduce motion), dan ada koreksi offset lirik global.
 */
package com.lyreon.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.settings.LyreonSettings
import com.lyreon.app.lyrics.LrcLine
import com.lyreon.app.lyrics.LyricsResult
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonMotion
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.LyreonScrimSheet
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.lyreonSpring
import com.lyreon.app.ui.theme.lyreonTween
import com.lyreon.app.ui.theme.reduceMotionEnabled
import com.lyreon.app.ui.utils.fadingEdge
import com.lyreon.app.ui.vm.LocalLyreon
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

// Konstanta gerak mengikuti Metrolist/Meld: auto-scroll lambat terasa "mengalir",
// bukan melompat per baris. Semua nilai ini dilewatkan ke helper tema sehingga
// reduce motion meniadakannya.
private const val LYRICS_POLL_MS = 50L
private const val ACTIVE_LEAD_MS = 120L
private const val AUTO_SCROLL_MS = 1500
private const val INITIAL_SCROLL_MS = 800
private const val SEEK_SCROLL_MS = 600
private const val MANUAL_RESUME_MS = 2000L
private const val CENTER_TOLERANCE_PX = 10
private const val OFFSET_STEP_MS = 500
private const val OFFSET_LIMIT_MS = 10_000

internal sealed interface LyricsUi {
    data object Loading : LyricsUi
    data class Ready(val result: LyricsResult) : LyricsUi
    data object Failed : LyricsUi
}

/**
 * Konten lirik mandiri (loading → sinkron/polos) — dipakai lembar bawah dan
 * mode lirik-di-sampul Now Playing. Latar belakang diserahkan ke pemanggil.
 */
@Composable
fun LyricsContent(
    track: LyreonTrack,
    positionMs: Long,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locator = LocalLyreon.current
    val scope = rememberCoroutineScope()
    val settings by locator.settings.settings
        .collectAsStateWithLifecycle(initialValue = LyreonSettings())
    val offsetMs = settings.lyricsOffsetMs

    val ui by produceState<LyricsUi>(initialValue = LyricsUi.Loading, track.videoId) {
        value = LyricsUi.Loading
        value = runCatching { locator.lyrics.lyrics(track) }
            .fold(
                onSuccess = { LyricsUi.Ready(it) },
                onFailure = { LyricsUi.Failed },
            )
    }

    Box(modifier) {
        when (val state = ui) {
            LyricsUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LyreonCrimson)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.lyrics_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextMuted,
                    )
                }
            }

            LyricsUi.Failed -> LyricsMessage(stringResource(R.string.lyrics_error))

            is LyricsUi.Ready -> {
                val result = state.result
                Column(Modifier.fillMaxSize()) {
                    when {
                        result.hasSynced -> SyncedLyrics(
                            result = result,
                            positionMs = positionMs,
                            offsetMs = offsetMs,
                            onSeekMs = onSeekMs,
                            modifier = Modifier.fillMaxWidth().weight(1f),
                        )
                        result.instrumental -> LyricsMessage(
                            stringResource(R.string.lyrics_instrumental),
                            Modifier.weight(1f),
                        )
                        !result.plain.isNullOrBlank() -> PlainLyrics(
                            result.plain,
                            Modifier.weight(1f),
                        )
                        else -> LyricsMessage(
                            stringResource(R.string.lyrics_none),
                            Modifier.weight(1f),
                        )
                    }
                    LyricsFooter(
                        source = result.source,
                        offsetMs = offsetMs,
                        showOffsetControls = result.hasSynced,
                        onNudge = { delta ->
                            scope.launch {
                                locator.settings.setLyricsOffsetMs(
                                    (offsetMs + delta).coerceIn(-OFFSET_LIMIT_MS, OFFSET_LIMIT_MS),
                                )
                            }
                        },
                        onReset = { scope.launch { locator.settings.setLyricsOffsetMs(0) } },
                    )
                }
            }
        }
    }
}

/**
 * Lirik sinkron — baris aktif menyala dan selalu dipusatkan dengan guliran
 * lambat ala Metrolist. Menggulir sendiri menjeda auto-scroll selama 2 detik,
 * lalu lirik kembali mengikuti lagu. Ketuk baris = seek ke waktu baris itu.
 */
@Composable
private fun SyncedLyrics(
    result: LyricsResult,
    positionMs: Long,
    offsetMs: Int,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locator = LocalLyreon.current
    val player = remember { locator.player }
    val lines = remember(result) { result.synced }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reduced = reduceMotionEnabled

    // Posisi dibaca langsung dari pemutar (ticker UI hanya 500 ms — terlalu kasar
    // untuk lirik). Loop ini tidak merecompose apa pun; hanya `activeIndex` yang
    // berubah lewat derivedStateOf saat baris berganti.
    var clockMs by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(lines) {
        while (isActive) {
            clockMs = player.positionNow()
            delay(LYRICS_POLL_MS)
        }
    }

    // PENTING: nilai offset/posisi adalah state — derivedStateOf hanya re-aktif
    // pada pembacaan snapshot state, jadi keduanya harus lewat rememberUpdatedState.
    val nowMs by rememberUpdatedState(clockMs + offsetMs)
    val activeIndex by remember(lines) {
        derivedStateOf { findCurrentLineIndex(lines, nowMs) }
    }

    var autoScroll by remember { mutableStateOf(true) }
    var lastManualScrollMs by remember { mutableLongStateOf(0L) }
    var initialCentered by remember { mutableStateOf(false) }

    LaunchedEffect(lines) {
        autoScroll = true
        lastManualScrollMs = 0L
        initialCentered = false
    }

    // Lanjutkan auto-scroll 2 detik setelah pengguna berhenti menggulir.
    LaunchedEffect(lastManualScrollMs) {
        if (lastManualScrollMs != 0L) {
            delay(MANUAL_RESUME_MS)
            lastManualScrollMs = 0L
            autoScroll = true
        }
    }

    /** Pusatkan baris [index] di tengah viewport dengan guliran halus. */
    suspend fun centerOn(index: Int, durationMs: Int) {
        val layoutInfo = listState.layoutInfo
        val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        if (item != null) {
            val viewport = layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset
            val center = layoutInfo.viewportStartOffset + viewport / 2
            val delta = (item.offset + item.size / 2) - center
            if (abs(delta) > CENTER_TOLERANCE_PX) {
                // tween mentah di sini (bukan lyreonTween) karena kita berada di
                // dalam fungsi suspend lokal — pemanggil @Composable tidak boleh
                // dipanggil dari sini. Reduce motion tetap dihormati: pemanggil
                // meneruskan durationMs = 0.
                listState.animateScrollBy(delta.toFloat(), tween(durationMs))
            }
        } else {
            // Belum terlihat: lompat dulu, siklus berikutnya yang menghaluskan.
            listState.scrollToItem(index)
        }
    }

    LaunchedEffect(activeIndex, autoScroll) {
        if (!autoScroll || activeIndex < 0) return@LaunchedEffect
        val duration = if (reduced) 0 else if (!initialCentered) INITIAL_SCROLL_MS else AUTO_SCROLL_MS
        centerOn(activeIndex, duration)
        initialCentered = true
    }

    // Guliran jari pengguna menjeda auto-scroll; guliran program (SideEffect) tidak.
    val nestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    autoScroll = false
                    lastManualScrollMs = System.currentTimeMillis()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                autoScroll = false
                lastManualScrollMs = System.currentTimeMillis()
                return Velocity.Zero
            }
        }
    }

    BoxWithConstraints(modifier) {
        // Ruang atas/bawah besar supaya baris pertama & terakhir bisa ke tengah.
        val topPad = maxHeight / 3
        val bottomPad = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .fadingEdge(vertical = 64.dp)
                .nestedScroll(nestedScroll),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = topPad, bottom = bottomPad),
        ) {
            itemsIndexed(lines, key = { i, l -> "${l.timeMs}_$i" }) { index, line ->
                LyricsLineView(
                    line = line,
                    active = index == activeIndex,
                    passed = activeIndex >= 0 && index < activeIndex,
                    onClick = {
                        onSeekMs((line.timeMs - offsetMs).coerceAtLeast(0L))
                        // Ketuk baris = niat eksplisit "ikuti pemutaran".
                        autoScroll = true
                        lastManualScrollMs = 0L
                        scope.launch {
                            listState.scrollToItem(index)
                            centerOn(index, if (reduced) 0 else SEEK_SCROLL_MS)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Satu baris lirik: ukuran huruf tetap (tidak ada reflow), yang dianimasikan
 * adalah alpha + skala + warna — baris aktif membesar halus dan menyala aksen.
 */
@Composable
private fun LyricsLineView(
    line: LrcLine,
    active: Boolean,
    passed: Boolean,
    onClick: () -> Unit,
) {
    val targetAlpha = when {
        active -> 1f
        passed -> 0.32f
        else -> 0.62f
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = lyreonTween(LyreonMotion.slow),
        label = "lyrics_alpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (active) 1f else 0.94f,
        animationSpec = lyreonSpring(LyreonMotion.dampingSoft, LyreonMotion.stiffnessBrisk),
        label = "lyrics_scale",
    )
    val color = when {
        active -> LyreonCrimson
        passed -> LyreonTextMuted
        else -> LyreonTextSecondary
    }

    Text(
        text = line.text,
        style = MaterialTheme.typography.titleMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .clip(RoundedCornerShape(LyreonRadius.sm))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}

/** Baris kaki: asal lirik + koreksi sinkronisasi (±0,5 detik). */
@Composable
private fun LyricsFooter(
    source: String?,
    offsetMs: Int,
    showOffsetControls: Boolean,
    onNudge: (Int) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (source.isNullOrBlank()) {
                stringResource(R.string.lyrics_source)
            } else {
                stringResource(R.string.lyrics_source_fmt, source)
            },
            style = MaterialTheme.typography.labelSmall,
            color = LyreonTextMuted,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (showOffsetControls) {
            IconButton(
                onClick = { onNudge(-OFFSET_STEP_MS) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Remove,
                    contentDescription = stringResource(R.string.lyrics_offset_minus),
                    tint = LyreonTextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = stringResource(R.string.lyrics_offset_value, offsetMs / 1000f),
                style = MaterialTheme.typography.labelSmall,
                color = if (offsetMs == 0) LyreonTextMuted else LyreonTextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(56.dp),
            )
            IconButton(
                onClick = { onNudge(OFFSET_STEP_MS) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.lyrics_offset_plus),
                    tint = LyreonTextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (offsetMs != 0) {
                TextButton(onClick = onReset) {
                    Text(
                        stringResource(R.string.lyrics_offset_reset),
                        style = MaterialTheme.typography.labelSmall,
                        color = LyreonCrimson,
                    )
                }
            }
        }
    }
}

/**
 * Baris terakhir yang waktunya sudah lewat pada [positionMs] (dengan sedikit
 * lead agar teks menyala tepat saat vokal mulai).
 */
private fun findCurrentLineIndex(lines: List<LrcLine>, positionMs: Long): Int {
    var index = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs + ACTIVE_LEAD_MS) index = i else break
    }
    return index
}

/**
 * Lembar bawah lirik (dipanggil dari menu "Lirik").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSheet(
    track: LyreonTrack,
    positionMs: Long,
    onSeekMs: (Long) -> Unit,
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
        Column(Modifier.fillMaxWidth().height(560.dp).padding(bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.lyrics_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = LyreonCrimson,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LyreonTextPrimary,
                    maxLines = 1,
                )
            }

            LyricsContent(
                track = track,
                positionMs = positionMs,
                onSeekMs = onSeekMs,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun LyricsMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = LyreonTextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PlainLyrics(plain: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(plain, style = MaterialTheme.typography.bodyLarge, color = LyreonTextSecondary)
    }
}
