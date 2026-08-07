/*
 * Copyright (C) 2026 pwagen contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.pwagen.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pwagen.config.DomainRuleMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    state: GeneratorUiState,
    onAdd: () -> Unit,
    onEdit: (PwaEntry) -> Unit,
    onGenerate: (PwaEntry) -> Unit,
    onExportApp: (PwaEntry) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbars = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbars.showSnackbar(it.text)
            onMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("pwagen") },
                actions = {
                    BarAction("⤓", onImportBackup)
                    BarAction("⤒", onExportBackup)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                text = { Text("Add web app") },
            )
        },
    ) { padding ->
        if (state.entries.isEmpty()) {
            EmptyState(Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.keyProblem?.let { problem ->
                item { KeyProblemCard(problem) }
            }

            items(state.entries, key = { it.config.packageName }) { entry ->
                PwaCard(
                    entry = entry,
                    busy = state.busyWith == entry.config.packageName,
                    onEdit = { onEdit(entry) },
                    onGenerate = { onGenerate(entry) },
                    onExport = { onExportApp(entry) },
                )
            }
        }
    }
}

/**
 * A top-bar action, deliberately larger than the Material default.
 *
 * These are text glyphs rather than icons, and a glyph renders visually smaller
 * than a 24dp icon set at the same nominal size — so the stock 48dp button left
 * them looking like fine print in the corner of the screen, which is the worst
 * place to make someone aim.
 */
@Composable
private fun BarAction(glyph: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Text(glyph, fontSize = 28.sp)
    }
}

@Composable
private fun PwaCard(
    entry: PwaEntry,
    busy: Boolean,
    onEdit: () -> Unit,
    onGenerate: () -> Unit,
    onExport: () -> Unit,
) {
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconTile(entry)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.config.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.config.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.config.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (entry.config.domainRules.mode != DomainRuleMode.OFF) {
                        val label = when (entry.config.domainRules.mode) {
                            DomainRuleMode.ALLOWLIST -> "allowlist"
                            DomainRuleMode.BLOCKLIST -> "blocklist"
                            DomainRuleMode.OFF -> ""
                        }
                        Chip("$label · ${entry.config.domainRules.patterns.size}")
                    }
                    Chip("v${entry.config.versionCode}")
                }
            }

            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                // The same glyph as the top bar's export, because it does the
                // same thing to a smaller subject: one web app to a file, in
                // the format the whole-set backup already uses.
                IconButton(onClick = onExport) {
                    Text("⤒", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = onGenerate) {
                    Text("▶", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

@Composable
private fun IconTile(entry: PwaEntry) {
    val bitmap = remember(entry.icon) {
        entry.icon?.let { bytes ->
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                entry.config.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun Chip(text: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text, style = MaterialTheme.typography.labelSmall) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun KeyProblemCard(problem: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Signing key unusable", style = MaterialTheme.typography.titleSmall)
            Text(problem, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No web apps yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Each one you add is built into its own signed APK, so it installs as " +
                "a separate Android app with its own UID, storage and permissions.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
