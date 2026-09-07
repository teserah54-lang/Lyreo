/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data.model

import androidx.annotation.DrawableRes
import com.lyreon.app.R

/** Genre movements — desain asli dipertahankan, kini terhubung ke kueri YouTube. */
data class LyreonGenre(
    val id: String,
    val name: String,
    val subtitle: String,
    @DrawableRes val imageRes: Int,
    val searchQuery: String,
)

/** Editorial sections dari desain asli — tombol play kini menjalankan kueri nyata. */
data class EditorialSection(
    val id: String,
    val indexLabel: String,
    val title: String,
    val body: String,
    @DrawableRes val backgroundRes: Int,
    @DrawableRes val previewRes: Int,
    val searchQuery: String,
)

object LyreonArchive {

    val genres = listOf(
        LyreonGenre("g1", "JAZZ", "Nocturne brass & smoke", R.drawable.cover_jazz, "jazz classics vinyl"),
        LyreonGenre("g2", "ELECTRONIC", "Modular pulse fields", R.drawable.cover_electronic, "electronic ambient mix"),
        LyreonGenre("g3", "CLASSICAL", "Strings & piano", R.drawable.cover_classical, "classical chamber music"),
        LyreonGenre("g4", "INDIE", "Amplifier residue", R.drawable.cover_indie, "indie rock essentials"),
        LyreonGenre("g5", "HIP-HOP", "Studio midnight gold", R.drawable.cover_hiphop, "hip hop classics"),
        LyreonGenre("g6", "AMBIENT", "Fog architecture", R.drawable.cover_ambient, "ambient sleep music"),
    )

    val editorials = listOf(
        EditorialSection(
            id = "e1",
            indexLabel = "01 / MALAM",
            title = "Lagu untuk Tengah Malam",
            body = "Ada kata-kata yang tak sempat terucap, dan melodi yang mengatakannya untukmu. Jazz lembut dan brass hangat untuk malam yang panjang.",
            backgroundRes = R.drawable.bg_vinyl,
            previewRes = R.drawable.cover_jazz,
            searchQuery = "late night jazz lounge",
        ),
        EditorialSection(
            id = "e2",
            indexLabel = "02 / PERJALANAN",
            title = "Suara Jalanan Malam",
            body = "Lo-fi, synth halus, dan hembusan cassette tua. Teman setia untuk perjalanan pulang, tugas yang belum selesai, dan pikiran yang berkelana.",
            backgroundRes = R.drawable.bg_stage,
            previewRes = R.drawable.cover_indie,
            searchQuery = "indie lo-fi night drive",
        ),
        EditorialSection(
            id = "e3",
            indexLabel = "03 / FOKUS",
            title = "Ritme untuk Berkarya",
            body = "Elektronik modular dan beat stabil yang menjaga tanganmu tetap menulis, menggambar, dan mencipta. Musik yang bekerja bersamamu, tanpa menuntut perhatian.",
            backgroundRes = R.drawable.bg_waves,
            previewRes = R.drawable.cover_electronic,
            searchQuery = "modular synth electronic mix",
        ),
        EditorialSection(
            id = "e4",
            indexLabel = "04 / RASA",
            title = "Senar untuk Hati",
            body = "Biola, cello, dan piano yang bicara lebih jujur daripada kata. Untuk hari-hari ketika kamu butuh didengar tanpa ditanya apa-apa.",
            backgroundRes = R.drawable.bg_theater,
            previewRes = R.drawable.cover_classical,
            searchQuery = "emotional classical strings",
        ),
        EditorialSection(
            id = "e5",
            indexLabel = "05 / JEDA",
            title = "Ruang untuk Bernapas",
            body = "Ambient yang perlahan membentang - musik untuk meredam bisingnya dunia dan menemukan kembali ritme napasmu sendiri.",
            backgroundRes = R.drawable.bg_headphones,
            previewRes = R.drawable.cover_ambient,
            searchQuery = "deep ambient focus",
        ),
        EditorialSection(
            id = "e6",
            indexLabel = "06 / RAGU",
            title = "Piano dan Hening",
            body = "Tiap tuts adalah jeda, tiap jeda adalah cerita. Neoklasik solo piano untuk malam ketika kamu ingin merasa, bukan hanya mendengar.",
            backgroundRes = R.drawable.bg_piano,
            previewRes = R.drawable.cover_classical,
            searchQuery = "solo piano neoclassical",
        ),
    )
}
