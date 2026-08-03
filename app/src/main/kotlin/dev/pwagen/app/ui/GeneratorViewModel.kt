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

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pwagen.app.PackageNaming
import dev.pwagen.app.data.BackupManager
import dev.pwagen.app.data.PwaRepository
import dev.pwagen.app.generate.AndroidIconRenderer
import dev.pwagen.app.generate.ApkGenerator
import dev.pwagen.app.generate.KeystoreSigningKey
import dev.pwagen.app.generate.PwaInstaller
import dev.pwagen.config.PwaConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A stored web app together with the icon bytes the user chose for it. */
data class PwaEntry(val config: PwaConfig, val icon: ByteArray?) {
    // Generated equals/hashCode would compare the icon array by identity.
    override fun equals(other: Any?): Boolean =
        other is PwaEntry &&
            config == other.config &&
            icon.contentEquals(other.icon)

    override fun hashCode(): Int = 31 * config.hashCode() + (icon?.contentHashCode() ?: 0)
}

/** Something worth telling the user about, shown once and dismissed. */
data class Message(val text: String, val isError: Boolean = false)

data class GeneratorUiState(
    val entries: List<PwaEntry> = emptyList(),
    val busyWith: String? = null,
    val message: Message? = null,
    val keyFingerprint: String? = null,
    val keyStrongBoxBacked: Boolean = false,
    val keyProblem: String? = null,
)

class GeneratorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PwaRepository(application)
    private val backups = BackupManager(repository)
    private val installer = PwaInstaller(application)

    private val _state = MutableStateFlow(GeneratorUiState())
    val state: StateFlow<GeneratorUiState> = _state.asStateFlow()

    init {
        refresh()
        inspectSigningKey()
    }

    // ------------------------------------------------------------------ editing

    fun save(config: PwaConfig, icon: ByteArray?) {
        val existing = repository.load()
        val updated = existing.filterNot { it.packageName == config.packageName } + config
        repository.save(updated)
        icon?.let { repository.saveIcon(config.packageName, it) }
        refresh()
    }

    fun forget(packageName: String) {
        repository.forget(packageName)
        refresh()
        show(Message("Removed from pwagen. The installed app is still on your device."))
    }

    /** Derives the package name for a URL, or null while the URL is unusable. */
    fun derivePackageName(url: String, discriminator: String): String? =
        runCatching { PackageNaming.derive(url, discriminator) }.getOrNull()

    // --------------------------------------------------------------- generating

    /**
     * Builds and installs a web app.
     *
     * The version is bumped on every run so the result lands as an in-place
     * upgrade, which is what preserves the app's cookies, logins and any firewall
     * rules already set against it.
     */
    fun generateAndInstall(entry: PwaEntry) {
        val icon = entry.icon
        if (icon == null) {
            show(Message("Choose an icon first", isError = true))
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busyWith = entry.config.packageName) }
            try {
                val bumped = entry.config.copy(versionCode = entry.config.versionCode + 1)
                val apk = withContext(Dispatchers.Default) { build(bumped, icon) }

                save(bumped, icon)

                installer.install(apk, bumped.packageName) { result ->
                    _state.update { it.copy(busyWith = null) }
                    when (result) {
                        is PwaInstaller.Result.Success ->
                            show(Message("${bumped.label} installed"))

                        is PwaInstaller.Result.Cancelled ->
                            show(Message("Install cancelled"))

                        is PwaInstaller.Result.Failure ->
                            show(Message(result.message, isError = true))
                    }
                    apk.delete()
                }
            } catch (e: Exception) {
                _state.update { it.copy(busyWith = null) }
                show(Message(e.message ?: "Generation failed", isError = true))
            }
        }
    }

    private fun build(config: PwaConfig, icon: ByteArray): File {
        val template = getApplication<Application>().assets
            .open(TEMPLATE_ASSET)
            .use { it.readBytes() }

        return ApkGenerator(template).generate(
            config = config,
            icon = AndroidIconRenderer.from(icon),
            signingKey = KeystoreSigningKey.loadOrCreate(),
            workDirectory = File(getApplication<Application>().cacheDir, "generated"),
        )
    }

    // ------------------------------------------------------------------ backups

    fun exportBackup(destination: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openOutputStream(destination)
                        ?.use { backups.export(it) }
                        ?: error("Could not open $destination for writing")
                }
            }.onSuccess {
                show(Message("Backup saved. It contains your web apps and icons, not the signing key."))
            }.onFailure {
                show(Message(it.message ?: "Backup failed", isError = true))
            }
        }
    }

    fun importBackup(source: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver
                        .openInputStream(source)
                        ?.use { backups.import(it) }
                        ?: error("Could not open $source for reading")
                }
            }.onSuccess { count ->
                refresh()
                show(Message("Restored $count web app${if (count == 1) "" else "s"}. Regenerate each to install."))
            }.onFailure {
                show(Message(it.message ?: "Restore failed", isError = true))
            }
        }
    }

    // ---------------------------------------------------------------- machinery

    fun dismissMessage() = _state.update { it.copy(message = null) }

    private fun show(message: Message) = _state.update { it.copy(message = message) }

    private fun refresh() {
        val entries = repository.load().map { config ->
            PwaEntry(config, repository.loadIcon(config.packageName))
        }
        _state.update { it.copy(entries = entries.sortedBy { entry -> entry.config.label }) }
    }

    /**
     * Reports the signing key's state without creating one, so first launch does
     * not silently mint a key before the user has asked for anything.
     */
    private fun inspectSigningKey() {
        if (!KeystoreSigningKey.exists()) return

        try {
            val key = KeystoreSigningKey.loadOrCreate()
            _state.update {
                it.copy(
                    keyFingerprint = key.fingerprint,
                    keyStrongBoxBacked = key.strongBoxBacked,
                    keyProblem = null,
                )
            }
        } catch (e: KeystoreSigningKey.Unusable) {
            _state.update { it.copy(keyProblem = e.message) }
        }
    }

    private companion object {
        const val TEMPLATE_ASSET = "shell-template.apk"
    }
}
