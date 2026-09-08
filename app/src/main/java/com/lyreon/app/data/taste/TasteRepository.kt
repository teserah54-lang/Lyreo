/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.data.taste

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lyreon.app.data.model.LyreonTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min

private val Context.tasteDataStore by preferencesDataStore(name = "lyreon_taste")

/**
 * Profil selera pengguna — dibangun murni dari sinyal implisit di perangkat:
 *  - artis  : afinitas nama artis/kanal (berbobot dari play, search, like)
 *  - tokens : modifier gaya ("speed up", "reverb", "nightcore", …)
 *  - genres : genre terdeteksi dari judul/query/tag
 *  - hashtags: tagar dari deskripsi/tag video yang sering diputar ("sadvibes")
 *  - playCounts: berapa kali tiap lagu diputar
 *  - durasi : preferensi panjang lagu (bucket pendek/sedang/panjang)
 *  - searches: riwayat query mentah terakhir
 */
data class TasteProfile(
    val artists: Map<String, Float> = emptyMap(),
    val tokens: Map<String, Float> = emptyMap(),
    val genres: Map<String, Float> = emptyMap(),
    val hashtags: Map<String, Float> = emptyMap(),
    val playCounts: Map<String, Int> = emptyMap(),
    val durations: Map<String, Int> = emptyMap(),
    val searches: List<String> = emptyList(),
    val updatedAtMs: Long = 0L,
) {
    /** Profil dianggap cukup kaya setelah ≥3 putaran bermakna. */
    val isRich: Boolean get() = playCounts.values.sum() >= 3 || artists.isNotEmpty()

    fun topArtists(n: Int = 5): List<String> = artists.entries.sortedByDescending { it.value }.take(n).map { it.key }
    fun topGenres(n: Int = 4): List<String> = genres.entries.sortedByDescending { it.value }.take(n).map { it.key }
    fun topTokens(n: Int = 6): List<String> = tokens.entries.sortedByDescending { it.value }.take(n).map { it.key }
    fun topHashtags(n: Int = 6): List<String> = hashtags.entries.sortedByDescending { it.value }.take(n).map { it.key }
}

class TasteRepository(private val context: Context) {

    private object Keys {
        val PROFILE = stringPreferencesKey("profile_json")
    }

    private val _profile = MutableStateFlow(TasteProfile())
    val profile: StateFlow<TasteProfile> = _profile.asStateFlow()

    /** Dipanggil sekali dari ServiceLocator (init ringan, IO di caller). */
    suspend fun warm() {
        val json = runCatching {
            context.tasteDataStore.data.first()[Keys.PROFILE]
        }.getOrNull()
        if (!json.isNullOrBlank()) {
            _profile.value = decode(json)
        }
    }

    // ------------------------------------------------------------------
    // Pencatatan sinyal
    // ------------------------------------------------------------------

    /**
     * Satu lagu selesai/ditinggalkan.
     * @param completionRatio 0..1 — bagian lagu yang benar-benar didengar.
     *   Selesai penuh ≈ 1.0 (bobot besar), skip cepat ≈ 0.1 (hampir diabaikan).
     */
    suspend fun recordPlay(track: LyreonTrack, completionRatio: Float) {
        val w = (0.25f + 2.75f * completionRatio.coerceIn(0f, 1f))
        mutate { p ->
            val artist = MusicTextAnalyzer.cleanArtist(track.artist).lowercase(Locale.ROOT)
            val mods = MusicTextAnalyzer.modifiersOf(track.title)
            val gens = MusicTextAnalyzer.genresOf(track.title + " " + track.artist)
            val bucket = durationBucket(track.durationSec)
            p.copy(
                artists = bump(p.artists, artist, w),
                tokens = bumpAll(p.tokens, mods, w * 0.9f),
                genres = bumpAll(p.genres, gens, w * 0.7f),
                playCounts = p.playCounts + (track.videoId to (p.playCounts[track.videoId] ?: 0) + 1),
                durations = bucket?.let { bump(p.durations.mapValues { e -> e.value.toFloat() }, it, 1f)
                    .mapValues { e -> e.value.toInt() } } ?: p.durations,
                updatedAtMs = System.currentTimeMillis(),
            ).decayedIfHuge()
        }
    }

    /** Query pencarian = sinyal kuat niat pengguna. */
    suspend fun recordSearch(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        mutate { p ->
            val gens = MusicTextAnalyzer.genresOf(q)
            val mods = MusicTextAnalyzer.modifiersOf(q)
            p.copy(
                tokens = bumpAll(p.tokens, mods, 1.2f),
                genres = bumpAll(p.genres, gens, 1.0f),
                searches = (listOf(q) + p.searches.filter { !it.equals(q, true) }).take(40),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** Lagu disukai - sinyal paling tegas. */
    suspend fun recordLike(track: LyreonTrack) {
        mutate { p ->
            val artist = MusicTextAnalyzer.cleanArtist(track.artist).lowercase(Locale.ROOT)
            p.copy(
                artists = bump(p.artists, artist, 4f),
                tokens = bumpAll(p.tokens, MusicTextAnalyzer.modifiersOf(track.title), 3f),
                genres = bumpAll(p.genres, MusicTextAnalyzer.genresOf(track.title + " " + track.artist), 2.5f),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** Tag/hashtag/kategori dari video yang menjadi benih antrean. */
    suspend fun recordTags(tags: List<String>, descriptionHashtags: List<String>, category: String) {
        if (tags.isEmpty() && descriptionHashtags.isEmpty() && category.isBlank()) return
        mutate { p ->
            val catGenres = MusicTextAnalyzer.genresOf(category.replace('-', ' '))
            p.copy(
                hashtags = bumpAll(p.hashtags, (tags + descriptionHashtags).map {
                    it.lowercase(Locale.ROOT).removePrefix("#")
                }, 1.3f),
                genres = bumpAll(p.genres, catGenres + MusicTextAnalyzer.genresOf(tags.joinToString(" ")), 0.8f),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    // ------------------------------------------------------------------
    // Query persona & scoring
    // ------------------------------------------------------------------

    /**
     * Query pencarian yang merepresentasikan "selera saat ini" — gabungan
     * hashtag + token + artis terkuat. Contoh: "sadvibes speed up reverb".
     */
    fun personalityQuery(): String? {
        val p = _profile.value
        if (!p.isRich) return null
        val parts = LinkedHashSet<String>()
        p.topHashtags(2).forEach { parts += it }
        p.topTokens(2).forEach { parts += it }
        p.topGenres(1).forEach { parts += it }
        p.topArtists(1).forEach { parts += it }
        if (parts.isEmpty()) return null
        return parts.joinToString(" ").take(60)
    }

    /** Label vibe untuk chips UI — hashtag & token terkuat (tampil apa adanya). */
    fun personalityChips(n: Int = 6): List<String> {
        val p = _profile.value
        val out = LinkedHashSet<String>()
        p.topHashtags(n).forEach { out += "#$it" }
        p.topTokens(n).forEach { out += it }
        return out.take(n)
    }

    /**
     * Skor kecocokan lagu dengan profil.
     * Bobot: artis 3.0 · gaya 2.0/token · genre 1.2 · hashtag (judul) 1.5
     *        + dorongan kecil familiar (ln playCount).
     */
    fun scoreTrack(track: LyreonTrack): Float {
        val p = _profile.value
        if (!p.isRich) return 0f
        val artist = MusicTextAnalyzer.cleanArtist(track.artist).lowercase(Locale.ROOT)
        var score = 0f
        p.artists[artist]?.let { score += 3f * it }
        val mods = MusicTextAnalyzer.modifiersOf(track.title)
        mods.forEach { m -> p.tokens[m]?.let { score += 2f * it } }
        val gens = MusicTextAnalyzer.genresOf(track.title + " " + track.artist)
        gens.forEach { g -> p.genres[g]?.let { score += 1.2f * it } }
        val titleNorm = track.title.lowercase(Locale.ROOT)
        // Batasi 40 tagar terkuat — CPU tetap ringan di perangkat lemah
        if (p.hashtags.isNotEmpty()) {
            p.hashtags.entries.sortedByDescending { it.value }.take(40).forEach { (tag, w) ->
                if (tag.length >= 4 && titleNorm.contains(tag)) score += 1.5f * w
            }
        }
        val plays = p.playCounts[track.videoId] ?: 0
        if (plays > 0) score += 0.3f * ln(plays + 1f)
        return min(score, 40f)
    }

    /**
     * Pilih kandidat yang bervariasi: skor selera + kemiripan judul benih,
     * dengan BATAS per artis (maxPerArtist) supaya antrean tidak diisi
     * lagu itu-itu saja — cara kerja rekomendasi yang terasa seperti YouTube.
     */
    fun diversePick(
        candidates: List<LyreonTrack>,
        limit: Int,
        existingIds: Set<String>,
        seedTitle: String,
        seedArtist: String,
        maxPerArtist: Int = 3,
        maxPerCoreTitle: Int = 1,
    ): List<LyreonTrack> {
        val seedWords = MusicTextAnalyzer.titleWords(seedTitle)
        val seedArtistClean = MusicTextAnalyzer.cleanArtist(seedArtist).lowercase(Locale.ROOT)
        val seen = HashSet(existingIds)
        val scored = candidates
            .asSequence()
            .filter { !it.isLocal }
            .filter { it.videoId !in seen }
            .distinctBy { it.videoId }
            .map { t ->
                val overlap = MusicTextAnalyzer.titleOverlap(seedWords, MusicTextAnalyzer.titleWords(t.title))
                val sameArtistAsSeed = MusicTextAnalyzer.cleanArtist(t.artist)
                    .lowercase(Locale.ROOT) == seedArtistClean
                val s = scoreTrack(t) + 1.2f * overlap + (if (sameArtistAsSeed) -1.5f else 0.5f)
                t to s
            }
            .sortedByDescending { it.second }
            .toList()

        val out = ArrayList<LyreonTrack>(limit)
        val added = HashSet<String>()
        val perArtist = HashMap<String, Int>()
        val perCoreTitle = HashMap<String, Int>()
        fun artistOf(t: LyreonTrack) = MusicTextAnalyzer.cleanArtist(t.artist).lowercase(Locale.ROOT)
        fun coreTitleOf(t: LyreonTrack) = MusicTextAnalyzer.coreTitle(t.title)

        for ((t, _) in scored) {
            if (out.size >= limit) break
            val a = artistOf(t)
            val ct = coreTitleOf(t)
            val nArtist = perArtist.getOrDefault(a, 0)
            val nTitle = perCoreTitle.getOrDefault(ct, 0)
            // Cap ganda: per-artis (channel beda) DAN per-judul-inti (cegah reupload
            // "speed up reverb"/"tiktok version"/dst. dari lagu SAMA banjirin antrean)
            if (nArtist >= maxPerArtist || (ct.isNotBlank() && nTitle >= maxPerCoreTitle)) continue
            perArtist[a] = nArtist + 1
            if (ct.isNotBlank()) perCoreTitle[ct] = nTitle + 1
            added += t.videoId
            out += t
        }
        // Relaksasi: kalau cap bikin antrean kurang dari limit, isi sisanya (cap diabaikan)
        if (out.size < limit) {
            for ((t, _) in scored) {
                if (out.size >= limit) break
                if (t.videoId in added) continue
                added += t.videoId
                out += t
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Persistensi
    // ------------------------------------------------------------------

    private suspend fun mutate(block: (TasteProfile) -> TasteProfile) {
        val updated = _profile.value.trimmed().let(block).trimmed()
        _profile.value = updated
        runCatching {
            context.tasteDataStore.edit { it[Keys.PROFILE] = encode(updated) }
        }
    }

    private fun bump(map: Map<String, Float>, key: String, w: Float): Map<String, Float> {
        if (key.isBlank()) return map
        return map + (key to ((map[key] ?: 0f) + w))
    }

    private fun bumpAll(map: Map<String, Float>, keys: List<String>, w: Float): Map<String, Float> {
        var m = map
        keys.forEach { m = bump(m, it, w) }
        return m
    }

    private fun durationBucket(sec: Long): String? = when {
        sec <= 0L -> null
        sec < 150L -> "short"
        sec < 330L -> "medium"
        else -> "long"
    }

    /** Decay halus saat sinyal menumpuk besar — selera baru cepat terasa. */
    private fun TasteProfile.decayedIfHuge(): TasteProfile {
        val total = artists.values.sum()
        if (total < 320f) return this
        val f = 0.9f
        return copy(
            artists = artists.mapValues { it.value * f },
            tokens = tokens.mapValues { it.value * f },
            genres = genres.mapValues { it.value * f },
            hashtags = hashtags.mapValues { it.value * f },
        )
    }

    /** Batasi ukuran peta (buang entri terlemah) agar JSON tetap ramping. */
    private fun TasteProfile.trimmed(): TasteProfile = copy(
        artists = artists.entries.sortedByDescending { it.value }.take(140).associate { it.toPair() },
        tokens = tokens.entries.sortedByDescending { it.value }.take(100).associate { it.toPair() },
        genres = genres.entries.sortedByDescending { it.value }.take(60).associate { it.toPair() },
        hashtags = hashtags.entries.sortedByDescending { it.value }.take(100).associate { it.toPair() },
        playCounts = playCounts.entries
            .drop((playCounts.size - 400).coerceAtLeast(0))
            .associate { it.toPair() },
        searches = searches.take(40),
    )

    private fun encode(p: TasteProfile): String {
        fun Map<String, Float>.toJson() = JSONObject().also { o -> forEach { (k, v) -> o.put(k, v.toDouble()) } }
        fun Map<String, Int>.toJsonI() = JSONObject().also { o -> forEach { (k, v) -> o.put(k, v) } }
        return JSONObject()
            .put("artists", p.artists.toJson())
            .put("tokens", p.tokens.toJson())
            .put("genres", p.genres.toJson())
            .put("hashtags", p.hashtags.toJson())
            .put("playCounts", p.playCounts.toJsonI())
            .put("durations", p.durations.toJsonI())
            .put("searches", org.json.JSONArray().also { a -> p.searches.forEach { a.put(it) } })
            .put("updatedAtMs", p.updatedAtMs)
            .toString()
    }

    private fun decode(json: String): TasteProfile {
        return runCatching {
            val o = JSONObject(json)
            fun f(key: String): Map<String, Float> {
                val j = o.optJSONObject(key) ?: return emptyMap()
                return j.keys().asSequence().associateWith { j.optDouble(it, 0.0).toFloat() }
            }
            fun i(key: String): Map<String, Int> {
                val j = o.optJSONObject(key) ?: return emptyMap()
                return j.keys().asSequence().associateWith { j.optInt(it, 0) }
            }
            val arr = o.optJSONArray("searches")
            TasteProfile(
                artists = f("artists"),
                tokens = f("tokens"),
                genres = f("genres"),
                hashtags = f("hashtags"),
                playCounts = i("playCounts"),
                durations = i("durations"),
                searches = arr?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                updatedAtMs = o.optLong("updatedAtMs", 0L),
            )
        }.getOrDefault(TasteProfile())
    }
}
