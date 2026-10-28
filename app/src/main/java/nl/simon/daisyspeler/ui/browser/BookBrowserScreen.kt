package nl.simon.daisyspeler.ui.browser

import android.Manifest
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.download.BrowserDownload
import nl.simon.daisyspeler.data.download.DownloadFolders
import nl.simon.daisyspeler.data.website.AudiobookSite

/**
 * In-app browser for the websites where the user has an account. The cover browser already
 * provides the shared [DaisyWebView] and browser configuration, so this screen only adds the
 * site picker and the download/import flow. Downloads go to the configured folder (by default
 * Download/audioboeken) and are imported into the library as soon as they finish.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookBrowserScreen(
  container: AppContainer,
  initialSite: AudiobookSite,
  onBack: () -> Unit,
) {
  val viewModel: BookBrowserViewModel = viewModel { BookBrowserViewModel(container) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  val checkingDownloads by viewModel.checkingDownloads.collectAsStateWithLifecycle()
  val importingFileName by viewModel.importingFileName.collectAsStateWithLifecycle()
  val folderName by viewModel.downloadFolder.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val snackbarHostState = remember { SnackbarHostState() }

  var site by remember { mutableStateOf(initialSite) }
  var loading by remember { mutableStateOf(true) }
  var progress by remember { mutableIntStateOf(0) }
  var canGoBack by remember { mutableStateOf(false) }
  var showSiteMenu by remember { mutableStateOf(false) }
  var showFolderDialog by remember { mutableStateOf(false) }

  val storagePermissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        viewModel.ensureDownloadFolder()
      } else {
        viewModel.reportStoragePermissionDenied()
      }
    }

  val webView = remember {
    DaisyWebView(context).apply {
      configureDaisyBrowser()
      webChromeClient =
        object : WebChromeClient() {
          override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress = newProgress
            if (newProgress >= 100) loading = false
          }
        }
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
            progress = 0
            canGoBack = view?.canGoBack() == true
          }

          override fun onPageFinished(view: WebView?, url: String?) {
            loading = false
            canGoBack = view?.canGoBack() == true
          }

          override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            canGoBack = view?.canGoBack() == true
          }
        }
      setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        val target = url?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (target == null) {
          viewModel.reportUnsupportedDownload()
        } else {
          viewModel.download(
            BrowserDownload(
              url = target,
              userAgent = userAgent,
              contentDisposition = contentDisposition,
              mimeType = mimeType,
              referer = this.url,
            )
          )
        }
      }
    }
  }

  LaunchedEffect(webView) {
    webView.loadUrl(initialSite.startUrl)
    snackbarHostState.showSnackbar(
      "Log in en tik bij een boek op Downloaden. ZIP-bestanden worden automatisch aan de " +
        "bibliotheek toegevoegd."
    )
  }

  LaunchedEffect(Unit) {
    if (container.audiobookDownloads.canDownloadToFolder()) {
      viewModel.ensureDownloadFolder()
    } else {
      storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
  }

  LaunchedEffect(state.message) {
    val message = state.message ?: return@LaunchedEffect
    snackbarHostState.showSnackbar(message)
    viewModel.consumeMessage()
  }

  // Finished downloads are imported when the user returns to the app, for example after
  // following the download notification or switching back from another app.
  DisposableEffect(lifecycleOwner) {
    val observer =
      LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) viewModel.checkCompletedDownloads()
      }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  DisposableEffect(webView) {
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    onDispose {
      webView.stopLoading()
      webView.destroy()
      CookieManager.getInstance().flush()
    }
  }

  BackHandler(enabled = canGoBack) { webView.goBack() }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(site.title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
          }
        },
        actions = {
          IconButton(onClick = { webView.reload() }) {
            Icon(Icons.Default.Refresh, contentDescription = "Herladen")
          }
          Box {
            IconButton(onClick = { showSiteMenu = true }) {
              Icon(Icons.Default.Public, contentDescription = "Andere website")
            }
            DropdownMenu(expanded = showSiteMenu, onDismissRequest = { showSiteMenu = false }) {
              AudiobookSite.entries.forEach { option ->
                DropdownMenuItem(
                  text = {
                    Column {
                      Text(option.title)
                      Text(
                        option.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  },
                  leadingIcon = {
                    if (option == site) {
                      Icon(Icons.Default.Check, contentDescription = "Gekozen website")
                    } else {
                      Spacer(Modifier.size(24.dp))
                    }
                  },
                  onClick = {
                    showSiteMenu = false
                    if (option != site) {
                      site = option
                      webView.loadUrl(option.startUrl)
                    }
                  },
                )
              }
            }
          }
        },
      )
    },
    bottomBar = {
      DownloadFolderBar(
        folderLabel = viewModel.folderLabel,
        checking = checkingDownloads,
        onChangeFolder = { showFolderDialog = true },
        onImportDownloads = viewModel::checkCompletedDownloads,
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      importingFileName?.let { fileName ->
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
          text = "Download voltooid. $fileName wordt uitgepakt en toegevoegd aan de bibliotheek…",
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (loading) {
        LinearProgressIndicator(
          progress = { progress / 100f },
          modifier = Modifier.fillMaxWidth(),
        )
      }
      AndroidView(
        factory = { webView },
        modifier = Modifier.weight(1f).fillMaxWidth(),
      )
    }
  }

  if (showFolderDialog) {
    var folderInput by remember { mutableStateOf(folderName) }
    val normalized = DownloadFolders.normalize(folderInput)
    AlertDialog(
      onDismissRequest = { showFolderDialog = false },
      title = { Text("Downloadmap") },
      text = {
        Column {
          Text(
            "Boeken die je in de browser downloadt, worden in deze map bewaard. Standaard " +
              "is dat ‘${DownloadFolders.DEFAULT_NAME}’.",
            style = MaterialTheme.typography.bodyMedium,
          )
          Spacer(Modifier.height(12.dp))
          OutlinedTextField(
            value = folderInput,
            onValueChange = { folderInput = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Mapnaam") },
          )
          Spacer(Modifier.height(8.dp))
          Text(
            "Download/$normalized",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.setDownloadFolder(folderInput)
            showFolderDialog = false
          }
        ) { Text("Opslaan") }
      },
      dismissButton = {
        TextButton(onClick = { showFolderDialog = false }) { Text("Annuleren") }
      },
    )
  }
}

@Composable
private fun DownloadFolderBar(
  folderLabel: String,
  checking: Boolean,
  onChangeFolder: () -> Unit,
  onImportDownloads: () -> Unit,
) {
  Surface(tonalElevation = 2.dp) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        Icons.Default.Folder,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.width(4.dp))
      Text(
        folderLabel,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      TextButton(onClick = onChangeFolder) { Text("Wijzigen") }
      TextButton(onClick = onImportDownloads, enabled = !checking) {
        if (checking) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
        }
        Text("Toevoegen")
      }
    }
  }
}
