/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.player.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Loudness referensi (dB) per video, dipanen dari respons InnerTube saat stream
 * diselesaikan ([com.lyreon.app.player.ResolvingDataSource]) dan dipakai
 * [com.lyreon.app.player.PlaybackService] untuk normalisasi volume per lagu.
 *
 * Sengaja berupa penyimpanan in-memory (bukan Room): nilainya berasal dari
 * respons jaringan yang bisa berubah antar-klien, dan cache URL stream sudah
 * punya masa berlaku sendiri (6 jam). Hilang saat proses mati = tidak masalah,
 * lagu berikutnya mengisinya lagi.
 */
object LoudnessStore {

    private val values = ConcurrentHashMap<String, Float>()

    private val _latest = MutableStateFlow<Pair<String, Float>?>(null)

    /** Pasangan (videoId, loudnessDb) terakhir yang tercatat. */
    val latest: StateFlow<Pair<String, Float>?> = _latest

    fun record(videoId: String, loudnessDb: Float) {
        values[videoId] = loudnessDb
        _latest.value = videoId to loudnessDb
    }

    fun loudnessFor(videoId: String): Float? = values[videoId]

    /** Dipakai saat pengguna membuang cache stream agar nilai lama tidak menempel. */
    fun clear() {
        values.clear()
        _latest.value = null
    }
}
