/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.lyreon.app.R
import com.lyreon.app.core.ServiceLocator
import com.lyreon.app.data.model.LyreonTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lyreon.app.widget.WidgetSnapshot
import com.lyreon.app.widget.WidgetState

data class PlayerUiState(
    val connected: Boolean = false,
    val currentTrack: LyreonTrack? = null,
    val queue: List<LyreonTrack> = emptyList(),
    val currentIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sleepDeadlineMs: Long? = null,
    val snackbarEvents: Int = 0,
)

/**
 * State frekuensi-tinggi (ticker 500ms) — dipisah dari [PlayerUiState] agar
 * layar yang hanya butuh track/isPlaying TIDAK ikut recompose tiap tick.
 * Hanya MiniPlayerBar & NowPlayingScreen yang mengoleksi flow ini.
 */
data class PlayerPosition(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * Kondisi jalur stream. Dipakai UI untuk menampilkan peringatan yang bisa
 * ditindaklanjuti (mis. "aktifkan akun YouTube") alih-alih loop senyap.
 */
enum class StreamHealthLevel {
    /** Semua normal. */
    OK,

    /** Ada kegagalan, tetapi pemutaran masih jalan (skip satu-dua lagu). */
    DEGRADED,

    /** Circuit breaker terbuka: pemutaran dihentikan, perlu tindakan pengguna. */
    TRIPPED,
}

data class StreamHealth(
    val level: StreamHealthLevel = StreamHealthLevel.OK,
    val consecutiveFailures: Int = 0,
    val skipsInWindow: Int = 0,
    /**
     * True bila kegagalan terakhir berciri SABR-only/HLS-only — artinya masalah
     * kebijakan server YouTube (kena ke semua lagu), bukan video tertentu.
     */
    val sabrSuspected: Boolean = false,
    /** Pesan siap-tampil (sudah dilokalkan) untuk banner/snackbar; null = tanpa pesan. */
    val message: String? = null,
    val updatedAtMs: Long = 0L,
)

/**
 * Satu pintu UI ↔ pemutar. Menghubungkan MediaController ke PlaybackService,
 * menyinkronkan state, mengelola antrean, sleep timer, restore sesi,
 * pencatatan riwayat, dan "radio" otomatis dari lagu terkait.
 */
class PlayerManager(
    private val context: Context,
    private val locator: ServiceLocator,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private companion object {
        /**
         * Tag logcat khusus kesehatan stream. Saring dengan
         * `adb logcat -s LyreonStreamHealth PlayerClientLadder InnertubeFallback`
         * untuk melihat seluruh rantai keputusan saat lagu gagal diputar.
         */
        const val TAG = "LyreonStreamHealth"

        /**
         * Bukti kemajuan playback yang diperlukan sebelum penghitung kegagalan
         * di-reset. 8 detik cukup untuk menyingkirkan "READY sesaat lalu error",
         * tapi masih jauh di bawah durasi lagu normal.
         */
        const val PROGRESS_RESET_MS = 8_000L

        /** Jendela geser (sliding window) untuk menghitung skip beruntun. */
        const val SKIP_WINDOW_MS = 30_000L

        /** Jumlah skip dalam [SKIP_WINDOW_MS] yang dianggap loop → buka breaker. */
        const val SKIP_BURST_LIMIT = 4

        /** Kegagalan beruntun tanpa bukti kemajuan → buka breaker. */
        const val FAILURE_LIMIT = 3
    }

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    // Posisi/durasi — satu-satunya state yang berubah tiap tick 500ms
    private val _position = MutableStateFlow(PlayerPosition())
    val position: StateFlow<PlayerPosition> = _position.asStateFlow()

    /**
     * Posisi pemutaran dibaca LANGSUNG dari pemutar (murah, dalam-proses, tanpa
     * memicu recomposition). Dipakai lirik hidup yang butuh ketelitian ~40 ms —
     * flow [position] sengaja hanya dipompa tiap 500 ms agar layar lain hemat.
     *
     * Pendekatan ini mengikuti Meld/Metrolist yang membaca `player.currentPosition`
     * di loop lirik, alih-alih berlangganan ticker UI.
     */
    fun positionNow(): Long =
        runCatching { controller?.currentPosition?.coerceAtLeast(0L) }.getOrNull()
            ?: _position.value.positionMs

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    // Pop-up donasi: menyala setiap pengguna 10× berganti lagu secara manual
    // (pilih lagu / next-prev — pergantian otomatis saat lagu habis tidak dihitung)
    private val _donateRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val donateRequests: SharedFlow<Unit> = _donateRequests.asSharedFlow()
    private var trackSwitchCount = 0

    // Rangkaian kegagalan stream beruntun — bila terlalu sering, stop total.
    //
    // PENTING (perbaikan bug): penghitung ini DULU di-reset setiap kali player
    // menyentuh STATE_READY. Di Media3, STATE_READY hanya berarti "buffer cukup
    // untuk mulai", bukan "playback benar-benar jalan" — track yang sempat READY
    // sepersekian detik sebelum error lagi (manifest parsial/rusak) membuat
    // breaker tak pernah terbuka, sehingga loop skip-skip-skip berjalan terus.
    // Reset sekarang hanya terjadi setelah ada BUKTI KEMAJUAN: posisi playback
    // benar-benar maju ≥ [PROGRESS_RESET_MS] untuk track yang sama (lihat
    // [observeProgress] yang dipanggil ticker 500 ms).
    private var consecutiveFailures = 0

    /** Waktu (ms) tiap skip akibat error — untuk mendeteksi burst/loop. */
    private val skipTimestamps = ArrayDeque<Long>()

    /** Track & posisi acuan terakhir untuk verifikasi kemajuan playback. */
    private var progressAnchorId: String? = null
    private var progressAnchorPos: Long = 0L

    private val _health = MutableStateFlow(StreamHealth())

    /** Kondisi jalur stream untuk UI (banner peringatan + tombol coba lagi). */
    val health: StateFlow<StreamHealth> = _health.asStateFlow()

    // Mode video: true = render permukaan video di NowPlaying (stream muxed ≤ 720p)
    private val _videoMode = MutableStateFlow(false)
    val videoMode: StateFlow<Boolean> = _videoMode.asStateFlow()

    /** Controller untuk diikat ke PlayerView (mode video). */
    val mediaController: MediaController? get() = controller

    private var controller: MediaController? = null
    private val registry = mutableMapOf<String, LyreonTrack>()
    private val errorRetries = mutableMapOf<String, Int>()

    private var ticker: Job? = null
    private var sleepJob: Job? = null
    private var saveJob: Job? = null
    private var restored = false

    /** Perintah play yang datang sebelum controller tersambung (cold start). */
    private var pendingAction: (() -> Unit)? = null

    init {
        connectController()
    }

    // ------------------------------------------------------------------
    // Koneksi controller
    // ------------------------------------------------------------------

    private fun connectController() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val mc = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = mc
            mc.addListener(listener)
            _state.update { it.copy(connected = true) }
            syncFromPlayer(mc)
            val pending = pendingAction
            pendingAction = null
            if (pending != null) {
                pending.invoke()
            } else {
                restoreSessionIfNeeded()
            }
            startTicker()
        }, MoreExecutors.directExecutor())
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
            publishWidgetState()
            if (isPlaying) {
                _state.value.currentTrack?.let { t ->
                    scope.launch { locator.library.recordPlay(t) }
                }
            }
            scheduleSave()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update {
                it.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
            }
            // STATE_READY TIDAK lagi mereset penghitung kegagalan: itu hanya
            // berarti buffer cukup untuk mulai, bukan playback benar-benar jalan.
            // Reset terjadi di observeProgress() setelah posisi maju nyata.
            if (playbackState == Player.STATE_ENDED) {
                onEnded()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            // ALGORITMA SELERA — catat lagu yang baru ditinggalkan beserta
            // rasio dengarnya (skip cepat ≈ sinyal lemah, didengar habis ≈ kuat)
            run {
                val prev = tasteActiveTrack
                if (prev != null) {
                    val durMs = prev.durationSec * 1000L
                    val posMs = _position.value.positionMs
                    val ratio = if (durMs > 0L) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0.5f
                    val finished = ratio >= 0.6f || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                    scope.launch { locator.taste.recordPlay(prev, if (finished) ratio.coerceAtLeast(0.6f) else ratio) }
                }
            }
            syncFromPlayer(c)
            tasteActiveTrack = _state.value.currentTrack
            errorRetries.clear()
            applyHlsBandwidthPolicy(c)
            warmUpcoming(c)
            maybeExtendQueue(c)
            scheduleSave()
            if (c.isPlaying) {
                _state.value.currentTrack?.let { t ->
                    scope.launch { locator.library.recordPlay(t) }
                }
            }
            // Hitung hanya pergantian manual (bukan auto-advance saat lagu selesai)
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                trackSwitchCount++
                if (trackSwitchCount >= 10) {
                    trackSwitchCount = 0
                    _donateRequests.tryEmit(Unit)
                }
            }
        }

        override fun onEvents(player: Player, events: Player.Events) {
            syncFromPlayer(controller ?: return)
        }

        override fun onPlayerError(error: PlaybackException) {
            val track = _state.value.currentTrack
            val id = track?.videoId.orEmpty()
            if (id.isNotBlank()) locator.youtube.invalidate(id)
            val tries = errorRetries.getOrDefault(id, 0)
            errorRetries[id] = tries + 1
            val c = controller ?: return

            // Alasan sesungguhnya ada di rantai cause: ResolvingDataSource
            // membungkus kegagalan resolusi, StreamUnavailableException membawa
            // tanda SABR-only/HLS-only dari tangga klien.
            val sabrSuspected = isSabrFailure(error)
            if (sabrSuspected) {
                com.lyreon.app.yt.innertube.PlayerClientLadder.push(
                    "player error $id berciri SABR/HLS (${error.errorCodeName})",
                )
            }

            // Stream hanya tersedia sebagai manifest HLS? Itu BUKAN kegagalan:
            // HLS adalah jalur anonim yang sah (manifest tidak menuntut poToken
            // GVS). Tukar MediaItem ke URL m3u8 + MIME yang benar supaya
            // DefaultMediaSourceFactory membangun HlsMediaSource, lalu putar lagi.
            val hls = findHlsRequest(error)
            val currentItem = c.currentMediaItem
            if (hls != null && id.isNotBlank() && currentItem != null &&
                currentItem.localConfiguration?.uri?.scheme == ResolvingDataSource.LYREON_SCHEME
            ) {
                val index = c.currentMediaItemIndex
                val swapped = currentItem.buildUpon()
                    .setUri(hls.manifestUrl)
                    .setMimeType(hls.manifestMime)
                    .build()
                com.lyreon.app.yt.innertube.PlayerClientLadder.push(
                    "player: $id ditukar ke manifest HLS",
                )
                Log.i(TAG, "track '$id' beralih ke manifest HLS")
                // Penukaran ini tidak memakan jatah retry satu-per-track.
                errorRetries[id] = tries
                runCatching {
                    c.replaceMediaItem(index, swapped)
                    c.seekTo(index, 0)
                    c.prepare()
                    c.play()
                }.onFailure { Log.w(TAG, "gagal menukar ke HLS: ${it.message}") }
                return
            }

            when {
                // Satu kesempatan ulang per track. Percobaan ulang ini tidak
                // mengulang jalur yang sama: klien yang baru saja membalas
                // SABR-only sudah didinginkan di PlayerClientLadder.
                tries < 1 && id.isNotBlank() -> {
                    emit(context.getString(R.string.stream_reloading))
                    markDegraded(sabrSuspected)
                    runCatching {
                        c.seekToDefaultPosition()
                        c.prepare()
                        c.play()
                    }
                }
                // Loncat ke track berikutnya — dengan dua rem: kegagalan beruntun
                // DAN burst skip dalam jendela waktu (pola loop "READY sesaat →
                // error lagi" yang dulu tak terdeteksi).
                c.hasNextMediaItem() && consecutiveFailures < FAILURE_LIMIT -> {
                    consecutiveFailures++
                    Log.w(
                        TAG,
                        "gagal #$consecutiveFailures/$FAILURE_LIMIT track='$id' " +
                            "errorCode=${error.errorCodeName} sabr=$sabrSuspected " +
                            "skipDalamJendela=${skipTimestamps.size}",
                    )
                    if (registerSkip()) {
                        tripPlayback(c, sabrSuspected)
                    } else {
                        markDegraded(sabrSuspected)
                        emit(context.getString(R.string.stream_track_skipped))
                        runCatching {
                            c.seekToNextMediaItem()
                            c.prepare()
                            c.play()
                        }
                    }
                }
                else -> tripPlayback(c, sabrSuspected)
            }
        }
    }

    // ------------------------------------------------------------------
    // Circuit breaker kesehatan stream
    // ------------------------------------------------------------------

    /**
     * Menelusuri rantai cause mencari sinyal "stream ini manifest HLS" yang
     * dilempar [ResolvingDataSource].
     */
    private fun findHlsRequest(error: PlaybackException): HlsRequiredException? {
        var node: Throwable? = error
        var depth = 0
        while (node != null && depth < 8) {
            if (node is HlsRequiredException) return node
            node = node.cause
            depth++
        }
        return null
    }

    /**
     * Menelusuri rantai cause mencari tanda YouTube menutup akses anonim:
     * SABR-only, hanya-HLS, format ber-DRM, atau URL yang ditolak CDN
     * (403 / pratinjau ~1 MiB — pola yang membuat lagu mati di tengah).
     */
    private fun isSabrFailure(error: PlaybackException): Boolean {
        var node: Throwable? = error
        var depth = 0
        while (node != null && depth < 8) {
            if (node is com.lyreon.app.yt.innertube.StreamUnavailableException) {
                return node.sabrOnly || node.hlsOnly || node.drmOnly || node.cdnRejected
            }
            val message = node.message.orEmpty()
            if (message.contains("SABR", ignoreCase = true)) return true
            if (message.contains("ContentNotSupported", ignoreCase = true)) return true
            node = node.cause
            depth++
        }
        // Tidak ada tanda eksplisit → pakai sinyal tangga klien (jendela sempit).
        return com.lyreon.app.yt.innertube.PlayerClientLadder
            .sabrPressureRecently(2L * 60_000L)
    }

    /** Catat satu skip; kembalikan true bila sudah masuk pola burst/loop. */
    private fun registerSkip(): Boolean {
        val now = System.currentTimeMillis()
        while (skipTimestamps.isNotEmpty() && now - skipTimestamps.first() > SKIP_WINDOW_MS) {
            skipTimestamps.removeFirst()
        }
        skipTimestamps.addLast(now)
        return skipTimestamps.size > SKIP_BURST_LIMIT
    }

    private fun markDegraded(sabrSuspected: Boolean) {
        if (_health.value.level == StreamHealthLevel.TRIPPED) return
        _health.update {
            it.copy(
                level = StreamHealthLevel.DEGRADED,
                consecutiveFailures = consecutiveFailures,
                skipsInWindow = skipTimestamps.size,
                sabrSuspected = it.sabrSuspected || sabrSuspected,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** Buka breaker: hentikan pemutaran, simpan antrean, beri pesan yang actionable. */
    private fun tripPlayback(c: MediaController, sabrSuspected: Boolean) {
        // Lyreon anonim (tanpa cookie akun), jadi pesannya tidak pernah menyuruh
        // pengguna login — yang bisa ditindaklanjuti adalah menunggu/mencoba lagi
        // atau memperbarui app saat tangga klien perlu disesuaikan.
        val message = if (sabrSuspected) {
            context.getString(R.string.stream_tripped_sabr_anonymous)
        } else {
            context.getString(R.string.stream_stopped_unavailable)
        }
        Log.e(
            TAG,
            "breaker TERBUKA (TRIPPED): kegagalan=$consecutiveFailures " +
                "skip=${skipTimestamps.size} sabr=$sabrSuspected — antrean dipertahankan",
        )
        emit(message)
        // Antrean DIPERTAHANKAN — `stop()` tidak menghapus item. Perilaku lama
        // mengosongkan antrean, sehingga pemulihan berarti mencari ulang lagu.
        runCatching {
            c.pause()
            c.stop()
        }
        _health.update {
            StreamHealth(
                level = StreamHealthLevel.TRIPPED,
                consecutiveFailures = consecutiveFailures,
                skipsInWindow = skipTimestamps.size,
                sabrSuspected = sabrSuspected,
                message = message,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
        errorRetries.clear()
        scheduleSave()
    }

    /**
     * Satu-satunya jalur reset breaker: posisi playback benar-benar maju
     * ≥ [PROGRESS_RESET_MS] untuk track yang sama sambil bermain.
     */
    private fun observeProgress(c: MediaController, positionMs: Long) {
        if (!c.isPlaying) return
        val id = c.currentMediaItem?.mediaId.orEmpty()
        if (id.isBlank()) return
        if (progressAnchorId != id) {
            progressAnchorId = id
            progressAnchorPos = positionMs
            return
        }
        if (positionMs - progressAnchorPos < PROGRESS_RESET_MS) return
        progressAnchorPos = positionMs
        if (consecutiveFailures == 0 && skipTimestamps.isEmpty() &&
            _health.value.level == StreamHealthLevel.OK
        ) {
            return
        }
        Log.i(
            TAG,
            "breaker di-reset: posisi maju ≥${PROGRESS_RESET_MS}ms pada track='$id' " +
                "(sebelumnya kegagalan=$consecutiveFailures, level=${_health.value.level})",
        )
        consecutiveFailures = 0
        skipTimestamps.clear()
        _health.value = StreamHealth()
    }

    private fun onEnded() {
        // Lagu terakhir antrean selesai penuh → sinyal selera terkuat
        _state.value.currentTrack?.let { t ->
            scope.launch { locator.taste.recordPlay(t, 1f) }
        }
        val current = _state.value.currentTrack ?: return
        if (current.isLocal) return // lagu lokal: tidak ada "radio" lagu terkait
        scope.launch {
            val autoplay = locator.settings.settings.first().autoplayRelated
            if (!autoplay) return@launch
            // Breaker terbuka → jangan menyeret pengguna kembali ke loop.
            if (_health.value.level == StreamHealthLevel.TRIPPED) return@launch
            emit("Membuka radio: lagu terkait…")
            val related = withContext(Dispatchers.IO) { locator.youtube.relatedOf(current.videoId) }
            if (related.isEmpty()) return@launch
            val existing = HashSet<String>()
            controller?.let { c -> for (i in 0 until c.mediaItemCount) existing.add(c.getMediaItemAt(i).mediaId) }
            existing += current.videoId
            val diverse = locator.taste.diversePick(
                candidates = related,
                limit = 20,
                existingIds = existing,
                seedTitle = current.title,
                seedArtist = current.artist,
            )
            if (diverse.isEmpty()) return@launch
            appendAll(diverse)
            val c = controller ?: return@launch
            runCatching {
                c.seekToNextMediaItem()
                c.prepare()
                c.play()
            }
        }
    }

    // ------------------------------------------------------------------
    // Sinkronisasi state dari player
    // ------------------------------------------------------------------

    private fun syncFromPlayer(c: MediaController) {
        val count = c.mediaItemCount
        val queue = mutableListOf<LyreonTrack>()
        for (i in 0 until count) {
            val id = c.getMediaItemAt(i).mediaId
            registry[id]?.let { queue.add(it) }
        }
        val index = c.currentMediaItemIndex.takeIf { it in 0 until count } ?: -1
        val currentId = c.currentMediaItem?.mediaId
        val duration = c.duration.takeIf { it > 0 && it != C.TIME_UNSET }
            ?: registry[currentId]?.durationSec?.times(1000L) ?: 0L

        _state.update {
            it.copy(
                queue = queue,
                currentIndex = index,
                currentTrack = currentId?.let(registry::get),
                shuffleEnabled = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
            )
        }
        _position.update {
            it.copy(positionMs = c.currentPosition.coerceAtLeast(0L), durationMs = duration)
        }
        publishWidgetState()
    }

    /**
     * Dorong snapshot widget. Murah dan idempoten: [WidgetState.publish] berhenti
     * lebih awal bila isinya tidak berubah, jadi pemanggilan berulang dari
     * sinkronisasi pemutar tidak memicu render ulang launcher.
     */
    private fun publishWidgetState() {
        val snapshot = _state.value
        val track = snapshot.currentTrack
        WidgetState.publish(
            context,
            WidgetSnapshot(
                title = track?.title.orEmpty(),
                artist = track?.artist.orEmpty(),
                artUrl = track?.thumbnailUrl.orEmpty(),
                isPlaying = snapshot.isPlaying,
                hasTrack = track != null,
            ),
        )
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val c = controller
                if (c != null) {
                    // HANYA flow posisi yang dipompa di sini — layar lain tidak terganggu.
                    // isBuffering sudah ditangani listener onPlaybackStateChanged.
                    val positionMs = c.currentPosition.coerceAtLeast(0L)
                    _position.update {
                        it.copy(
                            positionMs = positionMs,
                            durationMs = c.duration.takeIf { d -> d > 0 && d != C.TIME_UNSET }
                                ?: it.durationMs,
                        )
                    }
                    // Verifikasi kemajuan playback — satu-satunya pemicu reset
                    // circuit breaker (bukan STATE_READY).
                    observeProgress(c, positionMs)
                }
                delay(500L)
            }
        }
    }

    private fun warmUpcoming(c: MediaController) {
        val count = c.mediaItemCount
        if (count == 0) return
        val idx = c.currentMediaItemIndex
        val upcoming = listOf(idx + 1, idx + 2).filter { it < count }
        val ids = upcoming
            .map { c.getMediaItemAt(it).mediaId }
            .filterNot { it.startsWith(LyreonTrack.LOCAL_ID_PREFIX) } // lokal tak perlu warm-up
        if (ids.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runCatching { locator.youtube.warmUp(*ids.toTypedArray()) }
            // Lagu berikutnya yang ternyata hanya punya manifest HLS dipersiapkan
            // SEKARANG (bukan saat sudah error): MediaItem ditukar ke URL m3u8 +
            // MIME, jadi transisi mulus tanpa retry.
            withContext(Dispatchers.Main) { upgradeManifestItems(c, upcoming) }
        }
    }

    /**
     * Tukar `MediaItem` ber-skema `lyreon://` yang hasil resolusinya manifest HLS
     * menjadi item beralamat m3u8 langsung. Hanya untuk lagu yang BELUM diputar,
     * jadi tidak ada risiko memutus playback yang sedang berjalan.
     */
    private fun upgradeManifestItems(c: MediaController, indexes: List<Int>) {
        indexes.forEach { index ->
            val item = runCatching { c.getMediaItemAt(index) }.getOrNull() ?: return@forEach
            val local = item.localConfiguration ?: return@forEach
            if (local.uri.scheme != ResolvingDataSource.LYREON_SCHEME) return@forEach
            val manifest = locator.youtube.cachedManifestFor(item.mediaId) ?: return@forEach
            val swapped = item.buildUpon()
                .setUri(manifest.first)
                .setMimeType(manifest.second)
                .build()
            runCatching { c.replaceMediaItem(index, swapped) }
                .onSuccess { Log.i(TAG, "antrean[$index] (${item.mediaId}) disiapkan sebagai HLS") }
        }
        applyHlsBandwidthPolicy(c)
    }

    /**
     * Manifest HLS YouTube berisi varian video+audio yang sudah digabung. Lyreon
     * pemutar audio → matikan track video supaya ExoPlayer memilih varian termurah
     * yang masih mengandung audio (hemat kuota berlipat dibanding varian 720p).
     */
    private fun applyHlsBandwidthPolicy(c: MediaController) {
        val mime = c.currentMediaItem?.localConfiguration?.mimeType.orEmpty()
        val isHls = mime.contains("mpegurl", ignoreCase = true)
        runCatching {
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, isHls)
                .build()
        }
    }

    // ------------------------------------------------------------------
    // Antrean tanpa habis: mendekati ujung antrean → tambah 20 lagu terkait
    // ------------------------------------------------------------------

    private var extendJob: Job? = null

    /** Lagu yang sedang dicatat ke profil selera (diperbarui tiap transisi). */
    private var tasteActiveTrack: com.lyreon.app.data.model.LyreonTrack? = null

    /** Penghitung ekstensi antrean — genap/ganjil untuk menyelingi sumber kandidat. */
    private var extendCount = 0

    private fun maybeExtendQueue(c: MediaController) {
        val count = c.mediaItemCount
        if (count == 0) return
        // Jangan memperpanjang antrean saat jalur stream bermasalah — itu hanya
        // memperpanjang loop skip dan membuang permintaan ke YouTube.
        if (_health.value.level != StreamHealthLevel.OK) return
        // Perpanjang LEBIH AWAL (sisa ≤ 5, bukan 3) agar radio tidak pernah jeda
        // di tengah lagu — ala antrean kontinu yang selalu penuh di depan.
        val remaining = count - 1 - c.currentMediaItemIndex
        if (remaining > 5) return
        if (extendJob?.isActive == true) return
        val current = _state.value.currentTrack ?: return
        if (current.isLocal) return // lagu lokal tidak punya lagu terkait

        extendJob = scope.launch {
            val autoplay = locator.settings.settings.first().autoplayRelated
            if (!autoplay) return@launch
            val existing = HashSet<String>(count + 8)
            for (i in 0 until c.mediaItemCount) existing.add(c.getMediaItemAt(i).mediaId)

            // Sumber 1: radio YouTube dari lagu berjalan + panen konteks benihnya
            val bundle = withContext(Dispatchers.IO) {
                runCatching { locator.youtube.bundle(current.videoId) }.getOrNull()
            }
            if (bundle != null) {
                locator.taste.recordTags(bundle.seedTags, bundle.seedHashtags, bundle.seedCategory)
            }
            val candidates = ArrayList<com.lyreon.app.data.model.LyreonTrack>(64)
            bundle?.related?.let(candidates::addAll)
            // Jika Metrolist gagal/related kosong → pakai relatedOf (punya fallback InnerTube)
            if (candidates.isEmpty()) {
                candidates += withContext(Dispatchers.IO) {
                    runCatching { locator.youtube.relatedOf(current.videoId) }.getOrDefault(emptyList())
                }
            }

            // Sumber 2 (berselang): query PERSONA — hashtag/token dari selera pengguna
            // (mis. "sadvibes speed up reverb") sehingga antrean ikut berkonteks,
            // bukan hanya mengekor artis/judul lagu terakhir.
            extendCount++
            if (extendCount % 2 == 0) {
                val query = locator.taste.personalityQuery()
                if (query != null) {
                    candidates += withContext(Dispatchers.IO) {
                        locator.youtube.searchTracks(query, limit = 16)
                    }
                }
            }

            val additions = locator.taste.diversePick(
                candidates = candidates,
                limit = 20,
                existingIds = existing,
                seedTitle = current.title,
                seedArtist = current.artist,
            )
            if (additions.isNotEmpty()) {
                appendAll(additions)
                emit(context.getString(R.string.queue_extended, additions.size))
                return@launch
            }

            // Sumber 3: RIWAYAT PUTAR pengguna. Dipakai hanya bila radio dan
            // query persona sama-sama tidak menghasilkan apa pun, sehingga
            // antrean tidak pernah benar-benar berhenti (wishlist: "history play
            // yang bisa masuk otomatis dalam antrean").
            val useHistory = locator.settings.settings.first().historyAutoQueue
            if (useHistory) {
                val fromHistory = historyFiller(existing, limit = 15)
                if (fromHistory.isNotEmpty()) {
                    appendAll(fromHistory)
                    emit(context.getString(R.string.queue_history_filled, fromHistory.size))
                }
            }
        }
    }

    /**
     * Ambil dari riwayat putar lagu-lagu yang belum ada di antrean.
     *
     * Riwayat dibaca dari Room (Flow) sehingga selalu selaras dengan yang
     * ditampilkan di tab Riwayat; dedupe memakai judul-inti yang sama dengan
     * [appendAll] supaya varian "speed up reverb" tidak membanjiri antrean.
     */
    private suspend fun historyFiller(existing: Collection<String>, limit: Int): List<com.lyreon.app.data.model.LyreonTrack> {
        val history = runCatching { locator.library.history.first() }.getOrDefault(emptyList())
        if (history.isEmpty()) return emptyList()
        val known = HashSet<String>(existing)
        val out = ArrayList<com.lyreon.app.data.model.LyreonTrack>(limit)
        for (track in history) {
            if (out.size >= limit) break
            if (track.isLocal) continue
            if (!known.add(track.videoId)) continue
            out += track
        }
        return out
    }

    // ------------------------------------------------------------------
    // Sesi (restore antrean)
    // ------------------------------------------------------------------

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1200L)
            val c = controller ?: return@launch
            val st = _state.value
            locator.session.save(
                queue = st.queue,
                index = st.currentIndex.coerceAtLeast(0),
                positionMs = runCatching { c.currentPosition }.getOrDefault(0L),
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
            )
        }
    }

    private fun restoreSessionIfNeeded() {
        if (restored) return
        restored = true
        scope.launch {
            val saved = locator.session.load()
            val c = controller ?: return@launch
            if (c.mediaItemCount != 0) return@launch

            if (saved != null && saved.queue.isNotEmpty()) {
                saved.queue.forEach { registry[it.videoId] = it }
                runCatching {
                    c.setMediaItems(
                        saved.queue.map(::toMediaItem),
                        saved.index.coerceIn(0, saved.queue.lastIndex),
                        saved.positionMs.coerceAtLeast(0L),
                    )
                    c.shuffleModeEnabled = saved.shuffle
                    c.repeatMode = saved.repeatMode
                    c.prepare()
                    // playWhenReady false — pulihkan tapi tidak autoplay
                    syncFromPlayer(c)
                }
                return@launch
            }

            // Tidak ada sesi tersimpan: bangun antrean dari riwayat putar supaya
            // membuka app tidak memulai dari antrean kosong. Tidak diputar
            // otomatis — pengguna yang menekan play.
            if (!locator.settings.settings.first().historyAutoQueue) return@launch
            val seeded = historyFiller(emptySet(), limit = 25)
            if (seeded.isEmpty()) return@launch
            seeded.forEach { registry[it.videoId] = it }
            runCatching {
                c.setMediaItems(seeded.map(::toMediaItem), 0, 0L)
                c.prepare()
                syncFromPlayer(c)
            }
        }
    }

    // ------------------------------------------------------------------
    // Perintah publik
    // ------------------------------------------------------------------

    /**
     * Pindahkan lagu dalam antrean (reorder). Indeks di luar rentang diabaikan —
     * antrean bisa berubah dari notifikasi/Widget di saat bersamaan.
     */
    fun moveItem(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to) return
        val count = c.mediaItemCount
        if (from !in 0 until count || to !in 0 until count) return
        c.moveMediaItem(from, to)
    }

    fun playQueue(tracks: List<LyreonTrack>, startIndex: Int = 0, autoplay: Boolean = true) {
        if (tracks.isEmpty()) return
        tracks.forEach { registry[it.videoId] = it }
        val c = controller ?: run {
            pendingAction = { playQueue(tracks, startIndex, autoplay) }
            return
        }
        c.setMediaItems(tracks.map(::toMediaItem), startIndex.coerceIn(0, tracks.lastIndex), 0L)
        c.prepare()
        if (autoplay) c.play()
        syncFromPlayer(c)
    }

    /**
     * Mulai radio dari satu benih (tombol RADIO di halaman artis & Now Playing).
     *
     * Beda dari [playQueue] biasa: antrean LANGSUNG diisi lagu terkait dari
     * endpoint `/next`, jadi radio tidak terasa habis setelah satu lagu;
     * [maybeExtendQueue] lalu meneruskannya tanpa batas.
     */
    fun startRadio(seed: LyreonTrack) {
        if (seed.isLocal) {
            playQueue(listOf(seed), 0)
            return
        }
        scope.launch {
            emit(context.getString(R.string.radio_started, seed.title))
            val related = withContext(Dispatchers.IO) {
                runCatching { locator.youtube.relatedOf(seed.videoId) }.getOrDefault(emptyList())
            }
            val diverse = locator.taste.diversePick(
                candidates = related,
                limit = 24,
                existingIds = hashSetOf(seed.videoId),
                seedTitle = seed.title,
                seedArtist = seed.artist,
            )
            playQueue((listOf(seed) + diverse).distinctBy { it.videoId }, 0)
        }
    }

    /**
     * Masukkan riwayat putar ke antrean secara eksplisit (tombol di lembar
     * antrean). Jalur otomatisnya ada di [maybeExtendQueue].
     *
     * @param replace true = ganti antrean dan mulai dari [startIndex];
     *   false = tambahkan di belakang antrean yang ada.
     */
    fun enqueueHistory(limit: Int = 25, startIndex: Int = 0, replace: Boolean = false) {
        scope.launch {
            val c = controller
            val existing: Set<String> = if (replace) {
                emptySet()
            } else {
                HashSet<String>().apply {
                    if (c != null) for (i in 0 until c.mediaItemCount) add(c.getMediaItemAt(i).mediaId)
                }
            }
            val tracks = historyFiller(existing, limit)
            if (tracks.isEmpty()) {
                emit(context.getString(R.string.queue_history_empty))
                return@launch
            }
            if (replace) {
                playQueue(tracks, startIndex.coerceIn(0, tracks.lastIndex), autoplay = false)
            } else {
                appendAll(tracks)
            }
            emit(context.getString(R.string.queue_history_filled, tracks.size))
        }
    }

    fun appendAll(tracks: List<LyreonTrack>) {
        val c = controller ?: return
        // Bebas duplikat judul-inti (reupload speed-up/reverb/TikTok dsb): judul
        // yang sama dengan yang sudah antre tidak dimasukkan lagi — menjaga antrean
        // tetap variatif tanpa mengisi slot dengan varian judul yang sama.
        val existingTitles = HashSet<String>()
        for (i in 0 until c.mediaItemCount) {
            registry[c.getMediaItemAt(i).mediaId]?.title?.let {
                existingTitles.add(com.lyreon.app.data.taste.MusicTextAnalyzer.coreTitle(it))
            }
        }
        tracks.forEach { t ->
            val core = com.lyreon.app.data.taste.MusicTextAnalyzer.coreTitle(t.title)
            if (core.isNotBlank() && existingTitles.contains(core)) return@forEach
            existingTitles.add(core)
            registry[t.videoId] = t
            c.addMediaItem(toMediaItem(t))
        }
        syncFromPlayer(c)
    }

    fun playNext(track: LyreonTrack) {
        registry[track.videoId] = track
        val c = controller ?: return
        val idx = (c.currentMediaItemIndex + 1).coerceAtLeast(0)
        if (c.mediaItemCount == 0) {
            playQueue(listOf(track))
        } else {
            c.addMediaItem(idx, toMediaItem(track))
            emit("Diputar setelah ini: ${track.title}")
        }
        syncFromPlayer(c)
    }

    fun addToQueue(track: LyreonTrack) {
        registry[track.videoId] = track
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playQueue(listOf(track), autoplay = false)
        } else {
            c.addMediaItem(toMediaItem(track))
        }
        emit("Ditambahkan ke antrean: ${track.title}")
        syncFromPlayer(c)
    }

    /**
     * Sisipkan lagu pada posisi tertentu — dipakai tombol URUNGKAN setelah
     * menghapus baris antrean (pola Meld: `player.addMediaItem(index, …)`
     * setelah snackbar undo, lihat Queue.kt).
     */
    fun insertAt(index: Int, track: LyreonTrack) {
        registry[track.videoId] = track
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            playQueue(listOf(track), autoplay = false)
            return
        }
        c.addMediaItem(index.coerceIn(0, c.mediaItemCount), toMediaItem(track))
        syncFromPlayer(c)
    }

    fun toggle() {
        val c = controller ?: return
        when {
            c.isPlaying -> c.pause()
            c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED -> {
                c.prepare()
                c.play()
            }
            else -> {
                if (c.mediaItemCount == 0 && _state.value.queue.isNotEmpty()) {
                    val q = _state.value.queue
                    playQueue(q, 0)
                } else c.play()
            }
        }
    }

    fun next() = controller?.let { c ->
        if (c.hasNextMediaItem()) c.seekToNextMediaItem()
        else if (c.repeatMode == Player.REPEAT_MODE_ALL) c.seekTo(0, 0L)
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000L) {
            c.seekTo(0)
        } else if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
        } else {
            c.seekTo(0)
        }
    }

    fun seekTo(ms: Long) = controller?.seekTo(ms.coerceAtLeast(0L)) ?: Unit

    fun seekToFraction(fraction: Float) {
        val d = _position.value.durationMs
        if (d > 0) seekTo((fraction.coerceIn(0f, 1f) * d).toLong())
    }

    fun seekForward() = controller?.seekForward() ?: Unit
    fun seekBack() = controller?.seekBack() ?: Unit

    fun setShuffle(enabled: Boolean) {
        controller?.shuffleModeEnabled = enabled
        syncFromPlayer(controller ?: return)
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        syncFromPlayer(c)
    }

    fun playAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.seekTo(index, 0L).also { c.play() }
    }

    fun removeAt(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) {
            c.removeMediaItem(index)
            syncFromPlayer(c)
        }
    }

    fun move(from: Int, to: Int) {
        val c = controller ?: return
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) {
            c.moveMediaItem(from, to)
            syncFromPlayer(c)
        }
    }

    fun clearQueue() {
        controller?.clearMediaItems()
        _state.update {
            it.copy(queue = emptyList(), currentIndex = -1, currentTrack = null)
        }
        _position.value = PlayerPosition()
    }

    fun stop() {
        controller?.stop()
    }

    /**
     * Dilanjutkan setelah pengguna memperbaiki sebabnya (memasang cookie akun,
     * menunggu kebijakan YouTube bergeser, atau sekadar mencoba lagi): buang
     * cache URL lama, reset breaker, lalu putar dari track yang sama.
     */
    fun retryAfterFix() {
        val c = controller ?: return
        consecutiveFailures = 0
        skipTimestamps.clear()
        errorRetries.clear()
        progressAnchorId = null
        progressAnchorPos = 0L
        _health.value = StreamHealth()
        val queue = _state.value.queue
        val index = _state.value.currentIndex.coerceAtLeast(0)
        runCatching {
            if (c.mediaItemCount == 0 && queue.isNotEmpty()) {
                c.setMediaItems(queue.map(::toMediaItem), index.coerceIn(0, queue.lastIndex), 0L)
            }
            // URL yang ter-cache di-resolve dengan identitas/klien lama.
            locator.youtube.invalidateAll()
            c.prepare()
            c.play()
        }
        emit(context.getString(R.string.stream_retrying))
    }

    /** Tutup banner peringatan tanpa mengubah status breaker. */
    fun dismissHealthAlert() {
        _health.update { it.copy(message = null) }
    }

    // ------------------------------------------------------------------
    // Sleep timer
    // ------------------------------------------------------------------

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepDeadlineMs = deadline) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            controller?.pause()
            _state.update { it.copy(sleepDeadlineMs = null) }
            emit("Sleep timer: pemutaran dijeda.")
        }
        emit("Sleep timer $minutes menit aktif.")
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        if (_state.value.sleepDeadlineMs != null) emit("Sleep timer dibatalkan.")
        _state.update { it.copy(sleepDeadlineMs = null) }
    }

    // ------------------------------------------------------------------
    // Util
    // ------------------------------------------------------------------

    private fun emit(message: String) {
        _events.tryEmit(message)
    }

    // ------------------------------------------------------------------
    // Mode VIDEO ↔ AUDIO (posisi playback dijaga kontinu)
    // ------------------------------------------------------------------

    fun setVideoMode(enabled: Boolean) {
        scope.launch {
            val c = controller ?: return@launch
            val track = _state.value.currentTrack ?: return@launch
            if (track.isLocal) return@launch // file lokal tidak punya mode video YouTube
            if (enabled == _videoMode.value) return@launch
            val pos = c.currentPosition.coerceAtLeast(0L)

            if (enabled) {
                emit(context.getString(R.string.video_loading))
                val url = withContext(Dispatchers.IO) {
                    locator.youtube.resolveVideoMuxed(track.videoId)
                }
                if (url.isNullOrBlank()) {
                    emit(context.getString(R.string.video_unavailable))
                    return@launch
                }
                val item = MediaItem.Builder()
                    .setMediaId(track.videoId)
                    .setUri(Uri.parse(url))
                    .setMediaMetadata(toMediaItem(track).mediaMetadata)
                    .build()
                runCatching {
                    c.replaceMediaItem(c.currentMediaItemIndex, item)
                    c.prepare()
                    c.seekTo(c.currentMediaItemIndex, pos)
                    c.play()
                }
                _videoMode.value = true
            } else {
                // Balik ke audio-only (resolusi malas via skema lyreon://audio/)
                runCatching {
                    c.replaceMediaItem(c.currentMediaItemIndex, toMediaItem(track))
                    c.prepare()
                    c.seekTo(c.currentMediaItemIndex, pos)
                    c.play()
                }
                _videoMode.value = false
            }
        }
    }

    private fun toMediaItem(t: LyreonTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(t.videoId)
            .setUri(
                // Lagu lokal: file di penyimpanan perangkat (diputar langsung
                // via DefaultDataSource). Lagu YouTube: skema lyreon://audio.
                if (t.isLocal) {
                    com.lyreon.app.local.LocalMusicRepository.uriForVideoId(t.videoId)
                        ?: ResolvingDataSource.uriOf(t.videoId)
                } else {
                    ResolvingDataSource.uriOf(t.videoId)
                },
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(t.title)
                    .setArtist(t.artist)
                    .setAlbumTitle(t.album.ifBlank { null })
                    .setArtworkUri(t.thumbnailUrl.takeIf { it.isNotBlank() }?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    fun release() {
        ticker?.cancel()
        sleepJob?.cancel()
        saveJob?.cancel()
        controller?.release()
    }
}
