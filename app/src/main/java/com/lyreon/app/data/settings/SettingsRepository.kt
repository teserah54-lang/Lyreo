/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lyreon.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.lyreonDataStore by preferencesDataStore(name = "lyreon_settings")

enum class AudioQuality(val label: String) {
    HIGH("TERBAIK"),
    BALANCED("SEIMBANG"),
    DATA_SAVER("HEMAT DATA"),
}

data class LyreonSettings(
    // BAWAAN = SISTEM + Material You, sesuai keputusan produk 2026-09:
    // "Tema default adalah Material You". Di bawah Android 12 warna dinamis
    // otomatis diabaikan LyreonTheme, jadi SISTEM jatuh ke skema gelap/terang.
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val reduceMotion: Boolean = false,
    val audioQuality: AudioQuality = AudioQuality.BALANCED,
    val autoplayRelated: Boolean = true,
    /** Nama yang disapa di Home (kosong = default "Farizy"). */
    val displayName: String = "",
    /** Warna aksen ARGB kustom (-1 = crimson bawaan). */
    val accentArgb: Int = -1,
    /** Kunci jenis huruf: default / serif / mono / cursive. */
    val fontKey: String = "default",
    /** Material You: ambil palet dari wallpaper (Android 12+). */
    val dynamicColor: Boolean = true,
    /**
     * Aksen mengikuti palet thumbnail lagu yang sedang diputar. Hanya mengganti
     * token aksen (bukan permukaan), jadi tetap serasi dengan Material You:
     * permukaan dari wallpaper, warna penekanan dari sampul lagu.
     */
    val artworkAccent: Boolean = true,
    /**
     * "Smooth motion blur": lapisan buram tipis yang mengikuti gerakan
     * (transisi layar + gulir cepat). Butuh Android 12+; dimatikan otomatis
     * saat reduce motion atau perangkat rendah memori.
     */
    val motionBlur: Boolean = true,
    /** Riwayat putar ikut mengisi antrean secara otomatis saat antrean menipis. */
    val historyAutoQueue: Boolean = true,
    /**
     * Penyesuaian waktu lirik dalam milidetik (positif = lirik maju).
     * Disimpan global, bukan per lagu: cukup untuk mengoreksi sumber lirik yang
     * konsisten bergeser beberapa ratus ms.
     */
    val lyricsOffsetMs: Int = 0,
    /** Provider lirik KuGou sebagai cadangan setelah LRCLIB. */
    val kugouEnabled: Boolean = true,
    /** Buang keheningan dari audio (processor SilenceSkippingAudioProcessor media3). */
    val skipSilence: Boolean = false,
    /** Mode agresif: lompat maju bila keheningan panjang tetap terdengar. */
    val skipSilenceInstant: Boolean = false,
    /** Kecepatan putar (0.5×–2.0×). */
    val playbackSpeed: Float = 1f,
    /** Geser nada dalam semitone (-12..+12); 0 = nada asli. */
    val pitchSemitones: Int = 0,
    /** Samakan keras lagu memakai loudness referensi dari YouTube. */
    val normalizeAudio: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val AUTOPLAY_RELATED = booleanPreferencesKey("autoplay_related")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val ACCENT_ARGB = intPreferencesKey("accent_argb")
        val FONT_KEY = stringPreferencesKey("font_key")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ARTWORK_ACCENT = booleanPreferencesKey("artwork_accent")
        val MOTION_BLUR = booleanPreferencesKey("motion_blur")
        val HISTORY_AUTO_QUEUE = booleanPreferencesKey("history_auto_queue")
        val LYRICS_OFFSET_MS = intPreferencesKey("lyrics_offset_ms")
        val KUGOU_ENABLED = booleanPreferencesKey("kugou_enabled")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val SKIP_SILENCE_INSTANT = booleanPreferencesKey("skip_silence_instant")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val PITCH_SEMITONES = intPreferencesKey("pitch_semitones")
        val NORMALIZE_AUDIO = booleanPreferencesKey("normalize_audio")
    }

    val settings: Flow<LyreonSettings> = context.lyreonDataStore.data.map { prefs ->
        LyreonSettings(
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            reduceMotion = prefs[Keys.REDUCE_MOTION] ?: false,
            audioQuality = prefs[Keys.AUDIO_QUALITY]?.let {
                runCatching { AudioQuality.valueOf(it) }.getOrNull()
            } ?: AudioQuality.BALANCED,
            autoplayRelated = prefs[Keys.AUTOPLAY_RELATED] ?: true,
            displayName = prefs[Keys.DISPLAY_NAME].orEmpty(),
            accentArgb = prefs[Keys.ACCENT_ARGB] ?: -1,
            fontKey = prefs[Keys.FONT_KEY] ?: "default",
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            artworkAccent = prefs[Keys.ARTWORK_ACCENT] ?: true,
            motionBlur = prefs[Keys.MOTION_BLUR] ?: true,
            historyAutoQueue = prefs[Keys.HISTORY_AUTO_QUEUE] ?: true,
            lyricsOffsetMs = prefs[Keys.LYRICS_OFFSET_MS] ?: 0,
            kugouEnabled = prefs[Keys.KUGOU_ENABLED] ?: true,
            skipSilence = prefs[Keys.SKIP_SILENCE] ?: false,
            skipSilenceInstant = prefs[Keys.SKIP_SILENCE_INSTANT] ?: false,
            playbackSpeed = (prefs[Keys.PLAYBACK_SPEED] ?: 1f).coerceIn(0.5f, 2f),
            pitchSemitones = (prefs[Keys.PITCH_SEMITONES] ?: 0).coerceIn(-12, 12),
            normalizeAudio = prefs[Keys.NORMALIZE_AUDIO] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.lyreonDataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setReduceMotion(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.REDUCE_MOTION] = value }
    }

    suspend fun setAudioQuality(value: AudioQuality) {
        context.lyreonDataStore.edit { it[Keys.AUDIO_QUALITY] = value.name }
    }

    suspend fun setAutoplayRelated(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.AUTOPLAY_RELATED] = value }
    }

    suspend fun setDisplayName(value: String) {
        context.lyreonDataStore.edit { it[Keys.DISPLAY_NAME] = value.trim().take(24) }
    }

    suspend fun setAccentArgb(value: Int) {
        context.lyreonDataStore.edit { it[Keys.ACCENT_ARGB] = value }
    }

    suspend fun setFontKey(value: String) {
        context.lyreonDataStore.edit { it[Keys.FONT_KEY] = value }
    }

    suspend fun setDynamicColor(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.DYNAMIC_COLOR] = value }
    }

    suspend fun setArtworkAccent(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.ARTWORK_ACCENT] = value }
    }

    suspend fun setMotionBlur(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.MOTION_BLUR] = value }
    }

    suspend fun setHistoryAutoQueue(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.HISTORY_AUTO_QUEUE] = value }
    }

    suspend fun setLyricsOffsetMs(value: Int) {
        // Batas wajar: ±10 detik — lebih dari itu pasti salah tekan.
        context.lyreonDataStore.edit { it[Keys.LYRICS_OFFSET_MS] = value.coerceIn(-10_000, 10_000) }
    }

    suspend fun setKugouEnabled(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.KUGOU_ENABLED] = value }
    }

    suspend fun setSkipSilence(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.SKIP_SILENCE] = value }
    }

    suspend fun setSkipSilenceInstant(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.SKIP_SILENCE_INSTANT] = value }
    }

    /** Kecepatan dibulatkan ke langkah 0.05 lalu dijepit 0.5×–2.0×. */
    suspend fun setPlaybackSpeed(value: Float) {
        val snapped = (kotlin.math.round(value * 20f) / 20f).coerceIn(0.5f, 2f)
        context.lyreonDataStore.edit { it[Keys.PLAYBACK_SPEED] = snapped }
    }

    suspend fun setPitchSemitones(value: Int) {
        context.lyreonDataStore.edit { it[Keys.PITCH_SEMITONES] = value.coerceIn(-12, 12) }
    }

    suspend fun setNormalizeAudio(value: Boolean) {
        context.lyreonDataStore.edit { it[Keys.NORMALIZE_AUDIO] = value }
    }
}
