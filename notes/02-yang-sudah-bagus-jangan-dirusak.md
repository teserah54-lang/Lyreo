# 02 — Yang sudah bagus, jangan dirusak

Daftar **invarian**: keputusan dan mekanisme yang sudah dibayar mahal (bug, sesi
debugging, build CI) dan membuat Lyreon bekerja hari ini. Mengubah salah satunya
boleh, tetapi harus sadar dan beralasan — jangan sebagai efek samping refactor UI.

---

## 1. Keputusan produk: ANONIM, tanpa akun, tanpa server perantara

- `ServiceList.YouTube.setTokens()` **tidak pernah** dipanggil; tidak ada kode parse
  cookie, tidak ada `SAPISIDHASH`, tidak ada penyimpanan kredensial.
- Semua trafik on-device langsung ke Google (`youtubei/v1` + `googlevideo.com`) dan
  penyedia lirik; tidak ada backend Lyreon.
- Alasan lengkap: `docs/streaming-resilience.md` §3 (gesekan pengguna, risiko akun,
  permukaan privasi, beban dukungan).
- **Konsekuensi yang diterima sadar:** bila YouTube menutup semua celah anonim,
  pemutaran berhenti sampai tangga klien disesuaikan.

> Jangan menambah jalur login/cookie "untuk satu fitur saja". Dua fitur yang meminta
> itu (sinkronisasi akun, *listen together*) sudah ditandai **ditahan** di
> `05-peta-fitur-diinginkan.md` §konflik.

## 2. Lima lapisan ketahanan streaming

Urutan ini adalah alasan pemutaran bertahan saat YouTube mengubah kebijakan. Jangan
membongkar lapisannya tanpa membaca `docs/streaming-resilience.md`.

| # | Lapisan | Berkas | Invarian |
|---|---|---|---|
| 1 | Knob & bypass extractor | `yt/YouTubeRepository.kt`, `yt/innertube/PlayerClientLadder.kt` | `setLoadingTimeout(12)`, `setFetchDislike(false)`; extractor di-bypass 10 menit setelah 2× SABR beruntun |
| 2 | Tangga klien anonim (spesifikasi Meld) | `yt/innertube/PlayerClientLadder.kt`, `InnertubeRequest.kt` | `visionos` 0.1 → `android_vr` 1.65.10 → `android_vr_1_43_32` → `ipados` → `ios`; host `music.youtube.com`; `X-Goog-Visitor-Id` ke semua klien; STS hanya klien web/TV |
| 2b | **Validasi URL sebelum diputar** | `yt/innertube/StreamUrlValidator.kt` | `HEAD` + `Range` byte terakhir; 2xx/405 diterima, 403/410 ditolak → `REJECTED_BY_CDN`; IO diterima optimistis; manifest HLS di-probe tanpa `Range`; UA probe = UA klien pencetak URL |
| 3 | Pemutaran HLS | `player/PlayerManager.kt`, `player/ResolvingDataSource.kt`, `download/HlsFlatDownloader.kt` | `ResolvedAudio.isManifest` → `HlsRequiredException` → `MediaItem` ditukar ke m3u8 + MIME; track video dimatikan; `warmUpcoming()` menyiapkan lagu berikutnya |
| 4 | *Circuit breaker* berbasis bukti | `player/PlayerManager.kt` | `consecutiveFailures` di-reset **hanya** setelah posisi maju ≥ 8 dtk; rem burst >4 skip/30 dtk; antrean dipertahankan saat trip |
| 5 | Diagnostik & radar | `ui/screens/SettingsScreen.kt`, `ui/vm/ViewModels.kt`, `tools/ci/*.sh` | TES KONEKSI + SALIN DIAGNOSTIK + RESET; `visitor=`, `cdnRejected=`, `urlOnly=` wajib ada di laporan |

Invarian tambahan yang mudah terlewat:

- **`visitorData` wajib ada** dan asalnya dicatat (`sw.js` → `guide` → panen
  `responseContext`), TTL 12 jam, disegarkan setelah 3 respons ditolak beruntun.
- **Unduhan menolak URL `validated = false`** (`download/LyreonDownloadManager.kt`).
  Pemutaran boleh memakainya sebagai cadangan terakhir; unduhan tidak boleh menyimpan
  file terpotong diam-diam.
- **UA googlevideo hanya dari satu sumber**: `PlayerClientLadder.streamUserAgentFor(c, cver)`.
  Jangan menulis UA klien di tempat lain (pernah menyebabkan 403 massal).

## 3. Performa yang sudah diperjuangkan (jangan dikembalikan)

| Keputusan | Bukti di kode | Jangan |
|---|---|---|
| Animasi reveal per item **dimatikan** | `ui/components/Primitives.kt` → `RevealOnScroll` sekarang passthrough | menghidupkan lagi animasi per item di list panjang |
| Posisi lagu tidak menyebar ke seluruh tree | `ui/components/LyricsSheet.kt` → `rememberUpdatedState` + `derivedStateOf` | membaca `positionMs` di komposisi induk tiap frame |
| Lagu berikutnya disiapkan lebih dulu | `PlayerManager.warmUpcoming()` + `YouTubeRepository.cachedManifestFor()` | memindahkan resolusi ke UI thread |
| Resolusi stream blocking **di thread loader ExoPlayer** | `player/ResolvingDataSource.kt`, `resolveAudioBlocking` | menjadikannya `suspend` yang dipanggil dari komposisi |
| Cache URL ±6 jam + kunci anti-duplikasi request | `YouTubeRepository.streamCache`, `InnertubeFallback.streamCache` | menambah jalur resolve paralel tanpa cache |
| Kandidat format cadangan (m4a ↔ webm/opus) | `ResolvedAudio.fallbackUrl` | menghapus fallback saat merapikan model data |

## 4. Arsitektur & konvensi kode

- **DI manual lewat `core/ServiceLocator.kt`** (tidak ada Hilt/Koin). Semua repo
  `by lazy`: `db`, `library`, `settings`, `taste`, `youtube`, `lyrics`, `local`,
  `session`, `player`, `downloads`. Pertahankan — build cepat, alur mudah ditelusuri.
- **Room 2.8.4 + KSP** untuk DB lokal; **DataStore Preferences** untuk setelan
  (`data/settings/SettingsRepository.kt` → `LyreonSettings`). Jangan menambah
  SharedPreferences kedua.
- **`buildConfig` tidak aktif** (`buildFeatures { compose = true }` saja) → jangan
  menulis `BuildConfig.*`; pakai `android.os.Build.VERSION` / `packageInfo`.
- Dependensi kunci: Compose BOM `2026.01.01`, media3 `1.10.1` (exoplayer, session, ui,
  datasource-okhttp, **exoplayer-hls**), okhttp `5.1.0`, coil3 `3.3.0`, rhino `1.7.15`
  (decipher `base.js`), datastore `1.2.0`.
- **Media3 adalah `@UnstableApi`** — sudah di-opt-in lewat `freeCompilerArgs` di
  `app/build.gradle.kts`. Bila menambah modul, jangan lupa opt-in yang sama.
- Komentar & dokumentasi ditulis dalam Bahasa Indonesia, menjelaskan **kenapa**
  (bukan apa). Pertahankan gaya ini: komentar "kenapa" itulah yang menyelamatkan
  debugging jarak jauh.

### 4b. Lirik = tangga provider, bukan satu sumber

- `lyrics/LyricsRepository.kt` mencoba `LyreonLyricsProvider` berurutan menurut
  `priority` (LRCLIB 10 → KuGou 20) dan **berhenti pada hasil pertama yang punya baris
  tersinkron**; hasil non-synced disimpan sebagai cadangan. Semua hasil di-cache per
  `videoId` (satu lagu = satu rangkaian permintaan jaringan).
- Provider tidak boleh melempar exception keluar — kembalikan `null`. Jangan menambah
  provider yang butuh kunci API berbayar/akun (melanggar §1 anonim-only).
- KuGou bisa dimatikan pengguna (`kugouEnabled`). Provider baru wajib punya sakelar juga.
- UI lirik (`ui/components/LyricsSheet.kt`) membaca posisi lewat
  `PlayerManager.positionNow()` (poll 50 ms) — **jangan** dikembalikan ke flow
  `position` 500 ms: highlight akan terasa tersendat. Sebaliknya jangan menaikkan
  frekuensi poll di atas ~25 Hz tanpa mengukur baterai.
- Koreksi waktu lirik = `lyricsOffsetMs` global (langkah 500 ms, dijepit ±10 dtk) dan
  harus dipakai dua arah: `posisi + offset` untuk highlight, `waktuBaris - offset`
  untuk seek.

### 4c. Fitur audio hidup di PlaybackService, bukan di UI

- Hanya `PlaybackService` yang memegang `ExoPlayer`; UI cuma punya `MediaController`.
  Jadi kecepatan/nada/lewati-hening **diterapkan di service** dengan mengoleksi flow
  `settings` (`observeAudioSettings`) — jangan mencoba mengatur pitch dari UI lewat
  controller (MediaController tidak punya API pitch).
- Rantai processor audio wajib mempertahankan urutan: detektor hening Lyreon →
  `SilenceSkippingAudioProcessor` → `SonicAudioProcessor`. Membuang `SonicAudioProcessor`
  akan **mematikan kecepatan & nada** secara diam-diam.
- Perubahan media3 (1.10.1) yang sudah diverifikasi dari sumber upstream:
  `buildAudioSink(Context, Boolean, Boolean)` mengembalikan `AudioSink?`, builder memakai
  `setEnableAudioOutputPlaybackParameters`, `SonicAudioProcessor` ada di
  `androidx.media3.common.audio`, dan `SilenceSkippingAudioProcessor(long, long, short)`.
  Bila menaikkan versi media3, periksa ulang keempatnya sebelum menyentuh file ini.

- **Normalisasi volume** memakai `LoudnessStore` (in-memory, per videoId) yang diisi
  `ResolvingDataSource` saat stream diselesaikan, dan diterapkan `PlaybackService`
  lewat `android.media.audiofx.LoudnessEnhancer` (gain −loudnessDb×100 mB, dijepit
  −1500..+300). Bila tidak ada data loudness → **jangan sentuh volume sama sekali**.
  Efek ini ditolak sebagian perangkat: semua pemanggilan dibungkus `runCatching` dan
  kegagalan melepas enhancer, bukan mematikan pemutaran.

- **Widget home screen** (`widget/PlayerWidgetReceiver.kt`) memakai RemoteViews dan
  tombol yang mengirim `ACTION_MEDIA_BUTTON` + `KeyEvent` ke `PlaybackService` —
  jalur kendali yang sama dengan notifikasi/headset, jadi jangan menambah logika
  pemutaran kedua di widget. Snapshot dipasok `PlayerManager.publishWidgetState()`
  dan `WidgetState.publish` berhenti lebih awal bila isi tidak berubah.

- **Browse InnerTube** (`yt/BrowseModel.kt` → `BrowseParser`, `YouTubeRepository.browsePage`)
  adalah pengurai murni tanpa Android/jaringan: semua pembacaan memakai `optX` dan
  hasil kosong lebih baik daripada crash. Satu layar (`BrowseScreen`) melayani artis,
  album, genre/mood, dan kategori — jangan membuat layar kedua per jenis halaman.
  Rak lagu dirender dengan `Column`, BUKAN `LazyColumn` bersarang: item LazyColumn
  induk diukur dengan tinggi tak terbatas sehingga scrollable searah akan crash.

## 5. Internasionalisasi

- 6 berkas `strings.xml`: `values` (id, default), `values-en`, `values-hi`, `values-ja`,
  `values-ms`, `values-zh`.
- Jumlah saat ini: **271** string default vs **260** di lima locale lain → ada
  **11 string belum diterjemahkan** (utang yang diketahui; jangan ditambah). Perhitungan terakhir: 304 di `values` vs 293 di lima locale lain.
- Aturan: setiap string UI baru masuk ke **keenam** berkas dalam commit yang sama.
  Teks diagnostik/log boleh literal Indonesia di kode (bukan UI).
- Jangan memakai string literal di komposisi; jangan memakai `hardcoded` teks di
  `setContentDescription` tanpa resource.

## 6. CI & tooling

- Workflow build: `.github/workflows/android-build.yml` (JDK 21, SDK 36, Debug +
  Release APK, laporan error build, unggah artifact). **Satu-satunya verifikasi
  kompilasi yang tersedia** — sandbox tidak punya JDK/Android SDK.
- `.github/workflows/extractor-radar.yml` milik pemilik repo: token GitHub App di
  sesi ini **tidak punya izin `workflows`**, jadi jangan mendorong/mengubah berkas
  workflow. Kemampuan baru dikirim sebagai skrip di `tools/ci/` + dokumentasi.
- Skrip lokal: `tools/ci/extractor-radar.sh` (drift pin extractor),
  `tools/ci/meld-client-radar.sh` (drift spesifikasi klien vs Meld; saat ini sinkron,
  exit 0).
- Penandatanganan rilis pernah NPE dan sudah diperbaiki (commit `0f56a42`) — jangan
  "merapikan" blok signing tanpa alasan.

## 7. Diagnostik jarak jauh adalah fitur, bukan sisa debug

Pengguna melaporkan masalah dengan menempel teks SALIN DIAGNOSTIK. Formatnya
(`ladder:`, `hls:`, `probe:`, `lastGood=`, `visitor=`, `sabrTotal=`, baris
`client → VERDICT (ms) · detail`, `describeStreamUrl` yang **sudah disensor**:
tanpa `sig`/`signature`/`sparams`, `pot`/`n` hanya panjang) adalah antarmuka dukungan.
Bila mengubahnya: pertahankan nama kunci, jangan pernah mencetak materi tanda tangan
atau identitas pengguna, dan perbarui `docs/streaming-resilience.md` §5.

## 8. Lisensi & kepatuhan (sejak 2026-09-06: **GPL-3.0-only**)

- `LICENSE` = teks GPL-3.0 lengkap; **setiap** berkas Kotlin (56 berkas) dimulai dengan
  header `Copyright (C) 2026 rixz-dev` + `SPDX-License-Identifier: GPL-3.0-only`.
  Berkas baru wajib memakai header yang sama.
- Proyek sebelumnya MIT; relisensi diputuskan pemilik proyek supaya kode
  [Meld](https://github.com/FrancescoGrazioso/Meld) (GPL-3.0) boleh diserap langsung.
  Aturan menyerap kode + jejak kepatuhan: `notes/06-referensi-meld.md` §1 dan §4.
- Atribusi pihak ketiga dipajang di README §Lisensi (Meld, Metrolist/InnerTune,
  MetrolistExtractor/NewPipeExtractor, dependensi Apache-2.0/MPL-2.0).
  Jangan menghapus tabel itu saat merapikan README.
- Jangan menurunkan lisensi ke yang lebih permisif (MIT/Apache) dan jangan menambah
  pembatasan di atas GPL saat mendistribusikan APK.
- **Utang kepatuhan: SUDAH DILUNASI.** `app/build.gradle.kts` masih mengecualikan
  `META-INF/LICENSE*` dari APK, tetapi notice kini tersedia di dalam aplikasi:
  `ui/screens/LicensesScreen.kt` (rute `licenses`, dibuka dari Settings → Tentang)
  memuat daftar pustaka + pengenal SPDX + URL, kartu GPL-3.0-only, atribusi kode
  yang diporting dari Meld/Metrolist, dan pemberitahuan Apache-2.0.
  **Daftar itu ditulis tangan**: setiap kali dependensi bertambah/berganti,
  perbarui `APP_LIBRARIES`/`ART_AND_FONTS`/`CODE_PORTED_FROM` di berkas itu.

## 9. Tabel "jangan sentuh tanpa membaca"

| Berkas | Baca dulu |
|---|---|
| `yt/innertube/*.kt` | `docs/streaming-resilience.md` + `notes/01` §A |
| `player/PlayerManager.kt` | `notes/02` §2 lapisan 3–4 (HLS + breaker) |
| `player/ResolvingDataSource.kt` | `notes/01` §A5 (UA), §B5 (`validated`) |
| `download/HlsFlatDownloader.kt` | `docs/streaming-resilience.md` §7.4 |
| `ui/theme/{Color,Theme}.kt` | `notes/03` + `notes/04` |
| `app/build.gradle.kts` | `notes/02` §4 (opt-in UnstableApi, signing) |
