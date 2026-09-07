/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.vm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lyreon.app.core.ServiceLocator
import com.lyreon.app.data.db.DownloadEntity
import com.lyreon.app.data.db.DownloadState
import com.lyreon.app.data.db.PlaylistEntity
import com.lyreon.app.data.model.EditorialSection
import com.lyreon.app.data.model.LyreonGenre
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.model.SearchFilter
import com.lyreon.app.data.model.YtPlaylist
import com.lyreon.app.data.settings.AudioQuality
import com.lyreon.app.data.taste.MusicTextAnalyzer
import com.lyreon.app.yt.BrowseHeader
import com.lyreon.app.yt.BrowseSection
import com.lyreon.app.yt.MusicSearchSummary
import com.lyreon.app.yt.YouTubeRepository
import com.lyreon.app.yt.YtAlbum
import com.lyreon.app.yt.YtArtist
import com.lyreon.app.yt.YtSearchPlaylist
import com.lyreon.app.yt.YtSearchSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** CompositionLocal global untuk ServiceLocator. */
val LocalLyreon = staticCompositionLocalOf<ServiceLocator> {
    error("LocalLyreon belum di-provide")
}

@Composable
inline fun <reified VM : ViewModel> lyreonViewModel(
    locator: ServiceLocator = LocalLyreon.current,
    key: String? = null,
    crossinline build: (ServiceLocator) -> VM,
): VM = viewModel(key = key, factory = viewModelFactory { initializer { build(locator) } })

// ======================================================================
// HOME
// ======================================================================

data class HomeUiState(
    val loading: Boolean = true,
    val quickPicks: List<LyreonTrack> = emptyList(),
    val selectedGenreId: String? = null,
    val movementTracks: List<LyreonTrack> = emptyList(),
    val movementLoading: Boolean = false,
    val history: List<LyreonTrack> = emptyList(),
    val likedCount: Int = 0,
    val personalized: Boolean = false,
    /** Algoritma selera: lagu "Untuk Kamu" + chip vibe personality. */
    val personaTracks: List<LyreonTrack> = emptyList(),
    val personaChips: List<String> = emptyList(),
    val personaActive: Boolean = false,
    /** Artis populer per benua (dipindah dari Search — dimuat malas saat seksi terlihat) */
    val artistRegion: YouTubeRepository.ArtistRegion = YouTubeRepository.ArtistRegion.ASIA,
    val artists: List<YouTubeRepository.ArtistCard> = emptyList(),
    val artistsLoading: Boolean = false,
    val artistSectionShown: Boolean = false,
    /** Tren per negara: lagu tren + genre/mood yang sedang naik di negara itu. */
    val trendingTracks: List<LyreonTrack> = emptyList(),
    val trendingGenres: List<com.lyreon.app.yt.BrowseItem> = emptyList(),
    val trendingLoading: Boolean = false,
    val trendingCountry: String = "",
    val trendingCountryCode: String = "",
    val trendingSectionShown: Boolean = false,
    val error: String? = null,
)

class HomeViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val movementCache = mutableMapOf<String, List<LyreonTrack>>()

    init {
        refresh()
        observeLocal()
    }

    private fun observeLocal() {
        viewModelScope.launch {
            combine(
                locator.library.history,
                locator.library.likedTracks,
            ) { history, liked -> history to liked }
                .collect { (history, liked) ->
                    _state.update { it.copy(history = history.take(10), likedCount = liked.size) }
                }
        }
        viewModelScope.launch {
            locator.library.history.collect { hist ->
                if (_state.value.quickPicks.isEmpty() && !picksRequested) {
                    picksRequested = true
                    loadQuickPicks(hist.firstOrNull()?.videoId)
                }
            }
        }
    }

    @Volatile
    private var picksRequested = false

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // Personalisasi: benih = lagu yang PALING SERING diputar pengguna,
            // lalu ambil trek terkait dari YouTube. Tanpa riwayat → populer umum.
            val seed = runCatching {
                locator.library.mostPlayed.first().firstOrNull()?.videoId
            }.getOrNull()
            val picks = runCatching {
                locator.youtube.quickPicks(seed)
            }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    loading = false,
                    quickPicks = picks.ifEmpty { it.quickPicks },
                    personalized = picks.isNotEmpty() && seed != null,
                    error = if (picks.isEmpty() && it.quickPicks.isEmpty()) {
                        "Tidak bisa memuat feed. Periksa koneksi."
                    } else null,
                )
            }
            loadPersona()
            autoPickPersonaGenre()
        }
    }

    // ------------------------------------------------------------------
    // Algoritma selera di Home — "Untuk Kamu" dari query persona +
    // chip vibe (hashtag/token yang ditulis kreator di deskripsi video).
    // ------------------------------------------------------------------

    private var personaCache: Pair<Long, List<LyreonTrack>>? = null
    private var autoGenrePicked = false

    fun loadPersona(force: Boolean = false) {
        viewModelScope.launch {
            val taste = locator.taste
            val chips = taste.personalityChips(6)
            val profile = taste.profile.value
            if (!profile.isRich) {
                _state.update { it.copy(personaChips = chips, personaActive = false) }
                return@launch
            }
            val cached = personaCache
                ?.takeIf { !force && System.currentTimeMillis() - it.first < 20L * 60_000L }
            val tracks = cached?.second ?: run {
                val query = taste.personalityQuery() ?: return@launch
                locator.youtube.searchTracks(query, limit = 18)
                    .distinctBy { it.videoId }
                    .map { it to taste.scoreTrack(it) }
                    .sortedByDescending { it.second }
                    .map { it.first }
                    .take(10)
                    .also { personaCache = System.currentTimeMillis() to it }
            }
            _state.update {
                it.copy(
                    personaTracks = tracks,
                    personaChips = chips,
                    personaActive = tracks.isNotEmpty(),
                )
            }
        }
    }

    /** Genre rail: pilih otomatis (sekali) genre yang paling cocok dgn personality. */
    private fun autoPickPersonaGenre() {
        if (autoGenrePicked || _state.value.selectedGenreId != null) return
        autoGenrePicked = true
        val top = locator.taste.profile.value.topGenres(1).firstOrNull() ?: return
        com.lyreon.app.data.model.LyreonArchive.genres
            .firstOrNull {
                it.name.equals(top, true) || it.searchQuery.contains(top, true)
            }
            ?.let { selectGenre(it) }
    }

    // ------------------------------------------------------------------
    // Artis populer per benua — dipindah dari tab Search ke Home supaya
    // tab Search tetap ringan & bebas force close.
    // DIMUAT MALAS: fetch baru berjalan saat seksinya benar-benar dirender.
    // ------------------------------------------------------------------

    private val artistCache = HashMap<YouTubeRepository.ArtistRegion, List<YouTubeRepository.ArtistCard>>()

    /** Dipanggil sekali saat seksi artis muncul di layar (LaunchedEffect). */
    fun ensureArtistSection() {
        if (_state.value.artistSectionShown) return
        _state.update { it.copy(artistSectionShown = true) }
        // Tunda sejenak agar greeting/persona Home stabil dulu — anti jank
        viewModelScope.launch {
            kotlinx.coroutines.delay(600L)
            selectArtistRegion(defaultArtistRegion())
        }
    }

    private fun defaultArtistRegion(): YouTubeRepository.ArtistRegion =
        when (com.lyreon.app.core.LocaleHelper.currentTag(locator.app).lowercase()) {
            "in", "id", "ms", "zh", "ja", "hi" -> YouTubeRepository.ArtistRegion.ASIA
            "en" -> YouTubeRepository.ArtistRegion.AMERICA
            else -> YouTubeRepository.ArtistRegion.ASIA
        }

    fun selectArtistRegion(region: YouTubeRepository.ArtistRegion) {
        val cached = artistCache[region]
        _state.update { it.copy(artistRegion = region) }
        if (cached != null) {
            _state.update { it.copy(artists = cached, artistsLoading = false) }
            return
        }
        if (_state.value.artistsLoading) return
        viewModelScope.launch {
            _state.update { it.copy(artistsLoading = true) }
            val artists = runCatching { locator.youtube.topArtists(region) }
                .getOrDefault(emptyList())
            artistCache[region] = artists
            _state.update {
                if (it.artistRegion == region) it.copy(artists = artists, artistsLoading = false)
                else it.copy(artistsLoading = false)
            }
        }
    }

    // ------------------------------------------------------------------
    // Tren per negara — "genre yang tren di negara masing-masing".
    //
    // Negara diambil dari locale perangkat (bukan bahasa app), karena tren musik
    // mengikuti lokasi, bukan bahasa antarmuka: pengguna berbahasa Inggris di
    // Jakarta tetap ingin tren Indonesia.
    //
    // Dua sumber digabung:
    //  - lagu tren YouTube per `gl` (extractor, sudah ada);
    //  - rak genre/mood YouTube Music `FEmusic_moods_and_genres` dengan `gl`
    //    negara itu, sehingga daftar genrenya benar-benar lokal.
    // ------------------------------------------------------------------

    /** Dipanggil sekali saat seksinya muncul di layar (LaunchedEffect). */
    fun ensureTrendingSection() {
        if (_state.value.trendingSectionShown) return
        _state.update { it.copy(trendingSectionShown = true) }
        viewModelScope.launch {
            // Beri ruang bagi greeting/persona supaya frame pertama tetap mulus.
            delay(450L)
            val cc = deviceCountry()
            val countryName = runCatching {
                java.util.Locale("", cc).getDisplayCountry(
                    java.util.Locale(com.lyreon.app.core.LocaleHelper.currentTag(locator.app)),
                ).ifBlank { cc }
            }.getOrDefault(cc)
            _state.update { it.copy(trendingLoading = true, trendingCountryCode = cc, trendingCountry = countryName) }

            val tracks = runCatching { locator.youtube.trending(cc) }.getOrDefault(emptyList())
            val genres = runCatching {
                locator.youtube.browsePage("FEmusic_moods_and_genres", null, cc)
            }.getOrNull()?.sections
                ?.flatMap { it.items }
                ?.filter { it.browseId.isNotBlank() }
                ?.distinctBy { it.browseId }
                ?.take(14)
                .orEmpty()

            _state.update {
                it.copy(
                    trendingLoading = false,
                    trendingTracks = tracks,
                    trendingGenres = genres,
                    // Jangan menjanjikan nama negara bila hasilnya kosong.
                    trendingCountry = if (tracks.isEmpty() && genres.isEmpty()) "" else countryName,
                )
            }
        }
    }

    /** Kode negara ISO dari locale perangkat; jatuh ke ID bila tak dikenali. */
    private fun deviceCountry(): String {
        val fromDevice = runCatching { java.util.Locale.getDefault().country }.getOrDefault("")
        if (fromDevice.length == 2) return fromDevice.uppercase()
        return when (com.lyreon.app.core.LocaleHelper.currentTag(locator.app).lowercase()) {
            "id", "in" -> "ID"
            "ms" -> "MY"
            "hi" -> "IN"
            "zh" -> "CN"
            "ja" -> "JP"
            "en" -> "US"
            else -> "US"
        }
    }

    private fun loadQuickPicks(seed: String?) {
        viewModelScope.launch {
            val picks = locator.youtube.quickPicks(seed)
            if (picks.isNotEmpty()) {
                _state.update { it.copy(quickPicks = picks, loading = false) }
            }
        }
    }

    fun selectGenre(genre: LyreonGenre?) {
        if (genre == null) {
            _state.update { it.copy(selectedGenreId = null, movementTracks = emptyList()) }
            return
        }
        _state.update { it.copy(selectedGenreId = genre.id) }
        loadMovement(genre)
    }

    fun loadMovement(genre: LyreonGenre) {
        val cached = movementCache[genre.id]
        if (cached != null) {
            _state.update { it.copy(movementTracks = cached, movementLoading = false) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(movementLoading = true) }
            val tracks = locator.youtube.movement(genre.searchQuery)
            movementCache[genre.id] = tracks
            _state.update {
                if (it.selectedGenreId == genre.id) {
                    it.copy(movementTracks = tracks, movementLoading = false)
                } else it.copy(movementLoading = false)
            }
        }
    }

    fun playGenre(genre: LyreonGenre, play: (List<LyreonTrack>) -> Unit) {
        viewModelScope.launch {
            val tracks = movementCache[genre.id]
                ?: locator.youtube.movement(genre.searchQuery).also { movementCache[genre.id] = it }
            if (tracks.isNotEmpty()) play(tracks)
        }
    }

    fun playEditorial(section: EditorialSection, play: (List<LyreonTrack>) -> Unit) {
        viewModelScope.launch {
            val tracks = locator.youtube.movement(section.searchQuery, limit = 20)
            if (tracks.isNotEmpty()) play(tracks)
        }
    }
}

// ======================================================================
// SEARCH
// ======================================================================

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.SONGS,
    val suggestions: List<String> = emptyList(),
    val tracks: List<LyreonTrack> = emptyList(),
    /** Lagu dari perangkat (MediaStore + folder kustom) yang cocok dengan kueri. */
    val localTracks: List<LyreonTrack> = emptyList(),
    val playlists: List<YtPlaylist> = emptyList(),
    val searching: Boolean = false,
    val loadingMore: Boolean = false,
    val hasNext: Boolean = false,
    val searched: Boolean = false,
    val error: String? = null,
    val trending: List<LyreonTrack> = emptyList(),
    val trendingLoading: Boolean = false,
    val trendingCountry: String = "",
    // Hasil YouTube Music (WEB_REMIX): artis & album tidak dikenal extractor,
    // jadi keduanya hanya bisa datang dari jalur ini.
    val artists: List<YtArtist> = emptyList(),
    val albums: List<YtAlbum> = emptyList(),
    val musicPlaylists: List<YtSearchPlaylist> = emptyList(),
    val musicSearching: Boolean = false,
)

class SearchViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val youtube: YouTubeRepository get() = locator.youtube

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var session: YtSearchSession? = null
    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    init {
        // Hanya trending — jalur kartu artis dipindah ke Home agar tab ini ringan
        loadTrending()
    }

    /** Pemetaan bahasa aplikasi → region YouTube untuk halaman Trending. */
    private fun countryForLanguage(tag: String): String = when (tag.lowercase()) {
        "id", "in" -> "ID"
        "ms" -> "MY"
        "hi" -> "IN"
        "zh" -> "CN"
        "ja" -> "JP"
        "en" -> "US"
        else -> java.util.Locale.getDefault().country.ifBlank { "US" }
    }

    fun loadTrending() {
        if (_state.value.trendingLoading || _state.value.trending.isNotEmpty()) return
        viewModelScope.launch {
            val cc = countryForLanguage(com.lyreon.app.core.LocaleHelper.currentTag(locator.app))
            val countryName = runCatching {
                java.util.Locale("", cc).displayCountry.ifBlank { cc }
            }.getOrDefault(cc)
            _state.update { it.copy(trendingLoading = true, trendingCountry = countryName) }
            val tracks = youtube.trending(cc)
            _state.update {
                it.copy(
                    trendingLoading = false,
                    trending = tracks,
                    trendingCountry = if (tracks.isEmpty()) "" else countryName,
                )
            }
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        suggestJob?.cancel()
        if (q.length >= 2) {
            suggestJob = viewModelScope.launch {
                delay(280L)
                val list = youtube.suggestions(q)
                _state.update { it.copy(suggestions = list) }
            }
        } else {
            _state.update { it.copy(suggestions = emptyList()) }
        }
    }

    fun setFilter(filter: SearchFilter) {
        _state.update { it.copy(filter = filter) }
        if (_state.value.searched) search()
    }

    /**
     * Cap reupload/varian judul-sama (mis. belasan reupload "speed up reverb"
     * dari channel beda-beda) — kentara parah di filter SEMUA (unfiltered
     * YouTube search). List ini yang dipakai juga buat antrean pas diputar,
     * jadi cap di sini otomatis benerin dua-duanya.
     */
    private fun capNearDuplicates(tracks: List<LyreonTrack>, maxPerCoreTitle: Int = 2): List<LyreonTrack> {
        val perCoreTitle = HashMap<String, Int>()
        return tracks.filter { t ->
            val ct = MusicTextAnalyzer.coreTitle(t.title)
            if (ct.isBlank()) return@filter true
            val n = perCoreTitle.getOrDefault(ct, 0)
            if (n >= maxPerCoreTitle) return@filter false
            perCoreTitle[ct] = n + 1
            true
        }
    }

    fun search(queryOverride: String? = null) {
        val q = (queryOverride ?: _state.value.query).trim()
        if (q.isBlank()) return
        if (queryOverride != null) _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Query = sinyal niat — catat ke profil selera
            runCatching { locator.taste.recordSearch(q) }
            _state.update { it.copy(searching = true, musicSearching = true, error = null, suggestions = emptyList()) }
            val filter = _state.value.filter

            // Dua jalur PARALEL, bukan berurutan:
            //  - extractor NewPipe  → lagu berdurasi tepat + kelanjutan halaman;
            //  - YouTube Music      → artis, album, playlist (yang tidak dikenal
            //    extractor sama sekali).
            // Dijalankan bersamaan supaya filter SEMUA tidak menunggu dua RTT.
            val musicDeferred = async { runCatching { youtube.musicSearch(q, filter) }.getOrDefault(MusicSearchSummary()) }

            // Jalur ketiga PARALEL: musik lokal perangkat. Hanya untuk filter
            // SEMUA/LAGU — VIDEO/ALBUM/ARTIS/PLAYLIST/PODCAST murni YouTube.
            // MediaStore memakai SQL LIKE (murah); folder kustom di-walk. Tanpa
            // izin baca audio → hasil kosong (bukan error), layar tetap jalan.
            val includeLocal = filter == SearchFilter.SONGS || filter == SearchFilter.ALL
            val localDeferred = async {
                if (includeLocal) {
                    runCatching { locator.local.search(q, limit = 60) }.getOrDefault(emptyList())
                } else emptyList()
            }

            // Filter ARTIS murni dari YouTube Music: extractor tidak punya jalur artis.
            if (filter == SearchFilter.ARTISTS) {
                val summary = musicDeferred.await()
                _state.update {
                    it.copy(
                        searching = false,
                        musicSearching = false,
                        searched = true,
                        artists = summary.artists,
                        albums = emptyList(),
                        tracks = summary.songs,
                        localTracks = emptyList(),
                        playlists = emptyList(),
                        hasNext = false,
                        // Tanpa string error: keadaan kosong dirender layar
                        // (teks UI harus dari resource, bukan literal).
                        error = null,
                    )
                }
                return@launch
            }

            val s = youtube.newSearchSession(q, filter)
            session = s
            runCatching {
                val outcome = youtube.mapSearchItems(s.first())
                // Relevan YouTube tetap dasar; afinitas selera menaikkan yang
                // cocok (tokoh/gaya yang sering dicari) — mirip ranking YouTube.
                val ranked = if (locator.taste.profile.value.isRich) {
                    outcome.tracks
                        .mapIndexed { i, t ->
                            t to ((outcome.tracks.size - i) * 0.15f + locator.taste.scoreTrack(t))
                        }
                        .sortedByDescending { it.second }
                        .map { it.first }
                } else outcome.tracks
                // dedupe videoId + cap reupload judul-sama (fix antrean kebanjiran duplikat)
                val deduped = capNearDuplicates(ranked.distinctBy { it.videoId })
                val summary = musicDeferred.await()
                _state.update {
                    it.copy(
                        searching = false,
                        musicSearching = false,
                        searched = true,
                        tracks = deduped,
                        localTracks = localDeferred.await(),
                        playlists = outcome.playlists,
                        hasNext = s.hasNext,
                        artists = summary.artists,
                        albums = summary.albums,
                        musicPlaylists = summary.playlists,
                    )
                }
            }.onFailure { e ->
                // FALLBACK: Metrolist gagal → jalur InnerTube langsung ke Google
                // (searchTracks memakai fallback internal; SONGS paling umum dipakai).
                val fallbackTracks = runCatching {
                    youtube.searchTracks(q, limit = 30)
                }.getOrDefault(emptyList())
                // Walau extractor gagal, ringkasan YouTube Music sering berhasil —
                // jangan membuang artis/album hanya karena jalur lagu bermasalah.
                val summary = runCatching { musicDeferred.await() }.getOrDefault(MusicSearchSummary())
                if (fallbackTracks.isNotEmpty() || !summary.isEmpty) {
                    val deduped = capNearDuplicates(
                        fallbackTracks.ifEmpty { summary.songs }.distinctBy { it.videoId },
                    )
                    _state.update {
                        it.copy(
                            searching = false,
                            musicSearching = false,
                            searched = true,
                            tracks = deduped,
                            localTracks = localDeferred.await(),
                            playlists = emptyList(),
                            hasNext = false,
                            artists = summary.artists,
                            albums = summary.albums,
                            musicPlaylists = summary.playlists,
                            error = null,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            searching = false,
                            musicSearching = false,
                            searched = true,
                            // Lagu lokal tetap ditampilkan walau YouTube gagal.
                            localTracks = localDeferred.await(),
                            error = "Pencarian gagal: ${e.message ?: "jaringan"}",
                        )
                    }
                }
            }
        }
    }

    fun loadMore() {
        val s = session ?: return
        if (!s.hasNext || _state.value.loadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            runCatching {
                val outcome = youtube.mapSearchItems(s.more())
                _state.update {
                    it.copy(
                        loadingMore = false,
                        tracks = capNearDuplicates((it.tracks + outcome.tracks).distinctBy { t -> t.videoId }),
                        playlists = (it.playlists + outcome.playlists).distinctBy { p -> p.url },
                        hasNext = s.hasNext,
                    )
                }
            }.onFailure {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }
}

// ======================================================================
// LIBRARY
// ======================================================================

data class LibraryUiState(
    val tab: LibraryTab = LibraryTab.FAVORIT,
    val liked: List<LyreonTrack> = emptyList(),
    val playlists: List<com.lyreon.app.data.db.PlaylistWithStats> = emptyList(),
    val history: List<LyreonTrack> = emptyList(),
    val downloads: List<DownloadEntity> = emptyList(),
)

enum class LibraryTab(val label: String) { FAVORIT("FAVORIT"), PLAYLIST("PLAYLIST"), RIWAYAT("RIWAYAT"), OFFLINE("OFFLINE"), LOCAL("LOKAL") }

class LibraryViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                locator.library.likedTracks,
                locator.library.playlistsWithStats,
                locator.library.history,
                locator.library.downloads,
            ) { liked, playlists, history, downloads ->
                LibraryUiState(
                    tab = _state.value.tab,
                    liked = liked,
                    playlists = playlists,
                    history = history,
                    downloads = downloads,
                )
            }.collect { newState -> _state.update { newState } }
        }
    }

    fun selectTab(tab: LibraryTab) = _state.update { it.copy(tab = tab) }

    fun createPlaylist(name: String) {
        viewModelScope.launch { locator.library.createPlaylist(name) }
    }

    fun deletePlaylist(entity: PlaylistEntity) {
        viewModelScope.launch { locator.library.deletePlaylist(entity) }
    }

    fun renamePlaylist(id: Long, name: String) {
        viewModelScope.launch { locator.library.renamePlaylist(id, name) }
    }

    fun clearHistory() {
        viewModelScope.launch { locator.library.clearHistory() }
    }
}

// ======================================================================
// MUSIK LOKAL (file di penyimpanan perangkat)
// ======================================================================

data class LocalMusicUiState(
    val permission: Boolean = true,
    val scanning: Boolean = false,
    val scannedOnce: Boolean = false,
    val tracks: List<LyreonTrack> = emptyList(),
    val trees: List<String> = emptyList(),
)

class LocalMusicViewModel(private val locator: ServiceLocator) : ViewModel() {

    private val _state = MutableStateFlow(LocalMusicUiState())
    val state: StateFlow<LocalMusicUiState> = _state.asStateFlow()

    init {
        refreshPermission()
    }

    /** Dipanggil saat layar tampil/resume — izin bisa berubah dari Settings. */
    fun refreshPermission() {
        val granted = locator.local.hasPermission()
        _state.update { it.copy(permission = granted) }
        if (granted && !_state.value.scannedOnce) scan()
    }

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(permission = granted) }
        if (granted) scan()
    }

    fun addTree(uri: android.net.Uri) {
        locator.local.addTree(uri)
        scan()
    }

    fun removeTree(treeUri: String) {
        locator.local.removeTree(treeUri)
        scan()
    }

    fun treeLabel(treeUri: String): String = locator.local.treeLabel(treeUri)

    fun scan() {
        if (_state.value.scanning) return
        viewModelScope.launch {
            _state.update { it.copy(scanning = true, trees = locator.local.trees()) }

            // Gabung dua jalur: indeks MediaStore + folder kustom pilihan user
            val found = dedupe(locator.local.scanMediaStore() + locator.local.scanTrees())
            _state.update { it.copy(tracks = found, scannedOnce = true, permission = locator.local.hasPermission()) }

            // Ekstraksi art lagu MediaStore, progresif per 8 lagu
            found.filter {
                it.thumbnailUrl.isBlank() &&
                    !it.videoId.startsWith(com.lyreon.app.local.LocalMusicRepository.SAF_PREFIX)
            }.chunked(8).forEach { chunk ->
                val arts = chunk.mapNotNull { t ->
                    locator.local.embeddedArt(t)?.let { art -> t.videoId to art }
                }
                if (arts.isNotEmpty()) {
                    val map = arts.toMap()
                    _state.update { st ->
                        st.copy(
                            tracks = st.tracks.map { t ->
                                map[t.videoId]?.let { art -> t.copy(thumbnailUrl = art) } ?: t
                            },
                        )
                    }
                }
                kotlinx.coroutines.yield()
            }

            // Enrich metadata lagu SAF (judul/artis/durasi asli + art) per 6 lagu
            found.filter {
                it.videoId.startsWith(com.lyreon.app.local.LocalMusicRepository.SAF_PREFIX)
            }.chunked(6).forEach { chunk ->
                val enriched = locator.local.enrichSafChunk(chunk)
                val map = enriched.associateBy { it.videoId }
                _state.update { st ->
                    st.copy(tracks = st.tracks.map { t -> map[t.videoId] ?: t })
                }
                kotlinx.coroutines.yield()
            }

            _state.update { it.copy(scanning = false) }
        }
    }

    /** Lagu yang sama bisa muncul di MediaStore DAN folder kustom — satukan. */
    private fun dedupe(list: List<LyreonTrack>): List<LyreonTrack> {
        val seen = HashSet<String>()
        return list
            .sortedBy { it.title.lowercase() }
            .filter { t -> seen.add(t.title.lowercase().trim()) }
    }
}

// ======================================================================
// PLAYLIST DETAIL
// ======================================================================

class PlaylistViewModel(private val locator: ServiceLocator, val playlistId: Long) : ViewModel() {

    val tracks: StateFlow<List<LyreonTrack>> =
        locator.library.playlistItems(playlistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun remove(videoId: String) {
        viewModelScope.launch { locator.library.removeFromPlaylist(playlistId, videoId) }
    }

    fun move(from: Int, to: Int) {
        viewModelScope.launch { locator.library.moveInPlaylist(playlistId, from, to) }
    }

    fun rename(name: String) {
        viewModelScope.launch { locator.library.renamePlaylist(playlistId, name) }
    }
}

// ======================================================================
// DOWNLOADS
// ======================================================================

class DownloadsViewModel(private val locator: ServiceLocator) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntity>> =
        locator.library.downloads
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun cancel(videoId: String) {
        viewModelScope.launch { locator.downloads.cancel(videoId) }
    }

    fun remove(videoId: String) {
        viewModelScope.launch { locator.downloads.remove(videoId) }
    }

    fun retry(videoId: String) {
        viewModelScope.launch { locator.downloads.retry(videoId) }
    }

    fun isDone(e: DownloadEntity) = e.state == DownloadState.DONE
}

// ======================================================================
// YOUTUBE PLAYLIST DETAIL
// ======================================================================

data class YtPlaylistUiState(
    val loading: Boolean = true,
    val name: String = "",
    val uploader: String = "",
    val thumbnailUrl: String = "",
    val tracks: List<LyreonTrack> = emptyList(),
    val error: String? = null,
    val saved: Boolean = false,
)

/**
 * Satu halaman browse InnerTube (artis, album, genre/mood, kategori).
 * Layar yang sama dipakai untuk semuanya — bedanya hanya browseId/params.
 */
data class BrowseUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val title: String = "",
    val sections: List<BrowseSection> = emptyList(),
    /** Kepala halaman artis/album: sampul, ringkasan, endpoint radio & acak. */
    val header: BrowseHeader = BrowseHeader(),
)

class BrowseViewModel(
    private val locator: ServiceLocator,
    private val browseId: String,
    private val params: String,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowseUiState())
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            val page = runCatching {
                locator.youtube.browsePage(browseId, params.ifBlank { null })
            }.getOrNull()
            _state.update {
                it.copy(
                    loading = false,
                    failed = page == null || page.isEmpty,
                    title = page?.title.orEmpty().ifBlank { it.title },
                    sections = page?.sections.orEmpty(),
                    header = page?.header ?: it.header,
                )
            }
        }
    }
}

class YtPlaylistViewModel(private val locator: ServiceLocator, private val url: String) : ViewModel() {

    private val _state = MutableStateFlow(YtPlaylistUiState())
    val state: StateFlow<YtPlaylistUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                locator.youtube.playlistDetail(url)
            }.onSuccess { d ->
                _state.update {
                    it.copy(
                        loading = false,
                        name = d.name,
                        uploader = d.uploader,
                        thumbnailUrl = d.thumbnailUrl,
                        tracks = d.tracks,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: "Gagal memuat playlist") }
            }
        }
    }

    fun saveAsLocal() {
        val st = _state.value
        if (st.tracks.isEmpty() || st.saved) return
        viewModelScope.launch {
            val id = locator.library.createPlaylist(st.name.ifBlank { "YouTube Playlist" })
            st.tracks.forEach { t -> locator.library.addToPlaylist(id, t) }
            _state.update { it.copy(saved = true) }
        }
    }
}

// ======================================================================
// SETTINGS
// ======================================================================

class SettingsViewModel(private val locator: ServiceLocator) : ViewModel() {

    private companion object {
        /** Video yang dipakai "Tes koneksi" bila tidak ada lagu yang diputar. */
        const val PROBE_VIDEO_ID = "dQw4w9WgXcQ"
    }

    val settings = locator.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.lyreon.app.data.settings.LyreonSettings())

    /** Kondisi jalur stream dari PlayerManager (OK / DEGRADED / TRIPPED). */
    val health = locator.player.health

    private val _probe = MutableStateFlow<YouTubeRepository.StreamProbe?>(null)
    val probe: StateFlow<YouTubeRepository.StreamProbe?> = _probe.asStateFlow()

    private val _probeRunning = MutableStateFlow(false)
    val probeRunning: StateFlow<Boolean> = _probeRunning.asStateFlow()

    /**
     * Uji seluruh jalur resolusi untuk satu video (link atau ID boleh) dan
     * laporkan klien mana yang masih memberi URL — diagnostik SABR di lapangan.
     */
    fun runStreamTest(input: String?) {
        if (_probeRunning.value) return
        _probeRunning.value = true
        viewModelScope.launch {
            val target = input?.trim()?.takeIf { it.isNotBlank() }
                ?: locator.player.state.value.currentTrack?.videoId
                ?: PROBE_VIDEO_ID
            _probe.value = runCatching { locator.youtube.probeStream(target) }.getOrNull()
            _probeRunning.value = false
        }
    }

    fun retryStream() = locator.player.retryAfterFix()

    fun dismissHealthAlert() = locator.player.dismissHealthAlert()

    /** Buang cache URL stream + riwayat diagnostik, lalu coba putar ulang. */
    fun resetStreaming() {
        locator.youtube.invalidateAll()
        com.lyreon.app.yt.innertube.PlayerClientLadder.reset()
        _probe.value = null
        retryStream()
    }

    /**
     * Laporan diagnostik lengkap sebagai teks — untuk tombol "SALIN" di layar
     * Kesehatan Stream, supaya pengguna bisa menempelkannya ke laporan bug tanpa
     * perlu logcat. Tidak memuat data pribadi apa pun (Lyreon anonim).
     */
    fun diagnosticsReport(): String {
        val probe = _probe.value
        val health = locator.player.health.value
        return buildString {
            // BuildConfig tidak diaktifkan di modul ini, jadi identitas app cukup
            // dari SDK_INT + versi rilis yang dibaca runtime.
            append("Lyreon (Android ").append(android.os.Build.VERSION.RELEASE)
            append(", SDK ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            append("health: ").append(health.level.name)
            append(" failures=").append(health.consecutiveFailures)
            append(" skips=").append(health.skipsInWindow)
            append(" sabr=").append(health.sabrSuspected).append('\n')
            append(com.lyreon.app.yt.innertube.PlayerClientLadder.snapshot())
            if (probe != null) {
                append("probe: ").append(probe.videoId)
                append(" extractorOk=").append(probe.extractorOk)
                append(" streams=").append(probe.extractorAudioStreams)
                append(" ").append(probe.extractorMs).append("ms\n")
                if (probe.extractorError.isNotBlank()) {
                    append("  extractorError: ").append(probe.extractorError).append('\n')
                }
                append("  ladder ").append(probe.ladder.totalMs).append("ms")
                append(" audio=").append(probe.ladder.audioUrlFound)
                append(" hls=").append(probe.ladder.manifestClient ?: "-")
                append(" sabr=").append(probe.ladder.sabrOnly)
                append(" drm=").append(probe.ladder.drmOnly)
                // `cdnRejected`/`urlOnly` = URL ada tapi tak terbaca (pratinjau ~1 MiB / 403).
                append(" cdnRejected=").append(probe.ladder.cdnRejected)
                append(" urlOnly=").append(probe.ladder.urlOnlyClient ?: "-")
                append(" visitor=").append(probe.ladder.visitorOrigin).append('\n')
                probe.ladder.attempts.forEach { a ->
                    append("   ").append(a.client).append(" → ").append(a.verdict)
                    append(" (").append(a.elapsedMs).append("ms)")
                    if (a.detail.isNotBlank()) append(" · ").append(a.detail)
                    append('\n')
                }
            }
            append("riwayat:\n")
            com.lyreon.app.yt.innertube.PlayerClientLadder.report().forEach {
                append("  ").append(it).append('\n')
            }
        }
    }

    fun setTheme(mode: com.lyreon.app.ui.theme.ThemeMode) {
        viewModelScope.launch { locator.settings.setThemeMode(mode) }
    }

    fun setReduceMotion(v: Boolean) {
        viewModelScope.launch { locator.settings.setReduceMotion(v) }
    }

    fun setQuality(q: AudioQuality) {
        viewModelScope.launch { locator.settings.setAudioQuality(q) }
    }

    fun setAutoplay(v: Boolean) {
        viewModelScope.launch { locator.settings.setAutoplayRelated(v) }
    }

    fun setDisplayName(v: String) {
        viewModelScope.launch { locator.settings.setDisplayName(v) }
    }

    fun setAccent(argb: Int) {
        viewModelScope.launch { locator.settings.setAccentArgb(argb) }
    }

    fun setFont(key: String) {
        viewModelScope.launch { locator.settings.setFontKey(key) }
    }

    fun setDynamicColor(v: Boolean) {
        viewModelScope.launch { locator.settings.setDynamicColor(v) }
    }

    fun setArtworkAccent(v: Boolean) {
        viewModelScope.launch { locator.settings.setArtworkAccent(v) }
    }

    fun setMotionBlur(v: Boolean) {
        viewModelScope.launch { locator.settings.setMotionBlur(v) }
    }

    fun setHistoryAutoQueue(v: Boolean) {
        viewModelScope.launch { locator.settings.setHistoryAutoQueue(v) }
    }

    /** Geser waktu lirik (langkah 500 ms, dijepit ±10 detik di repository). */
    fun nudgeLyricsOffset(deltaMs: Int) {
        viewModelScope.launch {
            val current = locator.settings.settings.first().lyricsOffsetMs
            locator.settings.setLyricsOffsetMs(current + deltaMs)
        }
    }

    fun resetLyricsOffset() {
        viewModelScope.launch { locator.settings.setLyricsOffsetMs(0) }
    }

    fun setKugou(v: Boolean) {
        viewModelScope.launch { locator.settings.setKugouEnabled(v) }
    }

    fun setSkipSilence(v: Boolean) {
        viewModelScope.launch { locator.settings.setSkipSilence(v) }
    }

    fun setSkipSilenceInstant(v: Boolean) {
        viewModelScope.launch { locator.settings.setSkipSilenceInstant(v) }
    }

    fun setPlaybackSpeed(v: Float) {
        viewModelScope.launch { locator.settings.setPlaybackSpeed(v) }
    }

    fun setPitchSemitones(v: Int) {
        viewModelScope.launch { locator.settings.setPitchSemitones(v) }
    }

    fun setNormalizeAudio(v: Boolean) {
        viewModelScope.launch { locator.settings.setNormalizeAudio(v) }
    }

    fun clearHistory() {
        viewModelScope.launch { locator.library.clearHistory() }
    }
}
