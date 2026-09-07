# 06 — Referensi Meld: peta repo, cara mengambil sumber, apa yang sudah diporting

Rujukan: [FrancescoGrazioso/Meld](https://github.com/FrancescoGrazioso/Meld) —
klien musik YouTube/Android (fork `MetrolistGroup/Metrolist`, 282 commit di depan
hulu, push terakhir 2026-09-05). Kita menirunya karena pengembangnya **mengukur di
perangkat** dan menuliskan hasil ukurnya di komentar kode — itu yang tidak dimiliki
dokumentasi kebijakan yt-dlp.

Skala repo (diambil 2026-09-06): **1.487 path**, **592 berkas Kotlin**, 11 modul:

| Modul | Berkas | Isi | Relevansi Lyreon |
|---|---|---|---|
| `app` | 1052 | UI, playback, db, widget, listentogether, eq | utama |
| `innertube` | 124 | klien InnerTube, model respons, `pages/` | **sudah diporting sebagian** |
| `fastlane` | 94 | metadata store (screenshot/deskripsi) | referensi rilis |
| `kizzy` | 35 | status Discord (RPC) | tidak relevan |
| `spotify` | 30 | integrasi Spotify | tidak relevan |
| `betterlyrics` | 21 | penyedia lirik | **menarik untuk fitur #8** |
| `lastfm` | 20 | scrobbling Last.fm | opsional |
| `kugou` | 16 | penyedia lirik KuGou | **menarik untuk fitur #8** |
| `lrclib` | 13 | penyedia lirik LrcLib | **menarik untuk fitur #8** |
| `shazamkit` | 12 | pengenalan lagu | opsional |
| `paxsenix` | 11 | penyedia lirik | opsional |

Dependensi kunci mereka: NewPipeExtractor (TeamNewPipe) v0.26.0, media3 1.7.1,
ktor+okhttp, kotlinx.serialization. **Lyreon berbeda**: media3 1.10.1, okhttp 5.1.0,
`org.json`, Room+KSP, Coil 3, Rhino. Jadi porting = terjemahkan, bukan salin-tempel.

---

## 1. ✅ Lisensi — diputuskan: Lyreon kini GPL-3.0-only (2026-09-06)

| | Lisensi |
|---|---|
| Lyreon (`LICENSE`, header SPDX per berkas) | **GPL-3.0-only** ← sebelumnya MIT |
| Meld (`LICENSE`) | **GPL-3.0** (diwarisi dari Metrolist ← InnerTune) |
| MetrolistExtractor / NewPipeExtractor | GPL-3.0 |
| Media3, Compose, OkHttp, Room, DataStore, Coil | Apache-2.0 (satu arah kompatibel dengan GPL-3.0) |
| Rhino | MPL-2.0 (kompatibel sebagai bagian karya yang lebih besar) |

**Keputusan pemilik proyek:** *"Rilesensi mit ke 3.0"* — Lyreon direlisensi dari MIT ke
GPL-3.0-only supaya **boleh menyerap kode Meld apa adanya**. Konsekuensi yang diterima:
setiap turunan/rilis Lyreon tunduk copyleft (kode sumber harus tersedia bagi penerima),
dan kontribusi berikutnya ikut GPL-3.0-only.

Aturan praktis sejak keputusan ini:

1. **Menyalin berkas Meld diperbolehkan**, dengan syarat: pertahankan header hak cipta
   asli mereka, tambahkan baris asal sebagai komentar
   (`// Diadaptasi dari FrancescoGrazioso/Meld: <path> (GPL-3.0)`), dan sesuaikan
   package/impor ke `com.lyreon.app`.
2. **Setiap berkas baru Lyreon** memakai header SPDX yang sama:
   ```kotlin
   /*
    * Copyright (C) 2026 rixz-dev
    *
    * SPDX-License-Identifier: GPL-3.0-only
    */
   ```
3. **Catat porting** di §4 berkas ini (tanggal, commit, path sumber, apa yang diubah) —
   itu jejak kepatuhan kita.
4. **Jangan mencampur lisensi lain** ke dalam karya (mis. menyalin kode Apache-2.0 yang
   mengandung paten/klausul tambahan, atau kode berlisensi tidak jelas dari gist/blog).
   Dependensi Apache-2.0/MPL-2.0 sebagai *library* tetap aman.
5. **Notice dependensi di APK — SUDAH DILUNASI.** `app/build.gradle.kts` tetap
   mengecualikan `META-INF/LICENSE*` saat packaging (keputusan lama untuk
   menghindari konflik merge), tetapi notice kini disampaikan lewat layar
   `ui/screens/LicensesScreen.kt` (rute `licenses` dari Settings → Tentang):
   daftar pustaka + SPDX + URL, kartu GPL-3.0-only, atribusi Meld/Metrolist, dan
   pemberitahuan Apache-2.0. **Daftarnya manual** — perbarui saat dependensi berubah.
6. Distribusi APK = conveyance: sertakan tautan repositori sumber (sudah ada di README)
   dan jangan menambah pembatasan lain (DRM/anti-tivoisasi) di atasnya.

## 2. Cara mengambil sumber Meld dari lingkungan kerja ini

Sandbox memblokir HTML `github.com` dan TLS `googlevideo.com`/`youtube.com`, tetapi
`api.github.com` lewat `gh` berfungsi.

```bash
# 1) daftar seluruh berkas
gh api "repos/FrancescoGrazioso/Meld/git/trees/main?recursive=1" \
  --jq '.tree[].path' > /tmp/meld_files.txt

# 2) ambil satu berkas (PENTING: buang newline sebelum base64 -d,
#    kalau tidak akan gagal "base64: invalid input")
gh api "repos/FrancescoGrazioso/Meld/contents/<path>" --jq '.content' \
  | tr -d '\n' | base64 -d > /tmp/meld_x.kt

# 3) riwayat satu berkas (kapan nilai klien terakhir diubah)
gh api "repos/FrancescoGrazioso/Meld/commits?path=<path>&per_page=5" \
  --jq '.[] | "\(.sha[0:9]) \(.commit.committer.date) \(.commit.message | split("\n")[0])"'
```

`gh run view --log` sering gagal (EOF) → pakai
`gh api repos/{owner}/{repo}/actions/runs/{id}/jobs`.

## 3. Berkas Meld per domain (peta untuk porting berikutnya)

**Streaming / InnerTube (sudah kita pelajari habis)**
- `innertube/.../models/YouTubeClient.kt` — spesifikasi semua klien + KDoc pengukuran
  (gate per versi ANDROID_VR, kenapa buildId/cronet/packageName dihilangkan).
- `innertube/.../InnerTube.kt` — `ytClient()` (header), `player()` (body),
  `getSwJsData()`; `X-Goog-Visitor-Id` untuk semua klien.
- `innertube/.../YouTube.kt` — `visitorData()` (parse `sw.js_data`, regex `^Cg[t|s]`),
  `newPipePlayer()`.
- `app/.../utils/YTPlayerUtils.kt` (968 baris) — `STREAM_FALLBACK_CLIENTS`,
  `playerResponseForPlayback()`, `validateStatus()`, `describeStreamUrl()`,
  `findFormat()`, `findUrlOrNull()`; cascade logging satu baris.
- `app/.../utils/potoken/{PoTokenGenerator,PoTokenWebView,JavaScriptUtil}.kt` —
  poToken BotGuard via WebView (timeout 8 s). **Tidak kita porting** (lihat
  `docs/streaming-resilience.md` §7.1).
- `app/.../utils/sabr/EjsNTransformSolver.kt`, `utils/cipher/CipherDeobfuscator.kt` —
  n-transform & decipher. **Tidak kita porting**: tangga anonim tidak butuh
  (VISIONOS/ANDROID_VR/IOS mengirim URL polos). Lyreon punya `SignatureDecipher.kt`
  (Rhino) untuk kasus cipher saja.

**Pemutaran & audio (kandidat fitur #10, #12, #13)**
- `playback/audio/SilenceDetectorAudioProcessor.kt` → skip silence.
- `eq/EqualizerService.kt`, `eq/audio/CustomEqualizerAudioProcessor.kt`,
  `ui/screens/equalizer/{EqScreen,EQState,EQViewModel}.kt` → pola layar audio +
  AudioProcessor kustom (rujukan untuk normalisasi loudness).
- `playback/SleepTimer.kt` + `app/src/test/.../SleepTimerFadeTest.kt` → fade-out
  (Lyreon sudah punya timer, belum punya fade).
- `playback/MusicService.kt`, `constants/MediaSessionConstants.kt`,
  `playback/MediaLibrarySessionCallback.kt`.
- `playback/queues/{Queue,ListQueue,YouTubePlaylistQueue,YouTubeAlbumRadio,…}.kt`,
  `extensions/QueueExt.kt`, `models/PersistQueue.kt` → reorder/queue (#15).

**Lirik (fitur #8)**
- `ui/component/{Lyrics,LyricsLine,LyricsCommon,LyricsComponents,LyricsImageCard,
  LyricsBackgroundStyle}.kt` → tampilan + animasi baris.
- `lyrics/{LyricsHelper,LyricsEntry,KuGouLyricsProvider,LrcLibLyricsProvider,
  BetterLyricsProvider,LyricsPlusProvider}.kt` + modul `kugou/`, `lrclib/`,
  `betterlyrics/`, `paxsenix/`.
- `db/entities/LyricsEntity.kt`, `di/LyricsHelperEntryPoint.kt`.

**Tema (fitur #17 → `04-spesifikasi-tema-baru.md`)**
- `ui/theme/{Theme,Type,Font,PlayerColorExtractor,PlayerSliderColors}.kt`,
  `ui/screens/settings/ThemeScreen.kt`, `viewmodels/ThemeViewModel.kt`,
  `constants/Dimensions.kt`.

**Widget (fitur #16)**
- `widget/{MetrolistWidgetManager,MusicWidgetReceiver,TurntableWidgetReceiver,
  MusicRecognizerWidgetReceiver,MusicRecognizerWidgetService}.kt`.

**Halaman katalog (fitur #20)**
- `innertube/pages/{MoodAndGenres,ExplorePage,ArtistPage,ArtistItemsPage,AlbumPage,
  ChartsPage,NewReleaseAlbumPage,HomePage,RelatedPage,SearchPage,SearchSummaryPage}.kt`
- `ui/screens/{MoodAndGenresScreen,ExploreScreen,ChartsScreen,NewReleaseScreen,
  AlbumScreen}.kt`, `ui/screens/artist/*`, `ui/screens/library/*`.

**Akun (fitur #5, #9 — DITAHAN, lihat `05`)**
- `ui/screens/{LoginScreen,AccountScreen,ListenTogetherScreen}.kt`,
  `listentogether/{ListenTogetherManager,ListenTogetherClient,ListenTogetherServers,
  Protocol,MessageCodec,ListenTogetherActionReceiver}.kt`.

## 4. Apa yang sudah diporting ke Lyreon (commit `df7531a`, CI hijau)

> Porting ini dikerjakan **sebelum** keputusan relisensi (§1), jadi semuanya berupa
> reimplementasi dengan atribusi, bukan salinan kode. Ia tetap sah di bawah GPL-3.0-only.
> Porting **berikutnya** boleh menyalin berkas Meld langsung dengan syarat di §1.

| Dari Meld | Ke Lyreon | Bentuk porting |
|---|---|---|
| `YouTubeClient.kt` (VISIONOS 0.1, ANDROID_VR 1.65.10/1.43.32, IPADOS, IOS, TVHTML5, WEB*, WEB_CREATOR) | `yt/innertube/PlayerClientLadder.kt` | **fakta/spesifikasi** disalin; dijaga oleh `tools/ci/meld-client-radar.sh` |
| `STREAM_FALLBACK_CLIENTS` + aturan `loginRequired`-skip | `PlayerClientLadder.ordered(forPlayback=true)` + flag `loginRequired`/`useWebPoTokens`/`probeOnly` | reimplementasi |
| `InnerTube.ytClient()`/`player()` (host music.youtube.com, header, body ramping, STS per klien) | `yt/innertube/InnertubeRequest.kt` | reimplementasi (okhttp + org.json) |
| `YouTube.visitorData()` (`sw.js_data`, regex `^Cg[t|s]`) | `yt/innertube/InnertubeConfig.kt` | reimplementasi + cadangan `guide` + panen `responseContext` |
| `YTPlayerUtils.validateStatus()` + `describeStreamUrl()` | `yt/innertube/StreamUrlValidator.kt` | reimplementasi (probe + deskripsi tersensor) |
| `Format.isOriginal` (buang audio auto-dub) | `InnertubeFallback.pickAudio()` | reimplementasi (`audioTrack.audioIsAutoDubbed`) |
| Aturan "klien terakhir diterima tanpa validasi" | `fetchPlayer()` → `ResolvedAudio(validated = false)` | diadopsi + diperketat untuk unduhan |
| Cascade logging satu baris | `PlayerClientLadder.push()` + `attempts` | diadopsi idenya |

**Yang sengaja tidak diporting:** poToken/BotGuard, n-transform/EJS, SABR, NewPipe
signature deobfuscation, login/akun, modul Spotify/Last.fm/Kizzy/ShazamKit.

### 4b. Porting setelah relisensi GPL-3.0 (gelombang 2 — lirik)

Sejak Lyreon berlisensi GPL-3.0-only (§1), kode Meld boleh diadaptasi langsung dengan
header hak cipta asal + komentar path sumber. Yang sudah dipakai:

| Dari Meld (GPL-3.0) | Ke Lyreon | Catatan adaptasi |
|---|---|---|
| `kugou/src/main/kotlin/com/metrolist/kugou/KuGou.kt` + `models/*.kt` | `lyrics/KuGouProvider.kt` | Ktor + kotlinx.serialization → OkHttp + org.json; endpoint, parameter, toleransi durasi (8 dtk), normalisasi kata kunci, dan pemotongan kepala/ekor dipertahankan; pemotongan ekor dihitung dari daftar hasil potong kepala (upstream memakai indeks daftar awal) |
| `lyrics/LyricsProvider.kt` | `lyrics/LyricsProvider.kt` | tanpa `Context`/Hilt; hasil berupa `LyricsResult` Lyreon |
| `lyrics/{LyricsHelper,LyricsProviderRegistry}.kt` | `lyrics/LyricsRepository.kt` | tangga provider berprioritas + cache per `videoId` |
| `ui/utils/FadingEdge.kt` | `ui/utils/FadingEdge.kt` | disalin hampir apa adanya (hanya paket & KDoc) |
| `ui/component/OriginalLyrics.kt` (perilaku, bukan berkas) | `ui/components/LyricsSheet.kt` | diambil: `findCurrentLineIndex`, `performSmoothPageScroll` (memusatkan baris aktif), `NestedScrollConnection` yang menjeda auto-scroll lalu lanjut setelah `LyricsPreviewTime` 2 dtk, konstanta durasi (initial 800 / seek 600 / auto 1500 ms), ketuk baris = seek + auto-scroll aktif lagi. **Tidak** diambil: terjemahan AI (DeepL/OpenRouter), romanisasi, ekspor gambar lirik, mode seleksi, BetterLyrics/TTML per kata, palette artwork |
| `viewmodels/LyricsViewModel.kt` (ide offset per lagu) | `SettingsRepository.lyricsOffsetMs` + footer `LyricsSheet` | offset global ±10 dtk, langkah 500 ms |

### 4c. Porting audio (gelombang 3)

| Dari Meld (GPL-3.0) | Ke Lyreon | Catatan adaptasi |
|---|---|---|
| `playback/audio/SilenceDetectorAudioProcessor.kt` | `player/audio/SilenceDetectorAudioProcessor.kt` | disalin hampir apa adanya; `reset()` tidak lagi ditandai usang (di media3 1.10.1 hanya `flush()` yang `@Deprecated`) |
| `MusicService.createRenderersFactory` | `PlaybackService.buildRenderersFactory` | signature media3 1.10.1: `buildAudioSink(Context, Boolean enableFloatOutput, Boolean enableAudioOutputPlaybackParams): AudioSink?`, builder memakai `setEnableAudioOutputPlaybackParameters`, dan `SonicAudioProcessor` pindah ke `androidx.media3.common.audio`; `SilenceSkippingAudioProcessor(long, long, short)` → ambang harus `.toShort()` |
| `MusicService.handleLongSilenceDetected` + `performInstantSilenceSkip` | `PlaybackService` (nama sama) | konstanta dipertahankan: debounce 200 ms, langkah 15 dtk, maks 80 lompatan, guard ekor 500 ms, settle 300 ms. Timber diganti tanpa log |
| `MusicService.setupLoudnessEnhancer` (normalisasi) | `PlaybackService.refreshNormalization` + `player/audio/LoudnessStore.kt` | gain `(-loudnessDb * 100)` mB dijepit −1500..+300 (sama); sumber data berbeda — Meld membaca `Format.loudnessDb` dari DB mereka, Lyreon memanen `loudnessDb`/`perceptualLoudnessDb` langsung dari respons InnerTube di `pickAudio()` lalu menyimpannya per videoId; diterapkan ulang saat ganti lagu dan saat loudness baru tiba |

Sumber posisi: `PlayerManager.positionNow()` (baca `MediaController.currentPosition`
langsung, poll 50 ms) — mengikuti cara Meld membaca `playerConnection.player.currentPosition`
di loop lirik alih-alih berlangganan ticker UI 500 ms.

## 5. Cara memakai Meld sebagai rujukan harian

1. Lagu gagal massal → `bash tools/ci/meld-client-radar.sh` (drift spesifikasi).
2. Radar bersih tapi tetap gagal → baca `YTPlayerUtils.kt` HEAD Meld: KDoc di sana
   memuat pengukuran terbaru (klien mana 100% 206, mana yang pratinjau, mana yang mati
   server-side seperti `TVHTML5_SIMPLY_EMBEDDED_PLAYER`).
3. Mau fitur baru → cari berkasnya di §3, baca **KDoc-nya dulu** (sering berisi
   jebakan yang sudah mereka bayar), baru reimplementasi dengan gaya Lyreon.
4. Setiap porting baru → tambahkan baris di §4 (tanggal + commit) dan perbarui status
   di `05-peta-fitur-diinginkan.md`.
5. Sebelum menyalin kode: ikuti syarat §1 (header hak cipta asal + komentar path sumber +
   header SPDX Lyreon), lalu catat di §4.
