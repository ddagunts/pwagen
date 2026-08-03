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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Renders a user-supplied image into launcher icons.
 *
 * The source comes from the device — a file the user picked — because the
 * generator has no network access and so cannot fetch a site's own icon. Scaling
 * happens per density from the original rather than from an already-scaled copy,
 * so the largest bucket does not inherit blur from the smallest.
 */
class AndroidIconRenderer(private val source: Bitmap) : IconRenderer {

    override fun render(sizePx: Int): ByteArray {
        val square = cropToSquare(source)
        val scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, true)

        return ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output)
            if (scaled !== square) scaled.recycle()
            if (square !== source) square.recycle()
            output.toByteArray()
        }
    }

    /** Centre-crops, so a non-square source is not stretched out of proportion. */
    private fun cropToSquare(bitmap: Bitmap): Bitmap {
        if (bitmap.width == bitmap.height) return bitmap

        val side = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(
            bitmap,
            (bitmap.width - side) / 2,
            (bitmap.height - side) / 2,
            side,
            side,
        )
    }

    companion object {
        /** Ignored for PNG, which is lossless, but the API requires a value. */
        private const val PNG_QUALITY = 100

        /**
         * Decodes [bytes] into a renderer.
         *
         * @throws IllegalArgumentException if the bytes are not a decodable image.
         */
        fun from(bytes: ByteArray): AndroidIconRenderer {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("Not a decodable image")
            return AndroidIconRenderer(bitmap)
        }
    }
}
