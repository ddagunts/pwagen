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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNamingTest {

    @Test
    fun `reverses host into package ordering`() {
        assertEquals(
            "dev.pwagen.pwa.com_example_grafana",
            PackageNaming.derive("https://grafana.example.com/d/home"),
        )
    }

    @Test
    fun `is deterministic so a restored backup reproduces identical names`() {
        val url = "https://element.example.org/"
        assertEquals(PackageNaming.derive(url), PackageNaming.derive(url))
    }

    @Test
    fun `ignores path, port and query`() {
        val base = PackageNaming.derive("https://grafana.lan")
        assertEquals(base, PackageNaming.derive("https://grafana.lan:3000/d/x?y=1"))
    }

    @Test
    fun `strips www so it does not become a package segment`() {
        assertEquals(
            "dev.pwagen.pwa.org_wikipedia",
            PackageNaming.derive("https://www.wikipedia.org"),
        )
    }

    @Test
    fun `discriminator separates two apps on one host`() {
        val host = "https://element.example.org"
        val work = PackageNaming.derive(host, "work")
        val personal = PackageNaming.derive(host, "personal")

        assertNotEquals(work, personal)
        assertTrue(work.endsWith("__work"))
    }

    @Test
    fun `sanitises hyphens which are illegal in package segments`() {
        val derived = PackageNaming.derive("https://my-dashboard.example.com")
        assertEquals("dev.pwagen.pwa.com_example_my_dashboard", derived)
    }

    @Test
    fun `escapes a segment that would start with a digit`() {
        // Reversing puts the TLD first, so a numeric-leading TLD leads the segment.
        // Android rejects a leading underscore, so the escape has to be a letter.
        assertEquals(
            "dev.pwagen.pwa.site_3com_example",
            PackageNaming.derive("https://example.3com"),
        )
    }

    @Test
    fun `escapes an IP address, whose reversed form always starts with a digit`() {
        assertEquals(
            "dev.pwagen.pwa.site_5_1_168_192",
            PackageNaming.derive("https://192.168.1.5:8443/grafana"),
        )
    }

    @Test
    fun `folds non-ASCII letters, which a manifest package cannot carry`() {
        assertEquals(
            "dev.pwagen.pwa.de_m_nchen",
            PackageNaming.derive("https://münchen.de"),
        )
    }

    @Test
    fun `a digit inside the segment needs no escaping`() {
        assertEquals(
            "dev.pwagen.pwa.com_example_3d",
            PackageNaming.derive("https://3d.example.com"),
        )
    }

    @Test
    fun `escapes a single-label host that is a java keyword`() {
        // Only the joined segment can collide with a keyword; "com_example_public"
        // cannot, but a bare "public" can.
        assertEquals("dev.pwagen.pwa.public_", PackageNaming.derive("https://public"))
    }

    @Test
    fun `does not escape a keyword appearing as one label among several`() {
        assertEquals(
            "dev.pwagen.pwa.com_example_public",
            PackageNaming.derive("https://public.example.com"),
        )
    }

    @Test
    fun `every produced name is a legal manifest package name`() {
        val urls = listOf(
            "https://grafana.lan",
            "https://my-dashboard.example.com:8443/x",
            "https://3d.example.com",
            "https://example.3com",
            "https://public",
            "https://public.example.com",
            "https://www.wikipedia.org",
            "https://192.168.1.5:8443/grafana",
            "https://[fe80::1]/x",
            "https://münchen.de",
            "https://user:pw@10.0.0.1:9090",
        )
        // Android's own rule, from FrameworkParsingPackageUtils.validateName: every
        // segment starts with an ASCII letter, and at least one separator. It is
        // stricter than Java's, which is what INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME
        // reports when a segment leads with an underscore or a digit.
        val legal = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        for (url in urls) {
            for (discriminator in listOf("", "work", "2nd account")) {
                val derived = PackageNaming.derive(url, discriminator)
                assertTrue("illegal package name from $url: $derived", legal.matches(derived))
            }
        }
    }

    @Test
    fun `rejects a URL with no host`() {
        assertThrows(IllegalArgumentException::class.java) {
            PackageNaming.derive("not a url")
        }
    }
}
