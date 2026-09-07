/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.yt.innertube

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Bangun permintaan InnerTube (youtubei/v1) yang siap dikirim ke Google. */
internal object InnertubeRequest {

    private val JSON = "application/json".toMediaType()

    enum class Client(
        val clientName: String,
        val version: String,
        val id: Int,
        val isEmbedded: Boolean = false,
    ) {
        ANDROID_VR("ANDROID_VR", "1.60.19", 28),
        ANDROID_TESTSUITE("ANDROID_TESTSUITE", "1.9", 119),
        TV_EMBEDDED("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", 85, isEmbedded = true),
        IOS("IOS", "19.45.4", 5),
        WEB_REMIX("WEB_REMIX", "1.20260818.01.00", 67),
        ANDROID("ANDROID", "19.45.38", 3),
        WEB("WEB", "2.20260818.01.00", 1),
    }

    fun baseContextJson(webVersion: String): String {
        val client = JSONObject()
            .put("clientName", Client.WEB.clientName)
            .put("clientVersion", webVersion)
            .put("hl", "en")
            .put("gl", "US")
        val context = JSONObject().put("client", client)
        return JSONObject().put("context", context).toString()
    }

    // Catatan: `player()` gaya lama (satu enum `Client`) sudah dihapus — semua
    // permintaan player kini lewat [playerFromSpec] + [PlayerClientSpec], sehingga
    // menambah/mengubah klien cukup dengan mengedit tabel di PlayerClientLadder.

    fun search(client: Client, version: String, query: String, params: String?, visitorData: String?): Request {
        val clientJson = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", version)
            .put("hl", "en")
            .put("gl", "US")
        visitorData?.let { clientJson.put("visitorData", it) }
        val context = JSONObject().put("client", clientJson)
        val payload = JSONObject()
            .put("context", context)
            .put("query", query)
        params?.let { payload.put("params", it) }
        val url = "https://www.youtube.com/youtubei/v1/search?key=${InnertubeConfig.apiKey()}&prettyPrint=false"
        return build(url, payload.toString(), client)
    }

    fun playlist(client: Client, version: String, playlistId: String, visitorData: String?): Request {
        val clientJson = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", version)
            .put("hl", "en")
            .put("gl", "US")
        visitorData?.let { clientJson.put("visitorData", it) }
        val context = JSONObject().put("client", clientJson)
        val payload = JSONObject()
            .put("context", context)
            .put("browseId", "VL$playlistId")
        val url = "https://www.youtube.com/youtubei/v1/browse?key=${InnertubeConfig.apiKey()}&prettyPrint=false"
        return build(url, payload.toString(), client)
    }

    fun next(client: Client, version: String, videoId: String, visitorData: String?): Request {
        val clientJson = JSONObject()
            .put("clientName", client.clientName)
            .put("clientVersion", version)
            .put("hl", "en")
            .put("gl", "US")
        visitorData?.let { clientJson.put("visitorData", it) }
        val context = JSONObject().put("client", clientJson)
        val payload = JSONObject()
            .put("context", context)
            .put("videoId", videoId)
        val url = "https://www.youtube.com/youtubei/v1/next?key=${InnertubeConfig.apiKey()}&prettyPrint=false"
        return build(url, payload.toString(), client)
    }

    // ------------------------------------------------------------------
    // Jalur tangga klien (PlayerClientLadder) — bentuk request disalin dari
    // `InnerTube.ytClient` + `InnerTube.player` milik Meld (FrancescoGrazioso/
    // Meld, September 2026), dikurangi semua yang berhubungan dengan akun.
    // ------------------------------------------------------------------

    /** Alfabet & panjang nonce: `cpn` 16 karakter. */
    private val NONCE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray()
    private val nonceRandom = java.security.SecureRandom()

    fun nonce(length: Int): String {
        val out = StringBuilder(length)
        repeat(length) { out.append(NONCE_ALPHABET[nonceRandom.nextInt(NONCE_ALPHABET.size)]) }
        return out.toString()
    }

    /**
     * Request player untuk satu [PlayerClientSpec].
     *
     * Bentuknya mengikuti Meld, yang mengukurnya di perangkat per September 2026:
     * - `POST https://{host}/youtubei/v1/player?prettyPrint=false` dengan host default
     *   **`music.youtube.com`** untuk SEMUA klien (termasuk ANDROID_VR/IOS) — bukan
     *   `www.youtube.com`, bukan `youtubei.googleapis.com`
     * - header: `X-Goog-Api-Format-Version: 1`, `X-YouTube-Client-Name: {clientId}`,
     *   `X-YouTube-Client-Version`, `Origin` + `X-Origin`, `Referer`, dan
     *   **`X-Goog-Visitor-Id` untuk setiap klien** — termasuk yang `loginSupported = false`.
     *   Meld: tanpa visitorData, VISIONOS dan ANDROID_VR 1.65.10 menjawab
     *   UNPLAYABLE/LOGIN_REQUIRED dengan nol format.
     * - body `context.client{…}` hanya berisi field yang benar-benar dikirim Meld:
     *   `clientName`, `clientVersion`, `osName`, `osVersion`, `deviceMake`, `deviceModel`,
     *   `androidSdkVersion`, `gl`, `hl`, `visitorData`. Tidak ada `clientScreen`,
     *   `platform`, `buildId`, `cronetVersion`, `packageName`, `utcOffsetMinutes`.
     * - `playbackContext.contentPlaybackContext.signatureTimestamp` HANYA untuk klien
     *   dengan `useSignatureTimestamp = true` (klien web/TV). VISIONOS/ANDROID_VR/IOS
     *   mengirim URL polos sehingga STS tidak dikirim sama sekali.
     * - `serviceIntegrityDimensions.poToken` hanya bila klien `useWebPoTokens` dan kita
     *   punya token. Lyreon anonim tidak membuat poToken → field ini tidak pernah terisi;
     *   parameternya tetap ada supaya bentuk body identik dengan Meld bila kelak
     *   generator poToken ditambahkan.
     *
     * Lyreon anonim: TIDAK ada header `Cookie` maupun `Authorization` yang dikirim.
     */
    fun playerFromSpec(
        spec: PlayerClientSpec,
        videoId: String,
        visitorData: String?,
        webVersion: String,
        signatureTimestamp: Int? = null,
        playlistId: String? = null,
        poToken: String? = null,
    ): Request = playerRequest(spec, spec.host, videoId, visitorData, webVersion, signatureTimestamp, playlistId, poToken)

    private fun playerRequest(
        spec: PlayerClientSpec,
        host: String,
        videoId: String,
        visitorData: String?,
        webVersion: String,
        signatureTimestamp: Int?,
        playlistId: String?,
        poToken: String?,
    ): Request {
        val version = spec.clientVersion.ifBlank { webVersion }
        val origin = "https://$host"

        val client = JSONObject()
            .put("clientName", spec.clientName)
            .put("clientVersion", version)
        spec.osName?.let { client.put("osName", it) }
        spec.osVersion?.let { client.put("osVersion", it) }
        spec.deviceMake?.let { client.put("deviceMake", it) }
        spec.deviceModel?.let { client.put("deviceModel", it) }
        if (spec.androidSdkVersion > 0) client.put("androidSdkVersion", spec.androidSdkVersion)
        client.put("gl", "US")
        client.put("hl", "en")
        // Harus konsisten dengan header X-Goog-Visitor-Id di bawah (catatan Meld di
        // `InnerTube.player`: "Must stay consistent with the X-Goog-Visitor-Id header").
        visitorData?.takeIf { it.isNotBlank() }?.let { client.put("visitorData", it) }

        val context = JSONObject().put("client", client)
        spec.embedUrlValue?.let { embed ->
            context.put("thirdParty", JSONObject().put("embedUrl", embed))
        }

        val payload = JSONObject()
            .put("context", context)
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
        if (!playlistId.isNullOrBlank()) payload.put("playlistId", playlistId)
        if (spec.useSignatureTimestamp && signatureTimestamp != null && signatureTimestamp > 0) {
            payload.put(
                "playbackContext",
                JSONObject().put(
                    "contentPlaybackContext",
                    JSONObject().put("signatureTimestamp", signatureTimestamp),
                ),
            )
        }
        if (spec.useWebPoTokens && !poToken.isNullOrBlank()) {
            payload.put(
                "serviceIntegrityDimensions",
                JSONObject().put("poToken", poToken),
            )
        }

        // `music.youtube.com` tidak butuh API key di query (Meld tidak mengirimnya);
        // host www tetap memakai key publik hasil scrape.
        val key = InnertubeConfig.apiKey()
        val url = buildString {
            append("https://").append(host).append("/youtubei/v1/player?prettyPrint=false")
            if (host != "music.youtube.com" && key.isNotBlank()) append("&key=").append(key)
        }

        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", spec.userAgent)
            .header("X-Goog-Api-Format-Version", "1")
            // "Bukan typo: header Client-Name memang berisi client id" — Meld.
            .header("X-YouTube-Client-Name", spec.clientId)
            .header("X-YouTube-Client-Version", version)
            .header("Origin", origin)
            .header("X-Origin", origin)
            .header("Referer", "$origin/")
        if (!visitorData.isNullOrBlank()) builder.header("X-Goog-Visitor-Id", visitorData)

        return builder.post(payload.toString().toRequestBody(JSON)).build()
    }

    /**
     * Ulangi request player yang sama ke host cadangan ([PlayerClientSpec.altHost]).
     * Hanya untuk kegagalan TRANSPORT (4xx/5xx, body bukan JSON) — bukan untuk
     * `playabilityStatus` yang menolak, karena itu keputusan sisi server yang tidak
     * berubah hanya dengan pindah host.
     */
    fun playerFromSpecAltHost(
        spec: PlayerClientSpec,
        videoId: String,
        visitorData: String?,
        webVersion: String,
        signatureTimestamp: Int? = null,
    ): Request? {
        val alt = spec.altHost ?: return null
        if (alt == spec.host) return null
        return playerRequest(spec, alt, videoId, visitorData, webVersion, signatureTimestamp, null, null)
    }

    private fun build(url: String, body: String, client: Client): Request {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent(client))
            .header("X-Youtube-Client-Name", client.id.toString())
            .header("X-Youtube-Client-Version", client.version)
            .header("Accept-Language", "en-US,en;q=0.9")

        when (client) {
            Client.WEB_REMIX -> {
                req.header("Origin", "https://music.youtube.com")
                req.header("Referer", "https://music.youtube.com/")
            }
            Client.WEB, Client.TV_EMBEDDED -> {
                req.header("Origin", "https://www.youtube.com")
                req.header("Referer", "https://www.youtube.com/")
            }
            else -> {
                // Android & iOS clients typically do not pass browser Origin/Referer
            }
        }

        return req.post(body.toRequestBody(JSON)).build()
    }

    private fun userAgent(client: Client): String = when (client) {
        Client.ANDROID_VR ->
            "com.google.android.apps.youtube.vr/1.60.19 (Linux; U; Android 14) gzip"
        Client.ANDROID_TESTSUITE ->
            "com.google.android.youtube/1.9 (Linux; U; Android 14) gzip"
        Client.TV_EMBEDDED ->
            "Mozilla/5.0 (PlayStation; PlayStation 4/11.50) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
        Client.IOS ->
            "com.google.ios.youtube/19.45.4 (iPhone14,5; U; CPU iOS 17_6 like Mac OS X)"
        Client.WEB_REMIX ->
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        Client.ANDROID ->
            "com.google.android.youtube/19.45.38 (Linux; U; Android 14; Pixel 8 Pro) gzip"
        Client.WEB ->
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }
}
