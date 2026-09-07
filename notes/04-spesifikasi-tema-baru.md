# 04 — Spesifikasi tema baru: dinamis, minimalis, transparan, sebagian bulat (rasa iOS)

Permintaan pemilik proyek, diterjemahkan apa adanya:

> "Tema aplikasi baru dengan tema dinamis, minimalis, warna tetap sama, UI UX yang
> smooth, tanpa lag, tanpa warna mencolok, dan tema transparan, elemen elemen bulat
> (sebagian elemen saja), seperti OS iOS iPhone."

Dokumen ini mengubah kalimat itu menjadi **keputusan yang bisa diimplementasikan dan
diukur**, di atas fondasi yang sudah ada (`ui/theme/Color.kt`, `Theme.kt`,
`Primitives.kt`, `BlurryBackdrop.kt`). Referensi pembanding: Meld
`ui/theme/{Theme,Font,PlayerColorExtractor,PlayerSliderColors}.kt`,
`ui/screens/settings/ThemeScreen.kt`, `constants/Dimensions.kt`.

---

## 1. Permintaan → keputusan desain

| Permintaan | Keputusan konkret | Yang TIDAK dilakukan |
|---|---|---|
| Tema dinamis | `ThemeMode` diperluas: `SYSTEM`, `LIGHT`, `DARK`, `BLACK`, `MATERIAL_YOU` (Material You / `dynamicDarkColorScheme`) — plus aksen opsional dari artwork (`PlayerColorExtractor` ala Meld) | Mengganti warna merek; aksen artwork hanya untuk permukaan Now Playing, tidak global |
| Minimalis | Satu aksi primer per layar; ornamen dihapus; garis rambut 1 dp `LyreonLineSoft`; spasi berbasis kelipatan 4 dp | Menghapus informasi (judul/artis/durasi tetap ada) |
| Warna tetap sama | Crimson `#E94560` dipertahankan sebagai `primary`; palet netral gelap/terang yang ada tetap jadi dasar | Mengganti `LyreonCrimsonDefault` atau token publik |
| Smooth, tanpa lag | Budget: 60 fps di perangkat menengah; spring pendek; blur maksimal 1 lapis; tidak ada animasi per item list | `Modifier.blur` di item list, shadow besar, parallax penuh layar |
| Tanpa warna mencolok | Aksen hanya untuk: indikator aktif, tombol primer, progres, badge "baru". Luas aksen < 5% area layar | Bidang besar berwarna aksen, gradien neon |
| Tema transparan | Permukaan semi-transparan (`alpha` 0.60–0.85) di atas backdrop; blur nyata hanya di Now Playing / MiniPlayer / bottom sheet | Transparansi pada teks atau ikon (kontras turun) |
| Sebagian elemen bulat | Skala radius token (8/12/16/24/28/pill); kartu 16 dp (nilai sekarang), sheet 24–28 dp, chip 8 dp, tombol primer pill | Membulatkan semuanya, termasuk garis seksi & tabel |
| Rasa iOS iPhone | Spring lembut, hairline separator, inset list, kontrol geser tipis, tipografi dengan tracking longgar pada label kecil, haptic opsional | Meniru SF Symbols/ikon Apple; menyalin komponen UIKit mentah |

## 2. Token warna (perluasan `Color.kt`)

Tambahkan ke `LyreonPalette` — **nama token publik yang ada tidak boleh diganti**
(semua layar bergantung padanya):

| Token baru | Dark | Black (OLED) | Light | Fungsi |
|---|---|---|---|---|
| `surfaceTranslucent` | `surface.copy(alpha=.72f)` | `#000 @ .68f` | `elevated.copy(alpha=.72f)` | kartu di atas backdrop |
| `surfaceElevated2` | `#22222C` | `#0A0A0A` | `#FFFFFF` | lapisan di atas surface |
| `hairline` | `line @ .55f` | `#FFFFFF @ .08f` | `line @ .55f` | garis rambut iOS |
| `accentSoft` | `crimson @ .16f` | sama | `crimson @ .12f` | latar chip aktif/selection |
| `scrimSheet` | `#000 @ .45f` | `#000 @ .55f` | `#000 @ .28f` | di belakang bottom sheet |

Mode `BLACK` wajib memakai `#000000` murni untuk `background` (hemat daya OLED) dan
menaikkan alpha hairline, karena garis yang terlihat di dark menjadi tak terlihat di
black.

Material You: `dynamicDarkColorScheme()`/`dynamicLightColorScheme()` (API 31+), lalu
**petakan** ke token Lyreon (primary → `crimson`, surface → `surface`, dst.) supaya
komponen yang memakai token Lyreon ikut berubah. Di bawah API 31, `MATERIAL_YOU`
jatuh ke `SYSTEM` (tampilkan catatan di Settings).

## 3. Bentuk & material

- Skala radius → `ui/theme/Shapes.kt` (lihat `03` §3). Implementasikan juga sebagai
  `MaterialTheme.shapes` agar komponen M3 (Card, Sheet, Dialog) ikut.
- **Elevation tanpa bayangan**: lapisan lebih tinggi = permukaan lebih terang + alpha
  lebih besar + hairline. Bayangan hanya untuk elemen mengambang (MiniPlayer, FAB) dan
  maksimal `4.dp`.
- Separator: `1.dp` `hairline`, inset kiri 16–20 dp ala iOS (tidak menyentuh tepi).
- Kontrol geser: tipis (2–4 dp track), thumb kecil (12–14 dp) — referensi
  `PlayerSliderColors.kt` Meld.
- Ikon: satu keluarga (Material Symbols outline), ketebalan konsisten; jangan campur
  filled/outline dalam satu layar.

## 4. Motion (rasa iOS = spring lembut, bukan linear)

| Gerakan | Spesifikasi | Catatan |
|---|---|---|
| Transisi halaman | `spring(dampingRatio = 0.9f, stiffness = 400f)`, fade + slide 12–16 dp | bukan `tween(300, LinearEasing)` |
| Bottom sheet | `spring(dampingRatio = 0.85f, stiffness = 320f)` | ikuti kecepatan geser jari (`velocity`) |
| Artwork mini → Now Playing | shared element (`SharedTransitionLayout`) atau scale+fade terkoordinasi | satu animasi besar per interaksi |
| Baris lirik aktif | scale 1.0 → 1.04 + alpha 0.55 → 1.0, 220 ms | `LyricsSheet` sudah punya kerangka `derivedStateOf` |
| Tombol tekan | scale 0.97, 90 ms + ripple lembut | jangan ripple besar ala M3 default |
| Pull-to-refresh | indikator kecil, tanpa spinner besar | |

Aturan keras:
1. **Semua** animasi menghormati `settings.reduceMotion` → ganti ke fade 120 ms atau
   tanpa animasi. (Hari ini setelan itu **belum dihormati** di kode — wire sekali lewat
   `CompositionLocalProvider(LocalReduceMotion …)` di `MainActivity`.)
2. Durasi efektif 90–350 ms. Lebih dari 400 ms terasa lambat, bukan "smooth".
3. Satu animasi besar per layar pada satu waktu.
4. Jangan animasikan properti yang memicu relayout (width/height/padding) di list;
   animasikan `graphicsLayer` (scale/alpha/translation).

## 5. Tipografi

- Keluarga: `FontFamily.SansSerif` sistem (sudah bisa diganti lewat `fontKey` —
  pertahankan). SF Pro tidak tersedia di Android; rasa iOS didapat dari **skala dan
  tracking**, bukan dari font.
- Skala yang diusulkan (sesuaikan `Type.kt`): label kecil 11–12 sp dengan
  `letterSpacing = 0.6–1.0 sp` (huruf kapital longgar ala iOS), body 14–15 sp,
  judul layar 22–28 sp semi-bold, angka durasi tabular (`fontFeatureSettings = "tnum"`).
- Berat: maksimal dua (regular + semibold). Jangan pakai bold di seluruh layar.
- Hormati `fontScale` sistem: uji ukuran font terbesar (lihat `03` §6).

## 6. Per komponen: apa yang berubah

| Komponen | Perubahan |
|---|---|
| `MiniPlayerBar` | permukaan `surfaceTranslucent` + blur 1 lapis, sudut pill, artwork bulat 8 dp, progres garis tipis di tepi atas |
| `NowPlayingScreen` | backdrop artwork + blur nyata (satu-satunya tempat yang boleh), sheet lirik `scrimSheet`, kontrol pill, aksen opsional dari artwork |
| `HomeScreen` | kartu hero radius 24 dp, baris genre (`GenreReelSlider`) chip 8 dp, header tanpa ornamen |
| `LibraryScreen`, `PlaylistDetail`, `Downloads`, `Archive` | list inset, hairline separator, item radius 12 dp, tanpa border kartu |
| `SearchScreen` | field pill, hasil dikelompokkan dengan `SectionRule` (tetap tajam) |
| `SettingsScreen` | grup kartu radius 16 dp, sakelar M3 default, **bagian Kesehatan stream jangan diubah formatnya** |
| `TrackRow`, `TrackActions`, `Artwork`, `LyreonPlayButton` | radius token, target sentuh ≥ 48 dp |
| `LyricsSheet` | transisi baris aktif (tabel §4), tipografi lirik lebih longgar |

## 7. Implementasi bertahap (satu fase = satu commit = satu CI hijau)

**Fase 1 — Fondasi token — ✅ SELESAI (gelombang tema 1).**
Ditambahkan `ui/theme/Shape.kt` (`LyreonRadius` xs/sm/md/lg/xl/pill + `LyreonShapes`
yang diikat ke `MaterialTheme.shapes` + `LyreonChromeShape`), token baru di
`LyreonPalette` (`surfaceTranslucent`, `hairline`, `accentSoft`, `scrimSheet`),
`ThemeMode.BLACK` + `lyreonBlackPalette()` (#000 murni), dan `BrutalFrame` kini memakai
token radius + `hairline` (bukan `RoundedCornerShape(16.dp)` + border tebal).
*Sisa:* migrasi `RoundedCornerShape(...)` hardcoded yang masih tersebar di layar
(cek: `grep -rn "RoundedCornerShape(" app/src/main/java/com/lyreon/app/ui/screens`).

**Fase 2 — Tema dinamis — ✅ SELESAI (gelombang tema 1).**
`dynamicColor` di `LyreonSettings` + `dynamicDarkColorScheme`/`dynamicLightColorScheme`
(di-guard `Build.VERSION_CODES.S`, otomatis diabaikan di API < 31), pemetaan balik ke
token lewat `lyreonPaletteFromScheme()`, sakelar Material You di Settings (hanya tampil
di Android 12+), dan mode HITAM + Material You (`ColorScheme.withBlackBase()` → latar
#000, aksen tetap dari wallpaper). `surfaceTint` dinetralkan agar permukaan tidak
kemerahan.
*Sisa uji di perangkat:* ganti wallpaper → warna app ikut; kontras teks ≥ 4.5:1 di
keempat mode (SISTEM/GELAP/HITAM/KERTAS).

**Fase 3 — Transparansi & blur terbatas — 🟡 SEBAGIAN.**
Sudah: chrome memakai token baru (pulau navigasi = `surfaceTranslucent` + `hairline`,
mini player = radius `md` + `hairline`, lembar bawah = `scrimSheet` + `LyreonRadius.top()`).
Belum: blur satu lapis di chrome — **ditunda sengaja**, karena konten tidak digambar di
bawah bottom bar (Scaffold memberi padding), jadi blur tidak akan terlihat apa pun selain
memakan GPU. Kerjakan hanya bila chrome dibuat benar-benar mengambang di atas konten
(`Modifier.consumeWindowInsets` / background transparan pada content Scaffold), lalu
ukur `dumpsys gfxinfo com.lyreon.app framestats` sebelum/sesudah.

**Fase 4 — Motion iOS-like — 🟡 SEBAGIAN.**
Sudah: `ui/theme/Motion.kt` (`LocalReduceMotion`, `lyreonSpring`, `lyreonTween`,
`lyreonFade`, `lyreonChromeEnter/Exit`, konstanta durasi 90/180/260/350 ms dan damping
0.85/0.9) + semua animasi yang ada dimigrasikan: `MiniPlayerBar`, `IntroOverlay`
(dipersingkat jadi 550 ms saat reduce motion), `GenreReelSlider`, `EventCountdown`
(infinite pulse dimatikan), `LyreonPlayButton`, `Crossfade` lirik di `NowPlayingScreen`.
Belum: transisi antar-layar (masih bawaan `NavController`), transisi baris lirik aktif
(gelombang lirik), dan shared element artwork.

## 8. Larangan selama pengerjaan tema

1. Jangan mengubah perilaku streaming/pemutaran (`yt/`, `player/`, `download/`) —
   tema tidak boleh menyentuh jalur itu (lihat `notes/01`).
2. Jangan mengganti nama token warna publik atau `LyreonCrimsonDefault`.
3. Jangan mengubah format SALIN DIAGNOSTIK di `ui/vm/ViewModels.kt` dan panel
   Kesehatan stream di `SettingsScreen.kt` (boleh mengganti *gaya*-nya, bukan isinya).
4. Jangan menambah dependensi UI baru (mis. library blur/animasi pihak ketiga) tanpa
   alasan terukur — Compose + Material 3 sudah cukup.
5. Jangan mengklaim "tanpa lag" tanpa angka `framestats` sebelum/sesudah.
