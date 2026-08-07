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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import dev.pwagen.app.data.Examples
import dev.pwagen.config.Capabilities
import dev.pwagen.config.DomainRuleMode
import dev.pwagen.config.DomainRules
import dev.pwagen.config.NetworkSecurity
import dev.pwagen.config.OffScopePolicy
import dev.pwagen.config.PwaConfig

/**
 * Add or edit one web app.
 *
 * Note what is absent: there is no "fetch details from site" button. The
 * generator holds no INTERNET permission, so the name and icon come from you and
 * the device. That is a deliberate trade of convenience for a property you can
 * verify with `aapt2`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    initial: PwaEntry?,
    derivePackageName: (url: String, discriminator: String) -> String?,
    onPickIcon: () -> Unit,
    pickedIcon: ByteArray?,
    onSave: (PwaConfig, ByteArray?) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit,
) {
    var url by remember { mutableStateOf(initial?.config?.url.orEmpty()) }
    var label by remember { mutableStateOf(initial?.config?.label.orEmpty()) }
    var discriminator by remember { mutableStateOf("") }
    var themeColor by remember { mutableStateOf(initial?.config?.themeColor ?: "#101010") }
    var fullscreen by remember { mutableStateOf(initial?.config?.fullscreen ?: true) }
    var keepStatusBar by remember {
        mutableStateOf(initial?.config?.keepStatusBar ?: false)
    }
    var drawUnderCutout by remember {
        mutableStateOf(initial?.config?.drawUnderCutout ?: false)
    }
    var capabilities by remember {
        mutableStateOf(initial?.config?.capabilities ?: Capabilities())
    }
    var network by remember {
        mutableStateOf(initial?.config?.network ?: NetworkSecurity())
    }
    var offScope by remember {
        mutableStateOf(initial?.config?.offScopePolicy ?: OffScopePolicy.EXTERNAL)
    }
    var ruleMode by remember {
        mutableStateOf(initial?.config?.domainRules?.mode ?: DomainRuleMode.OFF)
    }
    var patterns by remember {
        mutableStateOf(initial?.config?.domainRules?.patterns.orEmpty().joinToString("\n"))
    }
    var announceBlocks by remember {
        mutableStateOf(initial?.config?.domainRules?.announceBlocks ?: false)
    }

    val icon = pickedIcon ?: initial?.icon
    val packageName = initial?.config?.packageName ?: derivePackageName(url, discriminator)
    val canSave = packageName != null && url.isNotBlank() && label.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Add web app" else "Edit web app") },
                navigationIcon = {
                    // Sized up from the Material default: this is the only way
                    // out of a screen that scrolls well past a screenful, so it
                    // is worth more than the minimum tappable area.
                    TextButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .heightIn(min = 56.dp),
                    ) {
                        Text("Back", style = MaterialTheme.typography.titleMedium)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Only offered while adding: on an existing app this would quietly
            // throw away settings that are already installed on the device.
            if (initial == null) {
                Section(
                    "Start from an example",
                    "Fills the whole form. Everything stays editable afterwards.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (example in Examples.ALL) {
                        AssistChip(
                            onClick = {
                                url = example.url
                                label = example.label
                                themeColor = example.themeColor
                                capabilities = example.capabilities
                                network = example.network
                                offScope = example.offScopePolicy
                                ruleMode = example.domainRules.mode
                                patterns = example.domainRules.patterns.joinToString("\n")
                                announceBlocks = example.domainRules.announceBlocks
                            },
                            label = { Text(example.label) },
                        )
                    }
                }
                Text(
                    Examples.ALL.joinToString("\n") { "${it.label}: ${it.note}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Site URL") },
                placeholder = { Text("https://grafana.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("App name") },
                supportingText = { Text("Shown on the launcher") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (initial == null) {
                OutlinedTextField(
                    value = discriminator,
                    onValueChange = { discriminator = it },
                    label = { Text("Discriminator (optional)") },
                    supportingText = {
                        Text("Only needed for a second app on the same host, e.g. two accounts")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            packageName?.let {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Package name", style = MaterialTheme.typography.labelMedium)
                        Text(it, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Derived from the host, so a restored backup reproduces it exactly.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            IconRow(icon = icon, onPickIcon = onPickIcon)

            OutlinedTextField(
                value = themeColor,
                onValueChange = { themeColor = it },
                label = { Text("Theme colour") },
                supportingText = { Text("#rrggbb, used for the status bar and recents tile") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            CapabilityToggle(
                "Full screen",
                fullscreen,
                detail = if (fullscreen) {
                    "The system bars are hidden and the site uses the whole screen. Swipe from the top edge to bring the status bar back."
                } else {
                    "The status bar stays visible and the site starts below it, so nothing on the page sits under it."
                },
            ) { fullscreen = it }

            if (fullscreen) {
                CapabilityToggle(
                    "Keep the status bar",
                    keepStatusBar,
                    detail = "Leaves the clock, battery and signal strength on " +
                        "screen. The navigation bar still goes, and the site is " +
                        "held below the status bar rather than under it.",
                ) { keepStatusBar = it }
            }

            if (fullscreen && !keepStatusBar) {
                CapabilityToggle(
                    "Draw under the camera cutout",
                    drawUnderCutout,
                    caution = "Anything the site puts up there sits behind the lens"
                        .takeIf { drawUnderCutout },
                    detail = "Off, the page stops below the camera and the strip " +
                        "is filled with the theme colour. Hiding the bars does not " +
                        "move the camera, and unlike the status bar there is no " +
                        "swipe that brings the covered part back.",
                ) { drawUnderCutout = it }
            }

            HorizontalDivider()

            Section("Capabilities", "Off by default. Anything left off is absent from the generated app's manifest, not merely unused.")

            CapabilityToggle("Camera", capabilities.camera) {
                capabilities = capabilities.copy(camera = it)
            }
            CapabilityToggle("Microphone", capabilities.microphone) {
                capabilities = capabilities.copy(microphone = it)
            }
            CapabilityToggle("Location", capabilities.location) {
                capabilities = capabilities.copy(location = it)
            }
            CapabilityToggle("Notifications", capabilities.notifications) {
                capabilities = capabilities.copy(notifications = it)
            }
            CapabilityToggle(
                "Safe Browsing",
                capabilities.safeBrowsing,
                "Reports visited URLs to Google",
            ) { capabilities = capabilities.copy(safeBrowsing = it) }
            CapabilityToggle(
                "Third-party cookies",
                capabilities.thirdPartyCookies,
                "Allows cross-site tracking",
            ) { capabilities = capabilities.copy(thirdPartyCookies = it) }

            HorizontalDivider()

            Section(
                "Transport security",
                "Unlike the capabilities above, none of this is revocable in Android Settings afterwards — only by regenerating the app",
            )

            CapabilityToggle(
                "Allow plaintext HTTP",
                network.cleartext,
                "Traffic travels unencrypted and unauthenticated",
            ) { network = network.copy(cleartext = it) }

            CapabilityToggle(
                "Accept invalid certificates",
                network.acceptInvalidCertificates,
                "Self-signed, expired and wrong-host certificates all pass. Anything on the path can impersonate the site.",
            ) { network = network.copy(acceptInvalidCertificates = it) }

            CapabilityToggle(
                "Trust user-installed CAs",
                network.trustUserCas,
            ) {
                // Dropping user CAs while the built-in set is already off would
                // leave nothing to validate against, so the built-in set comes
                // back with it rather than the app being left unable to connect.
                network = if (it) {
                    network.copy(trustUserCas = true)
                } else {
                    network.copy(trustUserCas = false, trustSystemCas = true)
                }
            }

            CapabilityToggle(
                "Trust Android's built-in CAs",
                network.trustSystemCas,
                caution = "Only the CAs you installed yourself are accepted"
                    .takeUnless { network.trustSystemCas },
                enabled = network.trustUserCas,
            ) { network = network.copy(trustSystemCas = it) }

            if (!network.trustUserCas) {
                Text(
                    "Turning the built-in CAs off requires your own CAs trusted first, or there would be nothing left to validate against.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Section("Off-scope links", "What happens when a link leaves the site")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (policy in OffScopePolicy.entries) {
                    FilterChip(
                        selected = offScope == policy,
                        onClick = { offScope = policy },
                        label = {
                            Text(
                                when (policy) {
                                    OffScopePolicy.EXTERNAL -> "Browser"
                                    OffScopePolicy.ALLOW -> "In-app"
                                    OffScopePolicy.BLOCK -> "Block"
                                },
                            )
                        },
                    )
                }
            }

            HorizontalDivider()

            Section(
                "Domain rules",
                "Applied to every request the app makes — scripts and images too, not just navigation",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in DomainRuleMode.entries) {
                    FilterChip(
                        selected = ruleMode == mode,
                        onClick = {
                            ruleMode = mode
                            // An empty allowlist would block the site's own
                            // requests, so seed it rather than hiding an
                            // implicit exception in the runtime.
                            if (mode == DomainRuleMode.ALLOWLIST && patterns.isBlank()) {
                                hostOf(url)?.let {
                                    patterns = DomainRules.seedFor(it).joinToString("\n")
                                }
                            }
                        },
                        label = {
                            Text(
                                when (mode) {
                                    DomainRuleMode.OFF -> "Off"
                                    DomainRuleMode.ALLOWLIST -> "Allowlist"
                                    DomainRuleMode.BLOCKLIST -> "Blocklist"
                                },
                            )
                        },
                    )
                }
            }

            if (ruleMode != DomainRuleMode.OFF) {
                OutlinedTextField(
                    value = patterns,
                    onValueChange = { patterns = it },
                    label = { Text("Patterns, one per line") },
                    supportingText = {
                        Text("* matches any run of characters. *.example.com does not match example.com.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )

                CapabilityToggle(
                    "Announce blocked requests",
                    announceBlocks,
                    detail = "Names each refused host in the app the first time it " +
                        "comes up, with a Mute button that silences it for good. " +
                        "Clearing the app's storage in Android Settings brings the " +
                        "muted ones back.",
                ) { announceBlocks = it }
            }

            HorizontalDivider()

            Button(
                onClick = {
                    onSave(
                        PwaConfig(
                            url = url.trim(),
                            label = label.trim(),
                            packageName = packageName!!,
                            scope = scopeOf(url),
                            offScopePolicy = offScope,
                            themeColor = themeColor.trim(),
                            fullscreen = fullscreen,
                            keepStatusBar = keepStatusBar,
                            drawUnderCutout = drawUnderCutout,
                            capabilities = capabilities,
                            network = network,
                            domainRules = DomainRules(
                                mode = ruleMode,
                                patterns = patterns.lines()
                                    .map(String::trim)
                                    .filter(String::isNotEmpty),
                                announceBlocks = announceBlocks,
                            ),
                            versionCode = initial?.config?.versionCode ?: 1,
                        ),
                        pickedIcon,
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

            onDelete?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                    Text("Remove from pwagen")
                }
            }
        }
    }
}

@Composable
private fun IconRow(icon: ByteArray?, onPickIcon: () -> Unit) {
    val bitmap = remember(icon) {
        icon?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
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
                Text("?", style = MaterialTheme.typography.headlineMedium)
            }
        }

        Column {
            OutlinedButton(onClick = onPickIcon) { Text("Choose icon") }
            Text(
                "From your device — pwagen cannot fetch one",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapabilityToggle(
    title: String,
    checked: Boolean,
    caution: String? = null,
    detail: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            caution?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** Scope defaults to the URL's origin, which is the useful boundary in practice. */
private fun scopeOf(url: String): String {
    val trimmed = url.trim()
    val schemeEnd = trimmed.indexOf("://")
    if (schemeEnd < 0) return trimmed

    val pathStart = trimmed.indexOf('/', schemeEnd + 3)
    return if (pathStart < 0) "$trimmed/" else trimmed.substring(0, pathStart + 1)
}

private fun hostOf(url: String): String? =
    runCatching { java.net.URI(url.trim()) }.getOrNull()
        ?.let { it.host ?: it.authority }
        ?.substringBefore(':')
        ?.takeIf { it.isNotBlank() }
