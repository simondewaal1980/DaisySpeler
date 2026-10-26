package nl.simon.daisyspeler.ui.cover

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebView.HitTestResult
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.simon.daisyspeler.AppContainer
import org.json.JSONTokener

/**
 * Small in-app browser that opens Google Images for the book. The user long-presses an image to
 * see a preview and can then save it as the cover of the book.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverBrowserScreen(
  container: AppContainer,
  bookId: String,
  onBack: () -> Unit,
) {
  val viewModel: CoverBrowserViewModel =
    viewModel(key = "cover-$bookId") { CoverBrowserViewModel(container, bookId) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val scope = rememberCoroutineScope()
  val snackbarHostState = remember { SnackbarHostState() }

  val initialQuery = viewModel.initialQuery.ifBlank { "boekomslag" }
  var query by rememberSaveable(bookId) { mutableStateOf(initialQuery) }
  var currentUrl by rememberSaveable(bookId) { mutableStateOf(imageSearchUrl(initialQuery)) }
  var canGoBack by remember { mutableStateOf(false) }
  var loading by remember { mutableStateOf(true) }

  val webView = remember(bookId) {
    CoverWebView(context).apply {
      configure()
      webViewClient =
        object : WebViewClient() {
          override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
          ): Boolean {
            val scheme = request.url.scheme?.lowercase()
            return scheme != "http" && scheme != "https"
          }

          override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            loading = true
            canGoBack = view?.canGoBack() == true
          }

          override fun onPageFinished(view: WebView?, url: String?) {
            loading = false
            canGoBack = view?.canGoBack() == true
          }

          override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            canGoBack = view?.canGoBack() == true
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
              currentUrl = url
            }
          }
        }
      setOnLongClickListener {
        requestImageAtPoint(
          webView = this,
          onImage = { url -> viewModel.selectImage(url, this.url) },
          onNothing = {
            scope.launch {
              snackbarHostState.showSnackbar(
                "Geen afbeelding gevonden. Houd een afbeelding zelf ingedrukt."
              )
            }
          },
        )
        true
      }
    }
  }

  LaunchedEffect(bookId) {
    webView.loadUrl(currentUrl)
    snackbarHostState.showSnackbar(
      "Tik op een afbeelding om hem groot te bekijken en houd hem ingedrukt om hem als " +
        "omslag te kiezen."
    )
  }

  DisposableEffect(webView) {
    onDispose {
      webView.stopLoading()
      webView.destroy()
    }
  }

  BackHandler(enabled = canGoBack) { webView.goBack() }

  LaunchedEffect(state.error) {
    val message = state.error
    if (message != null) {
      snackbarHostState.showSnackbar(message)
      viewModel.consumeError()
    }
  }

  LaunchedEffect(state.saved) {
    if (state.saved) {
      Toast.makeText(context, "Omslag opgeslagen", Toast.LENGTH_SHORT).show()
      onBack()
    }
  }

  fun submitSearch() {
    val normalized = query.trim()
    if (normalized.isEmpty()) return
    val url = imageSearchUrl(normalized)
    currentUrl = url
    webView.loadUrl(url)
    focusManager.clearFocus()
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Omslag zoeken") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
          }
        },
        actions = {
          IconButton(onClick = { webView.reload() }) {
            Icon(Icons.Default.Refresh, contentDescription = "Herladen")
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          modifier = Modifier.weight(1f),
          singleLine = true,
          placeholder = { Text("Zoek titel of auteur") },
          trailingIcon = {
            if (query.isNotEmpty()) {
              IconButton(onClick = { query = "" }) {
                Icon(Icons.Default.Close, contentDescription = "Zoekveld wissen")
              }
            }
          },
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
        )
        FilledIconButton(onClick = { submitSearch() }) {
          Icon(Icons.Default.Search, contentDescription = "Zoeken")
        }
      }

      if (loading) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      AndroidView(
        factory = { webView },
        modifier = Modifier.weight(1f).fillMaxWidth(),
      )
    }
  }

  if (state.fetching) {
    Dialog(onDismissRequest = {}) {
      Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
        Row(
          modifier = Modifier.padding(24.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          CircularProgressIndicator()
          Text("Afbeelding ophalen…")
        }
      }
    }
  }

  val pending = state.pendingCover
  if (pending != null) {
    AlertDialog(
      onDismissRequest = viewModel::cancelPending,
      title = { Text("Omslag gebruiken?") },
      text = {
        Column {
          CoverPreview(
            bytes = pending,
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
          )
          Spacer(Modifier.height(12.dp))
          Text(
            "Deze afbeelding wordt opgeslagen als omslag van dit boek.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      confirmButton = {
        TextButton(onClick = viewModel::confirmPending, enabled = !state.saving) {
          if (state.saving) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(8.dp))
          }
          Text(if (state.saving) "Opslaan…" else "Gebruiken")
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::cancelPending, enabled = !state.saving) {
          Text("Annuleren")
        }
      },
    )
  }
}

@SuppressLint("SetJavaScriptEnabled")
private fun CoverWebView.configure() {
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
    userAgentString = MOBILE_USER_AGENT
  }
}

/** Remembers the last touch position so the long-press handler knows which image was touched. */
private class CoverWebView(context: Context) : WebView(context) {
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

private fun requestImageAtPoint(
  webView: CoverWebView,
  onImage: (String) -> Unit,
  onNothing: () -> Unit,
) {
  val density = webView.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
  @Suppress("DEPRECATION") val scale = webView.scale.takeIf { it > 0f } ?: 1f
  val x = (webView.lastTouchX / (density * scale)).coerceAtLeast(0f)
  val y = (webView.lastTouchY / (density * scale)).coerceAtLeast(0f)

  webView.evaluateJavascript(imageAtPointScript(x, y)) { raw ->
    val fromDom = parseJsString(raw)
    val hit = webView.hitTestResult
    val fromHit =
      hit.extra?.takeIf {
        hit.type == HitTestResult.IMAGE_TYPE || hit.type == HitTestResult.SRC_ANCHOR_TYPE
      }
    val url = fromDom ?: fromHit
    if (url.isNullOrBlank()) {
      onNothing()
    } else {
      onImage(url)
    }
  }
}

private fun imageAtPointScript(x: Float, y: Float): String =
  """
  (function() {
    var el = document.elementFromPoint($x, $y);
    while (el) {
      if (el.tagName === 'IMG') {
        var src = el.currentSrc || el.src;
        if (src) return src;
      }
      el = el.parentElement;
    }
    return null;
  })()
  """.trimIndent()

private fun parseJsString(raw: String?): String? {
  if (raw.isNullOrBlank() || raw == "null") return null
  return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()
}

@Composable
private fun CoverPreview(bytes: ByteArray, modifier: Modifier = Modifier) {
  val image by produceState<ImageBitmap?>(initialValue = null, bytes) {
    value = withContext(Dispatchers.IO) { decodePreview(bytes) }
  }

  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    val bitmap = image
    if (bitmap != null) {
      Image(
        bitmap = bitmap,
        contentDescription = "Gekozen omslag",
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Icon(
        Icons.Default.BrokenImage,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(48.dp),
      )
    }
  }
}

private fun decodePreview(bytes: ByteArray): ImageBitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

  var sampleSize = 1
  val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
  while (largestSide / (sampleSize * 2) >= MAX_PREVIEW_SIDE) {
    sampleSize *= 2
  }

  val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
  return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}

private const val MAX_PREVIEW_SIDE = 1080

private const val MOBILE_USER_AGENT =
  "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/131.0.0.0 Mobile Safari/537.36"
