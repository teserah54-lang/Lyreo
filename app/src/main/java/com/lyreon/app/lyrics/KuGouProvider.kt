/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Diadaptasi dari FrancescoGrazioso/Meld (GPL-3.0):
 *   kugou/src/main/kotlin/com/metrolist/kugou/KuGou.kt
 *   kugou/src/main/kotlin/com/metrolist/kugou/models/ (Keyword, SearchSongResponse,
 *   SearchLyricsResponse, DownloadLyricsResponse)
 *   app/src/main/kotlin/com/metrolist/music/lyrics/KuGouLyricsProvider.kt
 * Metrolist Project (C) 2026 — lihat riwayat git upstream untuk kontributor.
 *
 * Perubahan Lyreon:
 * - Ktor + kotlinx.serialization diganti OkHttp + org.json (tidak menambah
 *   dependensi baru); endpoint, parameter, toleransi durasi, normalisasi kata
 *   kunci, dan pemotongan kepala/ekor dipertahankan apa adanya.
 * - Pemotongan ekor dihitung dari daftar yang sudah dipotong kepalanya
 *   (upstream memakai indeks daftar awal) agar tidak mungkin keluar batas.
 */
package com.lyreon.app.lyrics

import com.lyreon.app.data.settings.SettingsRepository
import com.lyreon.app.yt.LyreonHttp
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

/**
 * Provider KuGou — cakupan luas (termasuk lagu Asia) dan hampir selalu
 * time-synced. Dicoba setelah LRCLIB; bisa dimatikan di Pengaturan.
 */
class KuGouProvider(
    private val settings: SettingsRepository? = null,
) : LyreonLyricsProvider {

    override val name: String = "KuGou"
    override val priority: Int = 20

    override suspend fun isEnabled(): Boolean =
        settings?.settings?.first()?.kugouEnabled ?: true

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        val raw = getLyrics(query.title, query.artist, query.durationSec) ?: return null
        val lines = LrcParser.parse(raw)
        if (lines.isEmpty()) return null
        return LyricsResult(synced = lines, plain = null, instrumental = false).withSource(name)
    }

    // ------------------------------------------------------------------
    // Jalur KuGou: cari lagu → ambil kandidat lirik (by hash, lalu by keyword)
    //              → unduh LRC (base64) → bersihkan kredit kepala/ekor.
    // ------------------------------------------------------------------

    private fun getLyrics(title: String, artist: String, durationSec: Int): String? {
        val keyword = generateKeyword(title, artist)
        val candidate = getLyricsCandidate(keyword, durationSec) ?: return null
        val content = downloadLyrics(candidate.id, candidate.accessKey) ?: return null
        return content.normalizeKuGou().takeIf { it.isNotBlank() }
    }

    private fun getLyricsCandidate(keyword: Keyword, durationSec: Int): Candidate? {
        // 1) Lewat hash lagu — paling presisi bila durasi cocok.
        searchSongs(keyword).forEach { song ->
            if (durationSec <= 0 || abs(song.duration - durationSec) <= DURATION_TOLERANCE) {
                searchLyrics(byHash = song.hash, keyword = null, durationSec = 0)
                    .firstOrNull()
                    ?.let { return it }
            }
        }
        // 2) Fallback: cari langsung dengan kata kunci judul + artis.
        return searchLyrics(byHash = null, keyword = keyword, durationSec = durationSec).firstOrNull()
    }

    private data class Keyword(val title: String, val artist: String)
    private data class SongInfo(val hash: String, val duration: Int)
    private data class Candidate(val id: Long, val accessKey: String)

    private fun searchSongs(keyword: Keyword): List<SongInfo> {
        val url = "https://mobileservice.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("version", "9108")
            .addQueryParameter("plat", "0")
            .addQueryParameter("pagesize", PAGE_SIZE.toString())
            .addQueryParameter("showtype", "0")
            .addQueryParameter("keyword", keyword.asQuery())
            .build()
        val body = get(url.toString()) ?: return emptyList()
        return runCatching {
            val info = JSONObject(body).optJSONObject("data")?.optJSONArray("info")
                ?: return@runCatching emptyList()
            (0 until info.length()).mapNotNull { i ->
                val o = info.optJSONObject(i) ?: return@mapNotNull null
                val hash = o.optString("hash").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SongInfo(hash = hash, duration = o.optInt("duration", 0))
            }
        }.getOrDefault(emptyList())
    }

    private fun searchLyrics(byHash: String?, keyword: Keyword?, durationSec: Int): List<Candidate> {
        val builder = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "pc")
        if (byHash != null) {
            builder.addQueryParameter("hash", byHash)
        } else {
            // duration == -1/0 berarti durasi tak diketahui → jangan difilter.
            if (durationSec > 0) builder.addQueryParameter("duration", (durationSec * 1000).toString())
            builder.addQueryParameter("keyword", (keyword ?: return emptyList()).asQuery())
        }
        val body = get(builder.build().toString()) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONArray("candidates")
                ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optLong("id", 0L).takeIf { it > 0L } ?: return@mapNotNull null
                val key = o.optString("accesskey").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Candidate(id = id, accessKey = key)
            }
        }.getOrDefault(emptyList())
    }

    private fun downloadLyrics(id: Long, accessKey: String): String? {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .addQueryParameter("client", "pc")
            .addQueryParameter("ver", "1")
            .addQueryParameter("id", id.toString())
            .addQueryParameter("accesskey", accessKey)
            .build()
        val body = get(url.toString()) ?: return null
        val content = runCatching { JSONObject(body).optString("content") }.getOrNull()
        if (content.isNullOrBlank()) return null
        return runCatching {
            String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun Keyword.asQuery(): String = "$title - $artist"

    /** Buang tanda kurung/sudut berisi keterangan versi, dan satukan kredit artis. */
    private fun generateKeyword(title: String, artist: String): Keyword {
        val t = title
            .replace("\\(.*\\)".toRegex(), "")
            .replace("（.*）".toRegex(), "")
            .replace("「.*」".toRegex(), "")
            .replace("『.*』".toRegex(), "")
            .replace("<.*>".toRegex(), "")
            .replace("《.*》".toRegex(), "")
            .replace("〈.*〉".toRegex(), "")
            .replace("＜.*＞".toRegex(), "")
            .trim()
        val a = artist
            .replace(", ", "、")
            .replace(" & ", "、")
            .replace(".", "")
            .replace("和", "、")
            .replace("\\(.*\\)".toRegex(), "")
            .replace("（.*）".toRegex(), "")
            .trim()
        return Keyword(t.ifBlank { title }, a.ifBlank { artist })
    }

    /**
     * Hanya pertahankan baris bertag waktu, lalu potong kredit di kepala/ekor
     * (penyanyi, penulis, komposer, gitar, dsb.) yang ikut terbawa KuGou.
     */
    private fun String.normalizeKuGou(): String {
        val lines = lines().filter { it.matches(ACCEPTED_REGEX) }
        if (lines.isEmpty()) return ""

        var headCut = 0
        for (i in min(HEAD_CUT_LIMIT, lines.lastIndex) downTo 0) {
            if (lines[i].matches(BANNED_REGEX)) {
                headCut = i + 1
                break
            }
        }
        val filtered = lines.drop(headCut)
        if (filtered.isEmpty()) return ""

        var tailCut = 0
        for (i in min(filtered.size - HEAD_CUT_LIMIT, filtered.lastIndex) downTo 0) {
            if (filtered[filtered.lastIndex - i].matches(BANNED_REGEX)) {
                tailCut = i + 1
                break
            }
        }
        return filtered.dropLast(tailCut).joinToString("\n")
    }

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body.string()
        }
    }.getOrNull()

    private val client get() = LyreonHttp.extractClient

    private companion object {
        const val PAGE_SIZE = 8
        const val HEAD_CUT_LIMIT = 30

        /** Selisih durasi (detik) yang masih dianggap lagu yang sama. */
        const val DURATION_TOLERANCE = 8

        const val USER_AGENT = "Lyreon Android (github.com/rixz-dev/Lyra)"

        @Suppress("RegExpRedundantEscape")
        val ACCEPTED_REGEX = "\\[(\\d\\d):(\\d\\d)\\.(\\d{2,3})\\].*".toRegex()
        val BANNED_REGEX = ".+].+[:：].+".toRegex()
    }
}
