package nl.simon.daisyspeler

import android.content.Context
import nl.simon.daisyspeler.data.BookRepository
import nl.simon.daisyspeler.data.storage.BookmarkRepository
import nl.simon.daisyspeler.data.storage.LibraryRepository
import nl.simon.daisyspeler.data.storage.SleepTimerManager
import nl.simon.daisyspeler.data.storage.UiPreferences
import java.io.File

class AppContainer(context: Context) {
  val context: Context = context.applicationContext
  private val filesDir = this.context.filesDir

  val libraryRepository = LibraryRepository(File(filesDir, "library.json"))
  val bookmarkRepository = BookmarkRepository(File(filesDir, "bookmarks.json"))
  val sleepTimerManager = SleepTimerManager(File(filesDir, "sleep_timer.json"))
  val uiPreferences = UiPreferences(context)
  val bookRepository = BookRepository(context, libraryRepository)
}
