/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data.taste

import java.util.Locale

/**
 * Penganalisis teks musik — memetik sinyal selera dari judul, deskripsi,
 * dan tag video YouTube:
 *
 *  - MODIFIER gaya ("speed up", "reverb", "slowed", "nightcore", "8d audio", …)
 *    yang sering muncul di judul/deskripsi rilisan remix.
 *  - GENRE ("pop", "dangdut", "phonk", "afrobeats", …) dari judul/query.
 *  - HASHTAG dari deskripsi video ("#sadvibes #speedup"), persis seperti yang
 *    ditulis kreator — sesuai cara YouTube menandai konteksnya.
 *
 * Semua pencocokan case-insensitive & multi-kata (di-normalisasi spasi tunggal).
 */
object MusicTextAnalyzer {

    /** Frasa gaya/rilisan — ditulis bentuk kanonik (tampil) → alias pencocokan. */
    private val MODIFIERS: List<Pair<String, List<String>>> = listOf(
        "speed up" to listOf("speed up", "sped up", "speedup", "spedup", "sped"),
        "slowed" to listOf("slowed", "slow", "slowmo"),
        "reverb" to listOf("reverb", "echo"),
        "nightcore" to listOf("nightcore"),
        "daycore" to listOf("daycore"),
        "8d audio" to listOf("8d audio", "8d", "16d"),
        "bass boosted" to listOf("bass boosted", "bassboosted"),
        "acoustic" to listOf("acoustic", "akustik"),
        "piano version" to listOf("piano version", "piano cover"),
        "violin" to listOf("violin", "biola"),
        "cover" to listOf("cover", "covers"),
        "live" to listOf("live performance", "live at", "live in", "(live", " live"),
        "remix" to listOf("remix", "remixed"),
        "sped up + reverb" to listOf("sped up + reverb", "speed up + reverb", "sped up and reverb"),
        "slowed + reverb" to listOf("slowed + reverb", "slowed and reverb"),
        "instrumental" to listOf("instrumental"),
        "karaoke" to listOf("karaoke"),
        "orchestral" to listOf("orchestral", "orchestra"),
        "tiktok version" to listOf("tiktok version", "tiktok remix", "tiktok song"),
        "full album" to listOf("full album", "fullalbum"),
        "lyrics video" to listOf("lyric video", "lyrics video", "lyrics"),
        "lofi remix" to listOf("lofi remix", "lofi version", "lo-fi remix", "chill version"),
        "edit" to listOf("edit audio", "tiktok edit"),
        "phonk remix" to listOf("phonk remix", "drift phonk"),
        "mashup" to listOf("mashup"),
        "dj version" to listOf("dj version", "dj remix", "jedag jedug"),
    )

    /** Genre yang dikenali (bentuk kanonik tampil → alias pencocokan). */
    private val GENRES: List<Pair<String, List<String>>> = listOf(
        "pop" to listOf("pop"),
        "rock" to listOf("rock"),
        "pop punk" to listOf("pop punk", "poppunk"),
        "metal" to listOf("metal", "metalcore", "deathcore"),
        "indie" to listOf("indie"),
        "dangdut" to listOf("dangdut"),
        "koplo" to listOf("koplo"),
        "campursari" to listOf("campursari"),
        "melayu" to listOf("melayu", "malay"),
        "kpop" to listOf("kpop", "k-pop", "kpop in", "k pop"),
        "jpop" to listOf("jpop", "j-pop", "j pop", "city pop"),
        "anisong" to listOf("anisong", "anime song", "anime ost", "opening anime"),
        "bollywood" to listOf("bollywood", "hindi song", "punjabi"),
        "lofi" to listOf("lofi", "lo-fi", "lo fi"),
        "phonk" to listOf("phonk"),
        "edm" to listOf("edm", "electronic", "house music", "dance music"),
        "techno" to listOf("techno"),
        "dubstep" to listOf("dubstep"),
        "trap" to listOf("trap"),
        "hiphop" to listOf("hiphop", "hip-hop", "hip hop", "rap", "rapper"),
        "rnb" to listOf("rnb", "r&b", "soul"),
        "jazz" to listOf("jazz"),
        "blues" to listOf("blues"),
        "country" to listOf("country"),
        "folk" to listOf("folk", "folklore"),
        "acoustic" to listOf("acoustic session", "akustik"),
        "ballad" to listOf("ballad", "balada"),
        "sad" to listOf("sad song", "sadvibes", "sedih", "galau", "melancholy", "heartbreak", "broken heart"),
        "chill" to listOf("chill", "chillout", "chill vibes", "relax"),
        "latin" to listOf("latin", "reggaeton", "bachata", "salsa"),
        "afrobeats" to listOf("afrobeats", "afrobeat", "afro"),
        "amapiano" to listOf("amapiano"),
        "arabic" to listOf("arabic", "arab", "nasheed", "nasyid", "qasidah", "sholawat", "fairuz"),
        "reggae" to listOf("reggae", "ska"),
        "classical" to listOf("classical", "klasik", "symphony"),
        "gospel" to listOf("gospel", "rohani", "worship"),
        "funk" to listOf("funk", "funkot", "brazilian funk"),
        "ost" to listOf("soundtrack", "ost", "theme song", "original score"),
        "hardcore" to listOf("hardcore", "hardstyle"),
        "vocaloid" to listOf("vocaloid", "hatsune miku", "utaite"),
        "dj" to listOf("dj ", "disko remix"),
        "kids" to listOf("nursery", "lagu anak"),
    )

    private val HASHTAG_RE = Regex("#([\\p{L}\\p{N}_]{2,32})")
    private val MULTI_SPACE = Regex("\\s+")

    private fun norm(text: String): String =
        " " + text.lowercase(Locale.ROOT)
            .replace("[", "(").replace("]", ")")
            .replace(MULTI_SPACE, " ") + " "

    /** Modifier gaya yang muncul pada teks (judul/deskripsi), bentuk kanonik. */
    fun modifiersOf(text: String): List<String> {
        val n = norm(text)
        return MODIFIERS.filter { (_, aliases) -> aliases.any { n.contains(it) } }.map { it.first }
    }

    /** Genre yang muncul pada teks (judul/query), bentuk kanonik. */
    fun genresOf(text: String): List<String> {
        val n = norm(text)
        // Cegah false-positif pendek: alias ≥4 huruf cocok substring; yang pendek ("pop","rap","ost","dj") kata utuh.
        return GENRES.filter { (canon, aliases) ->
            aliases.any { alias ->
                if (alias.length <= 3) n.contains(" $alias ") else n.contains(alias)
            }
        }.map { it.first }
    }

    /** Hashtag dari deskripsi video — tanpa '#', lowercase ("#SadVibes" → "sadvibes"). */
    fun hashtagsOf(text: String): List<String> =
        HASHTAG_RE.findAll(text).map { it.groupValues[1].lowercase(Locale.ROOT) }.distinct().toList()

    /** Rapikan nama kanal: buang suffix “- Topic”, “VEVO”, “Official”. */
    fun cleanArtist(raw: String): String {
        var s = raw.trim()
        for (suffix in listOf(" - Topic", "- Topic", " - topic")) {
            if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
        }
        if (s.endsWith("vevo", true)) s = s.dropLast(4).trim().trimEnd('-', ' ')
        if (s.endsWith("official", true)) s = s.dropLast(8).trim().trimEnd('-', ' ')
        return s.ifBlank { raw.trim() }
    }

    /** Token kata bermakna dari judul untuk kemiripan judul (buang stopword umum). */
    fun titleWords(title: String): Set<String> {
        val stop = setOf(
            "the", "a", "an", "of", "to", "and", "di", "dan", "yang", "dari", "untuk",
            "official", "video", "music", "audio", "lyrics", "lyric", "hq", "hd", "mv",
            "feat", "ft", "with", "ost", "soundtrack", "version", "edit", "full",
        )
        return norm(title)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in stop }
            .toSet()
    }

    /** Jumlah kata judul yang tumpang-tindih (kemiripan sederhana antar lagu). */
    fun titleOverlap(a: Set<String>, b: Set<String>): Int = a.intersect(b).size

    private val ALL_MODIFIER_ALIASES: List<String> by lazy {
        MODIFIERS.flatMap { it.second }.distinct().sortedByDescending { it.length }
    }

    /** Judul inti tanpa embel gaya/rilisan/kurung — kunci deteksi reupload duplikat. */
    fun coreTitle(title: String): String {
        var n = norm(title)
        for (alias in ALL_MODIFIER_ALIASES) n = n.replace(alias, " ")
        n = n.replace(Regex("\\([^)]*\\)"), " ")
        n = n.replace(Regex("\\[[^]]*]"), " ")
        n = n.replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        return n.replace(MULTI_SPACE, " ").trim()
    }
}
