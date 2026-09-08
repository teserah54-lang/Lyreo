# Ketahanan streaming Lyreon (anonim, tanpa akun)

Dokumen ini menjelaskan **mengapa lagu bisa gagal diputar**, **apa yang Lyreon
lakukan**, dan **bagaimana menyesuaikannya saat YouTube mengubah kebijakan lagi**.
Ditulis untuk dibaca ulang enam bulan dari sekarang, saat gejalanya muncul lagi
dan ingatan tentang penyebabnya sudah hilang.

---

## 1. Gejala

Semua lagu gagal resolve → pemutar melompat dari satu lagu ke lagu berikutnya
tanpa henti ("loop skip"). Di log muncul:

```
ContentNotSupportedException: YouTube returned SABR-only streaming data without
usable stream URLs. Try logging in to get HLS fallback streams.
```

dan/atau, dari diagnostik dalam app (Settings → Kesehatan stream):

```
tv_embedded → PLAYABILITY_BLOCKED · ERROR: YouTube is no longer supported in this application or device.
web_remix   → PLAYABILITY_BLOCKED · UNPLAYABLE: Video unavailable
web         → PLAYABILITY_BLOCKED · UNPLAYABLE: Video unavailable
mweb        → PLAYABILITY_BLOCKED · UNPLAYABLE: The page needs to be reloaded.
```

Penting: ini **bukan** kegagalan per-video. Ia kebijakan server yang berlaku
untuk satu kelas permintaan, jadi begitu kena, seluruh katalog kena.

## 2. Akar masalah (per September 2026)

YouTube menutup akses anonim lewat tiga mekanisme yang saling menumpuk:

1. **SABR** (*server-side adaptive bitrate*). Response `youtubei/v1/player`
   berisi daftar `adaptiveFormats` **tanpa satu pun field `url`/`signatureCipher`**,
   plus `streamingData.serverAbrStreamingUrl`. URL-nya tidak ada untuk di-GET:
   klien resmi mengambil chunk lewat protokol SABR (protobuf, chunk dinamis).
   Extractor gaya NewPipe melempar `ContentNotSupportedException`.
2. **poToken (Proof of Origin)**. Sebagian klien wajib mengirim token atestasi
   BotGuard, kalau tidak: `403` di `googlevideo.com`, atau `UNPLAYABLE` sejak
   dari response player. Token diikat ke `visitorData`/sesi **dan ke videoId**,
   jadi tidak bisa dipanen sekali lalu dipakai ulang.
3. **DRM untuk klien TV**. Tanpa cookie "guest aktif", format dari klien
   `TVHTML5` dikembalikan terkunci DRM (`drmFamilies`) — ada URL, tapi tidak
   bisa diputar tanpa lisensi Widevine.

Yang tersisa untuk klien **anonim** adalah celah-celah sempit, dan celah itu
bergeser tiap beberapa bulan. Peta terakhir (yt-dlp PO Token Guide, revisi
Juli 2026 — sumber paling teruji karena dipakai jutaan request/hari):

| klien | poToken GVS | yang benar-benar bisa dipakai anonim |
|---|---|---|
| `visionos` | **tidak** | URL langsung, tanpa decipher JS — default anonim yt-dlp |
| `web_embedded` | **tidak** | URL langsung, hanya video yang boleh di-embed |
| `tv` / `tv_downgraded` | **tidak** | sering DRM; kadang itag 18 lolos |
| `web_safari` | ya (HTTPS) | **manifest HLS** (m3u8) — HLS tidak butuh poToken GVS |
| `tv_simply` | ya (HTTPS/DASH) | **manifest HLS** |
| `android_vr` | ya (HTTPS/DASH) | **manifest HLS**; sejak 2026-08-17 semua format 403 |
| `web` / `web_remix` / `mweb` | ya | SABR-only atau `UNPLAYABLE` |
| `android` / `ios` | ya (GVS/Player) | 403 tanpa poToken |

**Kesimpulan yang membentuk desain Lyreon:** untuk tetap anonim, hanya ada dua
barang yang bisa diputar — (a) URL langsung dari klien bebas poToken, dan
(b) **manifest HLS**. Karena itu dukungan HLS bukan opsional; ia separuh dari
strategi anonim.

### 2.1 Tiga koreksi dari Meld (September 2026)

Peta yt-dlp di atas adalah kebijakan **per keluarga klien**. Ia tidak menjawab
pertanyaan yang benar-benar menentukan: *klien mana yang hari ini sanggup
melayani satu file utuh ke perangkat anonim?* Untuk itu Lyreon meniru
[FrancescoGrazioso/Meld](https://github.com/FrancescoGrazioso/Meld) — klien musik
Android anonim (fork Metrolist, 282 commit di depan hulu, push terakhir
2026-09-05) yang pengembangnya mengukur langsung di perangkat. Tiga temuan
mereka mengubah desain Lyreon:

**a. Gate terjadi per VERSI klien, bukan per bentuk request.**

> "`ANDROID_VR_1_43_32` and `ANDROID_VR_1_61_48` are bot-gated **by client
> version**: measured on three videoIds, both return `LOGIN_REQUIRED / "Sign in
> to confirm you're not a bot"` with zero formats, anonymously *and* signed in,
> with or without visitorData, and with the device fields stripped. Only the
> version differs here, and 1.65.10 returns `OK` with 100% direct-url formats —
> so this is a server-side version gate, not a malformed request."

Konsekuensi: memperbaiki header/body/context tidak akan menolong bila versi
kliennya yang ditutup. Yang harus diubah adalah **pin versi**, dan itu pekerjaan
satu baris di `PlayerClientLadder.kt`. Lyreon menyimpan `android_vr_1_61_48`
sebagai *kontrol* di "Tes koneksi": bila ia `LOGIN_REQUIRED` sementara
`android_vr` (1.65.10) `OK`, laporan pengguna langsung membuktikan gate versi.

**b. `visitorData` wajib untuk semua klien — termasuk yang `loginSupported = false`.**

> "Sent to EVERY client … VISIONOS and ANDROID_VR 1.65.10 — the only clients
> that currently mint a fully readable stream URL — *require* it. Without one
> they answer UNPLAYABLE / LOGIN_REQUIRED with zero formats."

Jadi `LOGIN_REQUIRED: Sign in to confirm you're not a bot` **belum tentu** berarti
IP kita diblokir; bisa juga request kita berangkat tanpa identitas sesi. Lyreon
kini mengambil visitorData dari sumber Meld — `https://music.youtube.com/sw.js_data`
(bukan scrape halaman watch, bukan `guide`) — mengirimnya sebagai
`X-Goog-Visitor-Id` ke **setiap** klien, mencatat sumbernya
(`visitor=sw.js|guide|response|-`) di diagnostik, memanjatnya dari
`responseContext` bila ada respons yang lolos, dan menyegarkannya setelah tiga
respons ditolak beruntun.

**c. "Ada URL" ≠ "bisa diputar": URL IOS/IPADOS/ANDROID_VR lawas adalah pratinjau ~1 MiB.**

```
videoId       itag  byte terakhir terbaca   = detik audio
Rr1Cdli5nE8   251   1.040.807                ~61 dari 268
phLb_SoPBlA   251   1.049.091                ~61 dari 274
UbX5Yns8fHk   251   1.019.638                ~67 dari 159
```

googlevideo melayani awalan byte tetap lalu menjawab 403 untuk semua offset
sesudahnya — bukan batas laju, bukan jumlah request, bukan kedaluwarsa (URL baru
yang meminta `bytes=524288-1048575` sebagai request pertamanya pun langsung 403).
Inilah sumber laporan "lagu mati setelah 30–90 detik" dan, di Lyreon,
`ERROR_CODE_IO_BAD_HTTP_STATUS` padahal extractor melaporkan `streams=4`.

Karena itu setiap URL — dari tangga klien **maupun** dari extractor NewPipe —
kini di-probe sebelum diserahkan ke ExoPlayer: `HEAD` dengan
`Range: bytes=clen-1-clen-1` (byte **terakhir** file, supaya jendela pratinjau
terlampaui), jatuh ke `bytes=0-524287` bila panjang file tak diketahui. 2xx/405
diterima, 403/410 ditolak, `IOException` diterima optimistis (jangan membakar
klien karena timeout sesaat). Manifest HLS di-probe tanpa `Range`, karena HEAD
tanpa Range justru dijawab 403 untuk media googlevideo pada art track `- Topic`
(padahal GET ber-Range dijawab 206) — aturan yang tidak berlaku untuk manifest.

## 3. Keputusan produk: ANONIM, tanpa cookie

Lyreon **tidak** meminta, menyimpan, atau mengirim cookie akun YouTube.
`ServiceList.YouTube.setTokens()` sengaja tidak pernah dipanggil.

Alasan:

- **Gesekan pengguna.** Mengambil cookie butuh browser desktop, mode incognito
  (agar sesi tidak invalid), menyalin header `Cookie` utuh, dan memahami bahwa
  `SAPISID` wajib ada. Itu terlalu banyak untuk sebuah pemutar musik, dan
  sebagian besar pengguna akan menyerah di tengah jalan.
- **Risiko akun.** Cookie adalah kredensial penuh. Menyimpannya di aplikasi
  pihak ketiga memindahkan risiko pembajakan/penangguhan akun ke pengguna.
- **Permukaan privasi.** Tanpa cookie, tidak ada data identitas yang bisa bocor
  dari perangkat, terkirim ke log, atau terbawa ke permintaan pihak ketiga.
- **Beban dukungan.** "Cookie saya ditolak / akun saya kena peringatan" adalah
  kelas tiket yang tidak perlu ada.

Konsekuensi yang diterima secara sadar: bila suatu saat YouTube menutup **semua**
celah anonim (SABR + poToken di semua klien, HLS pun dikunci), Lyreon kehilangan
kemampuan memutar sampai tangga klien disesuaikan atau dukungan SABR/poToken
ditambahkan (§7). Itu risiko yang dipilih, bukan yang diabaikan.

> Kode login (parse cookie, SAPISIDHASH, `setTokens`) pernah diimplementasikan di
> branch `arena/01a0727b-lyr` commit `73aa177`, lalu **dihapus** pada keputusan
> ini. Bila perlu dihidupkan lagi, commit itu adalah rujukannya.

## 4. Pertahanan berlapis

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 1. Knob & bypass extractor                                                │
│    setLoadingTimeout(12) · setFetchDislike(false)                         │
│    extractor di-bypass 10 menit setelah 2× SABR beruntun (hemat latensi)  │
├──────────────────────────────────────────────────────────────────────────┤
│ 2. Tangga klien ANONIM (PlayerClientLadder) — urutan terukur Meld         │
│    visionos(0.1) → android_vr(1.65.10) → android_vr_1_43_32 → ipados → ios│
│    host music.youtube.com untuk semua klien · X-Goog-Visitor-Id wajib ·   │
│    lintasan HLS (web_safari, tv_simply) bila semua URL langsung ditolak   │
│    diagnostik saja: tvhtml5, web_creator, web_remix, web, mweb,           │
│      android_vr_1_61_48 (kontrol gate-versi)                              │
│    deteksi SABR-only / HLS-only / DRM / playability · urutan adaptif ·    │
│    cooldown 3 menit · signatureTimestamp (STS) hanya klien web/TV         │
├──────────────────────────────────────────────────────────────────────────┤
│ 2b. Validasi URL SEBELUM diputar (StreamUrlValidator)                     │
│    HEAD + Range byte terakhir file (clen-1) → 2xx/405 diterima,           │
│    403/410 ditolak → lanjut klien berikutnya (verdict REJECTED_BY_CDN)    │
│    menolak pratinjau ~1 MiB yang mematikan lagu di detik ke-60            │
│    UA probe = UA klien pencetak URL (identik dengan request ExoPlayer)    │
│    extractor NewPipe ikut divalidasi; gagal → turun ke tangga klien       │
│    cadangan terakhir: URL tak tervalidasi (validated=false) → putar boleh,│
│      unduhan MENOLAK supaya tidak menyimpan file terpotong                │
├──────────────────────────────────────────────────────────────────────────┤
│ 3. Pemutaran HLS (media3-exoplayer-hls)                                   │
│    ResolvedAudio.isManifest → HlsRequiredException → MediaItem ditukar    │
│    ke URL m3u8 + MIME → HlsMediaSource · track video dimatikan (hemat)    │
│    lagu berikutnya disiapkan lebih dulu oleh warmUpcoming()               │
│    unduhan: HlsFlatDownloader (init + segmen → satu file, tanpa FFmpeg)   │
├──────────────────────────────────────────────────────────────────────────┤
│ 4. Circuit breaker berbasis bukti                                         │
│    reset HANYA setelah posisi maju ≥ 8 s · rem burst (>4 skip/30 s)       │
│    saat trip: antrean DIPERTAHANKAN · pesan actionable · radio ditahan    │
├──────────────────────────────────────────────────────────────────────────┤
│ 5. Diagnostik & radar                                                     │
│    Tes koneksi (verdict + ms + hasil probe CDN per klien) · SALIN         │
│      DIAGNOSTIK (termasuk visitor=… & cdnRejected=…) · RESET              │
│    logcat: LyreonStreamHealth / PlayerClientLadder / InnertubeFallback /  │
│      InnertubeConfig / StreamUrlValidator                                 │
│    tools/ci/extractor-radar.sh  — drift pin extractor                     │
│    tools/ci/meld-client-radar.sh — drift spesifikasi klien vs Meld        │
└──────────────────────────────────────────────────────────────────────────┘
```

### Berkas kunci

| Berkas | Peran |
|---|---|
| `yt/innertube/PlayerClientLadder.kt` | tabel klien (spesifikasi Meld), verdict, cooldown, bypass extractor, diagnostik |
| `yt/innertube/StreamUrlValidator.kt` | probe URL/manifest sebelum diputar; deskripsi URL tersensor untuk log |
| `yt/innertube/InnertubeRequest.kt` | bentuk request per klien (endpoint, header, body, STS) |
| `yt/innertube/InnertubeConfig.kt` | scrape API key, versi web, `visitorData` (sw.js_data), `base.js`, `STS` |
| `yt/innertube/InnertubeFallback.kt` | menelusuri tangga → `ResolvedAudio` (URL langsung atau manifest) |
| `yt/YouTubeRepository.kt` | extractor utama → fallback; `StreamProbe`; cache URL |
| `player/ResolvingDataSource.kt` | resolusi malas `lyreon://`; melempar `HlsRequiredException` |
| `player/PlayerManager.kt` | `StreamHealth`, breaker, penukaran item ke HLS, warm-up antrean |
| `ui/screens/SettingsScreen.kt` | panel Kesehatan stream (tes, salin, reset) |
| `download/HlsFlatDownloader.kt` | manifest HLS → satu file (untuk unduhan offline) |
| `tools/ci/extractor-radar.sh` | cek drift pin extractor dari mesin lokal |
| `tools/ci/meld-client-radar.sh` | cek drift spesifikasi klien terhadap HEAD Meld |

## 5. Runbook: "semua lagu gagal lagi"

1. **Baca dulu, jangan tebak.** Settings → KESEHATAN STREAM → **TES KONEKSI**,
   lalu **SALIN DIAGNOSTIK** dan simpan teksnya.
   - Banyak `SABR_ONLY` → YouTube menutup klien anonim; naikkan klien yang masih
     `USABLE` di `PlayerClientLadder.SPECS` (satu-satunya tempat yang diubah).
   - `HLS_ONLY` / `manifest HLS ada` → normal, Lyreon memutarnya. Bila tetap
     gagal, periksa apakah `media3-exoplayer-hls` masih ada di `build.gradle.kts`
     dan apakah penukaran `MediaItem` terjadi (log `track '…' beralih ke manifest HLS`).
   - `DRM_ONLY` → khas klien TV; jangan dihitung sebagai kemenangan.
   - `REJECTED_BY_CDN` → klien memberi URL tetapi probe byte terakhir ditolak
     (403/410). Ini berbeda dari SABR: stream-nya ADA, hanya tidak terbaca sampai
     habis (pola pratinjau ~1 MiB). Tindakannya bukan menambah klien, melainkan
     **menaikkan klien yang terukur whole-file** (`visionos`, `android_vr` 1.65.10)
     atau menunggu pin versi baru dari Meld. Baris `visitor=` di SALIN DIAGNOSTIK
     harus `sw.js`/`guide`/`response` — bila `-`, perbaiki itu dulu (§2.1b).
   - `PLAYABILITY_BLOCKED: UNPLAYABLE / needs to be reloaded` → klien itu butuh
     poToken. Sudah benar bila ia hanya muncul di diagnostik, bukan di jalur putar.
     Bila pola ini muncul pada klien yang seharusnya bebas poToken, curigai
     `visitorData` basi: setelah 3 respons terblokir beruntun tangga klien
     membuangnya dan mengambil yang baru (baris `visitorData disegarkan` di
     riwayat). Kalau masih berlanjut, paksa scrape ulang dengan **RESET** di
     panel yang sama.
   - `TRANSPORT_ERROR HTTP 403/429` → signature/poToken/rate-limit, bukan SABR.
2. **Cek extractor.** Baris `Extractor:` di laporan yang sama menunjukkan apakah
   `MetrolistExtractor` masih menghasilkan stream audio. Bila ia terus membalas
   SABR, ladder otomatis mem-bypass-nya 10 menit (terlihat di riwayat).
3. **Sinkronkan dengan Meld dulu — ia rujukan utama sekarang.**
   ```bash
   bash tools/ci/meld-client-radar.sh     # drift spesifikasi klien vs HEAD Meld
   ```
   Skrip ini membandingkan `PlayerClientLadder.kt` dengan
   `innertube/.../models/YouTubeClient.kt` di `FrancescoGrazioso/Meld` dan keluar
   dengan kode 1 bila ada selisih (versi, clientId, userAgent, field device).
   Karena gate terjadi per versi (§2.1a), selisih satu angka versi pun berarti.
   Bila radar bersih tetapi lagu tetap gagal, baca `YTPlayerUtils.kt` Meld:
   komentar di sana memuat pengukuran terbaru (klien mana yang 100% 206, mana yang
   cuma pratinjau).
4. **Sinkronkan dengan upstream extractor.**
   ```bash
   bash tools/ci/extractor-radar.sh
   REPO=InfinityLoop1308/PipePipeExtractor bash tools/ci/extractor-radar.sh
   REPO=TeamNewPipe/NewPipeExtractor      bash tools/ci/extractor-radar.sh
   ```
   Pin `MetrolistExtractor` tidak bergerak sejak 2026-06-22 — jadi perbaikan
   hampir selalu datang dari **tangga klien milik kita**, bukan dari fork.
5. **Ambil bentuk request terbaru.** Bila sebuah klien mulai diblokir, bandingkan
   spec kita dengan `YouTubeClient.kt` Meld (rujukan terukur) dan
   `INNERTUBE_CLIENTS` di `yt_dlp/extractor/youtube/_base.py`
   (clientName, clientVersion, userAgent, deviceModel, osVersion, host).
   Perbedaan kecil di sini adalah penyebab paling umum verdict `UNPLAYABLE`.
6. **Jangan menambal sekali lalu lupa.** Setiap perubahan YouTube = satu entri di
   riwayat di bawah + satu baris di `PlayerClientLadder` bila urutan berubah.
### Saring logcat

```bash
adb logcat -s LyreonStreamHealth PlayerClientLadder InnertubeFallback InnertubeConfig StreamUrlValidator
```

| Tag | Yang dicatat |
|---|---|
| `LyreonStreamHealth` | kegagalan `#n/3`, breaker **TERBUKA**, breaker **di-reset** + bukti kemajuan, `track … beralih ke manifest HLS` |
| `PlayerClientLadder` | verdict per klien (`visionos → USABLE (412ms)`), `REJECTED_BY_CDN` + alasan probe, total SABR, bypass extractor, `extractor: N stream HLS saja` |
| `InnertubeFallback` | klien yang akhirnya memberi audio, dan alasan lengkap saat semua gagal |
| `InnertubeConfig` | hasil scrape API key/versi/`STS`; sumber & isi `visitorData` (`sw.js_data` → `guide` → panen `responseContext`); dicoba ulang 5 menit bila scrape awal gagal |
| `StreamUrlValidator` | `URL stream DITOLAK: code=403 range=bytes=…` + deskripsi URL tersensor (`host/itag/mime/c/cver/expire/hasPot/nLen/sabr/clen`), `manifest HLS DITOLAK`, dan probe yang diterima optimistis karena IO |

Pola yang menandakan loop lama sudah tertangani: beberapa baris `gagal #1..#3`
lalu satu baris `breaker TERBUKA` — bukan puluhan skip tanpa akhir.

### Riwayat penyesuaian

| Tanggal | Perubahan YouTube | Penyesuaian Lyreon |
|---|---|---|
| 2025 → 2026 | SABR digulirkan bertahap ke klien anonim | deteksi SABR-only + tangga klien |
| 2026-06 | poToken diikat ke videoId (tidak bisa dipanen) | klien butuh-poToken dikeluarkan dari jalur putar |
| 2026-07 | `web_safari` hanya memberi HLS ke sebagian sesi | dukungan pemutaran HLS (lapisan 3) |
| 2026-08 | `tv_simply` gagal tanpa poToken saat signed-out | dipakai sebagai sumber manifest HLS, bukan URL langsung |
| 2026-08-17 | `android_vr` v1.65.10: semua format 403 | diturunkan ke cadangan, `preferManifest = true` |
| 2026-08 | `TVHTML5_SIMPLY_EMBEDDED_PLAYER` (id 85) mati: *"YouTube is no longer supported in this application or device"* | dibuang, diganti `WEB_EMBEDDED_PLAYER` (id 56) + `thirdParty.embedUrl` non-YouTube |
| 2026-09 | `web`/`web_remix`/`mweb` membalas `UNPLAYABLE` tanpa poToken | ditandai `requiresPoToken` → diagnostik saja |
| 2026-09 | Bentuk `visionos` PipePipe (UA app-style, endpoint googleapis) tidak membalas stream | diganti bentuk yt-dlp: UA Safari desktop, `RealityDevice17,1`, osVersion `26.5.23O471`, host `www.youtube.com` |
| 2026-09 | Lagu yang hanya punya HLS tidak bisa diunduh sama sekali | `HlsFlatDownloader`: segmen disatukan jadi satu file fMP4/TS + verifikasi wadah |
| 2026-09 | Seluruh tangga lama (`visionos` 1.02 → `web_embedded` → `tv_downgraded` → `tv` → `android_vr` → `web_safari` → `tv_simply`) membalas `LOGIN_REQUIRED: Sign in to confirm you're not a bot` dari IP residensial ID; `web_embedded` → `ERROR: This video is unavailable`; `web`/`web_safari` → SABR-only | **Porting Meld**: spesifikasi klien disalin byte-per-byte (`VISIONOS` 0.1 + `RealityDevice14,1` + UA Safari 18.0, `ANDROID_VR` 1.65.10/1.43.32, `IPADOS`, `IOS` 21.03.1), urutan = `STREAM_FALLBACK_CLIENTS` Meld, host `music.youtube.com` untuk semua klien |
| 2026-09 | Extractor melapor `streams=4` tetapi pemutar mati dengan `ERROR_CODE_IO_BAD_HTTP_STATUS` (403) | `StreamUrlValidator`: probe `HEAD` byte terakhir sebelum URL diserahkan ke ExoPlayer (extractor ikut divalidasi); verdict baru `REJECTED_BY_CDN`; UA googlevideo diambil dari spec pencetak URL, bukan ditebak dari `c=` |
| 2026-09 | `LOGIN_REQUIRED` juga menimpa klien yang seharusnya bebas poToken | `visitorData` diambil dari `music.youtube.com/sw.js_data` (cara Meld), dikirim ke semua klien sebagai `X-Goog-Visitor-Id`, dipanen dari `responseContext`, TTL 12 jam, disegarkan setelah 3 respons ditolak; sumbernya dilapor di diagnostik (`visitor=`) |

## 6. Privasi

- Tidak ada cookie, token akun, atau identitas pengguna yang dikirim ke YouTube.
  Yang dikirim: `visitorData` anonim (diperoleh dari YouTube sendiri), versi
  klien, dan header perangkat yang meniru klien resmi.
- Semua permintaan keluar dari perangkat langsung ke domain Google
  (`youtube.com`, `youtubei.googleapis.com`, `googlevideo.com`). Tidak ada server
  Lyreon, tidak ada proksi, tidak ada telemetri.
- Diagnostik (riwayat 40 baris, hasil tes koneksi) hanya hidup di memori proses
  dan hanya memuat videoId + verdict + latensi. Tombol **SALIN DIAGNOSTIK**
  menyalin teks itu ke papan klip atas tindakan eksplisit pengguna.
- `returnyoutubedislikeapi.com` **tidak** pernah dipanggil (`setFetchDislike(false)`).

## 7. Pekerjaan lanjutan (belum dikerjakan, dengan alasannya)

### 7.1 poToken / BotGuard — sengaja TIDAK diimplementasikan

Menghasilkan poToken berarti menjalankan attestation BotGuard. Jalur yang
dipakai ekosistem: WebView (`PoTokenWebView` ala ReVanced/Morphe) atau engine JS
di luar proses (yt-dlp memakai `ejs` + Deno/Node). Keduanya menambah permukaan
keamanan dan kompleksitas besar, dan token tetap harus diikat per
(videoId, sessionId).

**Membaca kode Meld mengubah prioritas ini, dan jawabannya "tidak perlu" — bukan
"terlalu berat".** Di `YouTubeClient.kt`, flag `useWebPoTokens = true` hanya
dimiliki `WEB_REMIX`, `WEB`, `WEB_CREATOR`, dan `TVHTML5`. Kelima klien tangga
stream anonim — `VISIONOS`, `ANDROID_VR` 1.65.10, `ANDROID_VR` 1.43.32, `IPADOS`,
`IOS` — semuanya `useWebPoTokens = false`, artinya jalur anonim Meld **tidak
pernah membuat poToken**. Di `YTPlayerUtils.playerResponseForPlayback`:

```kotlin
// If MAIN_CLIENT needs a PoToken but we couldn't get one (WebView missing, JS
// blocked, network hostile), WEB_REMIX will return streams that 403 on play.
// Skip it and go straight to the fallback chain.
val skipMainClient = mainClientNeedsPoToken && poToken == null
```

poToken di Meld hanya dipakai untuk (a) `WEB_REMIX` sebagai klien metadata, dan
(b) konten berbatas umur lewat `WEB_CREATOR` yang **butuh login** — dua hal di
luar cakupan Lyreon (anonim, tanpa cookie). Karena itu Lyreon menyalin
tangganya tanpa generator poToken, tetapi tetap menyimpan field `useWebPoTokens`
dan slot `serviceIntegrityDimensions.poToken` di `InnertubeRequest.playerFromSpec`
supaya bentuk body identik dengan Meld bila suatu hari diperlukan.

Bila kelak YouTube menutup kelima klien itu dan poToken menjadi satu-satunya
jalan, cetak biru dari Meld yang sudah dibaca di sesi ini: `PoTokenWebView`
(BotGuard di WebView terisolasi) + `PoTokenGenerator` dengan
`POTOKEN_TIMEOUT_MS = 8_000` dan `Mutex` per sesi — timeout itu penting karena
proses WebView bisa di-cull OS sehingga panggilannya menggantung selamanya;
hasilnya dua token (`playerRequestPoToken` untuk body player,
`streamingDataPoToken` untuk ditempel sebagai `pot=` di URL googlevideo), dengan
`sessionId = visitorData` saat anonim. Jangan pernah mengirim data ke server
pihak ketiga.

### 7.2 Pemutaran SABR native
Protokol SABR = manifest protobuf + chunk yang diminta dinamis
(`serverAbrStreamingUrl`). PipePipeExtractor v5.3.0 sudah mendukungnya, tetapi
menuntut tiga hal sekaligus: **toolchain Java 25** (CI Lyreon JDK 21; class file
v69 tidak bisa dibaca compiler yang lebih tua), **WebView JS engine**
(`NewPipe.checkWebViewAvailable()`), dan **DataSource SABR sendiri** di sisi
media3 (dukungan media3 mereka dilepas). Migrasi ini masuk akal hanya bila
semua jalur anonim lain sudah mati.

### 7.3 Naik ke PipePipeExtractor
Terhalang tiga syarat di §7.2. Sebagai catatan hidup: HEAD mereka 2026-09-03,
push terakhir 2026-09-05 — aktif, jadi opsi ini tetap layak dipantau lewat
`tools/ci/extractor-radar.sh`.

### 7.4 ~~Unduhan dari manifest HLS~~ — SELESAI
`HlsFlatDownloader` mengambil init segment + seluruh segmen lalu menyatukannya
menjadi satu file (fMP4 → `.mp4`, MPEG-TS → `.ts`), dengan verifikasi bait
pertama supaya berkas rusak tidak pernah disimpan. Tanpa FFmpeg: segmen HLS
memang bisa disambung apa adanya.

Catatan mutu: HLS YouTube umumnya *muxed* (video+audio dalam satu varian), jadi
file hasil unduhan lagu HLS-only lebih besar daripada unduhan audio murni
(bisa 4–8×). Varian ber-bandwidth terendah yang masih layak (≥ 400 kbps) dipilih
untuk menekan ukuran. Bila ini terasa mahal, alternatifnya adalah menolak unduhan
untuk lagu HLS-only dan memberi pesan yang jelas — keputusan produk, bukan teknis.

## 8. Kebijakan pin extractor

- Pin **commit SHA**, bukan branch: `main` fork bisa bergerak dan mengubah
  perilaku tanpa jejak di riwayat kita.
- Naikkan pin hanya bila (a) ada perbaikan yang kita butuhkan, dan (b) CI hijau.
  Fork `MetrolistGroup/MetrolistExtractor` **tidak punya tag**, jadi referensi
  JitPack harus SHA atau nama branch.
- Setelah menaikkan: jalankan **TES KONEKSI**, bandingkan verdict per klien
  dengan sebelum, dan tambahkan satu baris di tabel riwayat §5.
- Radar drift: `bash tools/ci/extractor-radar.sh` (lokal, kapan saja) dan workflow
  `.github/workflows/extractor-radar.yml` (aktif, tiap Senin 02:17 UTC — membuka issue
  berlabel `extractor-drift` bila pin tertinggal). Berkas workflow hanya bisa diubah
  dari akun dengan izin `workflows`, bukan dari token agen.
