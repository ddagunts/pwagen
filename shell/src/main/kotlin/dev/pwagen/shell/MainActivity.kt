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

package dev.pwagen.shell

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.pwagen.config.Capabilities
import dev.pwagen.config.DomainMatcher
import dev.pwagen.config.NetworkSecurity
import dev.pwagen.config.OffScopePolicy
import dev.pwagen.config.PwaConfig
import java.io.ByteArrayInputStream

/**
 * The entire runtime of a generated PWA: one WebView, chromeless, pointed at one
 * site.
 *
 * This class is referenced by its fully-qualified name from the template
 * manifest so it resolves regardless of the package pwagen rewrites the APK to.
 * That is what allows `classes.dex` to be byte-identical in every generated APK.
 *
 * It deliberately extends the framework [Activity] rather than anything from
 * AppCompat: this is the network-facing half of the project and every dependency
 * it does not have is one less thing to audit.
 */
class MainActivity : Activity() {

    private lateinit var config: PwaConfig
    private lateinit var webView: WebView

    /** Precompiled once: consulted on every request from a worker thread. */
    private lateinit var matcher: DomainMatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = loadConfig()
        matcher = DomainMatcher.of(config.domainRules)
        applyThemeColor()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            harden(settings, config.capabilities, config.network)
            webViewClient = ScopedWebViewClient()
            webChromeClient = CapabilityGatingChromeClient(config.capabilities)
        }
        setContentView(webView)

        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webView, config.capabilities.thirdPartyCookies)

        val restored = savedInstanceState?.let { webView.restoreState(it) } != null
        if (!restored) webView.loadUrl(config.url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    /**
     * Back walks the page's own history first, and only leaves the app once
     * there is nothing left to go back to.
     */
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    private fun loadConfig(): PwaConfig =
        assets.open(PwaConfig.ASSET_PATH).bufferedReader().use { PwaConfig.decode(it.readText()) }

    /** Carries the site's own colour onto the system bars and the recents tile. */
    private fun applyThemeColor() {
        val color = runCatching { Color.parseColor(config.themeColor) }.getOrNull() ?: return
        window.statusBarColor = color
        window.navigationBarColor = color

        val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityManager.TaskDescription.Builder()
                .setLabel(config.label)
                .setPrimaryColor(color)
                .build()
        } else {
            @Suppress("DEPRECATION")
            ActivityManager.TaskDescription(config.label, null, color)
        }
        setTaskDescription(description)
    }

    /** Sends a URL that left [PwaConfig.scope] to the system browser. */
    private fun openExternally(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No handler for off-scope URL", e)
        }
    }

    private inner class ScopedWebViewClient : WebViewClient() {

        /**
         * Applies the PWA's domain rules to every request the page makes —
         * subresources included, which is where a page would otherwise reach
         * hosts the user never seeded.
         *
         * Called on a WebView worker thread, so [matcher] is precompiled and
         * this does no allocation beyond the refusal itself.
         */
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (matcher.allows(request.url.host)) return null

            Log.i(TAG, "Blocked by domain rules: ${request.url.host}")
            return REFUSED()
        }

        /**
         * Certificate validation failed — self-signed, expired, wrong host, or
         * an untrusted issuer.
         *
         * The default is to cancel, which is also what [WebViewClient] does. The
         * override exists only so that a PWA generated with
         * [NetworkSecurity.acceptInvalidCertificates] can continue, and it is
         * unconditional when it does: there is no prompt, because a prompt on
         * every load trains the user to dismiss it.
         *
         * Note what this does *not* cover. Trusting a private CA is a job for
         * the trust anchors in the generated app's network security config,
         * where the certificate is still checked; this throws the check away.
         */
        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError,
        ) {
            if (!config.network.acceptInvalidCertificates) {
                Log.w(TAG, "Rejected certificate for ${error.url}: ${error.primaryError}")
                handler.cancel()
                return
            }

            Log.w(TAG, "Accepting invalid certificate for ${error.url}: ${error.primaryError}")
            handler.proceed()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url.toString()
            if (url.startsWith(config.scope)) return false

            return when (config.offScopePolicy) {
                OffScopePolicy.ALLOW -> false
                OffScopePolicy.BLOCK -> true
                OffScopePolicy.EXTERNAL -> {
                    openExternally(url)
                    true
                }
            }
        }
    }

    private class CapabilityGatingChromeClient(
        private val capabilities: Capabilities,
    ) : WebChromeClient() {
        /**
         * Denies anything the PWA was not generated with. This is belt-and-braces:
         * capabilities left disabled also have their permissions stripped from the
         * manifest entirely, so the request could not succeed regardless.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            val granted = request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> capabilities.camera
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> capabilities.microphone
                    else -> false
                }
            }
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        }
    }

    private companion object {
        const val TAG = "pwagen"

        /**
         * The response handed back for a request the domain rules refused.
         *
         * A 403 with an empty body is deliberate: it fails the request outright
         * rather than serving a plausible-looking empty document, so a blocked
         * subresource surfaces in the page's own error handling.
         */
        @Suppress("FunctionName")
        fun REFUSED(): WebResourceResponse = WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked by pwagen domain rules",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )

        /**
         * Hardened defaults. Everything permissive here is opt-in per PWA, and
         * Safe Browsing in particular stays off because it reports visited URLs
         * to Google.
         */
        @SuppressLint("SetJavaScriptEnabled")
        @Suppress("DEPRECATION")
        fun harden(
            settings: WebSettings,
            capabilities: Capabilities,
            network: NetworkSecurity,
        ) {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true

            settings.safeBrowsingEnabled = capabilities.safeBrowsing
            settings.setGeolocationEnabled(capabilities.location)

            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.databaseEnabled = false
            settings.mediaPlaybackRequiresUserGesture = true

            // Mixed content follows the plaintext setting rather than getting a
            // switch of its own. Whether the http:// URL is the page or one of
            // its subresources is not a distinction worth a second question:
            // both are the app speaking plaintext, which is exactly what the one
            // setting is about.
            settings.mixedContentMode = if (network.cleartext) {
                WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }
    }
}
