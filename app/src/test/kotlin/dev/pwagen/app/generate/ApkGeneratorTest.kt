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

import dev.pwagen.config.Capabilities
import dev.pwagen.config.DomainRuleMode
import dev.pwagen.config.DomainRules
import dev.pwagen.config.NetworkSecurity
import dev.pwagen.config.PwaConfig
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Drives the real generation pipeline and checks its output with the same
 * build-tools binaries Android itself ships.
 *
 * This is the project's main safety net. The pipeline rewrites binary manifests
 * and signs archives, where a mistake produces a file that looks fine and then
 * fails at install time on a real phone, so nothing here trusts the library's
 * word for it: every assertion reads the finished APK back.
 */
class ApkGeneratorTest {

    // ---------------------------------------------------------------- manifest

    @Test
    fun `rewrites the package name`() {
        assertEquals(config().packageName, badging(apk)["package"])
    }

    @Test
    fun `rewrites the application label`() {
        assertEquals("Test Dashboard", badging(apk)["label"])
    }

    @Test
    fun `carries the version code through`() {
        assertEquals("7", badging(apk)["versionCode"])
    }

    @Test
    fun `keeps the activity resolvable after the package is rewritten`() {
        // The template names MainActivity fully-qualified precisely so that it
        // still resolves once the package changes; if that ever regresses the
        // generated app installs and then dies on launch.
        assertEquals("dev.pwagen.shell.MainActivity", badging(apk)["activity"])
    }

    // ------------------------------------------------------------- permissions

    @Test
    fun `grants internet, which every PWA needs`() {
        assertTrue("android.permission.INTERNET" in permissions(apk))
    }

    @Test
    fun `omits permissions for capabilities not opted into`() {
        // Absent, not merely unused: the manifest is the enforcement point.
        val granted = permissions(apk)
        assertFalse("android.permission.CAMERA" in granted)
        assertFalse("android.permission.RECORD_AUDIO" in granted)
        assertFalse("android.permission.ACCESS_FINE_LOCATION" in granted)
        assertFalse("android.permission.POST_NOTIFICATIONS" in granted)
    }

    @Test
    fun `adds only the permissions a capability requires`() {
        val cameraOnly = generate(
            config(
                packageName = "dev.pwagen.pwa.test_camera",
                capabilities = Capabilities(camera = true),
            ),
            into = "camera",
        )
        val granted = permissions(cameraOnly)

        assertTrue("android.permission.CAMERA" in granted)
        assertFalse(
            "enabling the camera must not drag in the microphone",
            "android.permission.RECORD_AUDIO" in granted,
        )
    }

    @Test
    fun `location capability adds both precision levels`() {
        val located = generate(
            config(
                packageName = "dev.pwagen.pwa.test_location",
                capabilities = Capabilities(location = true),
            ),
            into = "location",
        )
        val granted = permissions(located)

        assertTrue("android.permission.ACCESS_FINE_LOCATION" in granted)
        assertTrue("android.permission.ACCESS_COARSE_LOCATION" in granted)
    }

    // ------------------------------------------------------- transport security

    @Test
    fun `defaults to HTTPS and the system CAs`() {
        assertEquals("false", applicationAttribute(apk, "usesCleartextTraffic"))

        val policy = networkSecurityConfig(apk)
        assertTrue(
            "plaintext should be refused by default. Config said:\n$policy",
            "cleartextTrafficPermitted=false" in policy,
        )
        assertEquals(setOf("system"), trustAnchorsOf(policy))
    }

    @Test
    fun `allowing plaintext moves both the flag and the config`() {
        // The two must agree. The config is what the platform enforces; the flag
        // is what anything reading the manifest coarsely reports about the app.
        val plaintext = generate(
            config(
                packageName = "dev.pwagen.pwa.test_cleartext",
                network = NetworkSecurity(cleartext = true),
            ),
            into = "cleartext",
        )

        assertEquals("true", applicationAttribute(plaintext, "usesCleartextTraffic"))

        val policy = networkSecurityConfig(plaintext)
        assertTrue(
            "plaintext should be permitted. Config said:\n$policy",
            "cleartextTrafficPermitted=true" in policy,
        )
        assertEquals(
            "allowing plaintext must not disturb the trust anchors",
            setOf("system"),
            trustAnchorsOf(policy),
        )
    }

    @Test
    fun `trusting user CAs keeps the system set alongside them`() {
        val policy = networkSecurityConfig(
            generate(
                config(
                    packageName = "dev.pwagen.pwa.test_user_ca",
                    network = NetworkSecurity(trustUserCas = true),
                ),
                into = "user-ca",
            ),
        )

        assertEquals(setOf("user", "system"), trustAnchorsOf(policy))
    }

    @Test
    fun `excluding the system CAs leaves only the user set`() {
        val policy = networkSecurityConfig(
            generate(
                config(
                    packageName = "dev.pwagen.pwa.test_user_only_ca",
                    network = NetworkSecurity(trustUserCas = true, trustSystemCas = false),
                ),
                into = "user-only-ca",
            ),
        )

        assertEquals(setOf("user"), trustAnchorsOf(policy))
    }

    @Test
    fun `every plaintext and trust anchor combination resolves to a shipped config`() {
        // The generator names the resource by rule rather than picking it from a
        // list, so a combination with no file behind it would surface only when
        // some user happened to select it. Walk all six here instead.
        val expected = listOf(
            NetworkSecurity() to
                ("false" to setOf("system")),
            NetworkSecurity(trustUserCas = true) to
                ("false" to setOf("user", "system")),
            NetworkSecurity(trustUserCas = true, trustSystemCas = false) to
                ("false" to setOf("user")),
            NetworkSecurity(cleartext = true) to
                ("true" to setOf("system")),
            NetworkSecurity(cleartext = true, trustUserCas = true) to
                ("true" to setOf("user", "system")),
            NetworkSecurity(cleartext = true, trustUserCas = true, trustSystemCas = false) to
                ("true" to setOf("user")),
        )

        expected.forEachIndexed { index, (network, wanted) ->
            val (cleartext, anchors) = wanted
            val policy = networkSecurityConfig(
                generate(
                    config(
                        packageName = "dev.pwagen.pwa.test_nsc_$index",
                        network = network,
                    ),
                    into = "nsc-$index",
                ),
            )

            assertTrue(
                "$network should permit cleartext=$cleartext. Config said:\n$policy",
                "cleartextTrafficPermitted=$cleartext" in policy,
            )
            assertEquals("wrong trust anchors for $network", anchors, trustAnchorsOf(policy))
        }
    }

    @Test
    fun `trusting nothing falls back to the system CAs rather than to no anchors`() {
        // Only reachable from a hand-edited config: the editor cannot build it.
        // An app with no trust anchors at all would fail every HTTPS connection.
        val policy = networkSecurityConfig(
            generate(
                config(
                    packageName = "dev.pwagen.pwa.test_no_ca",
                    network = NetworkSecurity(trustUserCas = false, trustSystemCas = false),
                ),
                into = "no-ca",
            ),
        )

        assertEquals(setOf("system"), trustAnchorsOf(policy))
    }

    @Test
    fun `opts out of WebView metrics`() {
        // Declared in the template rather than offered as a setting, so it has
        // to survive the rewrite for every generated app.
        val tree = xmlTree(apk, "AndroidManifest.xml")
        val optOut = tree.lines()
            .zipWithNext()
            .firstOrNull { (name, _) -> "android.webkit.WebView.MetricsOptOut" in name }

        assertTrue(
            "no WebView.MetricsOptOut meta-data. Manifest said:\n$tree",
            optOut != null && Regex(""":value\(0x[0-9a-f]+\)=true""").containsMatchIn(optOut.second),
        )
    }

    // ------------------------------------------------------------------ config

    @Test
    fun `embeds the config where the shell looks for it`() {
        val embedded = ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry("assets/${PwaConfig.ASSET_PATH}")) {
                "generated APK has no assets/${PwaConfig.ASSET_PATH}"
            }
            zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
        }

        val decoded = PwaConfig.decode(embedded)
        assertEquals("https://dashboard.example.com/", decoded.url)
        assertEquals(DomainRuleMode.ALLOWLIST, decoded.domainRules.mode)
        assertTrue("dashboard.example.com" in decoded.domainRules.patterns)
    }

    // ------------------------------------------------------------------- icons

    @Test
    fun `replaces the icon at every density the template ships`() {
        val icons = badgingLines(apk)
            .filter { it.startsWith("application-icon-") }
            .map { it.substringAfter(":").trim('\'') }

        assertEquals("expected one icon per density bucket", 5, icons.size)

        // Each density gets its own render, so no two icon files should be
        // byte-identical; a single image copied five times would mean the
        // density lookup silently collapsed.
        val digests = ZipFile(apk).use { zip ->
            icons.map { path ->
                zip.getInputStream(zip.getEntry(path)).readBytes().toList()
            }
        }
        assertEquals("icons were not rendered per density", digests.size, digests.toSet().size)
    }

    // --------------------------------------------------------- packaging traps

    @Test
    fun `keeps resources_arsc uncompressed`() {
        // Compressing resources.arsc makes installation fail outright on
        // targetSdk 30+, and the failure only shows up on a real device.
        val entry = ZipFile(apk).use { it.getEntry("resources.arsc") }
        assertEquals(
            "resources.arsc must be STORED",
            java.util.zip.ZipEntry.STORED,
            entry.method,
        )
    }

    @Test
    fun `is correctly zipaligned`() {
        val result = run(buildTool("zipalign"), "-c", "-v", "4", apk.absolutePath)
        assertEquals("zipalign reported misalignment:\n${result.output}", 0, result.exitCode)
    }

    // --------------------------------------------------------------- signature

    @Test
    fun `produces a signature Android will accept`() {
        val result = run(
            buildTool("apksigner"), "verify", "--min-sdk-version", "30",
            "--print-certs", apk.absolutePath,
        )
        assertEquals("apksigner rejected the APK:\n${result.output}", 0, result.exitCode)
    }

    @Test
    fun `signs with v3, and omits the schemes that minSdk 30 makes redundant`() {
        val output = run(
            buildTool("apksigner"), "verify", "--min-sdk-version", "30",
            "-v", apk.absolutePath,
        ).output

        // "Verified using vN scheme (...): true|false" -> { "1" to false, ... }
        val schemes = Regex("""Verified using v([\d.]+) scheme[^:]*:\s*(true|false)""")
            .findAll(output)
            .associate { it.groupValues[1] to it.groupValues[2].toBoolean() }

        // v3 is the operative scheme: supported since API 28, well below our
        // minSdk of 30.
        assertEquals("v3 should be signed. apksigner said:\n$output", true, schemes["3"])

        // v1 (JAR signing) and v2 both exist for platforms older than we target,
        // so apksig leaves them out. Asserting their absence keeps the archive
        // honest about what it actually relies on.
        assertEquals("v1 should be off. apksigner said:\n$output", false, schemes["1"])
        assertEquals(
            "v2 is redundant above API 28; apksig drops it. apksigner said:\n$output",
            false,
            schemes["2"],
        )
    }

    // ------------------------------------------------------------- determinism

    @Test
    fun `regenerating the same config keeps the same package name`() {
        // This is what makes a restored backup land as an in-place upgrade
        // rather than a duplicate app beside the original: same package, higher
        // version, so Android replaces it and the PWA keeps its cookies.
        val again = generate(config(versionCode = 8), into = "regenerated")

        assertEquals(badging(apk)["package"], badging(again)["package"])
        assertEquals("7", badging(apk)["versionCode"])
        assertEquals("8", badging(again)["versionCode"])
    }

    // --------------------------------------------------------------- machinery

    private fun config(
        packageName: String = "dev.pwagen.pwa.com_example_dashboard",
        versionCode: Int = 7,
        capabilities: Capabilities = Capabilities(),
        network: NetworkSecurity = NetworkSecurity(),
    ) = PwaConfig(
        url = "https://dashboard.example.com/",
        label = "Test Dashboard",
        packageName = packageName,
        scope = "https://dashboard.example.com/",
        themeColor = "#181b1f",
        capabilities = capabilities,
        network = network,
        domainRules = DomainRules(
            DomainRuleMode.ALLOWLIST,
            DomainRules.seedFor("dashboard.example.com"),
        ),
        versionCode = versionCode,
    )

    /**
     * Each call gets its own directory. Generated APKs are named after their
     * package, which is deterministic by design, so two generations of the same
     * config would otherwise overwrite one another.
     */
    private fun generate(config: PwaConfig, into: String = "default"): File =
        generator.generate(
            config,
            TestIconRenderer,
            TestSigningKey,
            File(workDirectory, into),
        )

    private data class Execution(val exitCode: Int, val output: String)

    private fun run(vararg command: String): Execution {
        val builder = ProcessBuilder(*command).redirectErrorStream(true)

        // apksigner is a shell wrapper that shells out to `java`, and this
        // project builds against Android Studio's bundled JBR with no system JDK
        // on PATH. Hand the subprocess the JVM already running these tests.
        val javaHome = System.getProperty("java.home")
        builder.environment()["JAVA_HOME"] = javaHome
        builder.environment()["PATH"] = listOfNotNull(
            File(javaHome, "bin").absolutePath,
            System.getenv("PATH"),
        ).joinToString(File.pathSeparator)

        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        return Execution(process.waitFor(), output)
    }

    private fun buildTool(name: String): String {
        val path = File(buildToolsDirectory, name)
        check(path.exists()) { "$name not found at $path" }
        return path.absolutePath
    }

    private fun badgingLines(apk: File): List<String> =
        run(buildTool("aapt2"), "dump", "badging", apk.absolutePath).output.lines()

    /** Pulls the handful of badging fields the assertions care about. */
    private fun badging(apk: File): Map<String, String> {
        val lines = badgingLines(apk)
        val packageLine = lines.first { it.startsWith("package:") }

        fun field(source: String, key: String) =
            Regex("$key='([^']*)'").find(source)?.groupValues?.get(1)

        return buildMap {
            field(packageLine, "name")?.let { put("package", it) }
            field(packageLine, "versionCode")?.let { put("versionCode", it) }
            lines.firstOrNull { it.startsWith("application-label:") }
                ?.let { put("label", it.substringAfter(":").trim('\'')) }
            lines.firstOrNull { it.startsWith("launchable-activity:") }
                ?.let { line -> field(line, "name")?.let { put("activity", it) } }
        }
    }

    private fun permissions(apk: File): Set<String> =
        badgingLines(apk)
            .filter { it.startsWith("uses-permission:") }
            .mapNotNull { Regex("name='([^']*)'").find(it)?.groupValues?.get(1) }
            .toSet()

    private fun xmlTree(apk: File, path: String): String =
        run(buildTool("aapt2"), "dump", "xmltree", "--file", path, apk.absolutePath).output

    /**
     * Value of an `android:`-namespaced attribute on the manifest's
     * `<application>` element, as aapt2 renders it: `false`, `36`, `@0x7f030000`.
     * Null when the attribute is absent.
     */
    private fun applicationAttribute(apk: File, name: String): String? {
        val lines = xmlTree(apk, "AndroidManifest.xml").lines()
        val application = lines.indexOfFirst { it.trim().startsWith("E: application ") }
        check(application >= 0) { "no <application> element in the generated manifest" }

        return lines.asSequence()
            .drop(application + 1)
            .takeWhile { it.trim().startsWith("A: ") }
            .firstNotNullOfOrNull {
                Regex("""\bandroid:$name\(0x[0-9a-f]+\)=(\S+)""").find(it)?.groupValues?.get(1)
            }
    }

    /**
     * The network security config the generated manifest actually points at,
     * dumped as text.
     *
     * Read through the resource table rather than by guessing a path: the
     * template is built with resource shrinking, so `res/xml/nsc_system.xml`
     * ships as something like `res/30.xml` and the manifest's reference is the
     * only honest way back to the file.
     */
    private fun networkSecurityConfig(apk: File): String {
        val reference = requireNotNull(applicationAttribute(apk, "networkSecurityConfig")) {
            "generated manifest has no android:networkSecurityConfig"
        }
        return xmlTree(apk, resourceFile(apk, reference.removePrefix("@")))
    }

    /** Path inside [apk] of the file resource [id] resolves to. */
    private fun resourceFile(apk: File, id: String): String {
        val lines = run(buildTool("aapt2"), "dump", "resources", apk.absolutePath).output.lines()
        val entry = lines.indexOfFirst { it.trim().startsWith("resource $id ") }
        check(entry >= 0) { "no resource $id in the generated APK" }

        return lines.asSequence()
            .drop(entry + 1)
            .takeWhile { !it.trim().startsWith("resource ") }
            .firstNotNullOfOrNull { Regex("""\(file\) (\S+)""").find(it)?.groupValues?.get(1) }
            ?: error("resource $id is not backed by a file")
    }

    /** The `src` of every `<certificates>` in a network security config dump. */
    private fun trustAnchorsOf(policy: String): Set<String> =
        Regex("""src="([^"]*)"""").findAll(policy).map { it.groupValues[1] }.toSet()

    companion object {
        private lateinit var generator: ApkGenerator
        private lateinit var workDirectory: File
        private lateinit var buildToolsDirectory: File
        private lateinit var apk: File

        @BeforeClass
        @JvmStatic
        fun generateOnce() {
            val templatePath = requireNotNull(System.getProperty("pwagen.template.apk")) {
                "pwagen.template.apk not set; the test task should supply it"
            }
            buildToolsDirectory = File(
                requireNotNull(System.getProperty("pwagen.buildTools")) {
                    "pwagen.buildTools not set; the test task should supply it"
                },
            )

            workDirectory = File(System.getProperty("java.io.tmpdir"), "pwagen-test-${System.nanoTime()}")
            generator = ApkGenerator(File(templatePath).readBytes())

            apk = ApkGeneratorTest().generate(ApkGeneratorTest().config())
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            workDirectory.deleteRecursively()
        }
    }
}
