/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Rantai audio (SilenceSkippingAudioProcessor + SonicAudioProcessor + processor
 * deteksi hening) dan logika lompat-hening mengikuti FrancescoGrazioso/Meld
 * (GPL-3.0): app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt
 * (createExoPlayer, createRenderersFactory, handleLongSilenceDetected,
 * performInstantSilenceSkip). Metrolist Project (C) 2026 — lihat riwayat git
 * upstream untuk kontributor.
 *
 * Catatan versi: Lyreon memakai media3 1.10.1, jadi
 * - buildAudioSink(Context, Boolean, Boolean) [parameter ketiga bernama
 *   enableAudioOutputPlaybackParams sejak 1.10],
 * - SonicAudioProcessor pindah ke androidx.media3.common.audio.
 */
package com.lyreon.app.player

import android.app.PendingIntent
import android.content.Context
import android.media.audiofx.LoudnessEnhancer
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lyreon.app.LyreonApp
import com.lyreon.app.MainActivity
import com.lyreon.app.player.audio.LoudnessStore
import com.lyreon.app.player.audio.SilenceDetectorAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import kotlin.math.pow

/**
 * Background playback engine — Media3 MediaSessionService.
 * Notifikasi media (lock-screen, headset, Bluetooth, Android Auto) dikelola otomatis
 * oleh Media3 dari sesi ini. Audio-only: kita hanya pernah menyerahkan stream audio.
 *
 * Selain memutar, service ini juga tempat fitur audio "di bawah UI":
 * lewati keheningan (processor + mode lompat), kecepatan & nada (PlaybackParameters).
 * Semuanya dikendalikan dari Pengaturan dan diterapkan di sini karena hanya service
 * yang memegang ExoPlayer (UI hanya punya MediaController).
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var silenceDetector: SilenceDetectorAudioProcessor? = null
    private var silenceSkipJob: Job? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    @Volatile
    private var normalizeEnabled: Boolean = true

    @Volatile
    private var isSilenceSkipping = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val app = application as LyreonApp
        val locator = app.locator

        val dataSourceFactory = ResolvingDataSource.Factory(
            context = this,
            youtube = locator.youtube,
            downloadFileLookup = locator::downloadedFileFor,
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val detector = SilenceDetectorAudioProcessor(
            minSilenceDurationUs = MIN_SILENCE_DURATION_US,
            silenceThreshold = SILENCE_THRESHOLD,
            onLongSilence = ::handleLongSilence,
        )
        silenceDetector = detector

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(buildRenderersFactory(detector))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        observeAudioSettings(locator, player)
        observeNormalization(locator, player)
    }

    /**
     * Rantai processor audio: detektor hening Lyreon → pembuang hening media3 →
     * Sonic (kecepatan & nada). Processor kustom Lyreon harus berada SEBELUM dua processor bawaan itu.
     */
    private fun buildRenderersFactory(
        detector: SilenceDetectorAudioProcessor,
    ): DefaultRenderersFactory = object : DefaultRenderersFactory(this) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink? = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf<AudioProcessor>(detector),
                    SilenceSkippingAudioProcessor(
                        MIN_SILENCE_DURATION_US,
                        PADDING_SILENCE_US,
                        SILENCE_THRESHOLD.toShort(),
                    ),
                    SonicAudioProcessor(),
                ),
            )
            .build()
    }

    /** Terapkan preferensi audio begitu berubah — tanpa perlu memutus sesi. */
    private fun observeAudioSettings(locator: com.lyreon.app.core.ServiceLocator, player: ExoPlayer) {
        serviceScope.launch {
            locator.settings.settings
                .map {
                    AudioPrefs(
                        skipSilence = it.skipSilence,
                        instantSkip = it.skipSilenceInstant,
                        speed = it.playbackSpeed,
                        semitones = it.pitchSemitones,
                    )
                }
                .distinctUntilChanged()
                .collect { prefs ->
                    // Pembuang hening bawaan media3 (menghapus jeda sunyi dari audio).
                    player.skipSilenceEnabled = prefs.skipSilence
                    // Mode lompat hanya berarti bila pembuang hening juga aktif.
                    silenceDetector?.instantModeEnabled = prefs.skipSilence && prefs.instantSkip
                    if (!prefs.skipSilence || !prefs.instantSkip) {
                        silenceDetector?.resetTracking()
                        silenceSkipJob?.cancel()
                    }
                    runCatching {
                        player.setPlaybackParameters(
                            PlaybackParameters(prefs.speed, pitchRatio(prefs.semitones)),
                        )
                    }
                }
        }
    }

    private data class AudioPrefs(
        val skipSilence: Boolean,
        val instantSkip: Boolean,
        val speed: Float,
        val semitones: Int,
    )

    // ------------------------------------------------------------------
    // Lompat keheningan panjang (mode agresif)
    // ------------------------------------------------------------------

    private fun handleLongSilence() {
        val player = mediaSession?.player ?: return
        if (silenceDetector?.instantModeEnabled != true) return
        if (isSilenceSkipping || silenceSkipJob?.isActive == true) return
        silenceSkipJob = serviceScope.launch {
            // Debounce: fade pendek atau transisi halus tidak boleh memicu lompatan.
            delay(SILENCE_DEBOUNCE_MS)
            performInstantSilenceSkip(player)
        }
    }

    private suspend fun performInstantSilenceSkip(player: Player) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (duration <= SILENCE_SKIP_STEP_MS) return
        val detector = silenceDetector ?: return

        isSilenceSkipping = true
        try {
            var hops = 0
            while (coroutineContext.isActive && detector.instantModeEnabled && detector.isCurrentlySilent()) {
                val current = player.currentPosition
                val target = (current + SILENCE_SKIP_STEP_MS).coerceAtMost(duration - TAIL_GUARD_MS)
                if (target <= current) break
                // Reset pelacakan SEBELUM seek agar segmen yang sama tidak memicu ulang.
                detector.resetTracking()
                player.seekTo(target)
                hops++
                if (hops >= MAX_SILENCE_HOPS || target >= duration - TAIL_GUARD_MS) break
                delay(SILENCE_SETTLE_MS)
            }
        } finally {
            isSilenceSkipping = false
        }
    }

    // ------------------------------------------------------------------
    // Normalisasi volume per lagu (loudnessDb dari YouTube)
    // ------------------------------------------------------------------

    /**
     * Gain = -loudnessDb (dalam millibel), dijepit -1500..+300 mB — sama seperti
     * Meld/Metrolist: lagu yang direkam pelan dinaikkan, yang terlalu panas
     * diturunkan sedikit, dan tidak pernah dipaksa lebih dari +3 dB.
     */
    private fun observeNormalization(locator: com.lyreon.app.core.ServiceLocator, player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                refreshNormalization(player)
            }
        })

        serviceScope.launch {
            locator.settings.settings
                .map { it.normalizeAudio }
                .distinctUntilChanged()
                .collect { enabled ->
                    normalizeEnabled = enabled
                    refreshNormalization(player)
                }
        }

        // Loudness sering baru diketahui SETELAH lagu mulai (URL diselesaikan saat
        // DataSource dibuka), jadi store-nya diamati terpisah.
        serviceScope.launch {
            LoudnessStore.latest.collect { latest ->
                val currentId = player.currentMediaItem?.mediaId
                if (latest != null && latest.first == currentId) refreshNormalization(player)
            }
        }
    }

    private fun refreshNormalization(player: ExoPlayer) {
        if (!normalizeEnabled) {
            setEnhancerEnabled(false)
            return
        }
        val mediaId = player.currentMediaItem?.mediaId
        if (mediaId == null) {
            setEnhancerEnabled(false)
            return
        }
        val loudnessDb = LoudnessStore.loudnessFor(mediaId)
        if (loudnessDb == null) {
            // Tidak ada data loudness (klien ini tidak memberikannya) → jangan
            // mengubah volume sama sekali.
            setEnhancerEnabled(false)
            return
        }
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return
        val enhancer = runCatching {
            loudnessEnhancer ?: LoudnessEnhancer(sessionId).also { loudnessEnhancer = it }
        }.getOrNull() ?: return
        val gainMb = (-loudnessDb * 100f).toInt().coerceIn(MIN_GAIN_MB, MAX_GAIN_MB)
        runCatching {
            enhancer.setTargetGain(gainMb)
            enhancer.enabled = true
        }.onFailure {
            // Sebagian perangkat menolak efek ini — matikan bersih, jangan crash.
            releaseEnhancer()
        }
    }

    private fun setEnhancerEnabled(enabled: Boolean) {
        runCatching { loudnessEnhancer?.enabled = enabled }
    }

    private fun releaseEnhancer() {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        silenceSkipJob?.cancel()
        releaseEnhancer()
        serviceScope.cancel()
        silenceDetector = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        /** Keheningan minimal yang dibuang/dideteksi (2 detik, sama seperti Meld). */
        const val MIN_SILENCE_DURATION_US = 2_000_000L

        /** Sisa keheningan yang dipertahankan agar tidak terdengar terpotong. */
        const val PADDING_SILENCE_US = 20_000L

        /** Ambang sampel PCM 16-bit di bawah 256 dianggap hening. */
        const val SILENCE_THRESHOLD = 256

        /** Batas gain normalisasi (millibel): -15 dB .. +3 dB, mengikuti Meld. */
        const val MIN_GAIN_MB = -1500
        const val MAX_GAIN_MB = 300

        const val SILENCE_DEBOUNCE_MS = 200L
        const val SILENCE_SKIP_STEP_MS = 15_000L
        const val SILENCE_SETTLE_MS = 300L
        const val MAX_SILENCE_HOPS = 80
        const val TAIL_GUARD_MS = 500L

        /** Nada dalam semitone → rasio pitch (12 semitone = 1 oktaf = 2×). */
        fun pitchRatio(semitones: Int): Float =
            2f.pow(semitones.coerceIn(PITCH_MIN_SEMITONES, PITCH_MAX_SEMITONES) / 12f)

        const val PITCH_MIN_SEMITONES = -12
        const val PITCH_MAX_SEMITONES = 12
    }
}
