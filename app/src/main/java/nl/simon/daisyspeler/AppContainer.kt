package nl.simon.daisyspeler

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.simon.daisyspeler.data.BookImporter
import nl.simon.daisyspeler.data.BookRepository
import nl.simon.daisyspeler.data.download.AudiobookDownloads
import nl.simon.daisyspeler.data.download.DownloadImportMonitor
import nl.simon.daisyspeler.data.storage.BookmarkRepository
import nl.simon.daisyspeler.data.storage.LibraryRepository
import nl.simon.daisyspeler.data.storage.SleepTimerManager
import nl.simon.daisyspeler.data.storage.UiPreferences
import java.io.File

class AppContainer(context: Context) {
  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  val context: Context = context.applicationContext
  private val filesDir = this.context.filesDir

  val libraryRepository = LibraryRepository(File(filesDir, "library.json"))
  val bookmarkRepository = BookmarkRepository(File(filesDir, "bookmarks.json"))
  val sleepTimerManager = SleepTimerManager(File(filesDir, "sleep_timer.json"))
  val uiPreferences = UiPreferences(context)
  val bookRepository = BookRepository(context, libraryRepository)
  val bookImporter = BookImporter(bookRepository, libraryRepository)
  val audiobookDownloads = AudiobookDownloads(context, uiPreferences)
  internal val downloadImportMonitor = DownloadImportMonitor(appScope) {
    audiobookDownloads.importCompleted(bookImporter)
  }

  private val downloadReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
      val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
      // This receiver accepts DownloadProvider broadcasts, but only checks our own transfers.
      if (audiobookDownloads.isPendingDownload(id)) downloadImportMonitor.check()
    }
  }

  init {
    // DownloadProvider runs outside our UID. NOT_EXPORTED can hide its completion broadcast.
    // Keep listening when the user leaves the browser and returns to the library.
    ContextCompat.registerReceiver(
      this.context,
      downloadReceiver,
      IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
      ContextCompat.RECEIVER_EXPORTED,
    )
    appScope.launch {
      audiobookDownloads.ensureFolder()
      // Imports downloads that finished after the browser was closed or the app was killed.
      downloadImportMonitor.check()
    }
  }
}
