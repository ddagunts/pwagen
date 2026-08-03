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

import com.android.apksig.ApkSigner
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import dev.pwagen.config.Capabilities
import dev.pwagen.config.NetworkSecurity
import dev.pwagen.config.PwaConfig
import dev.pwagen.config.TrustAnchors
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Renders the launcher icon at a requested pixel size.
 *
 * Abstracted so the pipeline stays free of Android imaging APIs and can
 * therefore be exercised end-to-end on the desktop JVM, which is where the
 * generated APKs are checked against `aapt2` and `apksigner`.
 */
fun interface IconRenderer {
    /** @return PNG bytes, [sizePx] by [sizePx]. */
    fun render(sizePx: Int): ByteArray
}

/**
 * The key a generated APK is signed with.
 *
 * On device this wraps a non-exportable AndroidKeyStore entry: apksig only ever
 * calls `Signature.initSign(privateKey)`, so the private key stays inside the
 * TEE and never enters app memory. Tests supply an ordinary software key.
 */
interface SigningKey {
    val privateKey: PrivateKey
    val certificates: List<X509Certificate>
}

/** Anything that went wrong while building a PWA's APK. */
class GenerationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Turns the bundled shell template into a signed, installable APK for one PWA.
 *
 * Only four things vary between generated APKs: the manifest, the launcher icon
 * files, `assets/config.json`, and the signature. `classes.dex` is byte-identical
 * every time, because the template's manifest names its activity by fully
 * qualified name and so does not depend on the package it is rewritten to.
 */
class ApkGenerator(private val templateBytes: ByteArray) {

    /**
     * @param workDirectory scratch space for the unsigned intermediate; the
     *   caller owns cleanup.
     * @return the signed APK, ready to hand to `PackageInstaller`.
     */
    fun generate(
        config: PwaConfig,
        icon: IconRenderer,
        signingKey: SigningKey,
        workDirectory: File,
    ): File {
        workDirectory.mkdirs()
        val unsigned = File(workDirectory, "${config.packageName}-unsigned.apk")
        val signed = File(workDirectory, "${config.packageName}.apk")

        try {
            ApkModule.readApkBytes(templateBytes).use { module ->
                module.setPackageName(config.packageName)

                val manifest = module.androidManifest
                manifest.setApplicationLabel(config.label)
                manifest.versionCode = config.versionCode
                manifest.versionName = config.versionCode.toString()

                addOptedInPermissions(module, config.capabilities)
                applyNetworkSecurity(module, config.network)
                replaceLauncherIcons(module, icon)

                module.add(
                    ByteInputSource(
                        config.encode().toByteArray(Charsets.UTF_8),
                        "assets/${PwaConfig.ASSET_PATH}",
                    ),
                )

                module.writeApk(unsigned)
            }
        } catch (e: Exception) {
            throw GenerationException("Failed to rewrite template for ${config.packageName}", e)
        }

        sign(unsigned, signed, signingKey)
        unsigned.delete()
        return signed
    }

    /**
     * Adds only what the user opted into, on top of the template's INTERNET.
     *
     * The manifest is the enforcement point: a capability left off is not a
     * permission the app holds and declines to use, it is a permission the app
     * does not have.
     */
    private fun addOptedInPermissions(module: ApkModule, capabilities: Capabilities) {
        val manifest = module.androidManifest
        val wanted = buildList {
            if (capabilities.camera) add("android.permission.CAMERA")
            if (capabilities.microphone) add("android.permission.RECORD_AUDIO")
            if (capabilities.location) {
                add("android.permission.ACCESS_FINE_LOCATION")
                add("android.permission.ACCESS_COARSE_LOCATION")
            }
            if (capabilities.notifications) add("android.permission.POST_NOTIFICATIONS")
        }

        val existing = manifest.usesPermissions.toSet()
        for (permission in wanted) {
            if (permission !in existing) manifest.addUsesPermission(permission)
        }
    }

    /**
     * Points the manifest at the network security config matching the user's
     * plaintext and trust-anchor settings.
     *
     * Nothing is synthesized here. The template carries all six policies as real
     * `res/xml` resources, and this only moves the `android:networkSecurityConfig`
     * reference from one to another — so what a generated app enforces is a file
     * you can read in the repository, not something assembled at build time from
     * a JSON field.
     *
     * `android:usesCleartextTraffic` is set to match even though the config wins
     * wherever both apply. Leaving the two disagreeing would mean anything
     * reading the coarser flag — `aapt2 dump badging`, a reviewer skimming the
     * manifest — got the wrong answer about the app.
     */
    private fun applyNetworkSecurity(module: ApkModule, network: NetworkSecurity) {
        val application = module.androidManifest.applicationElement
            ?: throw GenerationException("Template manifest has no <application> element")

        application.attribute(ATTR_USES_CLEARTEXT_TRAFFIC, "usesCleartextTraffic")
            .setValueAsBoolean(network.cleartext)

        val name = networkSecurityConfigName(network)
        val resource = module.tableBlock.getLocalResource("xml", name)
            ?: throw GenerationException("Template ships no res/xml/$name.xml")

        application.attribute(ATTR_NETWORK_SECURITY_CONFIG, "networkSecurityConfig").apply {
            valueType = ValueType.REFERENCE
            data = resource.resourceId
        }
    }

    /**
     * Resolves an attribute the template is expected to declare already.
     *
     * By resource id first, since that is what the binary manifest actually keys
     * on and it survives any renaming of the string pool; by name as a fallback,
     * for the case where the template was built without framework attribute ids
     * resolved.
     */
    private fun ResXmlElement.attribute(
        resourceId: Int,
        name: String,
    ): ResXmlAttribute =
        searchAttributeByResourceId(resourceId)
            ?: searchAttributeByName(name)
            ?: throw GenerationException(
                "Template manifest declares no android:$name on <application>",
            )

    /**
     * Names the template resource holding [network]'s declarative policy.
     *
     * Kept mechanical on purpose: the six files in `shell/src/main/res/xml` are
     * named by exactly this rule, so a missing combination is a build-time typo
     * that the generator turns into a clear failure rather than a silently wrong
     * policy.
     */
    private fun networkSecurityConfigName(network: NetworkSecurity): String {
        val anchors = when (network.trustAnchors) {
            TrustAnchors.SYSTEM -> "system"
            TrustAnchors.USER_AND_SYSTEM -> "user_system"
            TrustAnchors.USER -> "user"
        }
        return "nsc_$anchors" + if (network.cleartext) "_cleartext" else ""
    }

    /**
     * Rewrites the launcher icon in place, at every density the template ships.
     *
     * Resolution goes through the resource table rather than hardcoded paths:
     * AGP shortens `res/` names under minification, so the template's icons are
     * really called things like `res/9w.png` and those names are not stable
     * across builds. The resource id from the manifest is.
     */
    private fun replaceLauncherIcons(module: ApkModule, icon: IconRenderer) {
        val iconResourceId = module.androidManifest.iconResourceId
        if (iconResourceId == 0) {
            throw GenerationException("Template manifest declares no android:icon")
        }

        val iconFiles = module.listResFiles().filter { resFile ->
            resFile.entryList.any { it.resourceId == iconResourceId }
        }
        if (iconFiles.isEmpty()) {
            throw GenerationException(
                "No resource files found for icon id 0x${iconResourceId.toString(16)}",
            )
        }

        for (resFile in iconFiles) {
            val density = resFile.entryList
                .firstOrNull { it.resourceId == iconResourceId }
                ?.resConfig
                ?.densityValue
                ?.takeIf { it > 0 }
                ?: DEFAULT_DENSITY

            val sizePx = ICON_DP * density / DEFAULT_DENSITY
            module.add(ByteInputSource(icon.render(sizePx), resFile.filePath))
        }
    }

    private fun sign(input: File, output: File, key: SigningKey) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "pwagen",
            key.privateKey,
            key.certificates,
        ).build()

        try {
            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(input)
                .setOutputApk(output)
                .setMinSdkVersion(MIN_SDK)
                // v1 (JAR signing) is for platforms far below our minSdk and
                // only bloats the archive. v2 stays nominally enabled, but
                // apksig drops it as redundant above API 28 and signs v3 only,
                // which is what actually lands in the generated APK.
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                // v4 needs a side-car file that PackageInstaller does not want.
                .setV4SigningEnabled(false)
                // Re-align rather than inheriting the template's layout. This is
                // what keeps resources.arsc uncompressed and 4-byte aligned,
                // without which installation fails outright at targetSdk 30+.
                .setAlignmentPreserved(false)
                .build()
                .sign()
        } catch (e: Exception) {
            throw GenerationException("Failed to sign ${output.name}", e)
        }
    }

    private companion object {
        // android.R.attr ids for the two <application> attributes the network
        // policy lives in. Framework attribute ids are frozen once shipped, so
        // these are as stable as the platform itself.
        const val ATTR_USES_CLEARTEXT_TRAFFIC = 0x010104ec
        const val ATTR_NETWORK_SECURITY_CONFIG = 0x01010527

        /** Launcher icons are 48dp; density scales that to pixels. */
        const val ICON_DP = 48

        /** mdpi, the density baseline where 1dp == 1px. */
        const val DEFAULT_DENSITY = 160

        const val MIN_SDK = 30
    }
}
