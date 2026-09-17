package nz.personal.checkpointwatch.collect

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
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
import androidx.webkit.WebMessageCompat
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nz.personal.checkpointwatch.Constants
import java.net.URI

/** Origin the collector talks to; both the message listener and the injected script are tied to it. */
private const val FACEBOOK_ORIGIN = "https://www.facebook.com"

/** Name of the JS object `collector.js` posts through. Never an `addJavascriptInterface` bridge. */
private const val BRIDGE_NAME = "cwBridge"

private const val ASSET_COLLECTOR_JS = "collector.js"

/** No page load and no message within this long means the network is not cooperating. */
private const val PAGE_LOAD_TIMEOUT_MS = 20_000L

/** Hard ceiling on a whole scan; whatever was captured by then is kept. */
private const val SCAN_TIMEOUT_MS = 45_000L

/** Ceiling on buffered JSON, in characters (~8 MB of ASCII). Chunks past it are dropped. */
private const val MAX_BUFFERED_CHARS = 8L * 1024 * 1024

private val LOGIN_PATH_PREFIXES = listOf(
    "/login",
    "/checkpoint",
    "/r.php",
    "/reg",
    "/recover",
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
    val path = url?.toUriOrNull()?.path?.lowercase() ?: return false
    return LOGIN_PATH_PREFIXES.any { path == it || path.startsWith("$it/") }
}

private fun String.toUriOrNull(): URI? = try {
    URI(this)
} catch (_: Exception) {
    null
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
        return withContext(Dispatchers.Main.immediate) { runScan(host, script) }
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
            webView.webViewClient = session.CollectorClient(script, injectManually = !documentStart)
            webView.webChromeClient = session.chromeClient
            webView.setDownloadListener { _, _, _, _, _ -> /* the collector never downloads */ }

            host.attach(webView)
            webView.loadUrl(Constants.PAGE_URL)

            loadWatchdog = launch {
                delay(PAGE_LOAD_TIMEOUT_MS)
                if (!session.progressed) done.complete(EndReason.NETWORK_ERROR)
            }

            finish(withTimeoutOrNull(SCAN_TIMEOUT_MS) { done.await() } ?: EndReason.TIMEOUT)
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
            withContext(NonCancellable) { session.teardown() }
        }
    }

    /**
     * The one JS -> Kotlin channel, locked to Facebook's origin. Never `addJavascriptInterface`:
     * that would expose Kotlin methods to every script on the page.
     */
    @SuppressLint("RequiresFeature") // WEB_MESSAGE_LISTENER is checked at the top of runScan.
    private fun addBridge(webView: WebView, listener: WebViewCompat.WebMessageListener) =
        WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf(FACEBOOK_ORIGIN), listener)

    private fun reset() = synchronized(lock) {
        jsonChunks.clear()
        bufferedChars = 0
        domPosts = emptyList()
        finalEnd = null
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

    private fun clearBrowsingData() {
        // Every scan is a brand-new logged-out visitor: no carried-over cookies, no storage.
        try {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies(null)
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
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

    /** The mutable state belonging to one WebView, kept together so teardown can be idempotent. */
    private inner class Session(
        private val webView: HeadlessWebView,
        private val host: WebViewHost,
        private val done: CompletableDeferred<EndReason>,
    ) {
        /** Set once the page load or the script shows a sign of life; read by the load watchdog. */
        @Volatile
        var progressed: Boolean = false
            private set

        private var tornDown = false

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
                if (looksLikeLoginRedirect(url)) done.complete(EndReason.BLOCKED)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (injectManually) view?.evaluateJavascript(script, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressed = true
                if (injectManually) view?.evaluateJavascript(script, null)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) done.complete(EndReason.NETWORK_ERROR)
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                // Facebook answering a main-frame request with 4xx/5xx is it refusing us, not the
                // network failing: recorded as BLOCKED so the scrape log tells the two apart.
                if (request?.isForMainFrame == true && (errorResponse?.statusCode ?: 0) >= 400) {
                    done.complete(EndReason.BLOCKED)
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.cancel()
                done.complete(EndReason.NETWORK_ERROR)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Returning true keeps the app alive. The WebView is unusable from here, so it is
                // detached and destroyed at once and a fresh one is built for the next scan.
                done.complete(EndReason.NETWORK_ERROR)
                teardown()
                return true
            }
        }

        private fun onMessage(message: WebMessageCompat) {
            progressed = true
            val raw = runCatching { message.data }.getOrNull() ?: return
            when (val decoded = CollectorMessage.decode(raw)) {
                is CollectorMessage.JsonChunk -> addChunk(decoded.body)
                is CollectorMessage.Dom -> setDomPosts(decoded.posts)
                is CollectorMessage.End -> done.complete(decoded.reason)
                null -> Unit // Unrecognised payload: ignored, never fatal.
            }
        }

        private fun isFacebookOrigin(origin: Uri): Boolean =
            origin.scheme.equals("https", ignoreCase = true) &&
                origin.host.equals("www.facebook.com", ignoreCase = true)

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
