/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data.model

import kotlinx.serialization.Serializable

/**
 * Satu unit musik di Lyreon — selalu mereferensikan video YouTube.
 * [videoId] adalah identitas kanonik di seluruh app (DB, antrean, unduhan).
 */
@Serializable
data class LyreonTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Long = 0L,
    val thumbnailUrl: String = "",
) {
    val watchUrl: String get() = "https://www.youtube.com/watch?v=$videoId"

    /**
     * Lagu dari penyimpanan perangkat (mp3/m4a/ogg/…), bukan YouTube.
     * Identitasnya ber-prefiks [LOCAL_ID_PREFIX] + MediaStore ID, sehingga
     * konten URI-nya selalu bisa direkonstruksi tanpa kolom DB tambahan.
     */
    val isLocal: Boolean get() = videoId.startsWith(LOCAL_ID_PREFIX)

    companion object {
        const val LOCAL_ID_PREFIX = "local:"
    }
}

/** Filter pencarian YouTube Music (dipetakan ke NewPipeExtractor content filter). */
enum class SearchFilter(val label: String, val newPipeFilter: String) {
    ALL("SEMUA", "all"),
    SONGS("LAGU", "music_songs"),
    VIDEOS("VIDEO", "music_videos"),
    ALBUMS("ALBUM", "music_albums"),
    /**
     * Artis/band. Extractor NewPipe tidak selalu punya filter "music_artists",
     * jadi jalur utama filter ini adalah pencarian YouTube Music
     * (`YouTubeRepository.musicSearch`) yang mengembalikan kanal artis lengkap
     * dengan `browseId` — itu yang dipakai membuka halaman artis khusus.
     */
    ARTISTS("ARTIS", "music_artists"),
    PLAYLISTS("PLAYLIST", "music_playlists"),
    // Podcast di YouTube tersimpan sebagai playlist episode → filter "playlists"
    PODCASTS("PODCAST", "playlists"),
}

/** Referensi playlist YouTube dari hasil pencarian (untuk dibuka/diimpor). */
data class YtPlaylist(val url: String, val name: String, val uploader: String, val thumbnailUrl: String, val streamCount: Long)

fun formatDuration(sec: Long): String {
    if (sec <= 0) return "--:--"
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatMs(ms: Long): String = formatDuration((ms / 1000L).coerceAtLeast(0L))
