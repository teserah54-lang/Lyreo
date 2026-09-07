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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.lyreon.app.R
import com.lyreon.app.data.model.LyreonArchive
import com.lyreon.app.ui.components.LyreonPlayButton
import com.lyreon.app.ui.theme.LyreonTextSecondary
import com.lyreon.app.ui.theme.LyreonTextPrimary
import com.lyreon.app.ui.theme.LyreonLine
import com.lyreon.app.ui.theme.LyreonBackground
import com.lyreon.app.ui.theme.LyreonCrimson
import com.lyreon.app.ui.theme.LyreonTextMuted

@Composable
fun EditorialDetailScreen(
    sectionId: String,
    onBack: () -> Unit,
    onPlayMovement: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val section = LyreonArchive.editorials.find { it.id == sectionId } ?: LyreonArchive.editorials.first()

    Box(modifier = modifier.fillMaxSize().background(LyreonBackground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(340.dp)) {
                    Image(
                        painter = painterResource(section.backgroundRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(LyreonBackground.copy(alpha = 0.2f), LyreonBackground),
                            ),
                        ),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = LyreonTextPrimary,
                            )
                        }
                        Text(stringResource(R.string.editorial_kicker), style = MaterialTheme.typography.labelMedium, color = LyreonTextSecondary)
                    }
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(20.dp),
                    ) {
                        Text(section.indexLabel, style = MaterialTheme.typography.labelMedium, color = LyreonCrimson)
                        Spacer(Modifier.height(8.dp))
                        Text(section.title, style = MaterialTheme.typography.displaySmall, color = LyreonTextPrimary)
                    }
                }
            }

            item {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        section.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = LyreonTextSecondary,
                    )
                    Spacer(Modifier.height(24.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, LyreonCrimson)
                            .background(LyreonCrimson.copy(alpha = 0.14f))
                            .clickable { onPlayMovement(section.searchQuery) }
                            .padding(18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LyreonPlayButton(
                                isPlaying = false,
                                onClick = { onPlayMovement(section.searchQuery) },
                                size = 52.dp,
                                showOrbit = true,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(
                                    stringResource(R.string.editorial_play),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LyreonTextPrimary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Text(
                        stringResource(R.string.editorial_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = LyreonTextMuted,
                    )
                }
            }
        }
    }
}
