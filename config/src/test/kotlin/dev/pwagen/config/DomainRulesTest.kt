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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainRulesTest {

    private fun matcher(mode: DomainRuleMode, vararg patterns: String) =
        DomainMatcher.of(DomainRules(mode, patterns.toList()))

    @Test
    fun `off allows everything`() {
        val subject = matcher(DomainRuleMode.OFF)
        assertTrue(subject.allows("anything.example.com"))
        assertTrue(subject.allows("tracker.invalid"))
    }

    @Test
    fun `allowlist admits only listed hosts`() {
        val subject = matcher(DomainRuleMode.ALLOWLIST, "example.com")
        assertTrue(subject.allows("example.com"))
        assertFalse(subject.allows("tracker.invalid"))
    }

    @Test
    fun `blocklist refuses only listed hosts`() {
        val subject = matcher(DomainRuleMode.BLOCKLIST, "tracker.invalid")
        assertFalse(subject.allows("tracker.invalid"))
        assertTrue(subject.allows("example.com"))
    }

    @Test
    fun `exact pattern does not match subdomains`() {
        val subject = matcher(DomainRuleMode.ALLOWLIST, "example.com")
        assertFalse(subject.allows("cdn.example.com"))
    }

    @Test
    fun `wildcard matches subdomains at any depth`() {
        val subject = matcher(DomainRuleMode.ALLOWLIST, "*.example.com")
        assertTrue(subject.allows("cdn.example.com"))
        assertTrue(subject.allows("a.b.c.example.com"))
    }

    @Test
    fun `leading wildcard does not match the apex`() {
        val subject = matcher(DomainRuleMode.ALLOWLIST, "*.example.com")
        assertFalse(
            "listing both the apex and the wildcard is required to cover it",
            subject.allows("example.com"),
        )
    }

    @Test
    fun `seedFor covers apex and subdomains together`() {
        val subject = DomainMatcher.of(
            DomainRules(DomainRuleMode.ALLOWLIST, DomainRules.seedFor("example.com")),
        )
        assertTrue(subject.allows("example.com"))
        assertTrue(subject.allows("cdn.example.com"))
        assertFalse(subject.allows("example.com.invalid"))
    }

    @Test
    fun `bare wildcard matches everything`() {
        val subject = matcher(DomainRuleMode.ALLOWLIST, "*")
        assertTrue(subject.allows("anything.invalid"))
    }

    @Test
    fun `dots are literal, not regex wildcards`() {
        val subject = matcher(DomainRuleMode.ALLOWLIST, "example.com")
        assertFalse("a dot must not match an arbitrary character", subject.allows("examplexcom"))
    }

    @Test
    fun `matching is case insensitive`() {
        val subject = matcher(DomainRuleMode.ALLOWLIST, "Example.COM")
        assertTrue(subject.allows("EXAMPLE.com"))
    }

    @Test
    fun `patterns are trimmed and blank entries ignored`() {
        val subject = matcher(DomainRuleMode.ALLOWLIST, "  example.com  ", "", "   ")
        assertTrue(subject.allows("example.com"))
        assertFalse("a blank entry must not become a match-all", subject.allows("other.invalid"))
    }

    @Test
    fun `empty allowlist blocks everything`() {
        val subject = matcher(DomainRuleMode.ALLOWLIST)
        assertFalse(subject.allows("example.com"))
    }

    @Test
    fun `empty blocklist allows everything`() {
        val subject = matcher(DomainRuleMode.BLOCKLIST)
        assertTrue(subject.allows("example.com"))
    }

    @Test
    fun `hostless schemes bypass the rules`() {
        // data:, blob: and about: never reach the network, and blocking them
        // would break ordinary pages.
        val subject = matcher(DomainRuleMode.ALLOWLIST, "example.com")
        assertTrue(subject.allows(null))
        assertTrue(subject.allows(""))
    }

    @Test
    fun `mid-pattern wildcard works`() {
        val subject = matcher(DomainRuleMode.BLOCKLIST, "ads.*.example.com")
        assertFalse(subject.allows("ads.eu.example.com"))
        assertTrue(subject.allows("cdn.eu.example.com"))
    }

    @Test
    fun `regex metacharacters in a pattern are literal`() {
        val subject = matcher(DomainRuleMode.BLOCKLIST, "a+b.example.com")
        assertFalse(subject.allows("a+b.example.com"))
        assertTrue("'+' must not be treated as a quantifier", subject.allows("aab.example.com"))
    }

    @Test
    fun `round trips through serialization`() {
        val original = PwaConfig(
            url = "https://example.com/",
            label = "Example",
            packageName = "dev.pwagen.pwa.com_example",
            scope = "https://example.com/",
            domainRules = DomainRules(
                DomainRuleMode.ALLOWLIST,
                listOf("example.com", "*.example.com"),
            ),
        )
        val restored = PwaConfig.decode(original.encode())
        assertTrue(restored == original)
    }
}
