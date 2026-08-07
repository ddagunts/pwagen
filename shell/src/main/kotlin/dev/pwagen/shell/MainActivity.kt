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
import android.graphics.Insets
import android.graphics.Typeface
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import dev.pwagen.config.Capabilities
import dev.pwagen.config.DomainMatcher
import dev.pwagen.config.NetworkSecurity
import dev.pwagen.config.OffScopePolicy
import dev.pwagen.config.PwaConfig
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

    /** Wraps the WebView; also what a blocked-request notice is attached to. */
    private lateinit var root: FrameLayout

    /** Precompiled once: consulted on every request from a worker thread. */
    private lateinit var matcher: DomainMatcher

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    /** Hosts silenced for good. A copy: the stored set must not be mutated. */
    private val mutedHosts: MutableSet<String> by lazy {
        prefs.getStringSet(MUTED_HOSTS, emptySet()).orEmpty().toMutableSet()
    }

    /**
     * Hosts already reported this launch. Written from WebView worker threads,
     * so it cannot be an ordinary set.
     */
    private val announcedHosts: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private var blockNotice: View? = null

    private var pullStartY = 0f
    private var pullFromTop = false
    private var pullArmed = false
    private var pullIndicator: View? = null

    // The touch listener below observes and never consumes, so the accessibility
    // click path it normally warns about is still the WebView's own.
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        config = loadConfig()
        matcher = DomainMatcher.of(config.domainRules)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            harden(settings, config.capabilities, config.network)
            webViewClient = ScopedWebViewClient()
            webChromeClient = CapabilityGatingChromeClient(config.capabilities)

            // Observed, never consumed: returning false leaves every event to
            // the WebView's own handling, so the page behaves exactly as before
            // unless the drag turns out to be a pull from the very top.
            setOnTouchListener { _, event ->
                trackPull(event)
                false
            }
        }

        // The WebView is wrapped rather than set as the content view directly so
        // that the strip left for the system bars shows the site's colour rather
        // than a black gap. Padding the WebView itself would not do: it would
        // scroll its own background away with the page.
        root = FrameLayout(this).apply {
            themeColor()?.let(::setBackgroundColor)
            addView(webView)
        }
        // Both of these reach through the window for its decor view, which does
        // not exist until setContentView has installed it. Asking any earlier
        // throws inside PhoneWindow, so the order here is load-bearing.
        setContentView(root)
        applyThemeColor()
        applyWindowFit(root)

        // Predictive back is on by default for apps targeting API 35 and up, and
        // when it is, the system routes Back through this dispatcher and never
        // calls onBackPressed. Without the registration, Back would leave the app
        // instead of walking the page's history.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { goBackOrExit() },
            )
        }

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
    private fun goBackOrExit() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    /**
     * The pre-API-33 delivery path for [goBackOrExit].
     *
     * Still reachable: this runs from API 30, and below 33 there is no back
     * dispatcher to register with. From 33 the manifest opts into the predictive
     * back callback and the system stops calling this entirely, which is why the
     * registration in onCreate is not an alternative but the primary route.
     */
    @Suppress("DEPRECATION", "MissingSuperCall", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() = goBackOrExit()

    private fun loadConfig(): PwaConfig =
        assets.open(PwaConfig.ASSET_PATH).bufferedReader().use { PwaConfig.decode(it.readText()) }

    // ---------------------------------------------------------------------
    // Blocked-request notices
    // ---------------------------------------------------------------------

    /**
     * Reports a host the domain rules refused, unless it has been silenced.
     *
     * Called from a WebView worker thread, and a single page load can refuse
     * dozens of requests to the same host, so the announcement is collapsed to
     * once per host per launch before it ever reaches the main thread.
     */
    private fun announceBlock(host: String?) {
        val name = host?.takeIf { it.isNotBlank() } ?: return
        if (name in mutedHosts) return
        if (!announcedHosts.add(name)) return

        runOnUiThread { showBlockNotice(name) }
    }

    /**
     * A one-line notice naming the refused host, with a way to silence it.
     *
     * Assembled from plain framework views rather than a Snackbar: the shell
     * carries no Material dependency, and in the network-facing half of the
     * project that is a constraint worth keeping rather than an inconvenience to
     * work around.
     */
    private fun showBlockNotice(host: String) {
        dismissBlockNotice()

        val label = TextView(this).apply {
            text = "Blocked request to $host"
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }

        val notice = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(NOTICE_BACKGROUND)
            setPadding(dp(16), dp(10), dp(4), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
            addView(label)
            addView(noticeAction("Mute") { muteHost(host) })
            addView(noticeAction("Dismiss") { dismissBlockNotice() })
        }

        root.addView(notice)
        blockNotice = notice

        // Left up, this would cover part of the page indefinitely — which is the
        // pestering the mute button exists to stop, not something to introduce
        // by another route.
        notice.postDelayed({ if (blockNotice === notice) dismissBlockNotice() }, NOTICE_TIMEOUT_MS)
    }

    private fun noticeAction(caption: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = caption
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isAllCaps = true
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onClick() }
        }

    /** Silences [host] for good, across restarts of the generated app. */
    private fun muteHost(host: String) {
        mutedHosts.add(host)
        // The set handed to SharedPreferences must not be the one that keeps
        // being mutated: the stored instance is read back as-is, so sharing it
        // would let later edits leak in without ever being committed.
        prefs.edit().putStringSet(MUTED_HOSTS, HashSet(mutedHosts)).apply()
        dismissBlockNotice()
    }

    private fun dismissBlockNotice() {
        blockNotice?.let(root::removeView)
        blockNotice = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------------
    // Pull to refresh
    // ---------------------------------------------------------------------

    /**
     * Turns a downward drag that starts with the page already at its top into a
     * reload.
     *
     * Hand-rolled rather than reached for: `SwipeRefreshLayout` would put an
     * AndroidX dependency in the network-facing module, and the short dependency
     * list there is worth more than the code it would have saved.
     *
     * The gesture is only claimed while the WebView has nowhere left to scroll
     * upwards, and is abandoned the moment the page moves or a second finger
     * arrives, so it cannot swallow a drag or a pinch the page wanted.
     */
    private fun trackPull(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pullStartY = event.y
                pullFromTop = webView.scrollY == 0
                pullArmed = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!pullFromTop) return
                if (webView.scrollY != 0) {
                    resetPull()
                    return
                }

                val armed = event.y - pullStartY > dp(96)
                if (armed == pullArmed) return

                pullArmed = armed
                if (armed) showPullIndicator() else hidePullIndicator()
            }

            MotionEvent.ACTION_UP -> {
                val reload = pullArmed
                resetPull()
                if (reload) webView.reload()
            }

            // A second finger means a pinch or a two-finger scroll, neither of
            // which is a pull.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> resetPull()
        }
    }

    private fun showPullIndicator() {
        if (pullIndicator != null) return

        pullIndicator = TextView(this).apply {
            text = "Release to refresh"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(NOTICE_BACKGROUND)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            )
            root.addView(this)
        }
    }

    private fun hidePullIndicator() {
        pullIndicator?.let(root::removeView)
        pullIndicator = null
    }

    private fun resetPull() {
        pullArmed = false
        pullFromTop = false
        hidePullIndicator()
    }

    private fun themeColor(): Int? =
        runCatching { Color.parseColor(config.themeColor) }.getOrNull()

    /**
     * Decides who owns the strip of screen behind the system bars.
     *
     * Edge-to-edge is not optional from API 35 on: the window extends under the
     * status bar no matter what the activity asks for. Left alone that puts the
     * top of the page behind the bar, where taps go to the system and never
     * reach the site — a band of the page that looks live and is not. So the app
     * either hides the bars and genuinely uses the whole screen, or keeps them
     * and holds the page clear of them.
     */
    // setDecorFitsSystemWindows is deprecated because edge-to-edge stopped being
    // optional, which is only true from API 35. This runs from 30, where it is
    // still the switch that decides whether the framework insets the content.
    @Suppress("DEPRECATION")
    private fun applyWindowFit(root: View) {
        // Taking the decor out of the framework's hands is what makes the two
        // modes behave the same way on every API level this runs on, instead of
        // depending on whether the platform still honours decor fitting.
        window.setDecorFitsSystemWindows(false)

        // Ask for the whole display, cutout included, and then pad it back out
        // above. Left at the default, the platform letterboxes the cutout edge
        // itself in some orientations and not others, with a black bar rather
        // than the site's colour — so the app would look different in portrait
        // and landscape for reasons no setting here explained.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        applySystemBars()

        root.setOnApplyWindowInsetsListener { view, insets ->
            // The keyboard is padded for in both modes. Opting out of decor
            // fitting also opts out of the framework's own IME resizing, so
            // without this a focused input can sit under the keyboard.
            val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom

            // Hiding the system bars does not move the camera. Full screen still
            // holds the page clear of the cutout, because the alternative is the
            // top of the site disappearing behind a lens — and unlike a status
            // bar, there is no swipe that brings it back. The strip it leaves is
            // filled by the root's theme colour, so it reads as part of the app
            // rather than as a letterbox.
            //
            // The cutout is folded in with the bars in the other modes too: on a
            // device with a notch rather than a punch-hole, the status bar inset
            // alone does not clear it in landscape.
            val bars = when {
                !config.fullscreen -> insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )

                // Full screen with the clock kept: only the status bar is still
                // on screen, so it is the only bar the page has to clear. The
                // navigation bar is hidden and its strip belongs to the page.
                config.keepStatusBar -> insets.getInsets(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
                )

                // Opted into the lens: for a site whose top edge is empty, the
                // extra strip is worth more than the content it could hide.
                config.drawUnderCutout -> Insets.NONE

                else -> insets.getInsets(WindowInsets.Type.displayCutout())
            }

            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard))
            insets
        }
    }

    /**
     * Hides or reveals the system bars, and picks the icon colour drawn on them.
     *
     * The window's insets controller is only wired up once the window has been
     * attached, so this runs again on every focus gain rather than only at
     * creation. That second call is not merely defensive: a bar the user swiped
     * in transiently has to be put back afterwards, and returning from another
     * app is where that is noticed.
     */
    private fun applySystemBars() {
        val controller = window.insetsController ?: return

        if (config.fullscreen) {
            // Keeping the status bar leaves the clock, battery and signal in
            // place. The navigation bar goes either way: it is what full screen
            // is mostly for, and the swipe below brings it back when needed.
            controller.hide(
                if (config.keepStatusBar) {
                    WindowInsets.Type.navigationBars()
                } else {
                    WindowInsets.Type.systemBars()
                },
            )
            // A hidden bar has to stay reachable: a generated app is chromeless,
            // so the swipe is the only way back to the clock, notifications, and
            // on three-button devices the Back key.
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // The bar icons are drawn over the theme colour whenever the bars are
        // visible, so a light theme colour needs dark icons or the clock and
        // battery disappear into it.
        val color = themeColor() ?: return
        val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(
            if (Color.luminance(color) > 0.5f) appearance else 0,
            appearance,
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    /**
     * Carries the site's own colour onto the bars and the recents tile.
     *
     * The two bar-colour setters are no-ops from API 35, where the colour behind
     * a bar is whatever the app draws there — which is why the root view carries
     * the same colour. They are kept for API 30 to 34, where they are still what
     * tints the bars.
     */
    @Suppress("DEPRECATION")
    private fun applyThemeColor() {
        val color = themeColor() ?: return
        window.statusBarColor = color
        window.navigationBarColor = color

        val description =if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
            if (config.domainRules.announceBlocks) announceBlock(request.url.host)
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

        const val PREFS = "pwagen"
        const val MUTED_HOSTS = "mutedHosts"

        /** Near-opaque neutral, so the notice reads over whatever the page draws. */
        const val NOTICE_BACKGROUND = 0xF0202020.toInt()
        const val NOTICE_TIMEOUT_MS = 10_000L

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
