/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.yt.innertube

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Konfigurasi runtime InnerTube (kunci publik + versi client + visitorData).
 *
 * Nilai API key & client version bukan rahasia — keduanya dipajang oleh YouTube
 * di halaman mereka dan berubah sewaktu-waktu. Modul ini men-scrape-nya dari
 * halaman web YouTube sekali per sesi lalu men-cache-nya, supaya fallback tetap
 * hidup tanpa hardcode yang mudah basi.
 */
internal object InnertubeConfig {

    private const val TAG = "InnertubeConfig"

    // Cadangan bila halaman gagal di-scrape (kunci publik WEB_REMIX / TV / ANDROID yang dikenal).
    private const val FALLBACK_KEY = "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30"
    private const val FALLBACK_WEB_VERSION = "2.20260818.01.00"
    private const val FALLBACK_ANDROID_VERSION = "19.45.38"

    @Volatile private var apiKey: String = FALLBACK_KEY
    @Volatile private var webVersion: String = FALLBACK_WEB_VERSION
    @Volatile private var androidVersion: String = FALLBACK_ANDROID_VERSION
    @Volatile private var visitorData: String? = null
    @Volatile private var playerJsUrl: String? = null
    @Volatile private var signatureTs: Int = 0

    private val fetched = ConcurrentHashMap.newKeySet<String>()

    /** Jeda minimum sebelum scrape ytcfg diulang setelah kegagalan. */
    private const val RETRY_AFTER_MS = 5L * 60_000L

    @Volatile private var scrapeOk = false
    @Volatile private var lastScrapeAtMs = 0L

    /**
     * Mengambil konfigurasi dari halaman YouTube. Aman dipanggil berulang.
     *
     * Bila scrape pernah GAGAL (jaringan putus saat start-up, YouTube mengubah
     * markup), dicoba lagi setelah [RETRY_AFTER_MS] — memakai nilai cadangan
     * selamanya berarti semua klien bisa ditolak YouTube hanya karena satu
     * kegagalan sesaat di awal sesi.
     */
    @Synchronized
    fun ensure(ioClient: okhttp3.OkHttpClient, userAgent: String) {
        if (scrapeOk) return
        val now = System.currentTimeMillis()
        if (lastScrapeAtMs > 0L && now - lastScrapeAtMs < RETRY_AFTER_MS) return
        lastScrapeAtMs = now
        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .header("User-Agent", userAgent)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
                .build()
            ioClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                apiKey = Regex("\"INNERTUBE_API_KEY\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    ?.groupValues?.getOrNull(1) ?: apiKey
                webVersion = Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    ?.groupValues?.getOrNull(1) ?: webVersion
                playerJsUrl = Regex("\"PLAYER_JS_URL\"\\s*:\\s*\"([^\"]+)\"").find(html)
                    ?.groupValues?.getOrNull(1)
                    ?: Regex("src=\"(/s/player/[^\"]+/base\\.js)\"").find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("src=\"(https://www\\.youtube\\.com/s/player/[^\"]+/base\\.js)\"").find(html)?.groupValues?.getOrNull(1)
                // `STS` di ytcfg = signatureTimestamp. Diperlukan klien yang URL-nya
                // masih ditandatangani base.js; tanpanya URL kerap langsung 403.
                signatureTs = Regex("\"STS\"\\s*:\\s*(\\d{4,6})").find(html)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: signatureTs
                scrapeOk = html.isNotBlank()
                if (scrapeOk) Log.i(TAG, "scrape ytcfg OK: versi=$webVersion sts=$signatureTs")
            }
        }.onFailure { e ->
            Log.w(TAG, "ensure() gagal scrape: ${e.message} — pakai nilai cadangan")
        }
    }

    /**
     * Buang `visitorData` tersimpan supaya permintaan berikutnya mengambil yang baru.
     * Dipanggil tangga klien setelah beberapa respons beruntun ditolak (`LOGIN_REQUIRED`
     * bot gate, `UNPLAYABLE`, transport error): visitorData yang basi — atau yang terikat
     * akun lain — membuat YouTube menolak klien yang seharusnya lolos.
     *
     * Selalu membersihkan (bahkan saat sudah null) supaya `fetched` ikut ter-reset dan
     * pengambilan berikutnya benar-benar berjalan.
     */
    @Synchronized
    fun invalidateVisitor() {
        visitorData = null
        visitorSource = "-"
        visitorFetchedAtMs = 0L
        fetched.remove("visitor")
        Log.i(TAG, "visitorData dibuang — akan diambil ulang pada permintaan berikut")
    }

    /**
     * Sumber visitorData (untuk diagnostik): `sw.js` / `guide` / `response` / `-`.
     *
     * `visitorData` BUKAN opsional. Komentar `InnerTube.ytClient` milik Meld
     * (FrancescoGrazioso/Meld, September 2026) mencatat pengukurannya:
     *
     * > "Sent to EVERY client, including `loginSupported = false` ones. … VISIONOS and
     * >  ANDROID_VR 1.65.10 — the only clients that currently mint a fully readable stream
     * >  URL — *require* it. Without one they answer UNPLAYABLE / LOGIN_REQUIRED with zero
     * >  formats."
     *
     * Jadi tanpa visitorData, dua klien terbaik di tangga anonim justru membalas
     * `LOGIN_REQUIRED: Sign in to confirm you're not a bot` — persis gejala yang dilaporkan
     * dari IP residensial Indonesia. Karena itu Lyreon mengambilnya dari sumber yang sama
     * dengan Meld dan menyimpan asal-usulnya supaya laporan diagnostik bisa membedakan
     * "YouTube memblokir" dari "kita mengirim request tanpa identitas sesi".
     */
    @Volatile private var visitorSource: String = "-"

    /** Umur maksimum visitorData sebelum dianggap basi (12 jam). */
    private const val VISITOR_TTL_MS = 12L * 60L * 60L * 1000L

    @Volatile private var visitorFetchedAtMs = 0L

    /** Visitor data wajib untuk tangga klien — diambil dari YouTube, di-cache per sesi. */
    @Synchronized
    fun ensureVisitorData(ioClient: okhttp3.OkHttpClient, userAgent: String) {
        val existing = visitorData
        if (existing != null && !isVisitorStale()) return
        if (existing == null && !fetched.add("visitor")) return
        // Sumber utama: `music.youtube.com/sw.js_data` — cara yang dipakai Meld.
        // Tidak butuh cookie, tidak butuh scrape HTML, dan mengembalikan visitorData
        // milik sesi web YouTube Music (cocok dengan host `music.youtube.com` yang
        // dipakai tangga klien).
        val fromSwJs = runCatching { fetchVisitorFromSwJs(ioClient) }.getOrNull()
        if (fromSwJs != null) {
            visitorData = fromSwJs
            visitorSource = "sw.js"
            visitorFetchedAtMs = System.currentTimeMillis()
            Log.i(TAG, "visitorData dari sw.js_data: ${fromSwJs.take(12)}… (${fromSwJs.length} char)")
            return
        }
        // Cadangan: endpoint `guide` di www (perilaku Lyreon sebelumnya).
        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/guide?prettyPrint=false")
                .header("User-Agent", userAgent)
                .post(
                    InnertubeRequest.baseContextJson(webVersion)
                        .toRequestBody("application/json".toMediaType()),
                )
                .build()
            ioClient.newCall(req).execute().use { resp ->
                val body = org.json.JSONObject(resp.body?.string().orEmpty())
                val v = body.optString("visitorData").takeIf { it.isNotBlank() }
                    ?: body.optJSONObject("responseContext")?.optString("visitorData")
                        ?.takeIf { it.isNotBlank() }
                if (v != null) {
                    visitorData = v
                    visitorSource = "guide"
                    visitorFetchedAtMs = System.currentTimeMillis()
                    Log.i(TAG, "visitorData dari guide: ${v.take(12)}…")
                }
            }
        }.onFailure { e ->
            Log.w(TAG, "gagal ambil visitorData: ${e.javaClass.simpleName}: ${e.message}")
        }
        if (visitorData == null) {
            Log.w(
                TAG,
                "visitorData TIDAK tersedia — VISIONOS/ANDROID_VR kemungkinan besar membalas " +
                    "LOGIN_REQUIRED (bot gate). Lihat StreamUrlValidator & PlayerClientLadder.",
            )
        }
    }

    private fun isVisitorStale(): Boolean =
        visitorFetchedAtMs > 0L && System.currentTimeMillis() - visitorFetchedAtMs > VISITOR_TTL_MS

    /**
     * Ambil visitorData dari `https://music.youtube.com/sw.js_data`.
     *
     * Bentuk respons (Meld `YouTube.visitorData()`): diawali `)]}'` lalu JSON array;
     * visitorData adalah string pertama yang cocok `^Cg[t|s]` di dalam `[0][2]`.
     * Lyreon memindai jalur itu lebih dulu, lalu melakukan pemindaian terbatas ke
     * seluruh struktur bila YouTube mengubah susunannya — lebih tahan banting daripada
     * indeks tetap.
     */
    private fun fetchVisitorFromSwJs(ioClient: okhttp3.OkHttpClient): String? {
        val req = Request.Builder()
            .url("https://music.youtube.com/sw.js_data")
            .header("User-Agent", PlayerClientLadder.WEB_UA_FIREFOX)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://music.youtube.com/")
            .get()
            .build()
        val text = ioClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string().orEmpty()
        }
        if (text.isBlank()) return null
        // Buang pembungkus anti-JSON `)]}'` (Meld memakai `substring(5)`).
        val start = text.indexOfFirst { it == '[' || it == '{' }
        if (start < 0) return null
        val root = runCatching { org.json.JSONTokener(text.substring(start)).nextValue() }.getOrNull()
            ?: return null
        // 1) jalur persis Meld: [0][2]
        val arr0 = (root as? org.json.JSONArray)?.optJSONArray(0)
        val arr2 = arr0?.optJSONArray(2)
        findVisitorIn(arr2)?.let { return it }
        // 2) pemindaian terbatas (kedalaman ≤ 6) bila susunannya bergeser
        return findVisitorIn(root, depth = 0)
    }

    private val VISITOR_REGEX = Regex("^Cg[t|s]")

    private fun findVisitorIn(node: Any?, depth: Int = 0): String? {
        if (node == null || depth > 6) return null
        return when (node) {
            is String -> node.takeIf { VISITOR_REGEX.containsMatchIn(it) && it.length >= 20 }
            is org.json.JSONArray -> {
                for (i in 0 until node.length()) {
                    findVisitorIn(node.opt(i), depth + 1)?.let { return it }
                }
                null
            }
            is org.json.JSONObject -> {
                node.optString("visitorData").takeIf { it.isNotBlank() && VISITOR_REGEX.containsMatchIn(it) }
                    ?: node.optJSONObject("responseContext")?.optString("visitorData")
                        ?.takeIf { it.isNotBlank() }
            }
            else -> null
        }
    }

    /**
     * Panen visitorData dari response InnerTube mana pun (`responseContext.visitorData`).
     * Dipanggil tangga klien saat kita belum punya identitas sesi — satu respons yang
     * lolos bisa menyelamatkan seluruh sisa tangga tanpa request tambahan.
     *
     * @return true bila visitorData baru saja diadopsi.
     */
    fun adoptVisitor(root: org.json.JSONObject?): Boolean {
        if (root == null || visitorData != null) return false
        val v = root.optJSONObject("responseContext")?.optString("visitorData")
            ?.takeIf { it.isNotBlank() } ?: return false
        visitorData = v
        visitorSource = "response"
        visitorFetchedAtMs = System.currentTimeMillis()
        Log.i(TAG, "visitorData diadopsi dari responseContext: ${v.take(12)}…")
        return true
    }

    /** Segarkan visitorData sekarang (dipanggil setelah beberapa respons ditolak beruntun). */
    fun refreshVisitor(ioClient: okhttp3.OkHttpClient, userAgent: String) {
        invalidateVisitor()
        ensureVisitorData(ioClient, userAgent)
    }

    /** Asal visitorData saat ini — untuk laporan diagnostik. */
    fun visitorOrigin(): String = if (visitorData == null) "-" else visitorSource

    fun apiKey(): String = apiKey

    /**
     * signatureTimestamp (`STS`) hasil scrape ytcfg; null bila belum tersedia.
     * Dikirim sebagai `playbackContext.contentPlaybackContext.signatureTimestamp`
     * untuk klien yang URL stream-nya masih perlu di-decipher.
     */
    fun signatureTimestamp(): Int? = signatureTs.takeIf { it > 0 }
    fun webClientVersion(): String = webVersion
    fun androidClientVersion(): String = androidVersion
    fun visitor(): String? = visitorData
    fun baseJsUrl(): String? = playerJsUrl?.let { if (it.startsWith("//")) "https:$it" else it }
}
