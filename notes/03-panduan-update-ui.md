# 03 — Panduan update UI (wajib dibaca sebelum menyentuh `ui/`)

Tujuan: UI baru (dinamis, minimalis, transparan, sebagian bulat, rasa iOS) **tanpa
lag** dan tanpa merusak yang sudah bekerja. Semua aturan di bawah berasal dari kode
yang ada hari ini, bukan dari teori.

---

## 1. Peta UI hari ini

```
ui/
├─ theme/
│  ├─ Color.kt   LyreonPalette + token publik (LyreonBackground, LyreonSurface,
│  │             LyreonElevated, LyreonTextPrimary/Secondary/Muted/Bright,
│  │             LyreonLine, LyreonLineSoft, LyreonCrimson, LyreonRose, LyreonScrim)
│  ├─ Theme.kt   enum ThemeMode { SYSTEM, DARK, LIGHT } · LyreonTheme(themeMode,
│  │             accentArgb, fontKey) · darkScheme()/lightScheme() Material 3
│  └─ Type.kt    lyreonTypography(family) — satu fungsi, ikut setelan fontKey
├─ components/   Artwork · BlurryBackdrop · DonateDialog · EventCountdown ·
│                GenreReelSlider · IntroOverlay · LyreonPlayButton · LyricsSheet ·
│                MiniPlayerBar · Primitives (RevealOnScroll, BrutalFrame, SectionRule) ·
│                TrackActions · TrackRow
├─ screens/      Archive · Downloads · EditorialDetail · Home · Library · LocalMusic ·
│                NowPlaying · PlaylistDetail · Search · Settings · YtPlaylist
└─ vm/ViewModels.kt   ViewModel + SettingsViewModel (diagnosticsReport() di sini)
```

Perangkat kerangka: **Compose BOM 2026.01.01**, Material 3, Navigation Compose
(`rememberNavController` + `LyreonNavHost` di `MainActivity.kt`), `enableEdgeToEdge()`
dengan `WindowInsets.safeDrawing` dan `windowInsetsPadding(WindowInsets.navigationBars)`,
Coil 3 untuk artwork.

## 2. Aturan warna — satu sumber kebenaran

1. **Jangan menulis `Color(0xFF…)` di layar/komponen.** Pakai token
   (`LyreonSurface`, `LyreonTextSecondary`, …) atau `MaterialTheme.colorScheme`.
   Palet di-provide lewat `LocalLyreonPalette` (`staticCompositionLocalOf`), jadi
   mengganti tema/aksen otomatis mengalir ke semua layar — selama tidak ada yang
   hardcode.
2. **Warna identitas tidak berubah:** crimson `#E94560`
   (`LyreonCrimsonDefault`) adalah merek. Aksen kustom pengguna masuk lewat
   `accentArgb` (sentinel `-1` = bawaan; perhatikan: warna opaque ARGB sebagai `Int`
   bernilai negatif, jadi pengujiannya `!= -1`, bukan `< 0`).
3. **Baca token di composable scope, jangan di lambda draw.** Contoh benar ada di
   `BlurryBackdrop.kt`: `val background = LyreonBackground` dulu, baru dipakai di
   `drawBehind`. Melanggar ini = crash/`@ReadOnlyComposable` error.
4. Menambah token baru: tambahkan field di `LyreonPalette`, isi di
   `lyreonDarkPalette()` **dan** `lyreonLightPalette()` (plus varian BLACK nanti),
   lalu ekspos sebagai getter `@Composable @ReadOnlyComposable` di bagian "Token
   publik". Jangan menambahkan token hanya untuk satu layar.
5. **Warna "mencolok" dilarang** sesuai permintaan produk: aksen dipakai untuk
   penekanan kecil (indikator aktif, tombol utama, progres), bukan untuk bidang luas.
   Bidang luas = netral (background/surface/elevated).

## 3. Aturan bentuk (kelengkungan) — ubah sekali, berlaku semua

- Kartu hari ini lahir dari **satu** primitif: `BrutalFrame` di `Primitives.kt`
  (`clip(RoundedCornerShape(16.dp))` + `background(LyreonSurface)` +
  `border(1.dp, LyreonLine)`). Ini titik ungkit tema baru: ubah di sini, hampir
  seluruh kartu ikut.
- Permintaan produk: **sebagian** elemen bulat, rasa iOS. Jangan membulatkan
  semuanya — buat skala radius sebagai token, misalnya di berkas baru
  `ui/theme/Shapes.kt`:

  | Token | Nilai usulan | Dipakai untuk |
  |---|---|---|
  | `radiusXs` | 8 dp | chip, badge, tombol kecil |
  | `radiusSm` | 12 dp | input, item list |
  | `radiusMd` | 16 dp | kartu (nilai sekarang — pertahankan) |
  | `radiusLg` | 24 dp | sheet, dialog |
  | `radiusXl` | 28–32 dp | kartu hero / Now Playing |
  | `radiusPill` | 50% | tombol pil, avatar, FAB |

  Lalu **ganti pemakaian `RoundedCornerShape(16.dp)` yang tersebar** dengan token itu
  secara bertahap (grep: `RoundedCornerShape(` di `ui/`).
- Elemen yang **tetap tajam** sengaja: garis seksi (`SectionRule`), pembatas 1 dp.
  Garis rambut (hairline) ala iOS lebih baik dari bayangan berat.
- Bayangan: pakai elevation lewat **alpha/lapisan**, bukan `Modifier.shadow` besar.
  Shadow di Compose memaksa layer offscreen → biaya GPU di list yang scroll.

## 4. Transparansi & blur — bagian paling mahal, paling mudah bikin lag

- Yang sudah ada: `BlurryBackdrop.kt` = gambar statis + `ColorMatrix` saturasi 0.4 +
  gradien scrim. **Bukan blur nyata**, dan komentarnya menjelaskan kenapa:
  crossfade/parallax penuh layar adalah "penyebab utama lag saat berpindah halaman".
- Aturan untuk tema transparan baru:
  1. **Boleh**: permukaan semi-transparan di atas warna/gradien
     (`surface.copy(alpha = 0.72f)`) — nyaris gratis.
     **Mahal**: `Modifier.blur()` / `RenderEffect` — hanya untuk **satu** lapisan
     backdrop yang tidak ikut scroll (Now Playing, MiniPlayer bar, bottom sheet).
  2. Jangan pernah menaruh blur di dalam item `LazyColumn`/`LazyRow`.
  3. Satu blur per layar. Dua lapis blur bertumpuk = drop frame di perangkat kelas
     menengah.
  4. Sediakan jalur "tanpa blur" saat `reduceMotion` aktif atau saat perangkat
     rendah (cek `ActivityManager.isLowRamDevice` bila perlu).
  5. **Kontras**: teks di atas permukaan transparan wajib punya scrim di belakangnya.
     Uji dengan artwork terang dan gelap; target kontras teks ≥ 4.5:1.
- `LyreonScrim` sudah ada di palet — pakai itu, jangan mengarang alpha sendiri.

## 5. Performa Compose — checklist "tanpa lag"

**Recomposition**
- [ ] Parameter komposisi **stabil**: data class/immutable, bukan `List` yang dibuat
      ulang tiap recomposition. Untuk callback, hindari lambda baru di argumen
      (pakai `remember` atau method reference).
- [ ] State yang sering berubah (posisi lagu, progres) **diisolasi** ke komposisi
      terkecil. Contoh benar: `LyricsSheet.kt` memakai `rememberUpdatedState` +
      `derivedStateOf` dan hanya item aktif yang recompose.
- [ ] Jangan membaca `player.state.positionMs` di komposisi induk; ambil lewat
      `snapshotFlow`/`LaunchedEffect` atau turunan `derivedStateOf`.

**Lazy list**
- [ ] `items(list, key = { it.id })` — **wajib**. Tanpa `key`, animasi & scroll
      membayar biaya diff penuh dan state item tertukar.
- [ ] `contentType` untuk baris heterogen (header vs item vs footer).
- [ ] Tinggi item tetap/dapat diprediksi; hindari `IntrinsicSize` di list panjang.
- [ ] Tidak ada animasi per item (lihat `RevealOnScroll` yang sudah dipasifkan).

**Gambar**
- [ ] Artwork lewat `ui/components/Artwork.kt` (Coil 3) dengan ukuran eksplisit +
      `ContentScale.Crop` + placeholder; jangan `AsyncImage` tanpa ukuran.
- [ ] Jangan decode bitmap besar di UI thread; jangan `Modifier.graphicsLayer`
      ber-animasi pada banyak item sekaligus.

**Motion**
- [ ] Animasi masuk/keluar halaman: `AnimatedContent`/`Crossfade` dengan
      **spring** (lihat `04` §motion), durasi efektif 200–350 ms.
- [x] Setelan `reduceMotion` **sudah dihormati** (sejak gelombang tema 1):
      `LyreonTheme` menyediakan `LocalReduceMotion` — gabungan preferensi pengguna
      **dan** status animator sistem (`ValueAnimator.areAnimatorsEnabled()`, jadi
      "animation scale = 0" di Opsi Developer ikut dihitung).
- [ ] **Aturan wajib untuk animasi baru:** jangan panggil `tween(...)` / `spring(...)`
      mentah. Pakai helper di `ui/theme/Motion.kt`: `lyreonTween()`, `lyreonSpring()`,
      `lyreonFade()`, `lyreonChromeEnter()/Exit()` — semuanya runtuh jadi ~instan saat
      reduce motion. Baca statusnya lewat `reduceMotionEnabled` bila perlu cabang
      struktural (contoh: `EventCountdownChip` mematikan `rememberInfiniteTransition`
      sehingga tidak ada animasi berjalan terus di latar).
- [ ] Spec yang dipakai di dalam `LaunchedEffect` harus **diambil di luar** effect
      (helper tema bersifat `@Composable`) — lihat `LyreonPlayButton.morphSpec`.

**Cara mengukur (jangan menebak)**
```bash
# jejak frame saat scroll/berpindah layar
adb shell dumpsys gfxinfo com.lyreon.app framestats
# cari jank > 16.6 ms; bandingkan sebelum/sesudah perubahan
adb logcat -s Choreographer OpenGLRenderer
```
Aturan praktis: bila satu perubahan menambah lapisan blur/animasi, ukur sebelum dan
sesudah. Kalau tidak terukur, jangan diklaim "lebih smooth".

## 6. Aksesibilitas & bentuk perangkat

- Target sentuh ≥ 48×48 dp (iOS-like sering menggoda tombol 32 dp — beri padding).
- Uji `fontScale` besar (Settings → Display → font terbesar): teks panjang jangan
  terpotong; pakai `maxLines` + `overflow = TextOverflow.Ellipsis` secara sadar.
- Uji locale `ja`/`zh`/`hi`: teks lebih panjang/pendek dari Indonesia; dan `values`
  default punya 271 string vs 260 di locale lain (utang 11 string — jangan ditambah).
- `contentDescription` untuk kontrol ikon; `clearAndSetSemantics` untuk dekorasi.
- Edge-to-edge sudah aktif: setiap layar baru wajib menghormati
  `WindowInsets.safeDrawing` (atau `windowInsetsPadding`) — jangan hardcode status bar.
- Tidak ada locale RTL saat ini; bila nanti ditambah, audit `padding(start/end)` vs
  `left/right`.

## 7. Checklist pra-commit untuk perubahan UI

1. [ ] Tidak ada `Color(0x…)` / `RoundedCornerShape(N.dp)` baru yang hardcoded di
       layar (pakai token).
2. [ ] Semua string baru masuk **6** berkas `strings.xml`; `python3` parse XML sukses.
3. [ ] Mode DARK, LIGHT (dan BLACK bila sudah ada) terlihat benar; aksen kustom
       (`accentArgb`) tidak menghasilkan teks tak terbaca.
4. [ ] `reduceMotion = true` → tidak ada animasi besar (helper `Motion.kt` menangani;
       periksa juga animator sistem dimatikan → sama efeknya).
5. [ ] Scroll list panjang (≥ 200 item) tetap mulus di perangkat nyata; tidak ada
       blur di dalam item.
6. [ ] Navigasi: back dari layar baru tidak merusak state pemutar; MiniPlayer tetap
       terlihat dan tidak menutupi konten (padding bawah).
7. [ ] **Tidak menyentuh** `yt/`, `player/`, `download/` — perubahan UI tidak boleh
       mengubah perilaku streaming. Bila terpaksa, pisahkan commit dan baca `notes/01`.
8. [ ] Format SALIN DIAGNOSTIK tidak berubah (itu antarmuka dukungan).
9. [ ] CI hijau (Debug + Release APK) sebelum mengklaim selesai.

## 8. Jebakan yang sudah pernah terjadi di UI Lyreon

| Jebakan | Akibat | Penawar |
|---|---|---|
| Animasi reveal per item di list panjang | GPU drop, scroll patah | `RevealOnScroll` dipasifkan — jangan dihidupkan |
| Backdrop blur/parallax penuh layar saat pindah halaman | Lag berpindah halaman | `BlurryBackdrop` statis + saturasi + scrim |
| Membaca token warna di lambda `drawBehind` | Nilai tidak dinamis / error kompilasi | Baca di composable scope dulu |
| Teks diagnostik hardcoded tanpa resource | Sulit diterjemahkan, tapi sengaja untuk log | UI = resource; log = literal boleh |
| Mengubah `ThemeMode` tanpa migrasi DataStore | `ThemeMode.valueOf` gagal → fallback | Simpan sebagai string + `runCatching` (sudah begitu — pertahankan) |
