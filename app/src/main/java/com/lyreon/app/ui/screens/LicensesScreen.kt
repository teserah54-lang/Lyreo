/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.lyreon.app.ui.theme.LyreonRadius
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lyreon.app.R
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonElevated
import com.lyreon.app.ui.theme.LyreonSurface
import com.lyreon.app.ui.theme.LyreonSurfaceTranslucent
import com.lyreon.app.ui.theme.LyreonTextMuted
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonTextSecondary

/**
 * Layar lisensi sumber terbuka (wishlist #7).
 *
 * Dua kewajiban hukum dilunasi di sini:
 * 1. **GPL-3.0-only** — teks lisensi aplikasi sendiri harus tersedia bagi
 *    pengguna, beserta sumber kode dan atribusi kode yang diporting dari Meld
 *    (juga GPL-3.0).
 * 2. **Apache-2.0 / MIT / BSD** dari pustaka pihak ketiga — pemberitahuan
 *    hak cipta dan salinan lisensi wajib disertakan dalam distribusi.
 *    Nama pustaka, pengenal SPDX, dan URL ditulis apa adanya (bukan string
 *    yang diterjemahkan) supaya tetap akurat di semua bahasa.
 *
 * Daftar ini ditulis tangan dan harus ikut diperbarui saat dependensi berubah —
 * lihat `notes/06-referensi-meld.md` dan `app/build.gradle.kts`.
 */
private data class LicenseEntry(
    val name: String,
    val license: String,
    val url: String,
)

private val APP_LIBRARIES = listOf(
    LicenseEntry("AndroidX Core / AppCompat / Activity", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
    LicenseEntry("Jetpack Compose + Material 3", "Apache-2.0", "https://developer.android.com/jetpack/compose"),
    LicenseEntry("AndroidX Media3 (ExoPlayer, Session, UI, HLS, DataSource)", "Apache-2.0", "https://github.com/androidx/media"),
    LicenseEntry("AndroidX Room", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/room"),
    LicenseEntry("AndroidX DataStore (Preferences)", "Apache-2.0", "https://developer.android.com/topic/libraries/architecture/datastore"),
    LicenseEntry("AndroidX Lifecycle / ViewModel", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
    LicenseEntry("AndroidX Navigation Compose", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/navigation"),
    LicenseEntry("Kotlin Standard Library & Coroutines", "Apache-2.0", "https://kotlinlang.org"),
    LicenseEntry("OkHttp", "Apache-2.0", "https://github.com/square/okhttp"),
    LicenseEntry("Okio", "Apache-2.0", "https://github.com/square/okio"),
    LicenseEntry("Kotlinx Serialization JSON", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    LicenseEntry("Coil (Compose)", "Apache-2.0", "https://github.com/coil-kt/coil"),
    LicenseEntry("Accompanist (SystemUiController)", "Apache-2.0", "https://github.com/google/accompanist"),
)

private val ART_AND_FONTS = listOf(
    LicenseEntry("Material Symbols / material-icons-extended", "Apache-2.0", "https://fonts.google.com/icons"),
    LicenseEntry("Inter (font antarmuka)", "SIL Open Font License 1.1", "https://rsms.me/inter"),
    LicenseEntry("Material You dynamic color (Android 12+)", "Apache-2.0", "https://m3.material.io/styles/color/dynamic-color"),
)

private val CODE_PORTED_FROM = listOf(
    LicenseEntry("Meld (FrancescoGrazioso) - fork Metrolist: rantai audio, deteksi keheningan, normalisasi loudness, protokol lirik KuGou", "GPL-3.0-only", "https://github.com/FrancescoGrazioso/Meld"),
    LicenseEntry("Metrolist (mostafaalagamy) - pola animasi lirik & antrean", "GPL-3.0-only", "https://github.com/mostafaalagamy/Metrolist"),
)

@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LyreonBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = LyreonTextPrimary,
                )
            }
            Text(
                stringResource(R.string.licenses_title).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = LyreonCrimson,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                LicenseCard(
                    heading = stringResource(R.string.licenses_app_heading),
                    body = stringResource(R.string.licenses_app_body),
                    accent = true,
                )
            }
            item { SectionLabel(stringResource(R.string.licenses_sec_libraries)) }
            items(APP_LIBRARIES) { EntryRow(it) }
            item { SectionLabel(stringResource(R.string.licenses_sec_assets)) }
            items(ART_AND_FONTS) { EntryRow(it) }
            item { SectionLabel(stringResource(R.string.licenses_sec_ported)) }
            items(CODE_PORTED_FROM) { EntryRow(it) }
            item {
                LicenseCard(
                    heading = stringResource(R.string.licenses_notice_heading),
                    body = stringResource(R.string.licenses_notice_body),
                    accent = false,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = LyreonTextMuted,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun EntryRow(entry: LicenseEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LyreonRadius.md))
            .background(LyreonSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(LyreonRadius.xs))
                .background(LyreonCrimson.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                color = LyreonTextPrimary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                entry.license,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = LyreonTextSecondary,
            )
            Text(
                entry.url,
                style = MaterialTheme.typography.labelSmall,
                color = LyreonTextMuted,
            )
        }
    }
}

@Composable
private fun LicenseCard(heading: String, body: String, accent: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LyreonRadius.lg))
            .background(if (accent) LyreonSurfaceTranslucent else LyreonElevated)
            .padding(16.dp),
    ) {
        Text(
            heading,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) LyreonCrimson else LyreonTextPrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = LyreonTextSecondary,
        )
    }
}
