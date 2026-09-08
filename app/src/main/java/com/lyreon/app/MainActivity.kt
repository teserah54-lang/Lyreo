/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import com.lyreon.app.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lyreon.app.core.LocaleHelper
import com.lyreon.app.core.ServiceLocator
import com.lyreon.app.data.model.LyreonTrack
import com.lyreon.app.data.model.YtPlaylist
import com.lyreon.app.player.StreamHealthLevel
import com.lyreon.app.ui.components.AddToPlaylistDialog
import com.lyreon.app.ui.components.DonateDialog
import com.lyreon.app.ui.components.MiniPlayerBar
import com.lyreon.app.ui.components.PlaylistOption
import com.lyreon.app.ui.components.SleepTimerDialog
import com.lyreon.app.ui.components.TrackActions
import com.lyreon.app.ui.components.TrackActionsSheet
import com.lyreon.app.ui.screens.ArchiveScreen
import com.lyreon.app.ui.screens.DownloadsScreen
import com.lyreon.app.ui.screens.EditorialDetailScreen
import com.lyreon.app.ui.screens.HomeScreen
import com.lyreon.app.ui.screens.LibraryScreen
import com.lyreon.app.ui.screens.BrowseScreen
import com.lyreon.app.ui.vm.BrowseViewModel
import com.lyreon.app.ui.screens.LicensesScreen
import com.lyreon.app.ui.screens.NowPlayingScreen
import com.lyreon.app.ui.screens.PlaylistDetailScreen
import com.lyreon.app.ui.screens.SearchScreen
import com.lyreon.app.ui.screens.SettingsScreen
import com.lyreon.app.ui.screens.YtPlaylistScreen
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonTheme
import com.lyreon.app.ui.theme.LyreonAccentSoft
import com.lyreon.app.ui.theme.LyreonChromeShape
import com.lyreon.app.ui.theme.LyreonHairline
import com.lyreon.app.ui.theme.LyreonSurfaceTranslucent
import com.lyreon.app.ui.theme.MotionBlurTransitionRadius
import com.lyreon.app.ui.theme.NO_ARTWORK_ACCENT
import com.lyreon.app.ui.theme.artworkAccentArgb
import com.lyreon.app.ui.theme.deviceSupportsMotionBlur
import com.lyreon.app.ui.theme.lyreonScreenEnter
import com.lyreon.app.ui.theme.lyreonScreenExit
import com.lyreon.app.ui.theme.lyreonScreenPopEnter
import com.lyreon.app.ui.theme.lyreonScreenPopExit
import com.lyreon.app.ui.theme.motionBlurLayer
import com.lyreon.app.ui.theme.rememberMotionBlurState
import com.lyreon.app.ui.theme.reduceMotionEnabled
import com.lyreon.app.ui.theme.resetArtworkAccent
import com.lyreon.app.ui.theme.scrollMotionBlur
import com.lyreon.app.ui.theme.systemAnimatorsEnabled
import com.lyreon.app.ui.theme.updateArtworkAccent
import androidx.compose.foundation.border
import com.lyreon.app.ui.vm.DownloadsViewModel
import com.lyreon.app.ui.vm.HomeViewModel
import com.lyreon.app.ui.vm.LibraryViewModel
import com.lyreon.app.ui.vm.LocalLyreon
import com.lyreon.app.ui.vm.PlaylistViewModel
import com.lyreon.app.ui.vm.SearchViewModel
import com.lyreon.app.ui.vm.SettingsViewModel
import com.lyreon.app.ui.vm.YtPlaylistViewModel
import com.lyreon.app.ui.vm.lyreonViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Jeda sebelum aksen sampul baru diterapkan ke tema (ms). Sedikit lebih panjang
 * dari transisi layar terlama (`LyreonMotion.deliberate` = 350 ms) supaya
 * perubahan palet tidak pernah jatuh di tengah animasi pindah halaman.
 */
private const val ARTWORK_ACCENT_SETTLE_MS = 480L

class MainActivity : ComponentActivity() {

    private var intentState = mutableStateOf<Intent?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        intentState.value = intent
        setContent {
            val app = application as LyreonApp
            CompositionLocalProvider(LocalLyreon provides app.locator) {
                val settingsVm: SettingsViewModel = lyreonViewModel { SettingsViewModel(it) }
                val settings by settingsVm.settings.collectAsStateWithLifecycle()

                // ---- Aksen dari palet sampul lagu aktif ----
                // Hanya URL thumbnail yang dikoleksi (bukan seluruh PlayerUiState)
                // supaya pergantian antrean/posisi tidak menyusun ulang tema.
                val player = app.locator.player
                val artworkUrl by remember(player) {
                    player.state
                        .map { it.currentTrack?.thumbnailUrl }
                        .distinctUntilChanged()
                }.collectAsStateWithLifecycle(initialValue = null)
                val artworkAccent by artworkAccentArgb.collectAsStateWithLifecycle()
                val appContext = applicationContext
                LaunchedEffect(settings.artworkAccent, artworkUrl) {
                    if (settings.artworkAccent) {
                        // Jeda kecil sebelum warna baru diterapkan: memutar lagu dari
                        // Home berarti "ganti lagu" DAN "pindah halaman" terjadi di
                        // frame yang sama. Menyuntik palet baru ke tema tepat saat
                        // transisi NavHost berjalan membuat layar Now Playing
                        // tersendat/berosilasi (notes/01 §B7). Transisi layar selesai
                        // ≤ ~350 ms, jadi tunggu sedikit lebih lama dari itu — ini
                        // hanya menunda WARNA, bukan pemutaran.
                        delay(ARTWORK_ACCENT_SETTLE_MS)
                        updateArtworkAccent(appContext, artworkUrl)
                    } else {
                        resetArtworkAccent()
                    }
                }

                LyreonTheme(
                    themeMode = settings.themeMode,
                    accentArgb = settings.accentArgb,
                    fontKey = settings.fontKey,
                    dynamicColor = settings.dynamicColor,
                    artworkAccentArgb = if (settings.artworkAccent) artworkAccent else NO_ARTWORK_ACCENT,
                    reduceMotion = settings.reduceMotion,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        LyreonRoot(
                            intentState = intentState,
                            motionBlurEnabled = settings.motionBlur,
                            onConsumeIntent = { intentState.value = null },
                        )
                        // Intro pembuka: logo + loading selaras tema, lalu memudar
                        com.lyreon.app.ui.components.LyreonIntroOverlay()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intentState.value = intent
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "lyreon.extra.OPEN_NOW_PLAYING"
        const val EXTRA_OPEN_DOWNLOADS = "lyreon.extra.OPEN_DOWNLOADS"
    }
}

private data class Dest(
    val route: String,
    @StringRes val labelRes: Int,
    val selected: ImageVector,
    val unselected: ImageVector,
)

// 5 tab sesuai branding: Home · Explore · Library · Playlist · Profil
private val primaryDestinations = listOf(
    Dest("home", R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home),
    Dest("search", R.string.nav_explore, Icons.Filled.Search, Icons.Outlined.Search),
    Dest("library", R.string.nav_library, Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    Dest("playlists", R.string.nav_playlist, Icons.Filled.QueueMusic, Icons.Outlined.QueueMusic),
    Dest("settings", R.string.nav_profile, Icons.Filled.Person, Icons.Outlined.Person),
)

@Composable
fun LyreonRoot(
    intentState: androidx.compose.runtime.State<Intent?>,
    motionBlurEnabled: Boolean = true,
    onConsumeIntent: () -> Unit,
) {
    val locator = LocalLyreon.current
    val player = remember { locator.player }
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val playerState by player.state.collectAsStateWithLifecycle()
    val videoMode by player.videoMode.collectAsStateWithLifecycle()
    val likedIds by locator.library.likedIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val downloadedIds by locator.library.downloadedIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val playlists by locator.library.playlistsWithStats.collectAsStateWithLifecycle(initialValue = emptyList())

    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route.orEmpty()

    // ---- Smooth motion blur ----
    // SATU lapis untuk seluruh isi: transisi layar memicunya penuh (14dp sesaat),
    // gulir cepat memicu versi kecilnya (~4dp). Saat diam tidak ada RenderEffect
    // sama sekali, jadi biaya idle nol. Mati total bila reduce motion aktif,
    // pengguna mematikannya, atau perangkat rendah memori.
    val motionBlurState = rememberMotionBlurState()
    val blurContext = androidx.compose.ui.platform.LocalContext.current
    val systemReduced = remember { !systemAnimatorsEnabled() }
    val blurActive = remember(blurContext, motionBlurEnabled, systemReduced) {
        motionBlurEnabled && !systemReduced && deviceSupportsMotionBlur(blurContext)
    }
    LaunchedEffect(route, blurActive) {
        if (!blurActive) return@LaunchedEffect
        motionBlurState.pulse()
        delay(90)
        motionBlurState.release()
    }

    val hideChrome = route.startsWith("now_playing") || route.startsWith("editorial") ||
        route.startsWith("playlist/") || route.startsWith("ytplaylist")

    val useRail = LocalConfiguration.current.screenWidthDp >= 840

    // --- Lembar aksi & dialog global ---
    var moreTrack by remember { mutableStateOf<LyreonTrack?>(null) }
    var playlistPickerTrack by remember { mutableStateOf<LyreonTrack?>(null) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }

    // --- Snackbar dari player events ---
    // Saat circuit breaker stream terbuka, snackbar membawa aksi: buka Pengaturan
    // (tempat memasang cookie akun / melihat diagnostik klien) atau coba lagi.
    val snackContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        player.events.collect { msg ->
            val tripped = player.health.value.level == StreamHealthLevel.TRIPPED
            val actionLabel = if (tripped) {
                snackContext.getString(R.string.stream_snackbar_action)
            } else {
                null
            }
            runCatching {
                val result = snackbarHostState.showSnackbar(msg, actionLabel = actionLabel)
                if (tripped && result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    navController.navigate("settings") { launchSingleTop = true }
                }
            }
        }
    }

    // --- Pop-up donasi (2× ganti lagu dalam satu sesi) ---
    LaunchedEffect(Unit) {
        player.donateRequests.collect { showDonate = true }
    }

    // --- Izin notifikasi (Android 13+) ---
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // --- Intent dari luar (share YouTube / deep link / notifikasi) ---
    LaunchedEffect(intentState.value) {
        val intent = intentState.value ?: return@LaunchedEffect
        when {
            intent.getBooleanExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, false) -> {
                intent.removeExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING)
                onConsumeIntent()
                navController.navigate("now_playing")
            }
            intent.getBooleanExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, false) -> {
                intent.removeExtra(MainActivity.EXTRA_OPEN_DOWNLOADS)
                onConsumeIntent()
                navController.navigate("downloads")
            }
            else -> {
                val url = extractYouTubeUrl(intent)
                val playlistUrl = extractPlaylistUrl(intent)
                when {
                    url != null && playlistUrl == null -> {
                        onConsumeIntent()
                        playSharedUrl(locator, url, navController) { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                    }
                    playlistUrl != null -> {
                        onConsumeIntent()
                        navController.navigate("ytplaylist/${Uri.encode(playlistUrl)}")
                    }
                }
            }
        }
    }

    // CATATAN PERFORMA: posisi/durasi TIDAK lagi dihitung di root —
    // ticker 500ms hidup di flow PlayerManager.position dan hanya dikoleksi
    // oleh MiniPlayerBar / NowPlayingScreen, sehingga layar di belakangnya
    // (Home/Library/Search) tidak ikut recompose 2×/detik saat lagu jalan.

    val navColors = NavigationBarItemDefaults.colors(
        selectedIconColor = com.lyreon.app.ui.theme.LyreonCrimson,
        selectedTextColor = com.lyreon.app.ui.theme.LyreonCrimson,
        indicatorColor = LyreonAccentSoft,
        unselectedIconColor = LyreonTextMuted,
        unselectedTextColor = LyreonTextMuted,
    )
    val railColors = NavigationRailItemDefaults.colors(
        selectedIconColor = com.lyreon.app.ui.theme.LyreonCrimson,
        selectedTextColor = com.lyreon.app.ui.theme.LyreonCrimson,
        indicatorColor = LyreonAccentSoft,
        unselectedIconColor = LyreonTextMuted,
        unselectedTextColor = LyreonTextMuted,
    )

    val context = androidx.compose.ui.platform.LocalContext.current

    fun likeTrack(track: LyreonTrack) {
        scope.launch {
            val nowLiked = locator.library.toggleLike(track)
            // Lagu disukai = sinyal terkuat algoritma selera
            if (nowLiked) locator.taste.recordLike(track)
        }
    }

    fun enqueueDownload(track: LyreonTrack) {
        if (track.isLocal) return // file lokal sudah ada di perangkat
        scope.launch {
            locator.downloads.enqueue(track)
            snackbarHostState.showSnackbar(context.getString(R.string.snack_download_queue, track.title))
        }
    }

    fun shareTrack(track: LyreonTrack, context: android.content.Context) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Lyreon · ${track.title} - ${track.artist}\n${track.watchUrl}")
        }
        runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser))) }
    }

    fun openTrackActions(track: LyreonTrack) {
        moreTrack = track
    }

    fun playQueryMovement(query: String) {
        scope.launch {
            val tracks = withContext(Dispatchers.IO) {
                locator.youtube.movement(query, limit = 20)
            }
            if (tracks.isEmpty()) {
                snackbarHostState.showSnackbar(context.getString(R.string.snack_no_tracks))
            } else {
                player.playQueue(tracks, 0)
                navController.navigate("now_playing")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LyreonBackground)) {
        val miniPlayer: @Composable () -> Unit = {
            MiniPlayerBar(
                track = playerState.currentTrack,
                isPlaying = playerState.isPlaying,
                isBuffering = playerState.isBuffering,
                player = player,
                // Chrome keluar (slide + fade) saat layar penuh dibuka.
                visible = !hideChrome,
                onToggle = player::toggle,
                onNext = player::next,
                onOpen = { navController.navigate("now_playing") },
            )
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = LyreonBackground,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    Column {
                        miniPlayer()
                        if (!hideChrome) {
                            // Floating island ala iOS — bar navigasi melayang membulat
                            Box(
                                modifier = Modifier
                                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
                                    .clip(LyreonChromeShape)
                                    .background(LyreonSurfaceTranslucent)
                                    .border(1.dp, LyreonHairline, LyreonChromeShape),
                            ) {
                                NavigationBar(
                                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                    contentColor = LyreonTextPrimary,
                                    tonalElevation = 0.dp,
                                ) {
                                    primaryDestinations.forEach { dest ->
                                        val selected = route == dest.route
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = {
                                                navController.navigate(dest.route) {
                                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    if (selected) dest.selected else dest.unselected,
                                                    contentDescription = stringResource(dest.labelRes),
                                                )
                                            },
                                            label = { Text(stringResource(dest.labelRes), style = MaterialTheme.typography.labelSmall) },
                                            colors = navColors,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useRail && !hideChrome) {
                    NavigationRail(
                        containerColor = LyreonElevated,
                        contentColor = LyreonTextPrimary,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    ) {
                        primaryDestinations.forEach { dest ->
                            val selected = route == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        if (selected) dest.selected else dest.unselected,
                                        contentDescription = stringResource(dest.labelRes),
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes), style = MaterialTheme.typography.labelSmall) },
                                colors = railColors,
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        // Koneksi nested scroll dipasang di INDUK semua layar: satu
                        // koneksi melayani setiap LazyColumn tanpa biaya per item.
                        .scrollMotionBlur(motionBlurState, enabled = blurActive)
                        .motionBlurLayer(
                            state = motionBlurState,
                            maxRadius = MotionBlurTransitionRadius,
                            enabled = blurActive,
                        ),
                ) {
                    if (useRail) {
                        miniPlayer()
                    }

                    LyreonNavHost(
                        navController = navController,
                        locator = locator,
                        playerState = playerState,
                        videoMode = videoMode,
                        likedIds = likedIds,
                        downloadedIds = downloadedIds,
                        reduceMotion = reduceMotionEnabled,
                        onLike = ::likeTrack,
                        onMore = ::openTrackActions,
                        onEnqueueDownload = ::enqueueDownload,
                        onSleepTimerClick = { showSleepTimer = true },
                        playMovement = ::playQueryMovement,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // ---- Overlays ----
    moreTrack?.let { track ->
        TrackActionsSheet(
            track = track,
            actions = TrackActions(
                onPlayNext = { player.playNext(track) },
                onAddToQueue = { player.addToQueue(track) },
                onAddToPlaylist = { playlistPickerTrack = track },
                onDownload = { enqueueDownload(track) },
                onToggleLike = { likeTrack(track) },
                onShare = { shareTrack(track, context) },
                isLiked = likedIds.contains(track.videoId),
                isDownloaded = downloadedIds.contains(track.videoId),
            ),
            onDismiss = { moreTrack = null },
        )
    }

    playlistPickerTrack?.let { track ->
        AddToPlaylistDialog(
            track = track,
            playlists = playlists.map { PlaylistOption(it.playlist.id, it.playlist.name, it.itemCount) },
            onSelect = { playlistId ->
                scope.launch {
                    locator.library.addToPlaylist(playlistId, track)
                    snackbarHostState.showSnackbar(context.getString(R.string.snack_saved_playlist))
                }
            },
            onCreateNew = { name ->
                scope.launch {
                    val id = locator.library.createPlaylist(name)
                    locator.library.addToPlaylist(id, track)
                    snackbarHostState.showSnackbar(context.getString(R.string.playlist_created, name))
                }
            },
            onDismiss = { playlistPickerTrack = null },
        )
    }

    if (showSleepTimer) {
        SleepTimerDialog(
            activeDeadline = playerState.sleepDeadlineMs,
            onSelect = { minutes -> player.setSleepTimer(minutes) },
            onCancel = { player.cancelSleepTimer() },
            onDismiss = { showSleepTimer = false },
        )
    }

    if (showDonate) {
        DonateDialog(
            onDonate = {
                showDonate = false
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://saweria.co/riznotdev")),
                    )
                }
            },
            onDismiss = { showDonate = false },
        )
    }
}

@Composable
private fun LyreonNavHost(
    navController: NavHostController,
    locator: ServiceLocator,
    playerState: com.lyreon.app.player.PlayerUiState,
    videoMode: Boolean,
    likedIds: Set<String>,
    downloadedIds: Set<String>,
    reduceMotion: Boolean = false,
    onLike: (LyreonTrack) -> Unit,
    onMore: (LyreonTrack) -> Unit,
    onEnqueueDownload: (LyreonTrack) -> Unit,
    onSleepTimerClick: () -> Unit,
    playMovement: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val player = locator.player

    // Transisi antar-layar: geser kecil + pudar (spring tenang), dipasangkan
    // dengan motion blur di LyreonRoot. Sebelumnya semua transisi None sehingga
    // pindah layar terasa "melompat".
    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier.fillMaxSize(),
        enterTransition = { lyreonScreenEnter(reduceMotion) },
        exitTransition = { lyreonScreenExit(reduceMotion) },
        popEnterTransition = { lyreonScreenPopEnter(reduceMotion) },
        popExitTransition = { lyreonScreenPopExit(reduceMotion) },
    ) {
        composable("home") {
            val vm: HomeViewModel = lyreonViewModel { HomeViewModel(it) }
            HomeScreen(
                vm = vm,
                playerState = playerState,
                onPlayQueue = { tracks, index -> player.playQueue(tracks, index) },
                onTogglePlay = player::toggle,
                onOpenTrack = { navController.navigate("now_playing") },
                onTrackMore = onMore,
                onLike = onLike,
                likedIds = likedIds,
                downloadedIds = downloadedIds,
                onOpenEditorial = { navController.navigate("editorial/${it.id}") },
                onOpenBrowse = { id, name ->
                    navController.navigate("browse/$id?title=${Uri.encode(name)}")
                },
                onSearchClick = {
                    navController.navigate("search") {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        }

        composable("search") {
            val vm: SearchViewModel = lyreonViewModel { SearchViewModel(it) }
            SearchScreen(
                vm = vm,
                playerState = playerState,
                onPlayQueue = { tracks, index -> player.playQueue(tracks, index) },
                onTrackMore = onMore,
                onLike = onLike,
                onOpenYtPlaylist = { pl: YtPlaylist ->
                    val encoded = Uri.encode(pl.url)
                    navController.navigate("ytplaylist/$encoded")
                },
                onOpenBrowse = { id, name ->
                    navController.navigate("browse/$id?title=${Uri.encode(name)}")
                },
                likedIds = likedIds,
                downloadedIds = downloadedIds,
            )
        }

        composable("library") {
            val vm: LibraryViewModel = lyreonViewModel { LibraryViewModel(it) }
            LibraryScreen(
                vm = vm,
                playerState = playerState,
                onPlayQueue = { tracks, index -> player.playQueue(tracks, index) },
                onTrackMore = onMore,
                onLike = onLike,
                onOpenPlaylist = { id, name ->
                    navController.navigate("playlist/$id?name=${Uri.encode(name)}")
                },
                likedIds = likedIds,
                downloadedIds = downloadedIds,
            )
        }

        composable("playlists") {
            val vm: LibraryViewModel = lyreonViewModel(key = "playlists_tab") { LibraryViewModel(it) }
            LibraryScreen(
                vm = vm,
                initialTab = 1,
                playerState = playerState,
                onPlayQueue = { tracks, index -> player.playQueue(tracks, index) },
                onTrackMore = onMore,
                onLike = onLike,
                onOpenPlaylist = { id, name ->
                    navController.navigate("playlist/$id?name=${Uri.encode(name)}")
                },
                likedIds = likedIds,
                downloadedIds = downloadedIds,
            )
        }

        composable("archive") {
            ArchiveScreen(
                playerState = playerState,
                onOpenEditorial = { navController.navigate("editorial/${it.id}") },
                onOpenBrowse = { id, name ->
                    navController.navigate("browse/$id?title=${Uri.encode(name)}")
                },
                onPlayMovement = { playMovement(it.searchQuery) },
            )
        }

        composable("downloads") {
            val vm: DownloadsViewModel = lyreonViewModel { DownloadsViewModel(it) }
            DownloadsScreen(
                vm = vm,
                playerState = playerState,
                onBack = { navController.popBackStack() },
                onPlayQueue = { tracks, index -> player.playQueue(tracks, index) },
            )
        }

        composable("settings") {
            val vm: SettingsViewModel = lyreonViewModel { SettingsViewModel(it) }
            SettingsScreen(
                vm = vm,
                onOpenLicenses = { navController.navigate("licenses") { launchSingleTop = true } },
            )
        }

        composable(
            route = "browse/{browseId}?title={title}&params={params}",
            arguments = listOf(
                navArgument("browseId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("params") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val browseId = entry.arguments?.getString("browseId").orEmpty()
            val title = entry.arguments?.getString("title").orEmpty()
            val params = entry.arguments?.getString("params").orEmpty()
            val vm: BrowseViewModel = lyreonViewModel(key = "browse_$browseId$params") {
                BrowseViewModel(it, browseId, params)
            }
            BrowseScreen(
                vm = vm,
                fallbackTitle = title,
                playerState = playerState,
                likedIds = likedIds,
                downloadedIds = downloadedIds,
                onBack = { navController.popBackStack() },
                onPlayQueue = { tracks, index -> player.playQueue(tracks, index) },
                onLike = onLike,
                onTrackMore = onMore,
                onOpenBrowse = { id, name ->
                    navController.navigate("browse/$id?title=${Uri.encode(name)}")
                },
                onOpenPlaylist = { playlistId ->
                    val url = "https://music.youtube.com/playlist?list=$playlistId"
                    navController.navigate("ytplaylist/${Uri.encode(url)}")
                },
                // Radio artis: benih lagu teratas halaman, antrean langsung diisi
                // lagu terkait oleh PlayerManager.startRadio.
                onStartRadio = { seed ->
                    player.startRadio(seed)
                    navController.navigate("now_playing")
                },
            )
        }

        composable("licenses") {
            LicensesScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = "playlist/{id}?name={name}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("name") { type = NavType.StringType; defaultValue = "PLAYLIST" },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: return@composable
            val name = entry.arguments?.getString("name").orEmpty()
            val vm: PlaylistViewModel = lyreonViewModel(key = "pl$id") { PlaylistViewModel(it, id) }
            PlaylistDetailScreen(
                vm = vm,
                playlistName = name,
                playerState = playerState,
                onBack = { navController.popBackStack() },
                onPlayQueue = { tracks, index -> player.playQueue(tracks, index) },
                onSetShuffle = player::setShuffle,
                onTrackMore = onMore,
                onLike = onLike,
                likedIds = likedIds,
                downloadedIds = downloadedIds,
                onDelete = { navController.popBackStack() },
            )
        }

        composable(
            route = "ytplaylist/{url}",
            arguments = listOf(navArgument("url") { type = NavType.StringType }),
        ) { entry ->
            val url = Uri.decode(entry.arguments?.getString("url").orEmpty())
            val vm: YtPlaylistViewModel = lyreonViewModel(key = url) { YtPlaylistViewModel(it, url) }
            YtPlaylistScreen(
                vm = vm,
                playerState = playerState,
                onBack = { navController.popBackStack() },
                onPlayQueue = { tracks, index -> player.playQueue(tracks, index) },
                onAppendQueue = { tracks -> player.appendAll(tracks) },
                onTrackMore = onMore,
                onLike = onLike,
                likedIds = likedIds,
                downloadedIds = downloadedIds,
            )
        }

        composable("now_playing") {
            val current = playerState.currentTrack
            NowPlayingScreen(
                playerState = playerState,
                player = player,
                videoMode = videoMode,
                controller = player.mediaController,
                onSetVideoMode = player::setVideoMode,
                onSeekMs = player::seekTo,
                isLiked = current?.let { likedIds.contains(it.videoId) } == true,
                isDownloaded = current?.let { downloadedIds.contains(it.videoId) } == true,
                onBack = { navController.popBackStack() },
                onToggle = player::toggle,
                onNext = player::next,
                onPrev = player::previous,
                onSeekFraction = player::seekToFraction,
                onShuffle = player::setShuffle,
                onCycleRepeat = player::cycleRepeat,
                onLike = { current?.let(onLike) },
                onDownload = { current?.let(onEnqueueDownload) },
                onAddToPlaylist = { current?.let(onMore) },
                onSleepTimer = onSleepTimerClick,
                onShare = {
                    current?.let { t ->
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Lyreon · ${t.title} - ${t.artist}\n${t.watchUrl}")
                        }
                        navController.context.startActivity(Intent.createChooser(send, "Bagikan lagu"))
                    }
                },
                onPlayAt = player::playAt,
                onRemoveQueueItem = player::removeAt,
                onMoveQueueItem = player::moveItem,
            )
        }

        composable(
            route = "editorial/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            EditorialDetailScreen(
                sectionId = id,
                onBack = { navController.popBackStack() },
                onPlayMovement = playMovement,
            )
        }
    }
}

// ----------------------------------------------------------------------
// Share / deep-link helpers
// ----------------------------------------------------------------------

private fun extractYouTubeUrl(intent: Intent): String? {
    val candidates = mutableListOf<String>()
    intent.getStringExtra(Intent.EXTRA_TEXT)?.let(candidates::add)
    intent.dataString?.let(candidates::add)
    for (c in candidates) {
        val match = Regex(
            "(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com/watch\\?[^\\s]*v=|youtu\\.be/)([\\w-]{6,})",
        ).find(c)
        if (match != null) {
            return "https://www.youtube.com/watch?v=${match.groupValues[1]}"
        }
    }
    return null
}

private fun extractPlaylistUrl(intent: Intent): String? {
    val candidates = mutableListOf<String>()
    intent.getStringExtra(Intent.EXTRA_TEXT)?.let(candidates::add)
    intent.dataString?.let(candidates::add)
    for (c in candidates) {
        if (!c.contains("list=")) continue
        val match = Regex("[?&]list=([\\w-]{4,})").find(c) ?: continue
        return "https://www.youtube.com/playlist?list=${match.groupValues[1]}"
    }
    return null
}

private fun playSharedUrl(
    locator: ServiceLocator,
    url: String,
    navController: NavHostController,
    toast: (String) -> Unit,
) {
    val videoId = url.substringAfter("v=").substringBefore("&")
    if (videoId.isBlank()) return
    locator.appScope.launch {
        toast(locator.app.getString(R.string.snack_opening_link))
        val bundle = runCatching { locator.youtube.bundle(videoId) }.getOrNull()
        withContext(Dispatchers.Main) {
            if (bundle == null) {
                toast(locator.app.getString(R.string.snack_link_failed))
            } else {
                val queue = listOf(bundle.track) + bundle.related.take(15)
                locator.player.playQueue(queue, 0)
                navController.navigate("now_playing")
            }
        }
    }
}
