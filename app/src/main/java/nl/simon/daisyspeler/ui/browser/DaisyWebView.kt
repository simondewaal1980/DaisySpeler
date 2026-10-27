package nl.simon.daisyspeler.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * WebView shared by the cover browser and the audiobook websites browser. Keeping the browser
 * configuration in one place prevents the two in-app browsers from drifting apart.
 */
class DaisyWebView(context: Context) : WebView(context) {
  var lastTouchX: Float = 0f
    private set
  var lastTouchY: Float = 0f
    private set

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      lastTouchX = event.x
      lastTouchY = event.y
    }
    val handled = super.onTouchEvent(event)
    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
    return handled
  }

  override fun performClick(): Boolean {
    super.performClick()
    return true
  }
}

@SuppressLint("SetJavaScriptEnabled")
fun DaisyWebView.configureDaisyBrowser() {
  setBackgroundColor(AndroidColor.WHITE)
  settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true
    allowFileAccess = false
    allowContentAccess = false
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    setSupportZoom(true)
    builtInZoomControls = true
    displayZoomControls = false
    loadWithOverviewMode = true
    useWideViewPort = true
    userAgentString = DAISY_MOBILE_USER_AGENT
  }
}

const val DAISY_MOBILE_USER_AGENT =
  "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/131.0.0.0 Mobile Safari/537.36"
