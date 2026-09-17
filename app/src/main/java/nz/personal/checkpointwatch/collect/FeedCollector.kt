package nz.personal.checkpointwatch.collect

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import nz.personal.checkpointwatch.Constants
import java.net.URI
import kotlin.coroutines.resume

/** Origin the collector talks to; both the message listener and the injected script are tied to it. */
private const val FACEBOOK_ORIGIN = "https://www.facebook.com"

/** Name of the JS object `collector.js` posts through. Never an `addJavascriptInterface` bridge. */
private const val BRIDGE_NAME = "cwBridge"

private const val ASSET_COLLECTOR_JS = "collector.js"

/** No page load and no message within this long means the network is not cooperating. */
private const val PAGE_LOAD_TIMEOUT_MS = 20_000L

/** Hard ceiling on a whole scan; whatever was captured by then is kept. */
private const val SCAN_TIMEOUT_MS = 45_000L

/** Chrome's major version, as it appears in [Constants.DESKTOP_UA]. */
private const val UA_CHROME_MAJOR = "126"
private const val UA_CHROME_FULL = "126.0.0.0"

/**
 * The client hints that go with [Constants.DESKTOP_UA]: desktop Chrome 126 on 64-bit Windows.
 * Sent only where the WebView supports setting them; see `hideOurselvesFromFacebook`.
 */
private val DESKTOP_UA_METADATA: UserAgentMetadata by lazy {
    fun brand(name: String) = UserAgentMetadata.BrandVersion.Builder()
        .setBrand(name)
        .setMajorVersion(UA_CHROME_MAJOR)
        .setFullVersion(UA_CHROME_FULL)
        .build()

    UserAgentMetadata.Builder()
        .setBrandVersionList(listOf(brand("Chromium"), brand("Google Chrome")))
        .setPlatform("Windows")
        .setPlatformVersion("10.0.0")
        .setFullVersion(UA_CHROME_FULL)
        .setArchitecture("x86")
        .setBitness(64)
        .setModel("")
        .setMobile(false)
        .setWow64(false)
        .build()
}

/**
 * Ceiling on buffered JSON: 4 M UTF-16 characters, which is about 8 MB of heap. Chunks that would
 * cross it are dropped and the scan carries on with what it has.
 */
private const val MAX_BUFFERED_CHARS = 4L * 1024 * 1024

/**
 * Ceiling on the collector script's diagnostic log. The script caps itself at about 40 KB; this
 * is the backstop for a page that manages to send something larger through the bridge.
 */
private const val MAX_DIAG_CHARS = 64 * 1024

/** How many blocked navigations the diagnostics name. After a few, the rest say nothing new. */
private const val MAX_BLOCKED_LOGGED = 5

/** How long to wait for the cookie store to confirm it is empty before giving up on the callback. */
private const val COOKIE_CLEAR_TIMEOUT_MS = 2_000L

/** How long to wait for the script's last-chance DOM dump after the scan times out. */
private const val DOM_DUMP_TIMEOUT_MS = 500L

/** Asks `collector.js` for the DOM fallback without ending it; a no-op if the script never ran. */
private const val DOM_DUMP_JS = "window.__cwDump && window.__cwDump()"


/**
 * Path roots of Facebook's sign-in flows. Each matches on its own, with a `.php` suffix (the form
 * Facebook actually redirects logged-out visitors to), or as a path segment with more after it.
 */
private val LOGIN_PATH_ROOTS = listOf(
    "/login",
    "/checkpoint",
    "/recover",
    "/reg",
    "/r",
    "/privacy/consent",
    "/dialog/oauth",
)

/**
 * True only for the Checkpoint NZ page itself. Facebook appends and rewrites query strings and may
 * add a trailing slash, so host + path is the identity that matters; everything else (login walls,
 * checkpoint interstitials, app-store intents, other pages) is a navigation away from where we are
 * allowed to be. `about:blank` is allowed because teardown navigates there.
 */
internal fun isAllowedNavigation(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    if (url == "about:blank") return true
    val target = url.toUriOrNull() ?: return false
    val page = Constants.PAGE_URL.toUriOrNull() ?: return false
    if (!target.scheme.equals("https", ignoreCase = true)) return false
    if (!target.host.equals(page.host, ignoreCase = true)) return false
    return target.path.orEmpty().trimEnd('/')
        .equals(page.path.orEmpty().trimEnd('/'), ignoreCase = true)
}

/** Whether a blocked navigation looks like Facebook demanding an account rather than a stray link. */
internal fun looksLikeLoginRedirect(url: String?): Boolean {
    val path = url?.toUriOrNull()?.path?.lowercase()?.trimEnd('/') ?: return false
    return LOGIN_PATH_ROOTS.any { root ->
        path == root ||
            path == "$root.php" ||
            path.startsWith("$root/") ||
            path.startsWith("$root.php/")
    }
}

/** `facebook.com` itself or any subdomain of it, and nothing that merely looks like one. */
internal fun isFacebookHost(host: String?): Boolean {
    val lower = host?.lowercase() ?: return false
    return lower == "facebook.com" || lower.endsWith(".facebook.com")
}

/**
 * Whether a blocked main-frame navigation should end the scan as [EndReason.BLOCKED].
 *
 * Any http(s) navigation to a Facebook host that is not our page is Facebook refusing to show the
 * feed (a login, checkpoint or consent redirect), so the scan ends at once instead of idling until
 * the 45 s timeout. A sign-in page on another property counts too. App links (`intent:`, `market:`)
 * and unrelated sites are simply blocked and the scan carries on.
 */
internal fun endsScanAsBlocked(url: String?): Boolean {
    if (isAllowedNavigation(url)) return false
    val uri = url?.toUriOrNull() ?: return false
    val isWeb = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
    if (!isWeb) return false
    return isFacebookHost(uri.host) || looksLikeLoginRedirect(url)
}

private fun String.toUriOrNull(): URI? = try {
    URI(this)
} catch (_: Exception) {
    null
}

/**
 * A URL reduced to the only part of it the diagnostics are allowed to keep. Facebook puts
 * identifiers, redirect targets and tracking parameters in query strings, and a blocked navigation
 * is exactly the kind of URL that carries them, so everything after the path is dropped.
 */
internal fun hostAndPath(url: String?): String {
    val uri = url?.toUriOrNull() ?: return "(unreadable)"
    val host = uri.host ?: uri.scheme ?: ""
    return (host + uri.path.orEmpty()).ifBlank { "(unreadable)" }
}

/** What [View.getWindowVisibility] meant, in the word the constant is named after. */
private fun visibilityName(visibility: Int): String = when (visibility) {
    View.VISIBLE -> "VISIBLE"
    View.INVISIBLE -> "INVISIBLE"
    View.GONE -> "GONE"
    else -> visibility.toString()
}

/**
 * The half of a scan's diagnostics that the collector script cannot see: which WebView ran it,
 * where it was attached, how big it was, and what the page load itself did.
 *
 * Written only from the main thread, read under [FeedCollector]'s lock.
 */
private class ScanFacts {
    var webView: String? = null
    var host: String? = null
    var documentStart: String? = null
    var atAttach: String? = null
    var atPageFinished: String? = null
    var atEnd: String? = null
    var pageFinished: Boolean = false
    var mainFrameError: String? = null
    var httpStatus: String? = null
    val blocked = mutableListOf<String>()

    fun blocked(url: String?) {
        if (blocked.size >= MAX_BLOCKED_LOGGED) return
        val stripped = hostAndPath(url)
        if (stripped !in blocked) blocked.add(stripped)
    }

    fun fields(
        jsonChunks: Int,
        domPosts: Int,
        appVersion: String,
    ): List<Pair<String, String?>> = listOf(
        "app" to appVersion,
        "webView" to webView,
        "host" to host,
        "documentStartScript" to documentStart,
        "sizeAtAttach" to atAttach,
        "sizeAtPageFinished" to atPageFinished,
        "sizeAtEnd" to atEnd,
        "pageFinished" to pageFinished.toString(),
        "mainFrameError" to mainFrameError,
        "mainFrameHttpStatus" to httpStatus,
        "blockedNavigations" to blocked.takeIf { it.isNotEmpty() }?.joinToString(", "),
        "jsonChunks" to jsonChunks.toString(),
        "domPosts" to domPosts.toString(),
    )
}

/**
 * Runs one scan of the Checkpoint NZ page in a hidden WebView: loads the page with a desktop user
 * agent, lets `collector.js` close Facebook's first login dialog and scroll, and buffers the JSON
 * responses the page fetches while it does.
 *
 * One instance runs one scan at a time. [snapshot] can be read from any thread and returns what
 * has arrived so far, so a caller that cancels [collect] can still record the partial scan.
 */
class FeedCollector(private val appContext: Context) {

    private val lock = Any()
    private val jsonChunks = mutableListOf<String>()
    private var bufferedChars = 0L
    private var domPosts = emptyList<DomPost>()
    private var finalEnd: EndReason? = null
    private var diagBody: String? = null
    private var facts = ScanFacts()

    /**
     * Collects until the script says it is done, the scan times out, or the caller cancels.
     *
     * Cancellation propagates (nothing is returned); the caller records [snapshot] instead.
     */
    suspend fun collect(host: WebViewHost): CollectResult {
        // Reset first: a cancellation before the scan starts must not leave the previous run's
        // posts visible through snapshot() as if they were this run's.
        reset()
        val script = withContext(Dispatchers.IO) { CollectorScript.load(appContext) }
        // Dispatchers.Main, deliberately NOT Main.immediate. The scan ends when a WebView callback
        // completes `done`; with .immediate that resumption (and therefore teardown — stopLoading,
        // detach, destroy) would run undispatched, on the stack of the very native callback that is
        // still executing, which destroys the WebView from inside its own engine callback. Plain
        // Main always dispatches, so the scan resumes on a later main-loop turn, after the callback
        // has returned into Chromium.
        return withContext(Dispatchers.Main) { runScan(host, script) }
    }

    /**
     * What this collector has received so far. [CollectResult.end] is [EndReason.CANCELLED] until
     * a run finishes, which is exactly what a cancelled scan should be recorded as.
     */
    fun snapshot(): CollectResult = synchronized(lock) {
        CollectResult(
            jsonChunks = jsonChunks.toList(),
            domPosts = domPosts.toList(),
            end = finalEnd ?: EndReason.CANCELLED,
            diagnostics = DiagnosticsText.document(
                fields = facts.fields(
                    jsonChunks = jsonChunks.size,
                    domPosts = domPosts.size,
                    appVersion = appVersion,
                ),
                json = diagBody,
            ),
        )
    }

    private suspend fun CoroutineScope.runScan(host: WebViewHost, script: String): CollectResult {
        // Without the script or the message channel there is nothing to collect. Reported as a
        // network error so the coordinator falls through to HttpLatestFetcher.
        if (script.isBlank()) {
            finish(EndReason.NETWORK_ERROR)
            return snapshot()
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            finish(EndReason.NETWORK_ERROR)
            return snapshot()
        }

        fact {
            this.host = host.name
            this.webView = webViewDescription()
        }

        clearBrowsingData()

        // A device with its WebView provider disabled or mid-update throws here; that is a failed
        // scan, not a crashed app (this can run in a background worker).
        val webView = try {
            HeadlessWebView(appContext)
        } catch (_: Exception) {
            finish(EndReason.NETWORK_ERROR)
            return snapshot()
        }
        val done = CompletableDeferred<EndReason>()
        val session = Session(webView, host, done)
        var loadWatchdog: Job? = null
        try {
            configure(webView)
            addBridge(webView, session.messageListener)
            // The script must be in place before Facebook's own scripts run, otherwise the XHR and
            // fetch wrappers miss the feed responses. Where that is not supported, the WebViewClient
            // injects it at page start instead (and again at page finish, in case it was too early).
            val documentStart: Boolean
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(FACEBOOK_ORIGIN))
                documentStart = true
            } else {
                documentStart = false
            }
            fact {
                this.documentStart = if (documentStart) {
                    "supported, used"
                } else {
                    "unsupported, injected at page start instead"
                }
            }
            webView.webViewClient = session.CollectorClient(script, injectManually = !documentStart)
            webView.webChromeClient = session.chromeClient
            webView.setDownloadListener { _, _, _, _, _ -> /* the collector never downloads */ }

            host.attach(webView)
            fact { atAttach = measure(webView) }
            webView.loadUrl(Constants.PAGE_URL)

            loadWatchdog = launch {
                delay(PAGE_LOAD_TIMEOUT_MS)
                if (!session.progressed) session.endWith(EndReason.NETWORK_ERROR)
            }

            val reason = withTimeoutOrNull(SCAN_TIMEOUT_MS) { done.await() }
            if (reason == null) {
                // Timed out before the script finished, so it never sent its `dom` message. Ask for
                // one now: on a slow page that is the difference between the DOM fallback and
                // nothing at all.
                session.requestDomDump()
            }
            finish(reason ?: EndReason.TIMEOUT)
            return snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Anything the WebView stack throws at us ends the scan instead of the app; whatever
            // was already buffered is still returned.
            finish(EndReason.NETWORK_ERROR)
            return snapshot()
        } finally {
            loadWatchdog?.cancel()
            withContext(NonCancellable) {
                // Measured before teardown: how big the WebView ended up, and whether the system
                // still thought it was on a visible window, is half the answer when a feed never
                // paginated.
                fact { atEnd = measure(webView) }
                // One more main-loop turn before destroying anything, so that a callback which is
                // still on the stack (or a posted completion) is well clear of the WebView.
                yield()
                session.teardown()
            }
        }
    }

    /**
     * The one JS -> Kotlin channel, locked to Facebook's origin. Never `addJavascriptInterface`:
     * that would expose Kotlin methods to every script on the page.
     */
    @SuppressLint("RequiresFeature") // WEB_MESSAGE_LISTENER is checked at the top of runScan.
    private fun addBridge(webView: WebView, listener: WebViewCompat.WebMessageListener) =
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(FACEBOOK_ORIGIN), listener)

    /** The app's own version, for the diagnostics; read once, and never worth failing a scan. */
    private val appVersion: String by lazy {
        try {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** Which WebView implementation is actually running the page on this phone, and its version. */
    private fun webViewDescription(): String = try {
        WebViewCompat.getCurrentWebViewPackage(appContext)
            ?.let { "${it.packageName} ${it.versionName.orEmpty()}".trim() }
            ?: "unknown"
    } catch (_: Exception) {
        "unknown"
    }

    /** How big the WebView is right now, and whether the system believes anyone can see it. */
    private fun measure(webView: WebView): String = try {
        "${webView.width}x${webView.height}px attached=${webView.isAttachedToWindow} " +
            "windowVisibility=${visibilityName(webView.windowVisibility)}"
    } catch (_: Exception) {
        "unavailable"
    }

    /** Records one fact. Never lets the diagnostics cost the scan anything. */
    private fun fact(block: ScanFacts.() -> Unit) {
        try {
            synchronized(lock) { facts.block() }
        } catch (_: Exception) {
            // ignore: a missing line of diagnostics is not a reason to fail a scan
        }
    }

    private fun setDiag(body: String) = synchronized(lock) {
        diagBody = if (body.length > MAX_DIAG_CHARS) body.substring(0, MAX_DIAG_CHARS) else body
    }

    private fun reset() = synchronized(lock) {
        jsonChunks.clear()
        bufferedChars = 0
        domPosts = emptyList()
        finalEnd = null
        diagBody = null
        facts = ScanFacts()
    }

    private fun finish(reason: EndReason) = synchronized(lock) {
        finalEnd = reason
    }

    /** Drops the chunk instead of the scan when the buffer is full: partial data still parses. */
    private fun addChunk(body: String) = synchronized(lock) {
        if (bufferedChars + body.length > MAX_BUFFERED_CHARS) return@synchronized
        jsonChunks.add(body)
        bufferedChars += body.length
    }

    private fun setDomPosts(posts: List<DomPost>) = synchronized(lock) {
        domPosts = posts
    }

    /**
     * Every scan is a brand-new logged-out visitor: no carried-over cookies, no storage.
     *
     * Cookie removal is asynchronous, so this waits for its callback (and the flush) before the
     * page is loaded — otherwise the load can race the wipe and carry the last scan's session.
     */
    private suspend fun clearBrowsingData() {
        try {
            val cookies = CookieManager.getInstance()
            withTimeoutOrNull(COOKIE_CLEAR_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    cookies.removeAllCookies { _ ->
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A missing or updating WebView provider must not take the scan down on its own; the
            // load below will end the scan with NETWORK_ERROR if the provider really is unusable.
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(webView: WebView) {
        // JavaScript is the entire point: the page is a JS app, and only a real engine gets past
        // the login dialog. It is confined to https://www.facebook.com by the navigation guard,
        // by the single message listener, and by the settings below.
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = Constants.DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        hideOurselvesFromFacebook(webView.settings)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        // Invisible to the user and to accessibility, and it must never take input. Both hosts
        // need this, so it lives here rather than in ActivityHost.
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.isClickable = false
        webView.isLongClickable = false
        webView.isHapticFeedbackEnabled = false
        webView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER

        if ((appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Stops the WebView telling Facebook things about this phone that the page has no business
     * knowing, and that no ordinary desktop browser would send.
     *
     * 1. `X-Requested-With`. Android WebView puts the *host app's package name* in that header on
     *    every request, so every scan would announce `nz.personal.checkpointwatch` to Facebook —
     *    a stable identifier for a private app, attached to an otherwise anonymous logged-out
     *    visit. An empty allow list turns it off for every origin.
     * 2. User-Agent client hints. The user agent string says desktop Chrome on Windows
     *    ([Constants.DESKTOP_UA]), but the client hints the engine sends alongside it are derived
     *    from the real device — Android, mobile, a phone model. That contradiction is more
     *    distinctive than either half on its own, so the hints are set to match the string.
     *
     * Everything is behind a feature check and a `runCatching`: both APIs depend on the WebView
     * provider on the phone, and neither is worth failing a scan over.
     *
     * The allow-list API is deprecated and `@RestrictTo` in androidx.webkit 1.17, because newer
     * WebView versions have stopped sending the header at all — which is the outcome we want. It
     * is called anyway, with both warnings suppressed, because `minSdk` is 29 and the phone may
     * be carrying an older WebView that still sends it. If the method is withdrawn from a future
     * androidx.webkit, the `runCatching` below catches the resulting error and the app carries on.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("RestrictedApi")
    private fun hideOurselvesFromFacebook(settings: WebSettings) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            runCatching { WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet()) }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            runCatching { WebSettingsCompat.setUserAgentMetadata(settings, DESKTOP_UA_METADATA) }
        }
    }

    /** The mutable state belonging to one WebView, kept together so teardown can be idempotent. */
    private inner class Session(
        private val webView: HeadlessWebView,
        private val host: WebViewHost,
        private val done: CompletableDeferred<EndReason>,
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * Ends the scan from a WebView callback.
         *
         * Completing [done] resumes the scan, and the scan's `finally` destroys this WebView, so
         * the completion is posted: it must never happen on the stack of the engine callback that
         * is asking for it. Calling this twice is harmless; the first reason wins.
         */
        fun endWith(reason: EndReason) {
            if (done.isCompleted) return
            mainHandler.post { done.complete(reason) }
        }

        /** Set once the page load or the script shows a sign of life; read by the load watchdog. */
        @Volatile
        var progressed: Boolean = false
            private set

        private var tornDown = false

        /** Set while [requestDomDump] is waiting; completed by the `dom` message it asked for. */
        private var pendingDump: CompletableDeferred<Unit>? = null

        val messageListener =
            WebViewCompat.WebMessageListener { _, message, sourceOrigin, isMainFrame, _ ->
                if (isMainFrame && isFacebookOrigin(sourceOrigin)) onMessage(message)
            }

        val chromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?) =
                result.cancelled()

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?) =
                result.cancelled()

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ) = result.cancelled()

            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?) =
                result.cancelled()

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean = false

            /** Dismisses the dialog and tells the engine it was handled; nothing is ever shown. */
            private fun JsResult?.cancelled(): Boolean {
                this?.cancel()
                return true
            }
        }

        // androidx.webkit 1.17.0's MissingOnRenderProcessGone rule does not see overrides declared
        // in Kotlin (reproduced with a minimal top-level class): the override is right below.
        @SuppressLint("MissingOnRenderProcessGone")
        inner class CollectorClient(
            private val script: String,
            private val injectManually: Boolean,
        ) : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request?.isForMainFrame != true) return false
                val url = request.url?.toString()
                if (isAllowedNavigation(url)) return false
                fact { blocked(url) }
                if (endsScanAsBlocked(url)) endWith(EndReason.BLOCKED)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (injectManually) view?.evaluateJavascript(script, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressed = true
                fact {
                    pageFinished = true
                    if (atPageFinished == null && view != null) atPageFinished = measure(view)
                }
                if (injectManually) view?.evaluateJavascript(script, null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame != true) return
                fact {
                    if (mainFrameError == null) {
                        mainFrameError = runCatching {
                            "${error?.errorCode} ${error?.description}"
                        }.getOrDefault("unreadable")
                    }
                }
                endWith(EndReason.NETWORK_ERROR)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (request?.isForMainFrame != true) return
                val status = errorResponse?.statusCode ?: 0
                fact { if (httpStatus == null) httpStatus = status.toString() }
                // Facebook answering a main-frame request with 4xx/5xx is it refusing us, not the
                // network failing: recorded as BLOCKED so the scrape log tells the two apart.
                if (status >= 400) endWith(EndReason.BLOCKED)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // Cancel first: ending the scan tears the WebView down, and the handler belongs to it.
                handler?.cancel()
                fact { if (mainFrameError == null) mainFrameError = "SSL error" }
                endWith(EndReason.NETWORK_ERROR)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Returning true keeps the app alive. The WebView is unusable from here, so it is
                // detached and destroyed at once and a fresh one is built for the next scan.
                fact { if (mainFrameError == null) mainFrameError = "render process gone" }
                endWith(EndReason.NETWORK_ERROR)
                teardown()
                return true
            }
        }

        /**
         * Decoding stays here, on the main thread, deliberately.
         *
         * Two of the three messages are control, not data: `end` finishes the scan and `dom`
         * completes the handshake [requestDomDump] is waiting on. Both have to be applied in the
         * order they arrived, and both race teardown. Handing raw strings to another thread would
         * mean an ordered single consumer plus a rendezvous with that handshake — more moving
         * parts around the one part of the scan that must not deadlock, for a bounded cost: a few
         * chunks per scan, each capped at MAX_BODY, and the expensive half (walking the JSON for
         * posts) already runs off the main thread in ScanCoordinator.
         */
        private fun onMessage(message: WebMessageCompat) {
            progressed = true
            val raw = runCatching { message.data }.getOrNull() ?: return
            when (val decoded = CollectorMessage.decode(raw)) {
                is CollectorMessage.JsonChunk -> addChunk(decoded.body)
                is CollectorMessage.Dom -> {
                    setDomPosts(decoded.posts)
                    pendingDump?.complete(Unit)
                }
                is CollectorMessage.End -> endWith(decoded.reason)
                is CollectorMessage.Diag -> setDiag(decoded.body)
                null -> Unit // Unrecognised payload: ignored, never fatal.
            }
        }

        private fun isFacebookOrigin(origin: Uri): Boolean =
            origin.scheme.equals("https", ignoreCase = true) &&
                origin.host.equals("www.facebook.com", ignoreCase = true)

        /**
         * Best effort, on the main thread: ask the script to post its DOM posts and wait briefly
         * for them. Does nothing once the WebView is gone, and never fails the scan.
         */
        suspend fun requestDomDump() {
            if (tornDown) return
            val waiter = CompletableDeferred<Unit>()
            pendingDump = waiter
            try {
                val asked = runCatching { webView.evaluateJavascript(DOM_DUMP_JS, null) }.isSuccess
                if (asked) withTimeoutOrNull(DOM_DUMP_TIMEOUT_MS) { waiter.await() }
            } finally {
                pendingDump = null
            }
        }

        /** Safe to call twice: [onRenderProcessGone] and the scan's `finally` both call it. */
        fun teardown() {
            if (tornDown) return
            tornDown = true
            runCatching { webView.stopLoading() }
            runCatching { webView.loadUrl("about:blank") }
            runCatching { host.detach(webView) }
            runCatching { webView.removeAllViews() }
            runCatching { webView.destroy() }
        }
    }
}

/** `collector.js` is read from assets once per process and kept; it is a few kilobytes. */
private object CollectorScript {

    @Volatile
    private var cached: String? = null

    fun load(context: Context): String {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: read(context).also { cached = it }
        }
    }

    private fun read(context: Context): String = try {
        context.assets.open(ASSET_COLLECTOR_JS).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        ""
    }
}
