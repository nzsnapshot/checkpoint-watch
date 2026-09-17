package nz.personal.checkpointwatch.collect

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/**
 * Facebook's feed only loads more posts when it believes it is on screen (IntersectionObserver,
 * lazy rendering), so the collector's WebView has to be laid out at a realistic size. How that is
 * arranged differs between a foreground scan (there is an Activity window) and a background one
 * (there is none), which is what a host abstracts.
 *
 * Every member is called on the main thread.
 */
interface WebViewHost {
    fun attach(webView: WebView)
    fun detach(webView: WebView)
}

/** Size the collector's WebView pretends to be: a tall desktop viewport. */
internal const val COLLECTOR_WIDTH_PX = 1280
internal const val COLLECTOR_HEIGHT_PX = 2400

/**
 * A [WebView] that can be told it is on a visible window without one.
 *
 * `dispatchWindowVisibilityChanged` is `public` on [View] today but is not part of the documented
 * surface for third parties to call on another view, so the collector always builds this subclass
 * and [HeadlessHost] calls through it. That keeps the call inside a subclass of the view it
 * affects, which is legal regardless of whether the platform later narrows the method to
 * `protected`.
 */
class HeadlessWebView(context: Context) : WebView(context) {

    /** Tells the renderer the window showing this view is visible, so lazy loading runs. */
    fun forceWindowVisible() {
        dispatchWindowVisibilityChanged(View.VISIBLE)
    }
}

/**
 * Foreground host: puts the WebView in the Activity's content frame as the *first* child, so the
 * opaque Compose content added after it draws on top and Facebook is never seen. The view is
 * never brought to front and never takes focus or touches.
 */
class ActivityHost(private val activity: ComponentActivity) : WebViewHost {

    override fun attach(webView: WebView) {
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        webView.layoutParams = FrameLayout.LayoutParams(COLLECTOR_WIDTH_PX, COLLECTOR_HEIGHT_PX)
        content.addView(webView, 0)
    }

    override fun detach(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }
}

/**
 * Background host: there is no window at all. The WebView is measured and laid out by hand and
 * told its window is visible, which is enough for Chromium to run the page's lazy loading.
 *
 * `onResume`/`resumeTimers` are called as well, because an unattached WebView is otherwise liable
 * to have its timers throttled. Whether this is enough on a real device is the known risk in the
 * design spec; [HttpLatestFetcher] is the documented fallback when it is not.
 */
class HeadlessHost : WebViewHost {

    override fun attach(webView: WebView) {
        webView.layoutParams = ViewGroup.LayoutParams(COLLECTOR_WIDTH_PX, COLLECTOR_HEIGHT_PX)
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(COLLECTOR_WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(COLLECTOR_HEIGHT_PX, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, COLLECTOR_WIDTH_PX, COLLECTOR_HEIGHT_PX)
        (webView as? HeadlessWebView)?.forceWindowVisible()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun detach(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }
}
