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

package dev.pwagen.config

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
enum class DomainRuleMode {
    /** Every request is allowed through. */
    OFF,

    /** Only hosts matching a pattern are allowed; everything else is refused. */
    ALLOWLIST,

    /** Hosts matching a pattern are refused; everything else is allowed. */
    BLOCKLIST,
}

/**
 * Per-PWA domain filtering, applied to *every* request the WebView makes —
 * navigations, images, scripts, XHR and fetch alike — not just top-level
 * navigation.
 *
 * This sits underneath whatever per-app firewall is in use rather than
 * replacing it. A firewall sees the app's UID and can stop it talking to a host
 * at all; these rules work inside the app, where the origin of each individual
 * request is still known.
 *
 * ### Pattern syntax
 *
 * Patterns are matched against the request's hostname, case-insensitively.
 * `*` is the only metacharacter and matches any run of characters, dots
 * included:
 *
 * | Pattern             | Matches                                    | Does not match  |
 * |---------------------|--------------------------------------------|-----------------|
 * | `example.com`       | `example.com`                              | `a.example.com` |
 * | `*.example.com`     | `a.example.com`, `a.b.example.com`         | `example.com`   |
 * | `*`                 | everything                                 | —               |
 *
 * To cover a domain and its subdomains, list both `example.com` and
 * `*.example.com`.
 *
 * ### Deliberate omissions
 *
 * There are no implicit rules. In [DomainRuleMode.ALLOWLIST] an empty
 * [patterns] blocks every request including the PWA's own site, so the
 * generator seeds the list with the site's host when the mode is first turned
 * on. Keeping the runtime free of hidden exceptions means what you see in the
 * config is exactly what is enforced.
 */
@Serializable
data class DomainRules(
    val mode: DomainRuleMode = DomainRuleMode.OFF,
    val patterns: List<String> = emptyList(),
) {
    companion object {
        /** Patterns covering [host] and everything beneath it. */
        fun seedFor(host: String): List<String> = listOf(host, "*.$host")
    }
}

/**
 * A [DomainRules] compiled for repeated matching.
 *
 * Requests are intercepted on a WebView worker thread and a busy page issues
 * hundreds, so the patterns are turned into regexes once here rather than on
 * every request.
 */
class DomainMatcher private constructor(
    private val mode: DomainRuleMode,
    private val patterns: List<Regex>,
) {
    /**
     * @param host the request's hostname, or null for a scheme that has none.
     * @return whether the request may proceed.
     */
    fun allows(host: String?): Boolean {
        if (mode == DomainRuleMode.OFF) return true

        // Schemes such as data:, blob: and about: carry no host and never leave
        // the device, so they are not the filter's business. Blocking them would
        // break ordinary pages without withholding anything from the network.
        if (host.isNullOrBlank()) return true

        val normalised = host.lowercase(Locale.ROOT)
        val matched = patterns.any { it.matches(normalised) }

        return when (mode) {
            DomainRuleMode.ALLOWLIST -> matched
            DomainRuleMode.BLOCKLIST -> !matched
            DomainRuleMode.OFF -> true
        }
    }

    companion object {
        fun of(rules: DomainRules): DomainMatcher =
            DomainMatcher(rules.mode, rules.patterns.mapNotNull(::compile))

        /**
         * Escapes everything except `*`, which becomes `.*`. Blank patterns are
         * dropped so a stray empty line cannot silently match nothing (in an
         * allowlist) or everything (in a blocklist).
         */
        private fun compile(pattern: String): Regex? {
            val trimmed = pattern.trim().lowercase(Locale.ROOT)
            if (trimmed.isEmpty()) return null

            val expression = trimmed
                .split('*')
                .joinToString(".*") { Regex.escape(it) }

            return Regex("^$expression$")
        }
    }
}
