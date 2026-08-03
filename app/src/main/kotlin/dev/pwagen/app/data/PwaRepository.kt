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

package dev.pwagen.app.data

import android.content.Context
import dev.pwagen.config.PwaConfig
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Stores the web apps the user has defined, and the icons they chose.
 *
 * Icons live as files beside the configuration rather than inline in it: they are
 * the only large values here, and keeping them separate means the definition list
 * stays small enough to read and diff by hand.
 */
class PwaRepository(context: Context) {

    private val configFile = File(context.filesDir, "pwas.json")
    private val iconsDirectory = File(context.filesDir, "icons")

    fun load(): List<PwaConfig> {
        if (!configFile.exists()) return emptyList()
        return runCatching {
            PwaConfig.json.decodeFromString(
                ListSerializer(PwaConfig.serializer()),
                configFile.readText(),
            )
        }.getOrElse { emptyList() }
    }

    fun save(entries: List<PwaConfig>) {
        configFile.writeText(
            PwaConfig.json.encodeToString(ListSerializer(PwaConfig.serializer()), entries),
        )
    }

    fun saveIcon(packageName: String, png: ByteArray) {
        iconsDirectory.mkdirs()
        iconFile(packageName).writeBytes(png)
    }

    fun loadIcon(packageName: String): ByteArray? =
        iconFile(packageName).takeIf { it.exists() }?.readBytes()

    /** Forgets a web app's definition and icon. Does not uninstall the app itself. */
    fun forget(packageName: String) {
        save(load().filterNot { it.packageName == packageName })
        iconFile(packageName).delete()
    }

    fun iconFile(packageName: String): File = File(iconsDirectory, "$packageName.png")

    /** Every stored icon, keyed by package name. */
    fun allIcons(): Map<String, ByteArray> =
        iconsDirectory.listFiles().orEmpty()
            .filter { it.extension == "png" }
            .associate { it.nameWithoutExtension to it.readBytes() }
}
