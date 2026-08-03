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

package dev.pwagen.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pwagen.app.ui.GeneratorUiState
import dev.pwagen.app.ui.GeneratorViewModel
import dev.pwagen.app.ui.PwaEntry
import dev.pwagen.app.ui.PwagenTheme
import dev.pwagen.app.ui.AppListScreen
import dev.pwagen.app.ui.EditorScreen

class GeneratorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PwagenTheme {
                PwagenApp()
            }
        }
    }
}

/** Which screen is showing. Two screens do not justify a navigation library. */
private sealed interface Destination {
    data object List : Destination
    data class Editing(val entry: PwaEntry?) : Destination
}

@Composable
private fun PwagenApp(viewModel: GeneratorViewModel = viewModel()) {
    val state: GeneratorUiState by viewModel.state.collectAsStateWithLifecycle()
    var destination: Destination by remember { mutableStateOf(Destination.List) }
    var pickedIcon: ByteArray? by remember { mutableStateOf(null) }

    val context = LocalContext.current

    val pickIcon = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let {
            pickedIcon = context.contentResolver.openInputStream(it)?.use { stream ->
                stream.readBytes()
            }
        }
    }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(viewModel::exportBackup) }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(viewModel::importBackup) }

    when (val current = destination) {
        is Destination.List -> AppListScreen(
            state = state,
            onAdd = {
                pickedIcon = null
                destination = Destination.Editing(null)
            },
            onEdit = { entry ->
                pickedIcon = null
                destination = Destination.Editing(entry)
            },
            onGenerate = viewModel::generateAndInstall,
            onExportBackup = { exportBackup.launch("pwagen-backup.zip") },
            onImportBackup = { importBackup.launch("application/zip") },
            onMessageShown = viewModel::dismissMessage,
        )

        is Destination.Editing -> EditorScreen(
            initial = current.entry,
            derivePackageName = viewModel::derivePackageName,
            onPickIcon = { pickIcon.launch("image/*") },
            pickedIcon = pickedIcon,
            onSave = { config, icon ->
                viewModel.save(config, icon)
                destination = Destination.List
            },
            onDelete = current.entry?.let { entry ->
                {
                    viewModel.forget(entry.config.packageName)
                    destination = Destination.List
                }
            },
            onBack = { destination = Destination.List },
        )
    }
}
