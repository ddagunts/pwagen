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
import kotlinx.serialization.json.Json

/**
 * What a generated PWA is allowed to do once a link leaves its [PwaConfig.scope].
 *
 * The default is deliberately [EXTERNAL]: a generated app is a window onto one
 * site, and silently following a link elsewhere would put unrelated traffic
 * under that app's UID — the exact thing the per-package design exists to avoid.
 */
@Serializable
enum class OffScopePolicy {
    /** Hand the URL to the system browser. */
    EXTERNAL,

    /** Load it in-app anyway. */
    ALLOW,

    /** Refuse to navigate. */
    BLOCK,
}

/**
 * Per-PWA relaxations of the shell's hardened defaults.
 *
 * Every flag here defaults to the restrictive value. Anything left disabled is
 * also stripped from the generated manifest, so a PWA without [camera] has no
 * `CAMERA` permission at all rather than an unused one.
 */
@Serializable
data class Capabilities(
    val camera: Boolean = false,
    val microphone: Boolean = false,
    val location: Boolean = false,
    val notifications: Boolean = false,
    val downloads: Boolean = false,

    /**
     * WebView Safe Browsing reports visited URLs to Google, so it is off unless
     * explicitly enabled.
     */
    val safeBrowsing: Boolean = false,

    /** Cross-site cookie access inside the WebView. */
    val thirdPartyCookies: Boolean = false,
)

/**
 * Which certificate authorities a generated PWA validates server certificates
 * against.
 *
 * Derived rather than stored: it is the two user-facing switches reduced to the
 * three combinations that mean anything, which is also exactly the set of
 * trust-anchor blocks the shell template ships.
 */
@Serializable
enum class TrustAnchors {
    /** Android's built-in CA set, and nothing else. The platform default. */
    SYSTEM,

    /** The platform set plus any CA the user installed themselves. */
    USER_AND_SYSTEM,

    /** Only user-installed CAs; the platform set is not trusted at all. */
    USER,
}

/**
 * How one generated PWA treats transport security.
 *
 * Every field defaults to the hardened value, and the first two deserve more
 * suspicion than anything in [Capabilities]: they remove a protection rather
 * than grant a capability, and no Android Settings toggle puts them back. The
 * only way to undo one is to regenerate the app.
 *
 * [cleartext] and the trust anchors are enforced by the generated APK's network
 * security config, which the generator selects from the fixed set the template
 * ships. [acceptInvalidCertificates] is enforced by the shell at runtime,
 * because there is no declarative way to express it.
 */
@Serializable
data class NetworkSecurity(
    /**
     * Permits `http://` — and with it, HTTPS pages pulling in HTTP subresources,
     * since a site reachable only over plaintext is the whole reason to ask.
     */
    val cleartext: Boolean = false,

    /**
     * Continues past a server certificate that failed validation: self-signed,
     * expired, or issued for a different host.
     *
     * This defeats TLS authentication outright — anything on the path can offer
     * a certificate of its own and be believed. Installing the site's CA on the
     * device and enabling [trustUserCas] gets a self-signed setup working while
     * still authenticating the server.
     */
    val acceptInvalidCertificates: Boolean = false,

    /** Also validate against CAs the user installed, e.g. a private or corporate root. */
    val trustUserCas: Boolean = false,

    /** Validate against Android's built-in CA set. */
    val trustSystemCas: Boolean = true,
) {
    /**
     * Trusting neither store would fail every connection, so that combination
     * reads as the platform default. The editor does not let it be built in the
     * first place; this is only here so a hand-edited config cannot produce an
     * app that silently connects to nothing.
     */
    val trustAnchors: TrustAnchors
        get() = when {
            trustUserCas && trustSystemCas -> TrustAnchors.USER_AND_SYSTEM
            trustUserCas -> TrustAnchors.USER
            else -> TrustAnchors.SYSTEM
        }
}

/**
 * The complete definition of one generated PWA.
 *
 * Serialized to `assets/config.json` inside the generated APK, and also the unit
 * of the generator's backup bundle.
 */
@Serializable
data class PwaConfig(
    /** Where the app opens. */
    val url: String,

    /** Launcher label and the generated app's `android:label`. */
    val label: String,

    /** Generated package name, derived from the URL host. */
    val packageName: String,

    /**
     * URL prefix treated as "inside" the app. Defaults to the origin of [url]
     * when the generator does not compute something narrower.
     */
    val scope: String,

    val offScopePolicy: OffScopePolicy = OffScopePolicy.EXTERNAL,

    /** `#rrggbb`, applied to the status bar and the task switcher tile. */
    val themeColor: String = "#000000",

    val capabilities: Capabilities = Capabilities(),

    /**
     * Plaintext and certificate handling. Hardened by default: HTTPS only,
     * system CAs only, no way past a bad certificate.
     */
    val network: NetworkSecurity = NetworkSecurity(),

    /**
     * Domain allow/block rules applied to every request this PWA makes. Off by
     * default, so a freshly generated app behaves like an ordinary browser tab
     * confined to its scope.
     */
    val domainRules: DomainRules = DomainRules(),

    /**
     * Bumped on every regeneration so the result installs as an in-place upgrade,
     * preserving the PWA's cookies, logins, and any firewall rules set against it.
     */
    val versionCode: Int = 1,
) {
    companion object {
        const val ASSET_PATH: String = "config.json"

        val json: Json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(text: String): PwaConfig = json.decodeFromString(serializer(), text)
    }

    fun encode(): String = json.encodeToString(serializer(), this)
}
