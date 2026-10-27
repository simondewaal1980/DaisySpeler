package nl.simon.daisyspeler.data

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.simon.daisyspeler.data.storage.LibraryEntry
import nl.simon.daisyspeler.data.storage.LibraryRepository
import nl.simon.daisyspeler.data.storage.LibrarySourceType

/**
 * Imports a DAISY book and immediately stores it in the library. Shared by the file pickers and
 * the in-app book browser so both entry points behave the same.
 */
class BookImporter(
  private val bookRepository: BookRepository,
  private val libraryRepository: LibraryRepository,
) {
  suspend fun importTree(uri: Uri): LoadedBook = import(uri, isZip = false)

  suspend fun importZip(uri: Uri): LoadedBook = import(uri, isZip = true)

  private suspend fun import(uri: Uri, isZip: Boolean): LoadedBook {
    val loaded =
      if (isZip) bookRepository.importZip(uri) else bookRepository.importTree(uri.toString())
    val book = loaded.book
    withContext(Dispatchers.IO) {
      libraryRepository.addOrUpdate(
        LibraryEntry(
          id = loaded.id,
          treeUri = uri.toString(),
          title = book.title.ifBlank { "Naamloos boek" },
          author = book.author,
          durationMs = book.totalDurationMs,
          tocCount = book.toc.size,
          addedAtEpochMs = System.currentTimeMillis(),
          sourceType = if (isZip) LibrarySourceType.ZIP else LibrarySourceType.FOLDER,
          localDir = loaded.localDir,
          coverPath = loaded.coverPath,
        )
      )
    }
    return loaded
  }
}
