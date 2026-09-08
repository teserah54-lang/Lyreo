/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Bentuk antarmuka mengikuti FrancescoGrazioso/Meld (GPL-3.0):
 *   app/src/main/kotlin/com/metrolist/music/lyrics/LyricsProvider.kt
 * Metrolist Project (C) 2026 — lihat riwayat git upstream untuk kontributor.
 * Disesuaikan untuk Lyreon: tanpa Context/Hilt, hasil berupa LyricsResult.
 */
package com.lyreon.app.lyrics

/** Permintaan lirik yang sudah dibersihkan dari embel-embel judul YouTube. */
data class LyricsQuery(
    val videoId: String,
    val title: String,
    val artist: String,
    /** Durasi dalam detik; 0/-1 = tidak diketahui (provider boleh mengabaikan filter durasi). */
    val durationSec: Int,
)

/**
 * Satu sumber lirik. [LyricsRepository] mencoba provider berurutan dan berhenti
 * pada hasil pertama yang punya baris tersinkron (time-synced).
 *
 * Kontrak:
 * - jangan melempar exception keluar — kembalikan `null` bila tidak menemukan;
 * - hormati filter durasi supaya tidak mengambil versi live/remix;
 * - beri `source` pada hasil agar UI bisa menyebut penyedianya.
 */
interface LyreonLyricsProvider {
    /** Nama tampilan (dipakai pada label "Sumber: …"). */
    val name: String

    /** Urutan percobaan; lebih kecil = dicoba lebih dulu. */
    val priority: Int

    /** Boleh dimatikan pengguna lewat Pengaturan. */
    suspend fun isEnabled(): Boolean = true

    /** Ambil lirik; `null` bila provider ini tidak punya apa-apa untuk lagu ini. */
    suspend fun fetch(query: LyricsQuery): LyricsResult?
}
