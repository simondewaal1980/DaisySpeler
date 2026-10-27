package nl.simon.daisyspeler

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import nl.simon.daisyspeler.data.BookImporter
import nl.simon.daisyspeler.data.BookRepository
import nl.simon.daisyspeler.data.download.AudiobookDownloads
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

  init {
    appScope.launch {
      audiobookDownloads.ensureFolder()
      // Imports downloads that finished after the browser was closed or the app was killed.
      audiobookDownloads.importCompleted(bookImporter)
    }
  }
}
