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

package dev.pwagen.app.generate

import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A throwaway EC key for tests, loaded from `test-signing.p12`.
 *
 * That file is committed deliberately and protects nothing: it exists so the
 * generation pipeline can be signed and verified on the desktop JVM. Real
 * installs are signed by a non-exportable AndroidKeyStore entry that never
 * leaves the device.
 */
object TestSigningKey : SigningKey {
    private const val ALIAS = "pwagen-test"
    private const val PASSWORD = "pwagen"

    private val entry: Pair<PrivateKey, X509Certificate> by lazy {
        val keyStore = KeyStore.getInstance("PKCS12")
        val stream = requireNotNull(
            TestSigningKey::class.java.getResourceAsStream("/test-signing.p12"),
        ) { "test-signing.p12 missing from test resources" }

        stream.use { keyStore.load(it, PASSWORD.toCharArray()) }

        val key = keyStore.getKey(ALIAS, PASSWORD.toCharArray()) as PrivateKey
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        key to certificate
    }

    override val privateKey: PrivateKey get() = entry.first
    override val certificates: List<X509Certificate> get() = listOf(entry.second)
}

/**
 * Writes real PNGs without any Android or AWT imaging code, so the pipeline can
 * be driven exactly as it is on device while running on a plain JVM.
 *
 * The image is a solid square whose colour is derived from its size, which makes
 * it easy to assert that each density slot received a distinctly rendered icon
 * rather than one image copied everywhere.
 */
object TestIconRenderer : IconRenderer {

    override fun render(sizePx: Int): ByteArray {
        val red = (sizePx * 7) % 256
        val green = (sizePx * 13) % 256
        val blue = (sizePx * 29) % 256

        val raw = ByteArrayOutputStream().apply {
            repeat(sizePx) {
                write(0) // filter type: none
                repeat(sizePx) {
                    write(red)
                    write(green)
                    write(blue)
                    write(0xFF)
                }
            }
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write(PNG_SIGNATURE)
            writeChunk("IHDR", ihdr(sizePx))
            writeChunk("IDAT", deflate(raw))
            writeChunk("IEND", ByteArray(0))
        }.toByteArray()
    }

    private val PNG_SIGNATURE =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun ihdr(size: Int): ByteArray = ByteArrayOutputStream().apply {
        writeInt(size)
        writeInt(size)
        write(8) // bit depth
        write(6) // colour type: RGBA
        write(0) // compression
        write(0) // filter
        write(0) // interlace
    }.toByteArray()

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write(value ushr 24 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeChunk(tag: String, data: ByteArray) {
        val tagBytes = tag.toByteArray(Charsets.US_ASCII)
        writeInt(data.size)
        write(tagBytes)
        write(data)

        val crc = CRC32().apply {
            update(tagBytes)
            update(data)
        }
        writeInt(crc.value.toInt())
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) {
            output.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        return output.toByteArray()
    }
}
