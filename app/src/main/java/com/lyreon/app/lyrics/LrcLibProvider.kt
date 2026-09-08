/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.lyrics

import com.lyreon.app.yt.LyreonHttp
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Provider LRCLIB (https://lrclib.net) — gratis, tanpa API key.
 *
 * Strategi: GET /api/get (exact match) → bila 404 → GET /api/search (fuzzy) lalu
 * pilih kandidat SYNCED dengan durasi PALING DEKAT. Mengambil hit synced pertama
 * begitu saja sering kena versi live/remix/edit → timestamp bergeser
 * ("lirik gak singkron").
 */
class LrcLibProvider : LyreonLyricsProvider {

    override val name: String = "LRCLIB"
    override val priority: Int = 10

    private val client get() = LyreonHttp.extractClient

    override suspend fun fetch(query: LyricsQuery): LyricsResult? {
        val duration = query.durationSec.toLong().coerceAtLeast(0L)

        // 1) Exact match
        val exactUrl = buildString {
            append("https://lrclib.net/api/get?track_name=").append(enc(query.title))
            if (query.artist.isNotBlank()) append("&artist_name=").append(enc(query.artist))
            if (duration > 0) append("&duration=").append(duration)
        }
        get(exactUrl)?.let { body ->
            if (!body.startsWith("{")) return@let
            runCatching { toResult(JSONObject(body)) }.getOrNull()?.let { return it.withSource(name) }
        }

        // 2) Fuzzy search
        val q = if (query.artist.isBlank()) query.title else "${query.title} ${query.artist}"
        val body = get("https://lrclib.net/api/search?q=" + enc(q)) ?: return null
        if (!body.startsWith("[")) return null
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null

        var bestSynced: LyricsResult? = null
        var bestSyncedDiff = Long.MAX_VALUE
        var bestPlain: LyricsResult? = null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val itemDuration = item.optDouble("duration", 0.0).toLong()
            val synced = item.optString("syncedLyrics")
            if (!synced.isNullOrBlank()) {
                val res = toResult(item) ?: continue
                val diff = if (duration > 0 && itemDuration > 0) abs(itemDuration - duration) else Long.MAX_VALUE / 2
                if (diff < bestSyncedDiff) {
                    bestSyncedDiff = diff
                    bestSynced = res
                }
                if (diff <= 3L) break // cukup presisi — hentikan lebih awal
            }
            if (bestPlain == null && !item.optString("plainLyrics").isNullOrBlank()) {
                bestPlain = toResult(item)
            }
        }
        // Terima synced bila durasi cocok (±10 dtk) atau durasi tak diketahui;
        // kalau terlalu jauh → hampir pasti versi salah, pakai plain dulu.
        val chosen = if (bestSynced != null && (duration <= 0 || bestSyncedDiff <= 10L)) {
            bestSynced
        } else {
            bestPlain ?: bestSynced
        }
        return chosen?.withSource(name)
    }

    private fun toResult(json: JSONObject): LyricsResult? {
        val instrumental = json.optBoolean("instrumental", false)
        val syncedRaw = json.optString("syncedLyrics")
        val plainRaw = json.optString("plainLyrics")
        val synced = if (syncedRaw.isNullOrBlank()) emptyList() else LrcParser.parse(syncedRaw)
        val plain = plainRaw?.takeIf { it.isNotBlank() }
        if (synced.isEmpty() && plain == null && !instrumental) return null
        return LyricsResult(synced = synced, plain = plain, instrumental = instrumental)
    }

    private fun get(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body.string()
        }
    }.getOrNull()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private companion object {
        /** LRCLIB meminta UA yang bisa dihubungi balik (kebijakan layanan mereka). */
        const val USER_AGENT = "Lyreon Android (github.com/rixz-dev/Lyra)"
    }
}
