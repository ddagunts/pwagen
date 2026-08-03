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

import java.net.URI
import java.util.Locale

/**
 * Derives the package name of a generated PWA from its URL.
 *
 * Naming is deterministic on purpose. Restoring a backup on a new device
 * regenerates byte-for-byte the same package names, so firewall rules can be
 * re-applied by name instead of rebuilt from scratch; and regenerating an
 * existing PWA lands as an in-place upgrade that keeps its cookies and logins
 * rather than installing a duplicate beside it.
 *
 * The host is reversed into the usual Java ordering so that related hosts sort
 * together: `grafana.example.com` becomes `dev.pwagen.pwa.com_example_grafana`.
 */
object PackageNaming {

    const val PREFIX = "dev.pwagen.pwa"

    /** Java keywords cannot appear as a package segment. */
    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "_",
    )

    /**
     * @param url the PWA's start URL.
     * @param discriminator distinguishes multiple PWAs on one host, such as two
     *   accounts on the same service. Blank means the plain host-derived name.
     * @throws IllegalArgumentException if [url] has no usable host.
     */
    fun derive(url: String, discriminator: String = ""): String {
        val host = hostOf(url)
        require(host.isNotBlank()) { "URL has no host: $url" }

        // The reversed host collapses into a single package segment, so the
        // keyword and leading-digit rules apply to the joined result rather than
        // to each label: "com_example_public" is fine, a bare "public" is not.
        val segment = host
            .split('.')
            .asReversed()
            .joinToString("_") { sanitizeLabel(it) }
            .let(::legalise)

        val suffix = discriminator.takeIf { it.isNotBlank() }
            ?.let { "__" + sanitizeLabel(it) }
            .orEmpty()
        return "$PREFIX.$segment$suffix"
    }

    private fun hostOf(url: String): String {
        val parsed = runCatching { URI(url.trim()) }.getOrNull()

        // getHost() enforces RFC 2396's hostname grammar and returns null for
        // perfectly reachable names whose TLD starts with a digit, so fall back
        // to the raw authority. The last fallback covers a scheme-less "host/path".
        val raw = parsed?.host
            ?: parsed?.authority
            ?: parsed?.path?.substringBefore('/')

        return raw.orEmpty()
            .substringAfterLast('@') // userinfo
            .stripPort()
            .lowercase(Locale.ROOT)
            .removePrefix("www.")
    }

    /** Drops a trailing `:port`, leaving bracketed IPv6 literals intact. */
    private fun String.stripPort(): String = when {
        startsWith("[") -> substringBefore(']').removePrefix("[")
        else -> substringBefore(':')
    }

    /**
     * Reduces one host label to lowercase ASCII alphanumerics and underscores.
     * Hyphens are common in hostnames and illegal in package segments, so they
     * fold to underscores here. The restriction to ASCII is deliberate: a
     * manifest package name admits no other letters, so an internationalised
     * host has to fold rather than pass through.
     */
    private fun sanitizeLabel(raw: String): String =
        buildString {
            for (character in raw.lowercase(Locale.ROOT)) {
                append(if (character.isAsciiAlphanumeric() || character == '_') character else '_')
            }
        }.trim('_').ifEmpty { "site" }

    private fun Char.isAsciiAlphanumeric(): Boolean =
        this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /**
     * Applies the rules that govern a whole package segment.
     *
     * Android is stricter than Java here: a manifest package segment must *start*
     * with a letter, so a digit-leading segment cannot be escaped with a leading
     * underscore the way a Java identifier can. Reversing puts the TLD first, so
     * this is the ordinary case for an IP-address URL, not a curiosity.
     */
    private fun legalise(segment: String): String {
        val safe = if (segment.first() in 'a'..'z') segment else "site_$segment"
        return if (safe in JAVA_KEYWORDS) "${safe}_" else safe
    }
}
