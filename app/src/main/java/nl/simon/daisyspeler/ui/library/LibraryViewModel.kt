package nl.simon.daisyspeler.ui.library

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.storage.LibraryEntry
import nl.simon.daisyspeler.data.storage.LibrarySourceType

data class LibraryUiState(
  val entries: List<LibraryEntry> = emptyList(),
  val importing: Boolean = false,
  val error: String? = null,
)

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
  private val local = MutableStateFlow(LibraryUiState())
  private val coverRequests = mutableSetOf<String>()

  val uiState: StateFlow<LibraryUiState> =
    combine(local, container.libraryRepository.entries) { state, entries ->
        state.copy(entries = entries)
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

  fun importTree(uri: Uri) = import(uri, isZip = false)

  fun importZip(uri: Uri) = import(uri, isZip = true)

  private fun import(uri: Uri, isZip: Boolean) {
    if (local.value.importing) return
    viewModelScope.launch {
      local.update { it.copy(importing = true, error = null) }
      runCatching {
          val loaded =
            if (isZip) container.bookRepository.importZip(uri)
            else container.bookRepository.importTree(uri.toString())
          val book = loaded.book
          container.libraryRepository.addOrUpdate(
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
        .onFailure { throwable ->
          local.update {
            it.copy(
              error = throwable.message
                ?: if (isZip) {
                  "Dit ZIP-bestand bevat geen geldig DAISY 2.02-boek."
                } else {
                  "Dit lijkt geen geldig uitgepakt DAISY 2.02-boek te zijn."
                }
            )
          }
        }
      local.update { it.copy(importing = false) }
    }
  }

  fun remove(entry: LibraryEntry) {
    viewModelScope.launch {
      container.libraryRepository.remove(entry.id)
      container.bookmarkRepository.removeBook(entry.id)
      container.bookRepository.forget(entry.id)
      container.bookRepository.deleteLocalFiles(entry)
      if (entry.sourceType != LibrarySourceType.ZIP) {
        runCatching {
          container.context.contentResolver.releasePersistableUriPermission(
            Uri.parse(entry.treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
      }
    }
  }

  fun clearError() {
    local.update { it.copy(error = null) }
  }

  /** Backfills the cover of a book that was imported before cover support existed. */
  fun ensureCover(entry: LibraryEntry) {
    if (entry.coverPath != null || !coverRequests.add(entry.id)) return
    viewModelScope.launch {
      runCatching { container.bookRepository.ensureCover(entry) }
      coverRequests.remove(entry.id)
    }
  }
}
