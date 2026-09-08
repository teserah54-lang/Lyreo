/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Arsitektur tangga provider mengikuti FrancescoGrazioso/Meld (GPL-3.0):
 *   app/src/main/kotlin/com/metrolist/music/lyrics/{LyricsHelper,LyricsProviderRegistry}.kt
 * Metrolist Project (C) 2026 — lihat riwayat git upstream untuk kontributor.
 */
package com.lyreon.app.lyrics

import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Satu baris lirik dari berkas LRC (time-synced). */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * Hasil lirik untuk satu lagu.
 *
 * @param source nama provider yang menghasilkan (mis. "LRCLIB" / "KuGou") —
 *   ditampilkan di UI agar pengguna tahu dari mana liriknya datang.
 */
data class LyricsResult(
    val synced: List<LrcLine>,
    val plain: String?,
    val instrumental: Boolean,
    val source: String? = null,
) {
    val hasSynced: Boolean get() = synced.isNotEmpty()
    val isEmpty: Boolean get() = !hasSynced && plain.isNullOrBlank() && !instrumental

    fun withSource(name: String): LyricsResult = copy(source = name)
}

/** Parser format LRC: `[mm:ss.xx]teks` (duplikat timestamp disatukan). */
object LrcParser {
    private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]")

    fun parse(raw: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        raw.lines().forEach { line ->
            val matches = TIME_TAG.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            matches.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: 0L
                val sec = m.groupValues[2].toLongOrNull() ?: 0L
                val fracRaw = m.groupValues[3]
                val frac = when (fracRaw.length) {
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    3 -> fracRaw.toLongOrNull() ?: 0L
                    else -> 0L
                }
                lines.add(LrcLine(timeMs = (min * 60L + sec) * 1000L + frac, text = text))
            }
        }
        return lines.sortedBy { it.timeMs }.distinctBy { it.timeMs to it.text }
    }
}

/**
 * Lirik untuk satu lagu, dicari lewat **tangga provider**: LRCLIB dulu (cepat,
 * cakupan Barat luas), lalu KuGou (cakupan Asia luas). Provider pertama yang
 * mengembalikan baris tersinkron menang; bila tidak ada yang sinkron, hasil
 * terbaik (lirik polos / instrumental) tetap dipakai.
 *
 * Semua hasil di-cache per `videoId` — satu lagu satu pencarian jaringan.
 */
class LyricsRepository(
    settings: SettingsRepository? = null,
) {

    private val providers: List<LyreonLyricsProvider> = listOf(
        LrcLibProvider(),
        KuGouProvider(settings),
    ).sortedBy { it.priority }

    private val cache = ConcurrentHashMap<String, LyricsResult>()

    suspend fun lyrics(track: LyreonTrack): LyricsResult = withContext(Dispatchers.IO) {
        cache[track.videoId]?.let { return@withContext it }
        val result = runCatching { search(track) }.getOrElse { LyricsResult(emptyList(), null, false) }
        cache[track.videoId] = result
        result
    }

    private suspend fun search(track: LyreonTrack): LyricsResult {
        val query = buildQuery(track)
        var fallback: LyricsResult? = null
        for (provider in providers) {
            val enabled = runCatching { provider.isEnabled() }.getOrDefault(false)
            if (!enabled) continue
            val result = runCatching { provider.fetch(query) }.getOrNull() ?: continue
            if (result.hasSynced) return result
            if (fallback == null && !result.isEmpty) fallback = result
        }
        return fallback ?: LyricsResult(emptyList(), null, false)
    }

    /** Bersihkan judul khas YouTube ("(Official Music Video)", "[Lyric]", dll). */
    private fun buildQuery(track: LyreonTrack): LyricsQuery {
        var title = track.title
        var artist = track.artist

        // Banyak judul YouTube berbentuk "Artis - Judul"
        if (artist.isBlank() && title.contains(" - ")) {
            val parts = title.split(" - ", limit = 2)
            artist = parts[0].trim()
            title = parts.getOrElse(1) { title }.trim()
        }
        val cleaned = title
            .replace(Regex("\\((?i)(official|music|lyric|audio|video|mv|hd|4k)[^)]*\\)"), "")
            .replace(Regex("\\[(?i)(official|music|lyric|audio|video|mv|hd|4k)[^]]*]"), "")
            .replace(Regex("(?i)\\s*[-–—]\\s*(official|music video|lyric video|audio)$"), "")
            .trim()
        return LyricsQuery(
            videoId = track.videoId,
            title = cleaned.ifBlank { title },
            artist = artist.replace(Regex("(?i)\\s*-\\s*topic$"), "").trim(),
            durationSec = track.durationSec.toInt(),
        )
    }
}
