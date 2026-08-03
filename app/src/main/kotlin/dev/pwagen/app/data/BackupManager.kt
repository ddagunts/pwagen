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

import dev.pwagen.config.PwaConfig
import kotlinx.serialization.builtins.ListSerializer
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Reads and writes pwagen's backup bundle.
 *
 * The bundle is self-contained — definitions *and* icon bytes — because the
 * generator has no network access and so could not re-fetch an icon on a new
 * device even if it wanted to.
 *
 * It deliberately does **not** contain the signing key. That key is
 * non-exportable by construction; it lives in the device's secure hardware and
 * cannot be put in a file. Restoring on a new device mints a fresh key and
 * regenerates every app, which works because package names are derived
 * deterministically from each site's host and so come out identical.
 */
class BackupManager(private val repository: PwaRepository) {

    fun export(destination: OutputStream) {
        val entries = repository.load()

        ZipOutputStream(destination).use { zip ->
            zip.putNextEntry(ZipEntry(CONFIG_ENTRY))
            zip.write(
                PwaConfig.json
                    .encodeToString(ListSerializer(PwaConfig.serializer()), entries)
                    .toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()

            for ((packageName, png) in repository.allIcons()) {
                zip.putNextEntry(ZipEntry("$ICONS_PREFIX$packageName.png"))
                zip.write(png)
                zip.closeEntry()
            }
        }
    }

    /**
     * Merges a bundle into the current set, replacing any web app whose package
     * name matches.
     *
     * @return the number of definitions restored.
     */
    fun import(source: InputStream): Int {
        var restored: List<PwaConfig> = emptyList()
        val icons = mutableMapOf<String, ByteArray>()

        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == CONFIG_ENTRY -> {
                        restored = PwaConfig.json.decodeFromString(
                            ListSerializer(PwaConfig.serializer()),
                            zip.readBytes().toString(Charsets.UTF_8),
                        )
                    }

                    entry.name.startsWith(ICONS_PREFIX) && entry.name.endsWith(".png") -> {
                        val packageName = entry.name
                            .removePrefix(ICONS_PREFIX)
                            .removeSuffix(".png")
                        icons[packageName] = zip.readBytes()
                    }
                }
                zip.closeEntry()
            }
        }

        val restoredNames = restored.map { it.packageName }.toSet()
        val merged = repository.load().filterNot { it.packageName in restoredNames } + restored
        repository.save(merged)

        for ((packageName, png) in icons) {
            repository.saveIcon(packageName, png)
        }
        return restored.size
    }

    private companion object {
        const val CONFIG_ENTRY = "config.json"
        const val ICONS_PREFIX = "icons/"
    }
}
