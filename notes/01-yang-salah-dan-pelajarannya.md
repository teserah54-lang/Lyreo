# 01 — Yang salah, dan pelajarannya

Catatan ini sengaja menyebut kesalahan secara eksplisit (termasuk yang dilakukan
asisten AI di sesi sebelumnya), karena satu-satunya cara agar tidak terulang adalah
menuliskan *gejala → sebab → perbaikan → pencegah*-nya.

Status per 2026-09-06: pemutaran **sudah bekerja** setelah porting Meld
(commit `df7531a`, CI hijau). Semua entri di bawah adalah masa lalu yang masih
relevan sebagai pencegah.

---

## A. Saga pemutaran (2026-08 → 2026-09)

### A1. Menganggap "response punya URL" = "bisa diputar"

- **Gejala:** extractor melapor `streams=4`, tangga klien melapor `USABLE`, tetapi
  pemutar mati dengan `ERROR_CODE_IO_BAD_HTTP_STATUS` (403). Diagnostik bilang sehat,
  pengguna bilang rusak.
- **Sebab:** verdict `USABLE` hanya memeriksa keberadaan `adaptiveFormats[].url`.
  Padahal URL dari `IOS`/`IPADOS`/`ANDROID_VR` lawas adalah **pratinjau ~1 MiB**:
  googlevideo melayani awalan byte tetap lalu 403 untuk offset sesudahnya
  (terukur oleh Meld: 1.040.807 byte ≈ 61 detik dari lagu 268 detik).
- **Perbaikan:** `yt/innertube/StreamUrlValidator.kt` — probe `HEAD` dengan
  `Range: bytes=clen-1-clen-1` (byte **terakhir**) sebelum URL diserahkan ke
  ExoPlayer; verdict baru `REJECTED_BY_CDN`. URL dari extractor NewPipe ikut diuji.
- **Pencegah:** jangan pernah menamai sebuah verdict "sukses" berdasarkan
  *keberadaan data*. Sukses = data terbukti **terbaca**. Aturan ini juga berlaku untuk
  diagnostik: laporan yang tidak bisa membedakan "ada" dari "bisa dipakai" akan
  menyesatkan debugging jarak jauh.

### A2. Spesifikasi klien diambil dari dokumentasi, bukan dari yang mengukur

- **Gejala:** seluruh tangga (`visionos` 1.02 → `web_embedded` → `tv_downgraded` →
  `tv` → `android_vr` → `web_safari` → `tv_simply`) membalas
  `LOGIN_REQUIRED: Sign in to confirm you're not a bot` dari IP residensial Indonesia.
- **Sebab:** nilai klien disalin dari yt-dlp/YouTube.js (dokumentasi kebijakan),
  padahal gate YouTube terjadi **per versi klien**: Meld mengukur `ANDROID_VR` 1.43.32
  dan 1.61.48 selalu bot-gated sementara 1.65.10 `OK` dengan 100% URL langsung —
  anonim maupun login, dengan/tanpa visitorData, field device dilucuti pun sama.
  `VISIONOS` yang bekerja pun versi `0.1` + `RealityDevice14,1` + UA Safari 18.0,
  bukan `1.02` + `RealityDevice17,1` + UA Safari 26.0.
- **Perbaikan:** spesifikasi disalin byte-per-byte dari `YouTubeClient.kt` Meld;
  urutan = `STREAM_FALLBACK_CLIENTS` mereka; `android_vr_1_61_48` disimpan sebagai
  *kontrol* diagnostik.
- **Pencegah:** `tools/ci/meld-client-radar.sh` membandingkan tabel kita dengan HEAD
  Meld dan keluar dengan kode 1 bila ada selisih. Jalankan setiap kali lagu mulai
  gagal massal: `bash tools/ci/meld-client-radar.sh`.

### A3. `visitorData` dianggap opsional

- **Gejala:** klien yang seharusnya bebas poToken pun membalas `LOGIN_REQUIRED` /
  `UNPLAYABLE` dengan nol format.
- **Sebab:** visitorData diambil dari endpoint `guide` (kadang gagal) dan diperlakukan
  sebagai "pelengkap". Padahal kutipan `InnerTube.ytClient` Meld: *"VISIONOS and
  ANDROID_VR 1.65.10 — the only clients that currently mint a fully readable stream
  URL — **require** it. Without one they answer UNPLAYABLE / LOGIN_REQUIRED with zero
  formats."*
- **Perbaikan:** sumber utama `https://music.youtube.com/sw.js_data` (cara Meld:
  buang 5 karakter pembungkus `)]}'`, ambil string pertama yang cocok `^Cg[t|s]`),
  cadangan `guide`, panen dari `responseContext` respons mana pun, TTL 12 jam,
  disegarkan setelah 3 respons ditolak beruntun, dikirim ke **semua** klien sebagai
  `X-Goog-Visitor-Id`, dan **sumbernya dilaporkan** (`visitor=sw.js|guide|response|-`).
- **Pencegah:** setiap identitas sesi yang wajib harus punya (a) lebih dari satu
  sumber, (b) indikator kehadiran di diagnostik. "Tidak ada" dan "ditolak" adalah dua
  diagnosis berbeda dan harus terbaca berbeda di laporan.

### A4. Salah host & bentuk request

- **Gejala:** respons berbeda dari yang diharapkan meski spesifikasi klien sudah benar.
- **Sebab:** Lyreon mengirim klien mobile ke `youtubei.googleapis.com` dengan
  `&t=…&id=…` dan klien web ke `www.youtube.com`, menambah field yang tidak dikirim
  siapa pun (`clientScreen`, `platform`, `utcOffsetMinutes`, `internalExperimentFlags`).
  Meld mengirim **semua** klien ke `music.youtube.com/youtubei/v1/` dengan
  `X-Goog-Api-Format-Version: 1`, `X-Origin`/`Referer` YouTube Music, dan
  `context.client` yang ramping.
- **Perbaikan:** `InnertubeRequest.playerRequest()` disamakan dengan Meld; field
  spekulatif dibuang; `signatureTimestamp` hanya untuk klien `useSignatureTimestamp`.
- **Pencegah:** saat meniru klien lain, tiru **byte**-nya. Setiap field tambahan yang
  kita karang adalah variabel yang tidak teruji dan tidak bisa di-debug dari jauh.

### A5. User-Agent googlevideo ditebak, dan tebakannya basi

- **Gejala:** URL sah di-GET → 403.
- **Sebab:** interceptor `streamClient` menebak UA dari substring `c=` lalu memasang
  UA app lawas: untuk `c=ANDROID_VR` yang dikirim justru
  `com.google.android.youtube/19.45.38` — **nama paket salah** (seharusnya
  `com.google.android.apps.youtube.vr.oculus/1.65.10`), versi salah.
- **Perbaikan:** `PlayerClientLadder.streamUserAgentFor(c, cver)` — UA diambil dari
  spec yang mencetak URL, cocok versi dulu lalu nama; probe `StreamUrlValidator`
  memakai UA yang sama, jadi uji dan pemutaran identik.
- **Pencegah:** jangan menyimpan dua sumber kebenaran untuk nilai yang sama. UA klien
  hanya boleh hidup di satu tabel.

### A6. Kesimpulan poToken benar, alasannya salah

- **Gejala:** poToken/BotGuard tidak diimplementasikan dengan alasan "terlalu berat";
  selama berbulan-bulan desain berkutat pada "klien bebas poToken + HLS" yang ternyata
  tetap diblokir.
- **Sebab:** keputusan diambil dari tabel kebijakan yt-dlp tanpa membaca kode klien
  yang benar-benar bekerja. Setelah dibaca: kelima klien tangga anonim Meld semuanya
  `useWebPoTokens = false`, dan `YTPlayerUtils` malah **melewati** klien utama bila
  poToken tak tersedia (`val skipMainClient = mainClientNeedsPoToken && poToken == null`).
  Jadi jalur anonim memang tidak butuh poToken — tetapi bukan itu sebabnya tangga lama
  gagal (sebabnya A2 + A3).
- **Perbaikan:** keputusan dipertahankan, alasannya dikoreksi, cetak biru bila kelak
  dibutuhkan dicatat di `docs/streaming-resilience.md` §7.1 (`PoTokenWebView`,
  `POTOKEN_TIMEOUT_MS = 8_000`, dua token: `playerRequestPoToken` untuk body,
  `streamingDataPoToken` untuk `pot=` di URL).
- **Pencegah:** "tidak perlu" harus dibuktikan dari kode rujukan, bukan dari beratnya
  implementasi. Alasan yang salah akan melahirkan keputusan yang salah saat kondisi
  berubah.

### A7. Diagnostik probe tanpa `Range`

- **Gejala:** uji manual terlihat lolos, produksi gagal sistematis.
- **Sebab:** `HEAD` tanpa `Range` dijawab **403 untuk setiap art track YouTube Music**
  (unggahan `- Topic`) padahal `GET` ber-`Range` dijawab 206 (terukur oleh Meld pada
  tiga videoId). Probe yang tidak meniru request pemutar menghasilkan sinyal palsu.
- **Perbaikan:** probe selalu ber-`Range`; manifest HLS di-probe **tanpa** `Range`
  (aturan art-track berlaku untuk media googlevideo, bukan untuk manifest).
- **Pencegah:** probe harus meniru request yang sebenarnya, termasuk header yang
  "tidak penting".

### A8. Login/cookie sempat diimplementasikan, lalu dihapus

- **Gejala:** fitur sync & bypass batas umur menggoda untuk memakai cookie akun.
- **Sebab:** cookie adalah kredensial penuh; memintanya memindahkan risiko pembajakan
  akun ke pengguna dan menambah gesekan besar (browser desktop, mode incognito,
  `SAPISID`).
- **Perbaikan:** pemilik proyek memutuskan **anonim saja**: `setTokens()` tidak pernah
  dipanggil, kode login dihapus (rujukan sejarah: commit `73aa177`).
- **Pencegah:** jangan menambah ulang jalur akun tanpa keputusan eksplisit pemilik
  proyek. Lihat `05` §konflik — dua fitur yang diminta (sinkronisasi akun, *listen
  together*) menyentuh batas ini dan **ditahan**, bukan diimplementasikan diam-diam.

---

## B. Kesalahan proses (tidak spesifik YouTube)

### B1. Mengira CI sudah berjalan, padahal tidak terpantau

- **Gejala:** satu commit (`cb50b99`) berstatus "tidak diketahui" selama satu sesi
  karena token GitHub kedaluwarsa di tengah pemantauan.
- **Sebab:** token GitHub App bisa kedaluwarsa saat sesi berjalan; pemantauan
  bergantung pada satu jalur (`gh run view --log`) yang ternyata gagal (EOF).
- **Perbaikan:** pakai `gh api repos/{owner}/{repo}/actions/runs/{id}/jobs`; kalau
  auth gagal, minta pengguna menyambungkan ulang GitHub di Arena (jangan pernah minta
  token/PASS/2FA di chat).
- **Pencegah:** catat ID run di pesan, dan verifikasi kesimpulan (`success`) sebelum
  menyebut sebuah commit "sudah hijau".

### B2. Mengasumsikan sandbox bisa mengompilasi

- **Gejala:** verifikasi lokal tidak mungkin; `javac`/`gradle`/Android SDK tidak ada,
  dan jaringan memblokir `dl.google.org`/maven/jitpack.
- **Sebab:** lingkungan hanya untuk menyunting kode; build terjadi di GitHub Actions.
- **Perbaikan:** commit kecil + push + pantau CI; pemeriksaan statis lokal (keseimbangan
  kurung/kurawal, grep pemakaian API yang dihapus, `python3` untuk validasi XML).
- **Pencegah:** sebelum menghapus/mengganti field publik, `grep` seluruh pemakainya.
  Sebelum menulis XML string, validasi dengan parser. Jangan meninggalkan import yang
  tidak terpakai.

### B3. `return` non-lokal di dalam fungsi ber-expression-body

- **Gejala:** hampir gagal kompilasi: `private fun executePlayer(...) = try { … return … }`
  dengan `return` di dalam lambda `.use {}`.
- **Sebab:** pola ini legal hanya bila lambda-nya inline dan fungsinya ber-block-body;
  mencampurnya dengan expression body membuat kode rapuh dan sulit dibaca.
- **Perbaikan:** `executePlayer` ditulis dengan block body + `when` yang mengembalikan
  nilai, tanpa `return` di dalam lambda.
- **Pencegah:** hindari `return` di dalam lambda; lebih baik ekspresi `when`/`if`
  sebagai nilai terakhir.

### B4. Mengubah satu konstanta yang dipakai dua jalur

- **Gejala:** menaikkan/menurunkan klien di tangga secara tak sengaja mengubah jalur
  unduhan (yang tidak boleh memakai manifest) dan jalur probe diagnostik.
- **Sebab:** satu daftar `SPECS` dipakai tiga konsumen dengan kebutuhan berbeda.
- **Perbaikan:** pemisahan eksplisit — `ordered(forPlayback = true)` (klien
  `playableAnonymous`), `hlsSpecs()` (lintasan kedua), `ordered(forPlayback = false)`
  (diagnostik), plus flag `loginRequired` / `useWebPoTokens` / `probeOnly` /
  `preferManifest`.
- **Pencegah:** bila satu tabel melayani banyak jalur, beri tiap jalur fungsi pemilih
  bernama — jangan biarkan konsumen memfilter sendiri di tempat pemakaian.

### B5. Unduhan bisa menyimpan file terpotong diam-diam

- **Gejala:** jalur "cadangan terakhir" (URL yang ditolak CDN tetap dipakai) masuk akal
  untuk pemutaran, tetapi berbahaya untuk unduhan.
- **Sebab:** satu nilai `ResolvedAudio` dipakai dua konsumen dengan toleransi berbeda.
- **Perbaikan:** field `validated: Boolean`; pemutaran boleh memakai `validated = false`,
  `LyreonDownloadManager` **menolak** dan melempar `IOException` beralasan jelas.
- **Pencegah:** data yang menuruni dua jalur harus membawa metadata kualitasnya.

---

### B6. Glob di dalam komentar blok menelan seluruh berkas (gelombang 2)

- **Gejala:** CI gagal dengan puluhan `Syntax error: Expecting a top level
  declaration` yang mulai di tengah berkas (`KuGouProvider.kt:199`), plus
  `Unresolved reference` di berkas lain. Tidak ada satu pun error di baris awal
  berkas — membingungkan.
- **Sebab:** header atribusi menulis `kugou/.../models/*.kt`. **Komentar blok
  Kotlin BERSARANG**: `/*` di dalam `/* … */` menaikkan kedalaman, jadi `*/`
  penutup header hanya menurunkan kedalaman ke 1 dan seluruh isi berkas (sampai
  `*/` pertama di KDoc berikutnya) ikut jadi komentar. Kode baru "mulai" di titik
  acak → parser melaporkan error jauh dari penyebabnya.
- **Perbaikan:** jangan pernah menulis `*/` atau `/*` di dalam komentar — ganti
  glob dengan daftar eksplisit (`models/ (Keyword, SearchSongResponse, …)`).
- **Pencegah:** sebelum commit, jalankan pemindai berikut (menemukan `/*` atau
  `*/` di tengah baris komentar):
  ```bash
  grep -rn '^\s*\*.*\(/\*\|\*/\)' --include=*.kt app/src | grep -vE ':\s*\*/\s*$'
  ```
  Aturan umum: path/glob di dalam KDoc ditulis tanpa karakter bintang ganda.

## C. Daftar periksa cepat sebelum menyimpulkan "ini salah YouTube"

1. `visitor=` di SALIN DIAGNOSTIK bukan `-`? Kalau `-`, perbaiki itu dulu.
2. `bash tools/ci/meld-client-radar.sh` → drift? Kalau ya, selaraskan versi klien.
3. Ada `REJECTED_BY_CDN`? Artinya URL ada tapi tak terbaca → naikkan klien yang
   terukur whole-file, jangan tambah klien baru.
4. Semua klien `PLAYABILITY_BLOCKED: LOGIN_REQUIRED` termasuk `android_vr` 1.65.10?
   → curigai identitas sesi/IP, bukan bentuk request.
5. `android_vr_1_61_48` (kontrol) ikut terblokir sementara 1.65.10 lolos?
   → konfirmasi gate per versi; tindakan = ikut pin Meld.
6. Sudah membaca `YTPlayerUtils.kt` HEAD Meld hari ini? Komentar di sana memuat
   pengukuran terbaru — lebih cepat daripada menebak.
