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

import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.pwagen.config.Capabilities
import dev.pwagen.config.DomainRuleMode
import dev.pwagen.config.DomainRules
import dev.pwagen.config.PwaConfig

/**
 * Renders the screens through layoutlib so the README has real screenshots
 * without needing a device or emulator attached.
 *
 * Dynamic colour is switched off here: it would otherwise pull from the host
 * device's wallpaper and make the output non-reproducible.
 */

@Preview(name = "App list", device = "spec:width=411dp,height=891dp", showSystemUi = true)
@Composable
fun AppListPreview() {
    PwagenTheme(darkTheme = true, dynamicColor = false) {
        AppListScreen(
            state = GeneratorUiState(entries = SampleData.entries),
            onAdd = {},
            onEdit = {},
            onGenerate = {},
            onExportBackup = {},
            onImportBackup = {},
            onMessageShown = {},
        )
    }
}

@Preview(name = "App list, empty", device = "spec:width=411dp,height=891dp", showSystemUi = true)
@Composable
fun EmptyListPreview() {
    PwagenTheme(darkTheme = true, dynamicColor = false) {
        AppListScreen(
            state = GeneratorUiState(),
            onAdd = {},
            onEdit = {},
            onGenerate = {},
            onExportBackup = {},
            onImportBackup = {},
            onMessageShown = {},
        )
    }
}

@Preview(name = "Editor", device = "spec:width=411dp,height=1400dp", showSystemUi = true)
@Composable
fun EditorPreview() {
    PwagenTheme(darkTheme = true, dynamicColor = false) {
        EditorScreen(
            initial = SampleData.entries.first(),
            derivePackageName = { _, _ -> "dev.pwagen.pwa.com_example_grafana" },
            onPickIcon = {},
            pickedIcon = null,
            onSave = { _, _ -> },
            onDelete = {},
            onBack = {},
        )
    }
}

private object SampleData {

    val entries: List<PwaEntry> = listOf(
        entry(
            label = "Grafana",
            url = "https://grafana.example.com/d/home",
            packageName = "dev.pwagen.pwa.com_example_grafana",
            icon = Icons.GRAFANA,
            versionCode = 4,
            rules = DomainRules(
                DomainRuleMode.ALLOWLIST,
                DomainRules.seedFor("grafana.example.com"),
            ),
        ),
        entry(
            label = "Element",
            url = "https://element.example.org/",
            packageName = "dev.pwagen.pwa.org_example_element",
            icon = Icons.ELEMENT,
            versionCode = 2,
            capabilities = Capabilities(camera = true, microphone = true, notifications = true),
        ),
        entry(
            label = "Home Assistant",
            url = "https://home.example.lan:8123/lovelace",
            packageName = "dev.pwagen.pwa.lan_example_home",
            icon = Icons.HOME_ASSISTANT,
            versionCode = 11,
            rules = DomainRules(
                DomainRuleMode.BLOCKLIST,
                listOf("*.analytics.invalid", "telemetry.invalid"),
            ),
        ),
    )

    private fun entry(
        label: String,
        url: String,
        packageName: String,
        icon: String,
        versionCode: Int,
        capabilities: Capabilities = Capabilities(),
        rules: DomainRules = DomainRules(),
    ) = PwaEntry(
        config = PwaConfig(
            url = url,
            label = label,
            packageName = packageName,
            scope = url.substringBefore('/', url).let { url },
            themeColor = "#181b1f",
            capabilities = capabilities,
            domainRules = rules,
            versionCode = versionCode,
        ),
        icon = Base64.decode(icon, Base64.DEFAULT),
    )

    /** Solid placeholder tiles, so the previews show icons rather than initials. */
    private object Icons {
        const val GRAFANA =
            "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAAAtklEQVR42u3RQQ0AMAgEQczUHPr5ty4gpHPJGriJynM1VzgBAAABACAAAAQAgAAAEAAAAgBAAAAIAAABACAAALQQYPsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAHwAEAIAAABAAAAIAQAAACAAAAQAgAAAAOAEAAAEAIAAA1N4DA2yYCs+mSpMAAAAASUVORK5CYII="
        const val ELEMENT =
            "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAAAtklEQVR42u3RAQ0AMAgDQQxMOZrQtrmAkF2TN9CLU3k1VzgBAAABACAAAAQAgAAAEAAAAgBAAAAIAAABACAAALQQYPsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAHwAEAIAAABAAAAIAQAAACAAAAQAgAAAAOAEAAAEAIAAA1N4DDq+9v07318wAAAAASUVORK5CYII="
        const val HOME_ASSISTANT =
            "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAAAtklEQVR42u3RQQ0AMAgEQaxVOLJ4ty4gpHPJGriJk3U1VzgBAAABACAAAAQAgAAAEAAAAgBAAAAIAAABACAAALQQYPsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAHwAEAIAAABAAAAIAQAAACAAAAQAgAAAAOAEAAAEAIAAA1N4DEgZovgvrb5MAAAAASUVORK5CYII="
    }
}
