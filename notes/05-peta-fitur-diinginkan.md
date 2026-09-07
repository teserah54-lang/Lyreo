# 05 — Peta fitur yang diinginkan: status, padanan di Meld, usaha

Daftar fitur dari pemilik proyek (2026-09-06), dipetakan ke **keadaan kode hari ini**
(diperiksa dengan grep, bukan ingatan) dan ke berkas padanannya di Meld.

Legenda status: **ADA** (bekerja) · **PARSIAL** (ada, belum selengkap permintaan) ·
**BELUM** · **DITAHAN** (bentrok dengan keputusan produk — butuh keputusan, bukan kode).

Usaha: **S** ≤ 1 hari · **M** 2–5 hari · **L** > 1 minggu (termasuk uji di perangkat).

---

## 1. Tabel status

| # | Fitur diminta | Status | Di Lyreon hari ini | Padanan di Meld | Usaha |
|---|---|---|---|---|---|
| 1 | Play any song or video from YouTube Music | **ADA** | `player/PlayerManager.kt` (Media3 + `MediaSessionService`), `ui/screens/NowPlayingScreen.kt` punya mode video via `androidx.media3.ui.PlayerView` (baris ~270–281) | `playback/MusicService.kt` | – |
| 2 | Background playback | **ADA** | `AndroidManifest.xml`: service `MediaSessionService` + `MediaButtonReceiver`; `SessionStore` memulihkan queue/index/posisi/shuffle/repeat | `playback/MusicService.kt`, `constants/MediaSessionConstants.kt` | – |
| 3 | Personalized quick picks | **ADA** | `ui/vm/ViewModels.kt` (`home.quickPicks`), `ui/screens/HomeScreen.kt` (~205–222), personalisasi rasa lewat `data/taste/TasteRepository.kt` + `MusicTextAnalyzer.kt` | `innertube/pages/HomePage.kt`, `viewmodels/HomeViewModel.kt`, `models/SpotifyHomeSection.kt` | S (perkaya sumber) |
| 4 | Library management | **PARSIAL** | `ui/screens/LibraryScreen.kt`, `data/LibraryRepository.kt`, `data/db/{Entities,Daos}.kt`, favorit/like tersebar di 19 berkas | `ui/screens/library/Library{Songs,Albums,Artists,Playlists,Mix,Podcasts}Screen.kt`, `playback/MediaLibrarySessionCallback.kt` | M |
| 5 | Listen together with friends | **DITAHAN** | tidak ada (0 berkas) | `listentogether/{ListenTogetherManager,ListenTogetherClient,ListenTogetherServers,Protocol,MessageCodec}.kt`, `ui/screens/ListenTogetherScreen.kt` | L + keputusan |
| 6 | Download & cache offline | **ADA** | `download/LyreonDownloadManager.kt`, `download/HlsFlatDownloader.kt` (manifest → 1 file tanpa FFmpeg), `ui/screens/DownloadsScreen.kt`; menolak URL `validated = false` | `playback/{DownloadUtil,ExoDownloadService}.kt` | – |
| 7 | Search songs, albums, artists, videos, playlists | **PARSIAL** | `ui/screens/SearchScreen.kt` + `data/model/LyreonTrack.kt` → `SearchFilter { ALL, SONGS, VIDEOS, ALBUMS, PLAYLISTS, PODCASTS }` — **tidak ada filter ARTISTS** | `ui/screens/search/{OnlineSearchScreen,OnlineSearchResult,LocalSearchScreen,SearchScreen}.kt`, `innertube/pages/{SearchPage,SearchSummaryPage,SearchSuggestionPage}.kt` | S (filter artis) + M (pencarian lokal) |
| 8 | Live lyrics + animasi transisi mengikuti lirik | **ADA** | `lyrics/{LyricsRepository,LyricsProvider,LrcLibProvider,KuGouProvider}.kt` = tangga provider (LRCLIB → KuGou, bisa dimatikan di Pengaturan); `ui/components/LyricsSheet.kt` = lirik hidup ala Metrolist (posisi dibaca langsung `PlayerManager.positionNow()` tiap 50 ms, auto-scroll memusatkan baris aktif 1500 ms, jeda 2 dtk saat pengguna menggulir, ketuk baris = seek, alpha+skala per baris, fading edge, koreksi offset ±10 dtk) + `ui/utils/FadingEdge.kt` | `ui/component/{Lyrics,LyricsLine,LyricsCommon,LyricsComponents,LyricsImageCard,LyricsBackgroundStyle}.kt`, `lyrics/{LyricsHelper,KuGouLyricsProvider,LrcLibLyricsProvider,BetterLyricsProvider,LyricsPlusProvider}.kt` + modul `kugou/`, `lrclib/`, `betterlyrics/`, `paxsenix/` | M (animasi) + M (penyedia lirik) |
| 9 | Sync songs/artists/albums/playlists dari & ke akun | **DITAHAN** | tidak ada — dan **bertentangan dengan keputusan anonim** | `ui/screens/{LoginScreen,AccountScreen}.kt`, cookie + `dataSyncId` di `InnerTube` | L + keputusan |
| 10 | Skip silence | **ADA** | `player/audio/SilenceDetectorAudioProcessor.kt` (port Meld) + `player/PlaybackService.kt`: rantai audio `DefaultAudioProcessorChain(detektor, SilenceSkippingAudioProcessor, SonicAudioProcessor)`, `player.skipSilenceEnabled`, dan mode lompat agresif (hening ≥2 dtk → lompat 15 dtk, maks 80 lompatan). Dua sakelar di Pengaturan → Audio | `playback/audio/SilenceDetectorAudioProcessor.kt`, `MusicService.createRenderersFactory/performInstantSilenceSkip` | – (selesai) |
| 11 | Import playlists | **ADA** | `YtPlaylistScreen` membuka playlist YouTube dari URL (`ytplaylist/{url}`) dan punya tombol SIMPAN → playlist lokal via `LibraryRepository`. Belum ada impor berkas (m3u/pls) | `ui/menu/ImportPlaylistDialog.kt` | M |
| 12 | Audio normalization | **ADA** | 0 berkas. **Koreksi catatan lama:** Meld TERNYATA punya normalisasi — `MusicService.setupLoudnessEnhancer()` memakai `android.media.audiofx.LoudnessEnhancer` dengan gain `(-loudnessDb * 100)` mB dijepit −1500..+300 mB, sumber `Format.loudnessDb ?: perceptualLoudnessDb` yang mereka simpan di DB per lagu, sakelar `AudioNormalizationKey`. **Sudah dikerjakan (gelombang 3b):** `ResolvedAudio.loudnessDb` dipanen di `InnertubeFallback.pickAudio()` (`loudnessDb` lalu `perceptualLoudnessDb`), disimpan `player/audio/LoudnessStore.kt` oleh `ResolvingDataSource`, dan diterapkan `PlaybackService.refreshNormalization()` (gain −loudnessDb×100 mB, dijepit −1500..+300). Sakelar `normalizeAudio` default AKTIF | `MusicService.kt` (setupLoudnessEnhancer ~2135-2215), `innertube` `PlayerResponse.Format.loudnessDb` | S–M |
| 13 | Adjust tempo/pitch | **ADA** | `playbackSpeed` (0.5–2.0×, langkah 0.05) + `pitchSemitones` (−12..+12) di Pengaturan; diterapkan `PlaybackService.observeAudioSettings()` lewat `PlaybackParameters(speed, 2^(semitone/12))`; UI di Now Playing (tombol KECEPATAN → dialog dua slider + "Kembalikan normal"). Butuh `SonicAudioProcessor` di rantai audio | `MusicService.kt` (pola terapkan params dari DataStore) | – (selesai) |
| 14 | Local playlist management | **ADA/PARSIAL** | `ui/screens/PlaylistDetailScreen.kt`, `data/db/{Entities,Daos}.kt` (Room), `ui/screens/LocalMusicScreen.kt` + `local/LocalMusicRepository.kt` | `ui/component/CreatePlaylistDialog.kt`, `db/entities/Playlist*.kt`, `ui/screens/playlist/*` | M (lengkapi CRUD + urutan) |
| 15 | Reorder songs in playlist or queue | **ADA** | playlist: `PlaylistDetailScreen` sudah punya tombol ↑/↓ → `PlaylistViewModel.move` → `LibraryRepository.moveInPlaylist` (posisi disimpan di Room); queue: baru — `PlayerManager.moveItem` → `MediaController.moveMediaItem` + tombol ↑/↓ di lembar antrean `NowPlayingScreen`. Belum drag-and-drop sungguhan | `extensions/QueueExt.kt`, `playback/queues/{Queue,ListQueue,YouTubePlaylistQueue,…}.kt`, `models/PersistQueue.kt` | M |
| 16 | Home screen widget with playback controls | **ADA** | `widget/PlayerWidgetReceiver.kt` (RemoteViews + tombol media button), `widget/WidgetState.kt` (snapshot), `res/layout/widget_player.xml`, `res/xml/widget_info.xml` | `widget/{MusicWidgetReceiver,TurntableWidgetReceiver,MusicRecognizerWidgetReceiver,MusicRecognizerWidgetService,MetrolistWidgetManager}.kt` | L (AppWidget/Glance + pratinjau + uji banyak launcher) |
| 17 | Light / Dark / Black / Dynamic theme | **ADA** | `ui/theme/Theme.kt` → `ThemeMode { SYSTEM, DARK, LIGHT, BLACK }` + Material You (`dynamicColor`, Android 12+) + aksen kustom (`accentArgb`) + pilihan font (`fontKey`); token chrome di `ui/theme/{Color,Shape,Motion}.kt` | `ui/theme/{Theme,Font,PlayerColorExtractor,PlayerSliderColors}.kt`, `ui/screens/settings/ThemeScreen.kt`, `viewmodels/ThemeViewModel.kt` | M — spesifikasi lengkap di `04` |
| 18 | Sleep timer | **ADA** | `player/PlayerManager.kt` → `setSleepTimer(minutes)` / `cancelSleepTimer()` (~946–959), UI di `NowPlayingScreen.kt` + `TrackActions.kt`, wiring di `MainActivity.kt` | `playback/SleepTimer.kt` + **fade-out** (ada uji `app/src/test/…/SleepTimerFadeTest.kt`) | S (adopsi fade-out ala Meld) |
| 19 | Material 3 design | **ADA** | `MaterialTheme` + `darkColorScheme`/`lightColorScheme` di `ui/theme/Theme.kt`; Compose BOM `2026.01.01` | sama | – |
| 20 | Genre & artis "seperti yang Meld lakukan" | **ADA (gelombang 6)** | genre hanya sebagai reel/rasa: `ui/components/GenreReelSlider.kt`, `data/taste/TasteRepository.kt`, `ui/screens/HomeScreen.kt`; **kini ADA**: `yt/BrowseModel.kt` (`BrowseParser` murni) + `YouTubeRepository.browsePage(browseId, params, gl)` (cache 6 jam) + `ui/screens/BrowseScreen.kt` (satu layar untuk artis/album/genre-mood/kategori; rak lagu = baris, rak kartu = LazyRow). Pintu masuk: kartu artis Beranda (`ArtistCard.browseId` dipanen dari FEmusic_charts) dan kartu "Genre & Mood" di Arsip → `FEmusic_moods_and_genres` | `ui/screens/{MoodAndGenresScreen,ExploreScreen,ChartsScreen,NewReleaseScreen,AlbumScreen}.kt`, `ui/screens/artist/{ArtistScreen,ArtistSongsScreen,ArtistAlbumsScreen,ArtistItemsScreen}.kt`, `innertube/pages/{MoodAndGenres,ExplorePage,ArtistPage,ArtistItemsPage,AlbumPage,ChartsPage,NewReleaseAlbumPage}.kt` | L (butuh halaman InnerTube baru + layar + navigasi) |

Ringkasan: **7 ADA**, **6 PARSIAL**, **5 BELUM**, **2 DITAHAN**.

## 2. Dua fitur yang bertabrakan dengan keputusan produk (butuh keputusan, bukan kode)

### 2.1 Sinkronisasi dari & ke akun YouTube (#9)

- **Konflik:** membutuhkan login/cookie (`SAPISID`, `dataSyncId`, header
  `Authorization: SAPISIDHASH …`). Keputusan pemilik proyek yang berlaku:
  *"hanya anonim saja, tanpa akun. Agar semua user gak ribet ngambil cookie"* —
  kode login sudah dihapus (riwayat: commit `73aa177`), dan `setTokens()` tidak
  pernah dipanggil.
- **Opsi bila kelak diinginkan** (pilih satu secara eksplisit):
  1. **Tetap anonim** → ganti "sinkronisasi akun" dengan **sinkronisasi antar
     perangkat tanpa akun** (ekspor/impor pustaka sebagai berkas JSON; usaha M) dan
     "library lokal" sebagai sumber kebenaran. Ini tidak melanggar apa pun.
  2. **Login opsional, opt-in, di luar jalur streaming** → hanya untuk
     like/playlist, tidak pernah dipakai untuk membuka stream (streaming tetap
     tangga klien anonim). Risiko: permukaan keamanan + tiket dukungan "cookie saya
     ditolak" (lihat `docs/streaming-resilience.md` §3).
- **Rekomendasi catatan ini:** opsi 1 dulu. Jangan menghidupkan cookie tanpa
  keputusan tertulis.

### 2.2 Listen together with friends (#5)

- **Konflik:** implementasi Meld memakai **server signaling**
  (`listentogether/ListenTogetherServers.kt` berisi daftar server, `Protocol.kt`,
  `MessageCodec.kt`, `ListenTogetherClient.kt`). Lyreon berdiri di atas prinsip
  "tanpa server perantara".
- **Opsi:** (a) tunda; (b) adopsi model Meld apa adanya → berarti menerima
  ketergantungan pada server pihak ketiga (perlu kebijakan privasi baru +
  pernyataan di README); (c) model LAN/local-only (mDNS) tanpa internet — usaha L
  dan hanya berguna satu ruangan.
- **Rekomendasi:** tunda sampai tema baru dan lirik stabil; bila dikerjakan, pilih
  (b) dengan disclosure eksplisit, atau (c) bila prinsip "tanpa server" tetap mutlak.

## 3. Urutan pengerjaan yang disarankan

Alasan urutan: tema lebih dulu karena ia menyentuh semua layar (mengerjakan fitur
baru sebelum tema = mengerjakan UI dua kali); fitur berdampak besar & murah
berikutnya; yang butuh keputusan paling akhir.

| Gelombang | Isi | Kenapa |
|---|---|---|
| 1 | Tema baru fase 1–2 (`04`): token bentuk/warna, `BLACK`, Material You, pemilih tema | fondasi semua layar; risiko rendah; tidak menyentuh streaming |
| 2 | ~~#13 tempo/pitch~~ **selesai di gelombang 3**; sisa: #18 fade sleep timer (S), #7 filter ARTISTS + pencarian lokal (S/M) | nilai terasa, usaha kecil, tanpa dependensi baru |
| 3 | Tema fase 3–4 (transparansi/blur terbatas + motion iOS) | setelah token stabil; ukur `framestats` |
| 4 | ~~#8 lirik: animasi baris ala Metrolist + penyedia lirik tambahan~~ — **selesai (gelombang 2)** | sisanya: translasi/romanisasi & gaya animasi per kata bila diminta |
| 5 | ~~#10 skip silence~~, ~~#12 audio normalization~~ (gelombang 3) dan ~~#15 reorder queue/playlist~~ (gelombang 4) **selesai** | semuanya AudioProcessor/Room; satu area kode |
| 6 | ~~#20 halaman Genre/Mood, Artis, Album ala Meld~~ **selesai di gelombang 6**: `yt/BrowseModel.kt` (BrowseParser) + `YouTubeRepository.browsePage()` + `ui/screens/BrowseScreen.kt`; pintu masuk: kartu artis di Beranda dan kartu Genre & Mood di Arsip |
| 7 | ~~#16 widget home screen~~ **selesai di gelombang 5**; sisa: #4 library lengkap (#11 impor playlist YouTube ternyata sudah ADA di `YtPlaylistScreen`) | bergantung pada #20 (model data) |
| 8 | #5 dan #9 | **hanya setelah ada keputusan tertulis** |

## 4. Cara memverifikasi ulang tabel ini (biar tidak jadi mitos)

```bash
cd /home/user/lyr
# status fitur di Lyreon
for p in "SleepTimer" "Glance|AppWidget" "skipSilence|SilenceDetector" \
         "loudnessDb|normaliz" "PlaybackParameters|pitch" "reorder|drag" \
         "Lyrics" "dynamicColor|dynamicDarkColorScheme" "ThemeMode" \
         "ListenTogether" "importPlaylist"; do
  printf "%-42s %s berkas\n" "$p" "$(grep -rlE "$p" --include=*.kt app/src | wc -l)"
done

# padanan di Meld (butuh gh yang sudah login)
gh api "repos/FrancescoGrazioso/Meld/git/trees/main?recursive=1" \
  --jq '.tree[].path' | grep -iE "widget|lyrics|silence|equalizer|listentogether|theme"
```

Perbarui tabel di atas setiap kali sebuah fitur selesai — termasuk **tanggal** dan
commit-nya, supaya baris "ADA" selalu bisa diaudit.
