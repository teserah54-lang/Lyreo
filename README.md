<div align="center">

![LYREON](docs/branding/lyreon-banner.png)

### *Hear What Words Can't Say*

**LYREON** adalah aplikasi musik Android *audio-only* untuk YouTube Music — pencarian penuh, playlist, unduhan offline, pemutaran latar (background), dan rekomendasi radio otomatis, dibalut tema gelap‑crimson.

`Kotlin` · `Jetpack Compose` · `Media3 ExoPlayer` · `MetrolistExtractor` · `Room`

[Donasi pengembang ☕](https://saweria.co/riznotdev) · [Website](https://achfarizy.biz.id)

</div>

---

## 📖 Tentang

LYREON memutar **hanya audio** dari YouTube Music (Opus/WebM atau AAC/M4A) sehingga lebih hemat kuota dibanding memutar video. Semua akses konten melalui *extractor* open source ([MetrolistExtractor], fork terpelihara dari NewPipeExtractor) — **tanpa API resmi YouTube dan tanpa akun**.

> ⚠️ **Disclaimer:** Proyek ini tidak berafiliasi dengan YouTube/Google. Pengguna bertanggung jawab mematuhi ketentuan layanan di wilayah masing‑masing. LYREON hanya untuk pemutaran pribadi.

## ✨ Fitur Utama

| # | Fitur | Detail |
|---|-------|--------|
| 1 | **Pencarian YouTube Music penuh** | Filter LAGU / VIDEO / ALBUM / PLAYLIST, paging "muat lebih banyak" |
| 2 | **Saran pencarian real‑time** | Autocomplete dengan *debounce* 280 ms |
| 3 | **Pemutaran audio‑only** | Hanya stream audio — hemat kuota |
| 4 | **Background playback** | `MediaSessionService` + notifikasi media (lockscreen, Bluetooth, Android Auto) |
| 5 | **Antrean pintar** | Play next, add to queue, hapus, sheet antrean |
| 6 | **Playlist lokal** | Buat/rename/hapus/urutkan, tersimpan di Room |
| 7 | **Unduhan offline** | Foreground service + notifikasi progres, tersimpan di storage eksternal |
| 8 | **Putar offline otomatis** | Lagu terunduh diputar dari file lokal (0 kuota) |
| 9 | **Favorit (liked)** | Tersinkron di semua layar |
| 10 | **Riwayat dengar** | Recently played + play‑count |
| 11 | **Radio otomatis** | Antrean habis → lanjut ke terkait (bisa dimatikan di Settings) |
| 12 | **Home dinamis** | Quick picks, "Lanjutkan Mendengar", Genre Reel |
| 13 | **Editorial Archive** | Kurasi desain → kueri musik nyata |
| 14 | **Sleep timer** | 5–90 menit + indikator sisa waktu |
| 15 | **Shuffle & Repeat + kualitas audio** | Terbaik / Seimbang / Hemat data |
| 16 | **Restore sesi + deep link** | Antrean pulih saat app dibuka; terima link `youtu.be` / `youtube.com/watch` |
| 17 | **Streaming 100% anonim** | Tanpa akun, tanpa cookie, tanpa server perantara: tangga klien InnerTube hasil porting **Meld** (`visionos` → `android_vr` 1.65.10 → `android_vr` 1.43.32 → `ipados` → `ios`), tiap URL **divalidasi** dengan probe byte terakhir sebelum diputar, plus pemutaran **HLS** untuk jalur yang hanya memberi manifest |
| 18 | **Kesehatan stream + diagnostik klien** | *Circuit breaker* berbasis bukti pemutaran (bukan `STATE_READY`), pesan gagal yang actionable, "Tes koneksi" per klien, tombol salin diagnostik, dan radar drift extractor |

*Bonus:* tema Gelap/Kertas/Sistem, reduce‑motion, retry otomatis saat URL kedaluwarsa, snackbar error.

## 🧱 Tech Stack

| Kategori | Teknologi |
|----------|-----------|
| Bahasa | **Kotlin 2.4** (K2), JVM target 17 |
| UI | **Jetpack Compose** (BOM `2026.01.01`), Material 3 |
| Player | **AndroidX Media3 1.10.1** — `ExoPlayer` + `MediaSession` (background) |
| Extractor | **MetrolistExtractor** (`com.github.MetrolistGroup:MetrolistExtractor`, di-pin per commit) — fork NewPipeExtractor, plus **tangga klien InnerTube** langsung ke Google (`music.youtube.com/youtubei/v1`) via `PlayerClientLadder` bila extractor utama gagal. Spesifikasi klien disalin dari [FrancescoGrazioso/Meld](https://github.com/FrancescoGrazioso/Meld) dan dijaga tetap sinkron oleh `tools/ci/meld-client-radar.sh`. **Selalu anonim** (`setTokens()` tidak pernah dipanggil) |
| Jaringan | **OkHttp 5.1**, **Coil 3** (gambar), `StreamingDataSource` kustom untuk resolve stream lazily |
| Persistence | **Room 2.8** (SQLite), **DataStore** (preferensi/settings + profil selera) |
| Navigasi | **Navigation Compose** |
| Build | **Gradle 9.x** (via `gradle-wrapper.properties`), **AGP 9.3**, **JDK 21** |

> **Mengapa fork extractor?** Upstream NewPipeExtractor v0.25+ tidak lagi ter‑publish benar di JitPack (modul kosong → 404). Ekosistem (Metrolist dkk.) bermigrasi ke fork terpelihara ini; API identik: `org.schabi.newpipe.extractor.*`.

## 🗺️ Arsitektur

```
┌─ UI (Compose) ─┬─ Screens: Home / Search / Library / Archive / Offline / Settings / NowPlaying
│                └─ Components: MiniPlayerBar, LyricsSheet, GenreReel, LyreonPlayButton …
│
├─ PlayerManager ─┬─ MediaController ⇄ PlaybackService (Media3 MediaSessionService, background)
│   (state)       ├─ queue / radio auto‑extend / sleep timer / restore sesi
│                 └─ antrean bebas duplikat (cap per judul‑inti via MusicTextAnalyzer)
│
├─ Repositories ─┬─ YouTubeRepository (pencarian, metadata, stream, charts artis)
│                 ├─ TasteRepository (profil selera + diversePick)
│                 ├─ LibraryRepository (Room: playlist, riwayat, liked, unduhan)
│                 └─ LocalMusicRepository (MediaStore + SAF, thumbnail album art)
│
├─ Streaming ────┬─ PlayerClientLadder (klien InnerTube anonim + deteksi SABR/DRM/HLS)
│  (ketahanan)    ├─ StreamUrlValidator (probe URL sebelum diputar → tolak 403/pratinjau)
│                ├─ InnertubeFallback (tangga klien → URL audio ATAU manifest HLS)
│  anonim         ├─ ResolvingDataSource → HlsRequiredException → MediaItem m3u8
│                 └─ PlayerManager.StreamHealth (circuit breaker berbasis kemajuan)
│
└─ Data ────────── Room DB · DataStore · model (LyreonTrack, SearchFilter, …)
```

**State scoping (anti‑lag):** `PlayerUiState` (track/queue/isPlaying — frekuensi rendah) dipisah dari `PlayerPosition` (posisi/durasi — ticker 500 ms). Hanya `MiniPlayerBar` & `NowPlayingScreen` yang mengoleksi flow posisi, sehingga layar lain tidak *recompose* tiap tick.

**Pembersihan duplikat:** pencarian & radio menggunakan `MusicTextAnalyzer.coreTitle()` (strip modifier "speed up/reverb/tiktok" + kurung) untuk membatasi varian judul‑sama (reupload channel berbeda) di hasil & antrean.

## 📁 Struktur Project

```
app/src/main/
├─ java/com/lyreon/app/
│  ├─ MainActivity.kt            # root Compose + NavHost
│  ├─ LyreonApp.kt               # Application + init NewPipe
│  ├─ core/                      # ServiceLocator, LocaleHelper
│  ├─ data/
│  │  ├─ db/                     # Room (Daos, Entities, Database)
│  │  ├─ model/                  # LyreonTrack, SearchFilter, LyreonArchive
│  │  ├─ taste/                  # MusicTextAnalyzer, TasteRepository (profil selera)
│  │  ├─ settings/               # SettingsRepository
│  │  └─ LibraryRepository.kt
│  ├─ download/                  # DownloadService, LyreonDownloadManager
│  ├─ local/                     # LocalMusicRepository (musik di storage)
│  ├─ lyrics/                    # LyricsRepository
│  ├─ player/                    # PlayerManager, PlaybackService, ResolvingDataSource (+HlsRequiredException)
│  ├─ ui/                        # components/, screens/, theme/, vm/
│  └─ yt/                        # YouTubeRepository, OkHttpDownloader
│     └─ innertube/              # fallback InnerTube (player/search/playlist) + decipher JS (Rhino)
│                                # + PlayerClientLadder (klien anonim, deteksi SABR/DRM/HLS)
├─ res/                          # mipmap (icon), drawable, values, xml
└─ AndroidManifest.xml

docs/streaming-resilience.md     # ketahanan streaming anonim: peta kebijakan, runbook, riwayat
notes/                           # memori proyek: kesalahan & pelajaran, invarian, panduan UI,
                                 # spesifikasi tema baru, peta fitur, referensi Meld
tools/ci/                        # extractor-radar.sh, meld-client-radar.sh (drift vs upstream)
```

## 🚀 Persiapan & Build

### Prasyarat
- **JDK 21** (AGP 9.x membutuhkannya).
- **Android SDK** Platform & Build‑Tools **36** (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`).
- **Gradle 9.x** — versi diambil otomatis dari `gradle/wrapper/gradle-wrapper.properties` (tidak perlu instal manual).

### Clone
```bash
git clone https://github.com/rixz-dev/Lyreon.git
cd Lyreon
```

### Build Debug APK
```bash
./gradlew :app:assembleDebug
# hasil: app/build/outputs/apk/debug/app-debug.apk
```

### Build Release APK (R8)
```bash
./gradlew :app:assembleRelease
# hasil: app/build/outputs/apk/release/app-release.apk
```

### Signing release APK
Keystore **tidak di‑commit** (material privat, lihat `.gitignore`). Tanpa keystore, `assembleRelease` menghasilkan APK **tidak ditandatangani** yang harus ditandatangani manual sebelum distribusi:

```bash
# 1) Buat keystore sendiri (sekali saja)
keytool -genkeypair -v -keystore upload.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 -alias upload

# 2) Tandatangani APK hasil build
apksigner sign --ks upload.keystore \
  --out app-release-signed.apk app/build/outputs/apk/release/app-release.apk
```

Atau letakkan `debug.keystore` di root repo (password `android`, alias `androiddebugkey`) agar otomatis dipakai — build akan mendeteksinya dan menandatangani debug **dan** release.

### Menghindari peringatan Google Play Protect (instalasi side‑load)
Play Protect menilai aplikasi berdasarkan **tanda tangan (signature) & reputasi**, bukan isi kode.
Oleh karena itu hal yang bisa dilakukan **di sisi build/kode** agar instalasi lebih bersih:

1. **Gunakan keystore release yang unik & stabil.** Jangan bagikan keystore atau menandatangani
   dengan *debug key* untuk distribusi. Signature yang sama di semua rilis membantu Play Protect
   mengenali aplikasi sebagai "pengembang yang sama".
2. **Konsisten menandatangani setiap update** dengan keystore yang sama — APK yang ganti‑ganti
   signature justru dicurigai sebagai aplikasi berbeda.
3. **Target SDK tinggi** — sudah `targetSdk = 36` (Android 16). Menjaga target SDK terkini adalah
   salah satu sinyal keamanan yang dinilai.
4. **Izin seminimal mungkin** — daftar izin di `AndroidManifest.xml` sudah minimal & hanya untuk
   fungsi nyata (streaming, background play, unduhan, musik lokal). Jangan menambah izin tak perlu.
5. **Kode bebas dari perilaku mencurigakan** — tidak ada pemuatan kode dari sumber tak dikenal,
   tidak ada *hook* sistem, tidak meminta izin berlebihan.

> Catatan realistis: meski semua poin di atas sudah dipenuhi, **tidak ada jaminan kode** untuk
> menonaktifkan peringatan Play Protect pada side‑load. "Unknown developer" kadang tetap muncul
> satu kali saat instal pertama; bila Play Protect benar‑benar mem‑blokir, jalur yang dijamin adalah
> verifikasi resmi (Play Console / pengajuan tinjauan Play Protect), bukan sesuatu yang bisa dipaksa
> lewat kode.

## ⚙️ CI (GitHub Actions)

### `android-build.yml` — build APK
Workflow build otomatis ada di **`.github/workflows/android-build.yml`** (trigger: `push` ke `main`/`arena/*` dan PR). Ia:
1. Setup JDK 21 + Android SDK 36.
2. Build **Debug** lalu **Release** (`assembleRelease` dengan R8).
3. Upload kedua APK sebagai *artifact* (retensi 30 hari).
4. Bila build gagal, ringkasan error otomatis diposting sebagai komentar commit.

Jalankan manual lewat tab **Actions → Android Build → Run workflow** (atau `gh run watch` via CLI).

### Radar dependency (anti-rot)
Kegagalan stream hampir selalu berawal dari pin extractor yang tertinggal dari upstream.
Ada dua bentuk, isinya sama:

```bash
# lokal — bisa langsung dijalankan (butuh gh + jq)
bash tools/ci/extractor-radar.sh
REPO=InfinityLoop1308/PipePipeExtractor bash tools/ci/extractor-radar.sh
```

Skrip itu membandingkan pin di `app/build.gradle.kts` dengan HEAD fork, mencetak commit
yang terlewat, dan keluar dengan kode 1 bila tertinggal. Versi workflow-nya
(**`.github/workflows/extractor-radar.yml`** — sudah aktif, tiap Senin 02:17 UTC + manual)
menambah satu langkah: **membuka issue berisi daftar commit yang terlewat**.

> Catatan: token GitHub App yang dipakai agen tidak punya izin `workflows`, jadi berkas
> workflow tidak bisa diubah dari sesi agen. Bila isinya perlu disesuaikan, edit langsung
> lewat UI GitHub atau dari akun Anda (mis. hapus blok komentar "BERKAS INI BELUM AKTIF"
> di bagian atas, yang sekarang sudah tidak berlaku).

## 🎵 Izin & Musik Lokal

Aplikasi bisa memutar **file musik di penyimpanan perangkat** (mp3, m4a, aac, flac, ogg, wav, …) lewat dua jalur:
- **MediaStore** — indeks audio bawaan Android (filter longgar: membuang hanya nada dering/notifikasi/alarm/rekaman).
- **Folder kustom (SAF, `ACTION_OPEN_DOCUMENT_TREE`)** — untuk menembus folder ber‑`.nomedia` (mis. WhatsApp/Telegram).

Izin: Android 13+ → `READ_MEDIA_AUDIO`; ≤12 → `READ_EXTERNAL_STORAGE`. Thumbnail diambil dari *embedded album art* (`MediaMetadataRetriever`) dan di‑cache di `cacheDir/local_art/`.

## 📝 Lirik & Radio

- **Lirik** diambil lewat `LyricsRepository` dan ditampilkan sinkron di `LyricsSheet`.
- **Radio otomatis**: saat antrean hampir habis (`maybeExtendQueue`) atau lagu berakhir (`onEnded`), `PlayerManager` mengambil related + (berselang) query persona, lalu memilih lewat `TasteRepository.diversePick()` — bebas duplikat judul‑sama.

## 🕵️ Streaming anonim & ketahanan

Sejak 2025 YouTube menutup akses anonim berlapis-lapis: **SABR** (response player
berisi format tanpa satu pun URL yang bisa di-GET), **poToken** (atestasi BotGuard
yang diikat ke videoId — tanpanya `403`/`UNPLAYABLE`), dan **DRM** pada format
klien TV tanpa cookie guest. Extractor lalu melempar
`ContentNotSupportedException: "YouTube returned SABR-only streaming data …"` —
dan karena ini kebijakan server, **semua lagu gagal sekaligus** → auto-skip → loop.

**Lyreon tetap anonim**: tidak ada akun, tidak ada cookie, `setTokens()` tidak
pernah dipanggil. Konsekuensinya hanya dua barang yang bisa diputar — URL langsung
dari klien yang tidak menuntut poToken, dan **manifest HLS** (HLS tidak menuntut
poToken GVS). Sejak September 2026 daftar klien itu **disalin dari
[Meld](https://github.com/FrancescoGrazioso/Meld)**, klien musik anonim yang
pengembangnya mengukur klien mana yang sanggup melayani *satu file utuh* (bukan
sekadar mengembalikan URL), dan setiap URL diuji dulu sebelum diserahkan ke
pemutar:

| # | Lapisan | Isi |
|---|---------|-----|
| 1 | Knob & bypass extractor | `setLoadingTimeout(12)` (default fork 5 s sering kepotong di jaringan seluler), `setFetchDislike(false)`, dan extractor **di-bypass 10 menit** setelah 2× SABR beruntun supaya tiap lagu tidak membayar timeout extractor |
| 2 | Tangga klien anonim (porting **Meld**) | `PlayerClientLadder`: `visionos` 0.1 → `android_vr` 1.65.10 → `android_vr` 1.43.32 → `ipados` → `ios`, semua ke `music.youtube.com` dengan `X-Goog-Visitor-Id` (diambil dari `sw.js_data` — **wajib**, tanpa itu VISIONOS/ANDROID_VR menjawab `LOGIN_REQUIRED`). Deteksi SABR-only/HLS-only/DRM, urutan adaptif, *cooldown* 3 menit, `signatureTimestamp` hanya untuk klien web/TV. Klien yang butuh login atau poToken (`tvhtml5`, `web_creator`, `web_remix`, `web`, `mweb`) **hanya dijalankan saat Tes koneksi** |
| 2b | Validasi URL sebelum diputar | `StreamUrlValidator`: `HEAD` dengan `Range: bytes=clen-1-clen-1` (byte **terakhir** file) → 2xx/405 diterima, 403/410 ditolak dan tangga lanjut. Ini yang membuang URL "pratinjau ~1 MiB" penyebab lagu mati di detik ke-60, dan membuat URL dari extractor NewPipe ikut teruji. Bila semua URL langsung ditolak, jalur **HLS** dicoba; bila itu pun gagal, URL terakhir tetap dipakai untuk putar (`validated = false`) tetapi **ditolak untuk unduhan** |
| 3 | Pemutaran HLS | `media3-exoplayer-hls` + `HlsRequiredException`: saat resolusi menghasilkan m3u8, `MediaItem` ditukar ke URL manifest + MIME yang benar, dan track video dimatikan (hemat kuota). Lagu berikutnya disiapkan lebih dulu oleh `warmUpcoming()` |
| 4 | *Circuit breaker* | `consecutiveFailures` di-reset **hanya** setelah bukti kemajuan (posisi maju ≥ 8 dtk), plus rem burst (>4 skip/30 dtk). Saat trip: antrean dipertahankan, pesan actionable, radio auto-extend ditahan |
| 5 | Diagnostik | Settings → *KESEHATAN STREAM*: **Tes koneksi** (verdict + latensi + hasil probe CDN tiap klien, termasuk `visitorData` dari mana), **Salin diagnostik** (laporan teks untuk laporan bug), **Reset cache stream**, riwayat 40 kejadian |

Rincian lengkap — peta kebijakan poToken per klien, runbook "semua lagu gagal lagi",
riwayat penyesuaian, dan pekerjaan lanjutan (poToken/BotGuard, SABR native,
migrasi PipePipeExtractor): [`docs/streaming-resilience.md`](docs/streaming-resilience.md).

## 🧩 Catatan & Keterbatasan

- **Fragilitas extractor:** YouTube mengubah kebijakan klien secara berkala (SABR, poToken, DRM, gate per versi klien) → sebagian atau semua lagu bisa “tak tersedia”. Penanganan Lyreon: tangga klien **anonim** hasil porting Meld, **validasi tiap URL** sebelum diputar (probe byte terakhir → menolak pratinjau ~1 MiB), **pemutaran HLS** untuk jalur manifest, bypass extractor saat terbukti SABR, penyegaran `visitorData` setelah 3 respons ditolak, retry + resolve ulang saat token basi, kandidat format beda (m4a ↔ webm/opus), dan *circuit breaker* agar kegagalan tidak berubah jadi loop. Yang **tidak** didukung dan alasannya: poToken/BotGuard (jalur anonim Meld pun tidak memakainya) dan pemutaran SABR native — lihat [`docs/streaming-resilience.md`](docs/streaming-resilience.md) §7.
- **Tanpa akun, tanpa cookie:** keputusan produk (privasi + tidak merepotkan pengguna). Risikonya diterima sadar: bila suatu hari YouTube menutup semua celah anonim, pemutaran berhenti sampai tangga klien disesuaikan — lihat §3 dokumen yang sama.
- **Charts artis** (tab Search/Home) mengandalkan YouTube Music Charts + profil kanal; bila gagal, digunakan kurasi per‑wilayah agar kartu tidak kosong.
- **Legalitas:** hanya untuk pemutaran pribadi; bukan pengganti layanan berlangganan resmi.

## 🤝 Kontribusi

1. Baca [`notes/README.md`](notes/README.md) — khususnya
   [`notes/02`](notes/02-yang-sudah-bagus-jangan-dirusak.md) (invarian yang tidak boleh
   dirusak) dan [`notes/03`](notes/03-panduan-update-ui.md) (wajib sebelum menyentuh `ui/`).
2. Fork → buat branch fitur (`git checkout -b feat/...`).
3. Pastikan `./gradlew :app:assembleDebug` lolos (di CI: workflow *Android Build*).
4. PR ke `main` dengan deskripsi jelas; string UI baru masuk ke **6** berkas `strings.xml`.

Isu extractor sebaiknya dilaporkan ke upstream [MetrolistExtractor]; isu UI/logika ke repo ini.

## 📜 Lisensi

```
LYREON — Hear What Words Can't Say.
Copyright (C) 2026 rixz-dev

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, version 3 of the License (GPL-3.0-only).

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

Seluruh teks lisensi: [`LICENSE`](LICENSE). Setiap berkas Kotlin membawa header
`SPDX-License-Identifier: GPL-3.0-only`.

> **Perubahan lisensi (2026-09-06).** Proyek ini sebelumnya berlisensi MIT. Karena
> Lyreon meniru spesifikasi klien, urutan tangga stream, dan pendekatan validasi URL
> dari [FrancescoGrazioso/Meld](https://github.com/FrancescoGrazioso/Meld)
> (**GPL-3.0**, fork Metrolist ← InnerTune), dan akan terus menyerap fitur dari
> ekosistem GPL itu, pemilik proyek memutuskan **relisensi ke GPL-3.0-only** agar
> kompatibel dan patuh. Salinan Lyreon versi MIT yang sudah terdistribusi sebelum
> tanggal itu tetap MIT; semua versi sejak commit relisensi tunduk pada GPL-3.0-only.

**Atribusi pihak ketiga.**

| Sumber | Lisensi | Yang diambil |
|---|---|---|
| [FrancescoGrazioso/Meld](https://github.com/FrancescoGrazioso/Meld) | GPL-3.0 | Spesifikasi klien InnerTube & urutan tangga stream, sumber `visitorData` (`sw.js_data`), pendekatan validasi URL (probe byte terakhir), filter audio auto-dub |
| [MetrolistGroup/Metrolist](https://github.com/MetrolistGroup/Metrolist) · InnerTune | GPL-3.0 | Silsilah desain klien musik YouTube anonim |
| [MetrolistGroup/MetrolistExtractor](https://github.com/MetrolistGroup/MetrolistExtractor) · NewPipeExtractor | GPL-3.0 | Dependensi ekstraksi stream (di-pin per commit) |
| AndroidX / Media3, Jetpack Compose, OkHttp, Room, DataStore, Coil | Apache-2.0 | Dependensi runtime & UI |
| Mozilla Rhino | MPL-2.0 | Decipher `base.js` (tanda tangan URL) |

Aturan internal saat menyerap kode GPL: pertahankan header hak cipta aslinya, cantumkan
path berkas sumber sebagai komentar, dan catat di
[`notes/06-referensi-meld.md`](notes/06-referensi-meld.md) §4. Rinciannya di
[`notes/02`](notes/02-yang-sudah-bagus-jangan-dirusak.md) §9.

[MetrolistExtractor]: https://github.com/MetrolistGroup/MetrolistExtractor
