# notes/ — memori proyek Lyreon

Folder ini adalah **memori kerja** Lyreon: apa yang pernah salah, apa yang sudah benar
dan tidak boleh dirusak, aturan main saat menyentuh UI, spesifikasi tema baru, peta
fitur yang diinginkan, dan peta referensi ke [Meld](https://github.com/FrancescoGrazioso/Meld).

Isinya ditulis untuk dibaca **sebelum** mengubah kode, bukan setelah. Dokumen di
`docs/` menjelaskan *sistem*; folder ini menjelaskan *keputusan dan jebakan*.

## Indeks

| Berkas | Isi | Baca kapan |
|---|---|---|
| [`01-yang-salah-dan-pelajarannya.md`](01-yang-salah-dan-pelajarannya.md) | 10 kesalahan nyata (gejala → sebab → perbaikan → pencegah), termasuk saga pemutaran 2026 | Sebelum menyentuh `yt/`, `player/`, `download/`; sebelum menyimpulkan "ini salah YouTube" |
| [`02-yang-sudah-bagus-jangan-dirusak.md`](02-yang-sudah-bagus-jangan-dirusak.md) | Invarian yang membuat app hidup: anonim, lapisan ketahanan, performa, i18n, CI | Setiap kali, sebelum refactor |
| [`03-panduan-update-ui.md`](03-panduan-update-ui.md) | Aturan token warna/bentuk, performa Compose (anti-lag), transparansi & blur, aksesibilitas, checklist pra-commit | **Wajib** sebelum mengubah file apa pun di `ui/` |
| [`04-spesifikasi-tema-baru.md`](04-spesifikasi-tema-baru.md) | Spesifikasi tema dinamis-minimalis-transparan-bulat ala iOS: token, radius, motion, 4 fase implementasi | Saat mengerjakan tema baru |
| [`05-peta-fitur-diinginkan.md`](05-peta-fitur-diinginkan.md) | 20 fitur yang diminta: status di Lyreon, padanannya di Meld, usaha, dan 2 konflik kebijakan | Saat merencanakan fitur baru |
| [`06-referensi-meld.md`](06-referensi-meld.md) | Peta repo Meld (1.487 berkas, 11 modul), cara mengambil sumbernya dari sandbox, apa yang sudah/belum diporting | Saat meniru fitur Meld |

## Aturan pakai folder ini

1. **Satu perubahan = satu baris riwayat.** Bila kamu memperbaiki bug atau mengubah
   kebijakan, tambahkan entri di berkas `01` (kalau itu kesalahan) atau di
   `docs/streaming-resilience.md` §"Riwayat penyesuaian" (kalau itu perubahan YouTube).
2. **Jangan menulis aspirasi sebagai fakta.** Setiap klaim "sudah ada" harus menyebut
   berkasnya. Setiap klaim "Meld begini" harus menyebut path di repo Meld — dan kalau
   itu hasil pengukuran, sebutkan bahwa itu pengukuran mereka, bukan kita.
3. **Konflik kebijakan ditandai, tidak diputuskan diam-diam.** Lihat `05` §"Dua fitur
   yang bertabrakan dengan keputusan produk": sinkronisasi akun dan *listen together*.
   Keduanya butuh keputusan pemilik proyek, bukan keputusan implementasi.
4. **Bahasa:** Indonesia (konsisten dengan `docs/` dan README). Istilah teknis dan nama
   berkas/klas boleh tetap Inggris.

## Peta cepat repo (per September 2026)

```
app/src/main/java/com/lyreon/app/
├─ core/         ServiceLocator (DI manual), LocaleHelper, SessionStore
├─ data/         LibraryRepository, db/{Daos,Entities,LyreonDatabase}, model/, settings/, taste/
├─ download/     LyreonDownloadManager, HlsFlatDownloader
├─ local/        LocalMusicRepository (musik di perangkat)
├─ lyrics/       LyricsRepository (lirik ber-timecode)
├─ player/       PlayerManager (Media3 + health/breaker), ResolvingDataSource
├─ ui/           screens/ (11), components/ (12), theme/{Color,Theme,Type}, vm/
└─ yt/           YouTubeRepository, LyreonHttp, innertube/ (5 berkas — JANTUNG streaming)

docs/streaming-resilience.md   — ketahanan streaming (anonim), runbook, riwayat
tools/ci/extractor-radar.sh    — drift pin extractor
tools/ci/meld-client-radar.sh  — drift spesifikasi klien vs HEAD Meld
```

Total 56 berkas Kotlin. Tidak ada framework DI (Hilt/Koin): semua lewat
`ServiceLocator` — pertahankan, itu alasan build cepat dan mudah ditelusuri.
