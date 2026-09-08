/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.widget

import android.content.Context

/**
 * Snapshot kecil yang dibutuhkan widget home screen. Sengaja dipisah dari
 * [com.lyreon.app.player.PlayerUiState] supaya widget tidak perlu menarik
 * dependensi ke antrean penuh atau media3.
 */
data class WidgetSnapshot(
    val title: String = "",
    val artist: String = "",
    val artUrl: String = "",
    val isPlaying: Boolean = false,
    val hasTrack: Boolean = false,
)

/**
 * Jembatan satu arah: pemutar menulis snapshot di sini, widget membacanya saat
 * dirender. Proses widget dan layanan pemutaran adalah proses yang sama, jadi
 * cukup sebuah singleton; bila proses mati, widget menampilkan snapshot terakhir
 * (atau keadaan kosong) tanpa crash.
 */
object WidgetState {

    @Volatile
    var snapshot: WidgetSnapshot = WidgetSnapshot()
        private set

    fun publish(context: Context, next: WidgetSnapshot) {
        if (snapshot == next) return
        snapshot = next
        PlayerWidgetReceiver.refresh(context)
    }
}
