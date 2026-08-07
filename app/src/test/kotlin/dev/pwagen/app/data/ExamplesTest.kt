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

import dev.pwagen.app.PackageNaming
import dev.pwagen.config.Capabilities
import dev.pwagen.config.DomainMatcher
import dev.pwagen.config.NetworkSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * An example is offered as a known-good starting point, so a typo in one is
 * worse than an ordinary bug: it would ship as advice. These check the two ways
 * that could go wrong quietly — an app that cannot reach its own site, and an
 * example that grants something it claims not to.
 */
class ExamplesTest {

    @Test
    fun `every example can reach its own site`() {
        for (example in Examples.ALL) {
            val host = URI(example.url).host
            assertTrue(
                "${example.label} blocks its own host $host",
                DomainMatcher.of(example.domainRules).allows(host),
            )
        }
    }

    @Test
    fun `every example yields a usable package name`() {
        for (example in Examples.ALL) {
            assertTrue(PackageNaming.derive(example.url).isNotBlank())
        }
    }

    @Test
    fun `wikipedia grants nothing and hardens nothing away`() {
        assertEquals(Capabilities(), Examples.WIKIPEDIA.capabilities)
        assertEquals(NetworkSecurity(), Examples.WIKIPEDIA.network)
        assertFalse(Examples.WIKIPEDIA.capabilities.safeBrowsing)
    }

    @Test
    fun `wikipedia allows wikipedia and wikimedia hosts and nothing else`() {
        val matcher = DomainMatcher.of(Examples.WIKIPEDIA.domainRules)

        for (host in listOf(
            "wikipedia.org",
            "en.wikipedia.org",
            "en.m.wikipedia.org",
            "upload.wikimedia.org",
            "meta.wikimedia.org",
            "wikimedia.org",
        )) {
            assertTrue("$host should be allowed", matcher.allows(host))
        }

        for (host in listOf(
            "example.com",
            "wikipedia.org.example.com",
            "notwikipedia.org",
        )) {
            assertFalse("$host should be blocked", matcher.allows(host))
        }
    }
}
