/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.yt

import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.model.YtPlaylist
import org.json.JSONArray
import org.json.JSONObject

/** Artis hasil pencarian YouTube Music. */
data class YtArtist(
    val name: String,
    val browseId: String,
    val thumbUrl: String = "",
    val subtitle: String = "",
    /** `RDAMVM…` / `RDCLAK…` dari tombol radio baris artis, bila ada. */
    val radioPlaylistId: String = "",
)

/** Album hasil pencarian YouTube Music. */
data class YtAlbum(
    val title: String,
    val browseId: String,
    val artist: String = "",
    val thumbUrl: String = "",
    val subtitle: String = "",
)

/** Playlist hasil pencarian YouTube Music. */
data class YtSearchPlaylist(
    val title: String,
    val playlistId: String,
    val author: String = "",
    val thumbUrl: String = "",
    val subtitle: String = "",
)

/**
 * Ringkasan pencarian YouTube Music: lagu, artis, album, dan playlist sekaligus
 * (pola `SearchSummaryPage` Metrolist — satu kueri, semua jenis hasil).
 */
data class MusicSearchSummary(
    val songs: List<LyreonTrack> = emptyList(),
    val artists: List<YtArtist> = emptyList(),
    val albums: List<YtAlbum> = emptyList(),
    val playlists: List<YtSearchPlaylist> = emptyList(),
) {
    val isEmpty: Boolean
        get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && playlists.isEmpty()
}

/**
 * Pengurai respons `youtubei/v1/search` klien WEB_REMIX (YouTube Music).
 *
 * Kenapa bukan extractor NewPipe: jalur extractor mengembalikan `videoRenderer`
 * YouTube biasa, yang TIDAK memuat artis maupun album sebagai entitas — hanya
 * video dan playlist. Untuk menampilkan kartu artis/album (permintaan pemilik
 * proyek: "hasil pencarian menampilkan artis/band/album") dibutuhkan rak
 * `musicShelfRenderer` dari YouTube Music.
 *
 * Bentuk yang ditangani:
 * - `contents.tabbedSearchResultsRenderer.tabs[].tabRenderer.content.sectionListRenderer`
 *   → rak `musicShelfRenderer` → `musicResponsiveListItemRenderer`
 * - kartu `musicTwoRowItemRenderer` (dipakai rak "Top result")
 *
 * Klasifikasi memakai `pageType` dari `browseEndpoint` (bukan judul rak, yang
 * terlokalisasi: "Artists" / "Artis" / "アーティスト").
 */
object MusicSearchParser {

    /** Batas aman: ringkasan tidak perlu ratusan butir per jenis. */
    private const val MAX_PER_KIND = 24

    fun parse(root: JSONObject): MusicSearchSummary {
        val songs = ArrayList<LyreonTrack>()
        val artists = ArrayList<YtArtist>()
        val albums = ArrayList<YtAlbum>()
        val playlists = ArrayList<YtSearchPlaylist>()
        val seenArtists = HashSet<String>()
        val seenAlbums = HashSet<String>()
        val seenSongs = HashSet<String>()
        val seenPlaylists = HashSet<String>()

        fun add(item: BrowseItem) {
            when (item.kind) {
                BrowseItemKind.ARTIST -> if (item.browseId.isNotBlank() && seenArtists.add(item.browseId)) {
                    artists += YtArtist(
                        name = item.title,
                        browseId = item.browseId,
                        thumbUrl = item.thumbUrl,
                        subtitle = item.subtitle,
                        radioPlaylistId = item.playlistId,
                    )
                }

                BrowseItemKind.ALBUM -> if (item.browseId.isNotBlank() && seenAlbums.add(item.browseId)) {
                    albums += YtAlbum(
                        title = item.title,
                        browseId = item.browseId,
                        artist = item.subtitle.substringBefore(" • "),
                        thumbUrl = item.thumbUrl,
                        subtitle = item.subtitle,
                    )
                }

                BrowseItemKind.PLAYLIST -> if (item.playlistId.isNotBlank() && seenPlaylists.add(item.playlistId)) {
                    playlists += YtSearchPlaylist(
                        title = item.title,
                        playlistId = item.playlistId,
                        author = item.subtitle.substringBefore(" • "),
                        thumbUrl = item.thumbUrl,
                        subtitle = item.subtitle,
                    )
                }

                BrowseItemKind.TRACK, BrowseItemKind.OTHER -> {
                    if (item.videoId.isBlank() || !seenSongs.add(item.videoId)) return
                    songs += LyreonTrack(
                        videoId = item.videoId,
                        title = item.title,
                        artist = item.subtitle.substringBefore(" • "),
                        album = item.subtitle.substringAfter(" • ", ""),
                        durationSec = item.durationSec.toLong(),
                        thumbnailUrl = item.thumbUrl,
                    )
                }
            }
        }

        forEachRenderer(root) { renderer ->
            val item = BrowseParser.parseRow(renderer) ?: return@forEachRenderer
            if (artists.size >= MAX_PER_KIND && albums.size >= MAX_PER_KIND &&
                songs.size >= MAX_PER_KIND * 2 && playlists.size >= MAX_PER_KIND
            ) {
                return@forEachRenderer
            }
            add(item)
        }

        forEachCard(root) { card ->
            val item = BrowseParser.parseCard(card) ?: return@forEachCard
            add(item)
        }

        // "Top result" dirender sebagai kartu khusus, bukan rak dua baris.
        forEachTopCard(root) { card -> add(topCardItem(card)) }

        return MusicSearchSummary(
            songs = songs,
            artists = artists.take(MAX_PER_KIND),
            albums = albums.take(MAX_PER_KIND),
            playlists = playlists.take(MAX_PER_KIND),
        )
    }

    // ------------------------------------------------------------------
    // Penelusuran rak
    // ------------------------------------------------------------------

    private fun forEachRenderer(root: JSONObject, visit: (JSONObject) -> Unit) {
        val contents = searchResults(root) ?: return
        for (s in 0 until contents.length()) {
            val node = contents.optJSONObject(s) ?: continue
            // Pencarian terfilter kadang memakai `itemSectionRenderer` biasa.
            val rows = node.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
                ?: node.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
                ?: continue
            for (i in 0 until rows.length()) {
                val renderer = rows.optJSONObject(i)
                    ?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                visit(renderer)
            }
        }
    }

    /** Kartu `musicCardShelfRenderer` = "Top result" (satu butir paling relevan). */
    private fun forEachTopCard(root: JSONObject, visit: (JSONObject) -> Unit) {
        val contents = searchResults(root) ?: return
        for (s in 0 until contents.length()) {
            val card = contents.optJSONObject(s)?.optJSONObject("musicCardShelfRenderer") ?: continue
            visit(card)
        }
    }

    /**
     * Bentuk `musicCardShelfRenderer` berbeda dari kartu rak biasa (identitas ada
     * di `onTap`, bukan di run judul), jadi dibangun di sini.
     */
    private fun topCardItem(card: JSONObject): BrowseItem {
        val onTap = card.optJSONObject("onTap")?.optJSONObject("browseEndpoint")
        val pageType = onTap
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
            .orEmpty()
        val playlistId = card.optJSONObject("onTap")
            ?.optJSONObject("watchPlaylistEndpoint")
            ?.optString("playlistId")
            .orEmpty()
        val titleRuns = card.optJSONObject("title")?.optJSONArray("runs")
        val watchVideoId = titleRuns
            ?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("watchEndpoint")
            ?.optString("videoId")
            .orEmpty()
        val thumb = card.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else "" }
            .orEmpty()
        return BrowseItem(
            kind = when {
                pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL" ->
                    BrowseItemKind.ARTIST
                pageType == "MUSIC_PAGE_TYPE_ALBUM" -> BrowseItemKind.ALBUM
                pageType == "MUSIC_PAGE_TYPE_PLAYLIST" || playlistId.isNotBlank() -> BrowseItemKind.PLAYLIST
                watchVideoId.isNotBlank() -> BrowseItemKind.TRACK
                else -> BrowseItemKind.OTHER
            },
            title = (0 until (titleRuns?.length() ?: 0)).joinToString("") {
                titleRuns?.optJSONObject(it)?.optString("text").orEmpty()
            },
            subtitle = card.optJSONObject("subtitle")?.optJSONArray("runs")?.let { runs ->
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }.orEmpty(),
            thumbUrl = thumb,
            videoId = watchVideoId,
            playlistId = playlistId,
            browseId = onTap?.optString("browseId").orEmpty(),
        )
    }

    private fun forEachCard(root: JSONObject, visit: (JSONObject) -> Unit) {
        val contents = searchResults(root) ?: return
        for (s in 0 until contents.length()) {
            val node = contents.optJSONObject(s) ?: continue
            val shelf = node.optJSONObject("musicCarouselShelfRenderer")
                ?: node.optJSONObject("musicImmersiveCarouselShelfRenderer")
            val cards: JSONArray? = shelf?.optJSONArray("contents")
                ?: node.optJSONObject("musicShelfRenderer")?.optJSONArray("contents")
            if (cards == null) continue
            for (i in 0 until cards.length()) {
                val card = cards.optJSONObject(i)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                visit(card)
            }
        }
    }

    /** Daftar rak di hasil pencarian, baik bertab maupun tidak. */
    private fun searchResults(root: JSONObject): JSONArray? {
        val tabbed = root.optJSONObject("contents")?.optJSONObject("tabbedSearchResultsRenderer")
        val tabs = tabbed?.optJSONArray("tabs")
        if (tabs != null) {
            for (t in 0 until tabs.length()) {
                val list = tabs.optJSONObject(t)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                if (list != null && list.length() > 0) return list
            }
            return null
        }
        return root.optJSONObject("contents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
    }
}
