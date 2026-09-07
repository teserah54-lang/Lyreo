/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.yt.innertube

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * SATU tempat berisi daftar klien InnerTube yang dicoba Lyreon, urut prioritas.
 *
 * ## Kenapa lapisan ini ada
 *
 * YouTube tidak mengumumkan klien mana yang boleh menerima `streamingData`
 * berisi URL yang bisa di-GET; kebijakannya bergeser terus (SABR digulirkan
 * bertahap sejak 2025, poToken diwajibkan per klien, `ANDROID_VR` di-gate per
 * **versi** sejak Agustus 2026). Tanpa lapisan ini, setiap perubahan kebijakan
 * YouTube berarti membongkar kode resolusi stream. Dengan lapisan ini, yang
 * perlu diubah hanya **tabel di bawah** — deteksi SABR/DRM/HLS, diagnostik,
 * validasi URL, dan pendinginan klien ikut otomatis.
 *
 * ## Sumber tabel: Meld (FrancescoGrazioso/Meld), September 2026
 *
 * Seluruh spesifikasi klien di sini **disalin byte-per-byte** dari
 * [`YouTubeClient.kt`](https://github.com/FrancescoGrazioso/Meld/blob/main/innertube/src/main/kotlin/com/metrolist/innertube/models/YouTubeClient.kt)
 * dan urutannya dari `YTPlayerUtils.STREAM_FALLBACK_CLIENTS` — klien musik Android
 * anonim yang pengembangnya mengukur langsung klien mana yang sanggup melayani
 * **satu file utuh** (bukan sekadar mengembalikan URL):
 *
 * ```
 * VISIONOS → ANDROID_VR 1.65.10 → TVHTML5 → ANDROID_VR 1.43.32 → IPADOS → IOS
 * ```
 *
 * Tiga temuan Meld yang mengubah desain Lyreon:
 *
 * 1. **Gate per versi, bukan per bentuk request.** `ANDROID_VR` 1.43.32 dan 1.61.48
 *    membalas `LOGIN_REQUIRED / "Sign in to confirm you're not a bot"` dengan nol format
 *    (anonim maupun login, dengan/tanpa visitorData, field device dilucuti pun sama),
 *    sedangkan 1.65.10 membalas `OK` dengan 100% URL langsung. Yang berbeda hanya versinya.
 * 2. **`visitorData` wajib**, dikirim ke SEMUA klien sebagai `X-Goog-Visitor-Id`.
 *    Tanpanya VISIONOS dan ANDROID_VR 1.65.10 menjawab UNPLAYABLE/LOGIN_REQUIRED —
 *    dua klien terbaik justru yang paling rewel. Lihat [InnertubeConfig].
 * 3. **URL dari `IOS`/`IPADOS`/`ANDROID_VR` lawas hanyalah pratinjau ~1 MiB** (403 setelah
 *    offset tertentu). Karena itu klien di ekor tangga tidak boleh dipakai selama klien di
 *    atasnya masih bisa melayani file utuh — dan setiap URL **divalidasi** dulu dengan probe
 *    byte terakhir ([StreamUrlValidator]).
 *
 * `TVHTML5` dan `WEB_CREATOR` di sini ditandai [PlayerClientSpec.loginRequired] karena
 * begitu di Meld: keduanya butuh sesi login untuk konten yang dilindungi umur, dan Lyreon
 * anonim (tanpa cookie) → keduanya hanya dijalankan saat "Tes koneksi", tidak untuk memutar.
 * Jalur putar anonim yang nyata = URL langsung dari lima klien di atas **atau** manifest HLS.
 */
internal data class PlayerClientSpec(
    /** Kunci pendek untuk log/diagnostik ("visionos", "android_vr", …). */
    val key: String,
    val clientName: String,
    /** Kosong = pakai versi web hasil scrape [InnertubeConfig]. */
    val clientVersion: String,
    /** Nilai header `X-YouTube-Client-Name`. */
    val clientId: String,
    val userAgent: String,
    /**
     * Host API. Default `music.youtube.com` — persis Meld, yang mengirim SEMUA request
     * `player` (termasuk ANDROID_VR dan IOS) ke `music.youtube.com/youtubei/v1/` dengan
     * `X-Origin`/`Referer` YouTube Music.
     */
    val host: String = "music.youtube.com",
    /** Host cadangan bila [host] gagal di lapisan transport (bukan karena playability). */
    val altHost: String? = "www.youtube.com",
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int = 0,
    /**
     * Kirim `playbackContext.contentPlaybackContext.signatureTimestamp` (STS hasil scrape
     * ytcfg). Meld menandainya per klien: hanya klien web/TV yang URL-nya masih
     * ditandatangani `base.js` yang membutuhkannya. VISIONOS/ANDROID_VR/IOS mengirim URL
     * polos → `false`.
     */
    val useSignatureTimestamp: Boolean = false,
    /**
     * Klien ini butuh poToken BotGuard untuk URL langsungnya. Lyreon anonim **tidak**
     * membuat poToken (Meld pun tidak untuk tangga anonimnya: `VISIONOS`, `ANDROID_VR`,
     * `IPADOS`, `IOS` semuanya `useWebPoTokens = false`), sehingga klien bertanda ini
     * tidak dipakai memutar — hanya untuk diagnostik.
     */
    val useWebPoTokens: Boolean = false,
    /**
     * Butuh sesi login untuk konten yang dilindungi umur. Di Lyreon (anonim, tanpa cookie)
     * klien seperti ini dilewati pada jalur putar, sama seperti `if (client.loginRequired &&
     * !isLoggedIn) continue` di `YTPlayerUtils.playerResponseForPlayback`.
     */
    val loginRequired: Boolean = false,
    /**
     * URL langsung klien ini tetap butuh poToken GVS, tetapi **manifest HLS-nya tidak** —
     * jadi bila response membawa `hlsManifestUrl`, manifest itu dipakai pada lintasan HLS
     * (lihat `fetchPlayer` di [InnertubeFallback]).
     */
    val preferManifest: Boolean = false,
    /** Nilai `context.thirdParty.embedUrl` untuk klien embed. */
    val embedUrlValue: String? = null,
    /**
     * Hanya dijalankan saat "Tes koneksi" — tidak pernah masuk tangga putar, meski
     * secara teknis tidak butuh login/poToken. Dipakai untuk klien pembanding
     * (mis. `ANDROID_VR` 1.61.48 sebagai kontrol teori gate-per-versi).
     */
    val probeOnly: Boolean = false,
    val note: String = "",
) {
    /** True bila klien ini masuk tangga putar anonim Lyreon. */
    val playableAnonymous: Boolean get() = !loginRequired && !useWebPoTokens && !probeOnly
}


/** Hasil pemeriksaan satu response player. */
internal enum class ClientVerdict {
    /** Ada `adaptiveFormats`/`formats` dengan `url`/`signatureCipher` yang tidak ber-DRM. */
    USABLE,

    /**
     * Response memberi URL, tetapi CDN menolaknya saat di-probe
     * ([StreamUrlValidator]): 403/410, atau URL itu cuma pratinjau ~1 MiB yang
     * mati di tengah lagu. Ini verdict yang membedakan "YouTube memberi URL"
     * dari "URL itu benar-benar bisa diputar sampai habis" — pembeda yang
     * membuat diagnosa `streams=4` tapi `ERROR_CODE_IO_BAD_HTTP_STATUS`
     * akhirnya terbaca.
     */
    REJECTED_BY_CDN,

    /**
     * Response berisi format tetapi tanpa satu pun URL/cipher — tanda tangan
     * SABR (`serverAbrStreamingUrl`). Inilah penyebab loop "stream tidak
     * tersedia" di semua lagu.
     */
    SABR_ONLY,

    /** Hanya `hlsManifestUrl` — bisa diputar karena Lyreon mendukung HLS. */
    HLS_ONLY,

    /**
     * Semua format terkunci DRM (`drmFamilies`). Khas klien TV anonim: YouTube
     * menuntut cookie "guest aktif" agar format tidak di-DRM.
     */
    DRM_ONLY,

    /** `playabilityStatus` != OK (LOGIN_REQUIRED, AGE_CHECK_REQUIRED, ERROR…). */
    PLAYABILITY_BLOCKED,

    /** Tidak ada `streamingData` sama sekali. */
    NO_STREAMING_DATA,

    /** `videoDetails.videoId` tidak cocok — YouTube mengganti response (anti-bot). */
    INVALID_RESPONSE,

    /** HTTP gagal / body bukan JSON. */
    TRANSPORT_ERROR,
}

/**
 * Urutan klien + memori jangka pendek: klien yang terakhir berhasil dinaikkan ke
 * depan, klien yang baru saja balas SABR-only didinginkan (tetap dicoba, hanya
 * digeser ke belakang) supaya satu lagu gagal tidak membuang waktu pada klien
 * yang jelas sedang ditutup YouTube.
 *
 * Selain itu ada **saklar bypass extractor**: `MetrolistExtractor` yang di-pin
 * memakai klien ANDROID_VR + WEB, keduanya sudah ditutup YouTube (403 / SABR).
 * Setelah gagal berulang dengan ciri SABR, extractor dilewati selama
 * [EXTRACTOR_BYPASS_MS] supaya tiap lagu tidak menunggu timeout extractor dulu.
 */
internal object PlayerClientLadder {

    private const val TAG = "PlayerClientLadder"

    /** Lama pendinginan klien yang balas SABR-only / transport error. */
    private const val COOLDOWN_MS = 3L * 60_000L

    /** Lama extractor utama dilewati setelah terbukti membalas SABR berulang. */
    private const val EXTRACTOR_BYPASS_MS = 10L * 60_000L

    /** Jumlah kegagalan SABR beruntun sebelum extractor di-bypass. */
    private const val EXTRACTOR_BYPASS_AFTER = 2

    /** Ukuran ring buffer diagnostik yang bisa dibaca dari layar Settings. */
    private const val DIAGNOSTIC_LIMIT = 40

    /**
     * UA web milik Meld (`YouTubeClient.USER_AGENT_WEB`) — Firefox 140, bukan Chrome.
     * Dipakai klien web DAN permintaan `sw.js_data` pengambil visitorData.
     */
    internal const val WEB_UA_FIREFOX =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    /** UA Safari macOS milik Meld untuk `VISIONOS` (Version/18.0, bukan 26.0). */
    private const val VISIONOS_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    /** UA Samsung Tizen milik Meld untuk `TVHTML5` (bukan Cobalt). */
    private const val TIZEN_TV_UA =
        "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 (KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15"

    private const val SAFARI_MAC_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
    private const val MWEB_UA =
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
    private const val CHROME_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36,gzip(gfe)"

    /**
     * Urutan per September 2026 = **`STREAM_FALLBACK_CLIENTS` milik Meld**, disusun
     * berdasarkan kemampuan TERUKUR melayani satu file utuh (bukan teori):
     *
     * | # | klien | hasil ukur Meld |
     * |---|---|---|
     * | 1 | `visionos` | satu-satunya yang 100% 206 sampai byte terakhir (2,4/4,5/4,7 MB), tahan 51 baca ber-jeda 300 dtk |
     * | 2 | `android_vr` 1.65.10 | `OK`, 100% URL langsung; wajib visitorData |
     * | 3 | `android_vr_1_43_32` | bitrate non-adaptive (audio tak patah di YT Music); kontrol gate-per-versi |
     * | 4 | `ipados` | pratinjau ~1 MiB → 403 sesudahnya (lagu mati ~60 dtk) |
     * | 5 | `ios` | pratinjau ~1 MiB → 403 sesudahnya |
     *
     * `TVHTML5` di Meld ada di posisi 3, tetapi `loginRequired = true` sehingga di Lyreon
     * (anonim) otomatis dilewati pada jalur putar — sama seperti `WEB_CREATOR`. Keduanya
     * tetap ada di tabel untuk "Tes koneksi".
     *
     * `TVHTML5_SIMPLY_EMBEDDED_PLAYER` (clientId 85) TIDAK ada di sini: Meld mengukurnya
     * mati di sisi server (`ERROR / "YouTube is no longer supported in this application or
     * device"`, nol format) pada empat cascade beruntun — menyimpannya hanya menambah satu
     * round-trip ~70 ms yang tak pernah berhasil.
     *
     * Pindahkan entri ke atas/bawah saat YouTube mengubah kebijakan: tidak ada kode lain
     * yang perlu disentuh.
     */
    private val SPECS: List<PlayerClientSpec> = listOf(
        // ------------------------------------------------------------------
        // 1) Tangga putar anonim — urutan terukur Meld
        // ------------------------------------------------------------------
        PlayerClientSpec(
            key = "visionos",
            clientName = "VISIONOS",
            clientVersion = "0.1",
            clientId = "101",
            userAgent = VISIONOS_UA,
            osName = "visionOS",
            osVersion = "1.3.21O771",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            note = "satu-satunya klien yang terukur melayani file utuh (100% 206)",
        ),
        PlayerClientSpec(
            key = "android_vr",
            clientName = "ANDROID_VR",
            clientVersion = "1.65.10",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
                "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            osName = "Android",
            osVersion = "12L",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            note = "pin yt-dlp/YouTube.js — WAJIB visitorData, tanpa buildId/cronet/packageName",
        ),
        PlayerClientSpec(
            key = "android_vr_1_43_32",
            clientName = "ANDROID_VR",
            clientVersion = "1.43.32",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.43.32 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)",
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            note = "bitrate non-adaptive (audio tak patah), tanpa AV1; ter-gate per versi",
        ),
        PlayerClientSpec(
            key = "ipados",
            clientName = "IOS",
            clientVersion = "21.03.3",
            clientId = "5",
            userAgent = "com.google.ios.youtube/21.03.3 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)",
            osName = "iPadOS",
            osVersion = "17.7.10.21H450",
            deviceMake = "Apple",
            deviceModel = "iPad7,6",
            note = "pratinjau ~1 MiB — hanya bila semua di atasnya gagal",
        ),
        PlayerClientSpec(
            key = "ios",
            clientName = "IOS",
            clientVersion = "21.03.1",
            clientId = "5",
            userAgent = "com.google.ios.youtube/21.03.1 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X;)",
            osName = "iOS",
            osVersion = "18.2.22C152",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            note = "pratinjau ~1 MiB — cadangan paling akhir",
        ),

        // ------------------------------------------------------------------
        // 2) Sumber manifest HLS — lintasan kedua Lyreon (bukan dari Meld)
        //    Manifest HLS tidak menuntut poToken GVS, jadi tetap layak dicoba
        //    bila seluruh URL langsung ditolak CDN.
        // ------------------------------------------------------------------
        PlayerClientSpec(
            key = "web_safari",
            clientName = "WEB",
            clientVersion = "",
            clientId = "1",
            userAgent = SAFARI_MAC_UA,
            host = "www.youtube.com",
            preferManifest = true,
            useWebPoTokens = true,
            note = "Safari UA → HLS pre-merged (m3u8); URL langsungnya butuh poToken",
        ),
        PlayerClientSpec(
            key = "tv_simply",
            clientName = "TVHTML5_SIMPLY",
            clientVersion = "1.0",
            clientId = "75",
            userAgent = CHROME_UA,
            host = "www.youtube.com",
            preferManifest = true,
            useWebPoTokens = true,
            note = "HTTPS butuh poToken, HLS tidak",
        ),

        // ------------------------------------------------------------------
        // 3) Diagnostik saja ("Tes koneksi") — tidak dipakai memutar
        // ------------------------------------------------------------------
        PlayerClientSpec(
            key = "tvhtml5",
            clientName = "TVHTML5",
            clientVersion = "7.20260213.00.00",
            clientId = "7",
            userAgent = TIZEN_TV_UA,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
            loginRequired = true,
            note = "Meld: untuk track unggahan pribadi (MLPT); butuh login",
        ),
        PlayerClientSpec(
            key = "web_creator",
            clientName = "WEB_CREATOR",
            clientVersion = "1.20260213.00.00",
            clientId = "62",
            userAgent = WEB_UA_FIREFOX,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
            loginRequired = true,
            note = "satu-satunya klien OK untuk konten dibatasi umur — butuh login + pot=",
        ),
        PlayerClientSpec(
            key = "web_remix",
            clientName = "WEB_REMIX",
            clientVersion = "1.20260213.01.00",
            clientId = "67",
            userAgent = WEB_UA_FIREFOX,
            useSignatureTimestamp = true,
            useWebPoTokens = true,
            note = "klien metadata Meld; formatnya di balik cipher/n-challenge",
        ),
        PlayerClientSpec(
            key = "web",
            clientName = "WEB",
            clientVersion = "2.20260213.00.00",
            clientId = "1",
            userAgent = WEB_UA_FIREFOX,
            note = "SABR-only tanpa poToken",
        ),
        PlayerClientSpec(
            key = "android_vr_1_61_48",
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientId = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 " +
                "(Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)",
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            probeOnly = true,
            note = "KONTROL: versi ini ter-gate (LOGIN_REQUIRED) sedangkan 1.65.10 OK",
        ),
        PlayerClientSpec(
            key = "mweb",
            clientName = "MWEB",
            clientVersion = "",
            clientId = "2",
            userAgent = MWEB_UA,
            host = "www.youtube.com",
            useWebPoTokens = true,
            probeOnly = true,
            note = "sering memberi URL, tapi URL web butuh n-transform → 403/throttle",
        ),
    )

    private val lastGood = AtomicReference<String?>(null)
    private val cooldownUntil = ConcurrentHashMap<String, Long>()
    private val diagnostics = ConcurrentLinkedDeque<String>()

    /**
     * [SimpleDateFormat] tidak thread-safe, sedangkan [note] bisa dipanggil dari
     * beberapa thread IO sekaligus — formatter dibuat per panggilan (jalur
     * diagnostik saja, bukan jalur panas).
     */
    private fun timestamp(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    @Volatile
    private var lastSabrAtMs: Long = 0L

    /** Total kejadian SABR-only sesi ini (extractor + tangga klien). */
    private val sabrTotal = AtomicInteger(0)

    /** Kegagalan extractor ber-ciri SABR yang beruntun (di-reset saat extractor sukses). */
    private val extractorSabrStreak = AtomicInteger(0)

    @Volatile
    private var extractorBypassUntilMs: Long = 0L

    /**
     * Urutan coba.
     *
     * @param forPlayback true untuk jalur pemutaran: klien yang butuh poToken
     *   dibuang karena tanpa poToken mereka pasti SABR-only/403 — mencoba mereka
     *   hanya menambah ~0,5 dtk latensi per lagu. false untuk "Tes koneksi"
     *   (diagnostik penuh, termasuk klien mati).
     */
    fun ordered(forPlayback: Boolean = true): List<PlayerClientSpec> {
        val base = SPECS.filter { !forPlayback || it.playableAnonymous }.toMutableList()
        lastGood.get()?.let { good ->
            val index = base.indexOfFirst { it.key == good }
            if (index > 0) base.add(0, base.removeAt(index))
        }
        val now = System.currentTimeMillis()
        val warm = ArrayList<PlayerClientSpec>(base.size)
        val cold = ArrayList<PlayerClientSpec>(4)
        base.forEach { spec ->
            if ((cooldownUntil[spec.key] ?: 0L) > now) cold += spec else warm += spec
        }
        return warm + cold
    }

    fun specOf(key: String): PlayerClientSpec? = SPECS.firstOrNull { it.key == key }

    /**
     * Klien sumber manifest HLS — lintasan kedua Lyreon.
     *
     * Bukan bagian dari Meld (Meld tidak memutar HLS), tetapi manifest HLS tidak menuntut
     * poToken GVS, jadi bila SEMUA URL langsung ditolak CDN lintasan ini masih bisa
     * menyelamatkan pemutaran. Hanya dijalankan setelah tangga utama gagal.
     */
    fun hlsSpecs(): List<PlayerClientSpec> = SPECS.filter { it.preferManifest }

    /**
     * User-Agent yang harus dipakai saat meminta byte dari `googlevideo.com`.
     *
     * CDN mencocokkan UA request dengan klien yang mencetak URL (`c=` + `cver=` di query);
     * UA yang tidak cocok adalah penyebab klasik 403 padahal URL-nya sah. Dulu Lyreon
     * menebak dari `c=` saja dan memasang UA app lawas (mis. `com.google.android.youtube/
     * 19.45.38` untuk `c=ANDROID_VR` — nama paket SALAH, versi salah). Sekarang UA diambil
     * dari spec yang benar-benar dipakai resolusi, dicocokkan versi lebih dulu.
     */
    fun streamUserAgentFor(clientName: String?, clientVersion: String?): String? {
        if (clientName.isNullOrBlank()) return null
        val exact = SPECS.firstOrNull {
            it.clientName.equals(clientName, ignoreCase = true) &&
                !clientVersion.isNullOrBlank() && it.clientVersion == clientVersion
        }
        return (exact ?: SPECS.firstOrNull { it.clientName.equals(clientName, ignoreCase = true) })
            ?.userAgent
    }

    /** Semua klien (termasuk yang butuh poToken) — untuk laporan diagnostik. */
    fun allSpecs(): List<PlayerClientSpec> = SPECS

    /** Catat hasil satu percobaan klien (diagnostik + urutan adaptif). */
    fun note(key: String, verdict: ClientVerdict, elapsedMs: Long, detail: String = "") {
        if (verdict == ClientVerdict.USABLE || verdict == ClientVerdict.HLS_ONLY) {
            lastGood.set(key)
            cooldownUntil.remove(key)
        }
        // `REJECTED_BY_CDN` ikut dihitung sebagai "tekanan": URL yang ditolak CDN dan
        // response SABR-only sama-sama berarti YouTube sedang menutup akses anonim, dan
        // sinyal ini yang dipakai `PlayerManager.isSabrFailure()` saat tidak ada tanda
        // eksplisit di rantai cause.
        if (verdict == ClientVerdict.SABR_ONLY || verdict == ClientVerdict.DRM_ONLY ||
            verdict == ClientVerdict.REJECTED_BY_CDN
        ) {
            lastSabrAtMs = System.currentTimeMillis()
            cooldownUntil[key] = lastSabrAtMs + COOLDOWN_MS
        }
        if (verdict == ClientVerdict.SABR_ONLY) sabrTotal.incrementAndGet()
        if (verdict == ClientVerdict.TRANSPORT_ERROR) {
            cooldownUntil[key] = System.currentTimeMillis() + COOLDOWN_MS
        }
        val suffix = if (detail.isBlank()) "" else " · $detail"
        push("${timestamp()} $key → $verdict (${elapsedMs}ms)$suffix")
        if (verdict == ClientVerdict.SABR_ONLY) {
            Log.w(
                TAG,
                "klien '$key' membalas SABR-only (tanpa URL stream). " +
                    "Total kejadian SABR sesi ini: ${sabrTotal.get()}",
            )
        }
        if (verdict == ClientVerdict.DRM_ONLY) {
            Log.w(TAG, "klien '$key' membalas format ber-DRM (butuh cookie guest) — dilewati")
        }
        if (verdict == ClientVerdict.REJECTED_BY_CDN) {
            Log.w(
                TAG,
                "klien '$key' memberi URL tetapi CDN menolaknya ($detail) — " +
                    "kemungkinan pratinjau ~1 MiB atau 403; lanjut ke klien berikutnya",
            )
        }
    }

    /**
     * Extractor utama juga bisa membalas SABR-only — `YoutubeStreamExtractor`
     * melempar `ContentNotSupportedException("YouTube returned SABR-only streaming
     * data …")`. Dicatat di buku yang sama supaya [sabrPressureRecently] mencakup
     * kedua jalur, dan supaya extractor bisa di-bypass sementara.
     */
    fun noteExtractorSabr(detail: String) {
        lastSabrAtMs = System.currentTimeMillis()
        sabrTotal.incrementAndGet()
        val streak = extractorSabrStreak.incrementAndGet()
        if (streak >= EXTRACTOR_BYPASS_AFTER) {
            extractorBypassUntilMs = System.currentTimeMillis() + EXTRACTOR_BYPASS_MS
            push(
                "${timestamp()} extractor di-bypass ${EXTRACTOR_BYPASS_MS / 60_000} menit " +
                    "(SABR $streak× beruntun) — langsung ke tangga klien",
            )
            Log.w(TAG, "extractor di-bypass $EXTRACTOR_BYPASS_MS ms setelah $streak kegagalan SABR")
        } else {
            push("${timestamp()} extractor → SABR_ONLY · $detail")
        }
        Log.w(TAG, "extractor membalas SABR-only ($detail) — total sesi ini: ${sabrTotal.get()}")
    }

    /** Extractor utama berhasil → hentikan bypass dan reset streak. */
    fun noteExtractorSuccess() {
        if (extractorSabrStreak.get() > 0 || extractorBypassUntilMs > 0L) {
            push("${timestamp()} extractor pulih — bypass dicabut")
        }
        extractorSabrStreak.set(0)
        extractorBypassUntilMs = 0L
    }

    /**
     * True bila extractor utama sebaiknya dilewati (baru saja terbukti membalas
     * SABR-only berulang). Menghemat satu round-trip + timeout extractor per lagu.
     */
    fun extractorBypassed(): Boolean = System.currentTimeMillis() < extractorBypassUntilMs

    /**
     * True bila dalam [windowMs] terakhir ada klien yang membalas SABR-only —
     * dipakai PlayerManager untuk memilih pesan error yang tepat.
     */
    fun sabrPressureRecently(windowMs: Long = 10L * 60_000L): Boolean =
        System.currentTimeMillis() - lastSabrAtMs < windowMs

    fun sabrCount(): Int = sabrTotal.get()

    /** Baris diagnostik terbaru (paling baru di depan) untuk layar Settings. */
    fun report(): List<String> = diagnostics.toList()

    fun push(line: String) {
        diagnostics.addFirst(line)
        while (diagnostics.size > DIAGNOSTIC_LIMIT) diagnostics.pollLast()
    }

    fun reset() {
        diagnostics.clear()
        cooldownUntil.clear()
        lastGood.set(null)
        lastSabrAtMs = 0L
        sabrTotal.set(0)
        extractorSabrStreak.set(0)
        extractorBypassUntilMs = 0L
    }

    // ------------------------------------------------------------------
    // Inspeksi response
    // ------------------------------------------------------------------

    /**
     * Menilai satu response `/youtubei/v1/player`.
     *
     * Deteksi SABR mengikuti logika `YoutubeStreamExtractor.isSabrOnlyResponse()`
     * milik fork: format ada tetapi tidak satu pun membawa `url`,
     * `signatureCipher`, atau `cipher`.
     */
    fun inspect(root: JSONObject, videoId: String): ClientVerdict {
        val returnedId = root.optJSONObject("videoDetails")?.optString("videoId").orEmpty()
        if (returnedId.isNotBlank() && returnedId != videoId) return ClientVerdict.INVALID_RESPONSE

        val status = root.optJSONObject("playabilityStatus")?.optString("status").orEmpty()
        if (status.isNotBlank() && !status.equals("OK", ignoreCase = true)) {
            return ClientVerdict.PLAYABILITY_BLOCKED
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: return ClientVerdict.NO_STREAMING_DATA

        if (hasDirectStreamUrl(streamingData)) return ClientVerdict.USABLE

        if (!streamingData.optString("hlsManifestUrl").isNullOrBlank()) {
            return ClientVerdict.HLS_ONLY
        }

        val formatCount =
            (streamingData.optJSONArray("adaptiveFormats")?.length() ?: 0) +
                (streamingData.optJSONArray("formats")?.length() ?: 0)
        if (formatCount > 0 && allFormatsDrmLocked(streamingData)) return ClientVerdict.DRM_ONLY

        val sabrUrl = streamingData.optString("serverAbrStreamingUrl").orEmpty()
        return if (formatCount > 0 || sabrUrl.isNotBlank()) {
            ClientVerdict.SABR_ONLY
        } else {
            ClientVerdict.NO_STREAMING_DATA
        }
    }

    /** True bila ada format dengan URL langsung / cipher yang bisa dipecahkan, tanpa DRM. */
    fun hasDirectStreamUrl(streamingData: JSONObject): Boolean {
        listOf("adaptiveFormats", "formats").forEach { key ->
            val array = streamingData.optJSONArray(key) ?: return@forEach
            for (i in 0 until array.length()) {
                val format = array.optJSONObject(i) ?: continue
                if (isDrmLocked(format)) continue
                if (!format.optString("url").isNullOrBlank()) return true
                if (!format.optString("signatureCipher").isNullOrBlank()) return true
                if (!format.optString("cipher").isNullOrBlank()) return true
            }
        }
        return false
    }

    /** Format ber-DRM tidak bisa diputar tanpa lisensi Widevine → dianggap tak berguna. */
    fun isDrmLocked(format: JSONObject): Boolean {
        val families = format.optJSONArray("drmFamilies")
        if (families != null && families.length() > 0) return true
        if (format.optInt("drmTrackCount", 0) > 0) return true
        return format.optString("drmFamilies").isNotBlank() ||
            format.optString("drmTrackType").isNotBlank()
    }

    /** True bila ada format tetapi semuanya ber-DRM (khas klien TV anonim). */
    fun allFormatsDrmLocked(streamingData: JSONObject): Boolean {
        var total = 0
        var locked = 0
        listOf("adaptiveFormats", "formats").forEach { key ->
            val array = streamingData.optJSONArray(key) ?: return@forEach
            for (i in 0 until array.length()) {
                val format = array.optJSONObject(i) ?: continue
                total++
                if (isDrmLocked(format)) locked++
            }
        }
        return total > 0 && locked == total
    }

    /** URL manifest HLS (`hlsManifestUrl`) bila ada — jalur putar tanpa poToken GVS. */
    fun hlsManifest(root: JSONObject?): String? =
        root?.optJSONObject("streamingData")
            ?.optString("hlsManifestUrl")
            ?.takeIf { it.isNotBlank() }

    /** Alasan `playabilityStatus` (untuk pesan error yang bisa ditindaklanjuti). */
    fun playabilityReason(root: JSONObject): String {
        val status = root.optJSONObject("playabilityStatus") ?: return ""
        val reason = status.optString("reason").orEmpty()
        return "${status.optString("status").orEmpty()}${if (reason.isBlank()) "" else ": $reason"}"
    }

    /**
     * Ringkasan keadaan tangga untuk laporan yang bisa disalin pengguna
     * (Settings → KESEHATAN STREAM → SALIN DIAGNOSTIK).
     */
    fun snapshot(): String = buildString {
        append("ladder: playback=").append(ordered(true).joinToString(",") { it.key }).append('\n')
        append("hls=").append(hlsSpecs().joinToString(",") { it.key }).append('\n')
        append("probe=").append(ordered(false).joinToString(",") { it.key }).append('\n')
        append("lastGood=").append(lastGood.get() ?: "-").append('\n')
        // visitorData wajib ada: tanpanya VISIONOS/ANDROID_VR 1.65.10 menjawab LOGIN_REQUIRED.
        // Baris ini yang membedakan "YouTube memblokir IP kita" dari "kita mengirim request
        // tanpa identitas sesi".
        append("visitor=").append(InnertubeConfig.visitorOrigin())
            .append(" present=").append(InnertubeConfig.visitor() != null)
            .append(" sts=").append(InnertubeConfig.signatureTimestamp() ?: "-").append('\n')
        append("sabrTotal=").append(sabrTotal.get())
            .append(" extractorBypassed=").append(extractorBypassed()).append('\n')
    }
}
