/*
 * Copyright (C) 2026 rixz-dev
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.lyreon.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyreon.app.data.model.EditorialSection
import com.lyreon.app.data.model.LyreonArchive
import com.lyreon.app.player.PlayerUiState
import com.lyreon.app.ui.components.BlurryBackdrop
import com.lyreon.app.ui.components.LyreonPlayButton
import com.lyreon.app.ui.components.RevealOnScroll
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextMuted
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.lyreon.app.R
import com.lyreon.app.ui.theme.LyreonRadius
import com.lyreon.app.ui.theme.LyreonSurface

/**
 * ARSIP — editorial movements dari desain asli.
 * Setiap movement kini bisa benar-benar dimainkan (kueri YouTube di balik layar).
 */
@Composable
fun ArchiveScreen(
    playerState: PlayerUiState,
    onOpenEditorial: (EditorialSection) -> Unit,
    onPlayMovement: (EditorialSection) -> Unit,
    onOpenBrowse: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val editorials = LyreonArchive.editorials
    val sectionIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, editorials.lastIndex) }
    }
    val parallax by remember {
        derivedStateOf { (listState.firstVisibleItemScrollOffset * 0.12f).coerceIn(-40f, 120f) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        BlurryBackdrop(
            imageRes = editorials[sectionIndex].backgroundRes,
            parallaxY = parallax,
            modifier = Modifier.fillMaxSize(),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 28.dp)) {
                    Text("Jelajah", style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Kurasi\nEditorial",
                        style = if (wide) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall,
                        color = LyreonTextPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(40.dp).height(2.dp).background(LyreonCrimson))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Kurasi editorial Lyreon. Setiap movement adalah pintu - tekan play dan biarkan kueri YouTube bekerja.",
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextSecondary,
                    )
                }
            }

            item(key = "genres_moods") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .clip(RoundedCornerShape(LyreonRadius.lg))
                        .background(LyreonSurface)
                        .clickable {
                            onOpenBrowse("FEmusic_moods_and_genres", "GENRE & MOOD")
                        }
                        .padding(16.dp),
                ) {
                    Text(
                        stringResource(R.string.browse_genres_title).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = LyreonCrimson,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.browse_genres_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextSecondary,
                    )
                }
            }

            itemsIndexed(editorials, key = { _, e -> e.id }) { index, section ->
                RevealOnScroll(delayMs = (index % 3) * 40) {
                    ArchiveCard(
                        section = section,
                        wide = wide,
                        onOpen = { onOpenEditorial(section) },
                        onPlay = { onPlayMovement(section) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchiveCard(
    section: EditorialSection,
    wide: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (wide) 240.dp else 280.dp)
                .border(1.dp, LyreonLine)
                .clickable(onClick = onOpen),
        ) {
            Image(
                painter = painterResource(section.backgroundRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.08f; scaleY = 1.08f; alpha = 0.85f },
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(LyreonBackground.copy(alpha = 0.35f), LyreonBackground.copy(alpha = 0.92f)),
                    ),
                ),
            )
            Column(
                Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(section.indexLabel, style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
                    Spacer(Modifier.height(10.dp))
                    Text(section.title, style = MaterialTheme.typography.headlineMedium, color = LyreonTextPrimary)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        section.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LyreonTextSecondary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LyreonPlayButton(isPlaying = false, onClick = onPlay, size = 44.dp, showOrbit = true)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Putar", style = MaterialTheme.typography.labelMedium, color = LyreonTextPrimary)
                    }
                }
            }
        }
    }
}
