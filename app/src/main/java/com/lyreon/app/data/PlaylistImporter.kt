/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.local.LocalMusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Hasil impor berkas playlist (m3u/pls). */
data class PlaylistImportResult(
    val playlistName: String,
    val imported: List<LyreonTrack>,
    val skipped: Int,
)

/** Satu baris mentah dari berkas playlist: lokasi + metadata opsional. */
private data class RawEntry(
    val location: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
)

/**
 * Mengimpor playlist lokal (.m3u/.m3u8/.pls) menjadi daftar [LyreonTrack].
 *
 * Dua format didukung:
 *  - M3U (extended): `#EXTM3U` + `#EXTINF:<dur>,<Artist> - <Title>` diikuti satu
 *    baris lokasi (URL YouTube atau path berkas lokal).
 *  - PLS: berkas INI `[playlist]` dengan pasangan `FileN=`/`TitleN=`/`LengthN=`.
 *
 * Setiap baris di-resolve:
 *  - URL YouTube (watch/youtu.be/shorts/embed) → track YouTube; judul/artis
 *    diambil dari metadata bila ada, sisanya diisi pemutar saat lagu dimuat.
 *  - Path berkas lokal → dicocokkan ke perpustakaan lokal (MediaStore + folder
 *    SAF) lewat nama berkas; bila tidak ditemukan, baris dilewati (dihitung
 *    sebagai [PlaylistImportResult.skipped]).
 *
 * Impor bersifat defensif: gagal baca/kueri menghasilkan hasil kosong, tidak
 * pernah melempar ke pemanggil.
 */
class PlaylistImporter(
    private val context: Context,
    private val local: LocalMusicRepository,
) {
    suspend fun import(uri: Uri): PlaylistImportResult = withContext(Dispatchers.IO) {
        val fileName = displayNameOf(uri) ?: uri.lastPathSegment ?: "playlist"
        val base =
            fileName.substringBeforeLast('.', fileName).trim().ifBlank { "Playlist impor" }
        val text = readText(uri) ?: return@withContext PlaylistImportResult(base, emptyList(), 0)
        val raw = parse(text)
        val tracks = resolve(raw)
        PlaylistImportResult(base, tracks, raw.size - tracks.size)
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun readText(uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()?.toString(Charsets.UTF_8)?.removePrefix("\uFEFF")

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    private fun parse(text: String): List<RawEntry> {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val isPls = lines.any { it.equals("[playlist]", ignoreCase = true) }
        return if (isPls) parsePls(lines) else parseM3u(lines)
    }

    private fun parseM3u(lines: List<String>): List<RawEntry> {
        val out = mutableListOf<RawEntry>()
        var title = ""
        var artist = ""
        var duration = 0L
        for (line in lines) {
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val payload = line.substringAfter(':').trim()
                    duration = payload.substringBefore(',').trim().toLongOrNull() ?: 0L
                    val (a, t) = splitArtistTitle(payload.substringAfter(',', "").trim())
                    artist = a
                    title = t
                }
                line.startsWith("#") -> Unit // abaikan header & direktif lain
                else -> {
                    out += RawEntry(line, title, artist, duration)
                    title = ""
                    artist = ""
                    duration = 0L
                }
            }
        }
        return out
    }

    private fun parsePls(lines: List<String>): List<RawEntry> {
        val files = linkedMapOf<Int, String>()
        val titles = hashMapOf<Int, String>()
        val lengths = hashMapOf<Int, Long>()
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") ||
                line.startsWith("[")
            ) {
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase()
            val value = line.substring(eq + 1).trim()
            val num = key.filter { it.isDigit() }.toIntOrNull() ?: continue
            when {
                key.startsWith("file") -> files[num] = value
                key.startsWith("title") -> titles[num] = value
                key.startsWith("length") -> lengths[num] = value.toLongOrNull() ?: 0L
            }
        }
        return files.entries.sortedBy { it.key }.map { (num, loc) ->
            val (a, t) = splitArtistTitle(titles[num].orEmpty())
            RawEntry(loc, t, a, lengths[num] ?: 0L)
        }
    }

    /** "Artist - Title" → (artist, title); tanpa pemisah → ("", seluruhnya). */
    private fun splitArtistTitle(label: String): Pair<String, String> {
        val idx = label.lastIndexOf(" - ")
        return if (idx > 0) {
            label.substring(0, idx).trim() to label.substring(idx + 3).trim()
        } else {
            "" to label
        }
    }

    // ------------------------------------------------------------------
    // Resolusi → LyreonTrack
    // ------------------------------------------------------------------

    private suspend fun resolve(raw: List<RawEntry>): List<LyreonTrack> {
        val out = mutableListOf<LyreonTrack>()
        val seen = HashSet<String>()
        for (e in raw) {
            val track = youtubeTrack(e) ?: local.findByFileName(locationFileName(e.location))
            if (track != null && seen.add(track.videoId)) out += track
        }
        return out
    }

    private fun youtubeTrack(e: RawEntry): LyreonTrack? {
        val id = youtubeVideoIdOf(e.location) ?: return null
        return LyreonTrack(
            videoId = id,
            title = e.title,
            artist = e.artist,
            durationSec = e.durationSec,
        )
    }

    /** Nama berkas dari sebuah lokasi (path lokal atau URI `file://`). */
    private fun locationFileName(location: String): String {
        val raw = if (location.startsWith("file://", ignoreCase = true)) {
            runCatching { Uri.parse(location).lastPathSegment }.getOrNull() ?: location
        } else {
            location
        }
        return raw.substringAfterLast('/').substringAfterLast('\\').trim()
    }

    companion object {
        private val YT_HOST = Regex("(youtube\\.com|youtu\\.be)", RegexOption.IGNORE_CASE)
        private val YT_PATTERNS = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("/shorts/([A-Za-z0-9_-]{11})"),
            Regex("/embed/([A-Za-z0-9_-]{11})"),
        )

        /** Ekstrak videoId YouTube (11 karakter) dari URL; null bila bukan YouTube. */
        fun youtubeVideoIdOf(url: String): String? {
            if (!YT_HOST.containsMatchIn(url)) return null
            for (p in YT_PATTERNS) {
                p.find(url)?.groupValues?.get(1)?.let { return it }
            }
            return null
        }
    }
}
