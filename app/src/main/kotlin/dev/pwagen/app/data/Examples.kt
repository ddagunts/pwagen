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

import dev.pwagen.config.Capabilities
import dev.pwagen.config.DomainRuleMode
import dev.pwagen.config.DomainRules
import dev.pwagen.config.NetworkSecurity
import dev.pwagen.config.OffScopePolicy

/**
 * A ready-made definition the editor can fill itself in from.
 *
 * Deliberately not a [dev.pwagen.config.PwaConfig]: an example carries no
 * package name, because that is derived from the URL when the app is saved and
 * must stay derived — a stored one would survive the user editing the URL.
 *
 * Every field the editor sets from an example is present here, defaults
 * included. Applying one therefore overwrites the form completely rather than
 * leaving whatever the user had already toggled: an example described as
 * granting nothing has to arrive granting nothing, whatever preceded it.
 */
data class Example(
    val label: String,
    val url: String,
    val themeColor: String,
    val note: String,
    val capabilities: Capabilities = Capabilities(),
    val network: NetworkSecurity = NetworkSecurity(),
    val domainRules: DomainRules = DomainRules(),
    val offScopePolicy: OffScopePolicy = OffScopePolicy.EXTERNAL,
)

/** The examples offered when adding a web app. */
object Examples {

    /**
     * A locked-down Wikipedia reader.
     *
     * Points at the mobile host rather than `en.wikipedia.org` on purpose. The
     * scope is derived from this URL, and the desktop host redirects a phone to
     * `en.m.wikipedia.org` — which, being outside the scope it just derived,
     * would hand the very first load to the system browser.
     *
     * The allowlist covers Wikipedia and Wikimedia both: the article text comes
     * from one and every image, style sheet and script from the other. Nothing
     * else is reachable, which for a reference app is the whole point — there is
     * no login to keep, no upload to make, and so nothing an outbound request
     * elsewhere could legitimately be for.
     */
    val WIKIPEDIA = Example(
        label = "Wikipedia",
        url = "https://en.m.wikipedia.org/",
        // The mobile site's own toolbar colour, so the strip left for the
        // system bars looks like part of the page rather than a border.
        themeColor = "#eaecf0",
        note = "No permissions, no Safe Browsing, and every request confined to " +
            "Wikipedia and Wikimedia hosts.",
        // Spelled out rather than left implicit: this is the example's point.
        capabilities = Capabilities(),
        network = NetworkSecurity(),
        domainRules = DomainRules(
            mode = DomainRuleMode.ALLOWLIST,
            patterns = DomainRules.seedFor("wikipedia.org") +
                DomainRules.seedFor("wikimedia.org"),
            // On by default here, unlike a hand-made app: an example exists to
            // be looked at, and a rule you can watch refuse things teaches more
            // than one that works silently.
            announceBlocks = true,
        ),
    )

    val ALL: List<Example> = listOf(WIKIPEDIA)
}
