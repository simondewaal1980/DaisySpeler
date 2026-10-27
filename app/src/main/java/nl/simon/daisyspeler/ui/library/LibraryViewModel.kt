package nl.simon.daisyspeler.ui.library

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.storage.LibraryEntry
import nl.simon.daisyspeler.data.storage.LibrarySourceType
import nl.simon.daisyspeler.playback.DaisyPlaybackService
import nl.simon.daisyspeler.playback.PlaybackCommands
import java.util.concurrent.TimeUnit

data class LibraryUiState(
  val entries: List<LibraryEntry> = emptyList(),
  val sortOrder: LibrarySortOrder = LibrarySortOrder.LAST_READ,
  val importing: Boolean = false,
  val error: String? = null,
)

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
  private val local = MutableStateFlow(LibraryUiState())
  private val coverRequests = mutableSetOf<String>()
  private val preferences =
    container.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val sortOrder = MutableStateFlow(readSortOrder())

  val uiState: StateFlow<LibraryUiState> =
    combine(local, container.libraryRepository.entries, sortOrder) { state, entries, order ->
        state.copy(entries = sortLibraryEntries(entries, order), sortOrder = order)
      }
      .stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LibraryUiState(sortOrder = sortOrder.value),
      )

  fun importTree(uri: Uri) = import(uri, isZip = false)

  fun importZip(uri: Uri) = import(uri, isZip = true)

  private fun import(uri: Uri, isZip: Boolean) {
    if (local.value.importing) return
    local.update { it.copy(importing = true, error = null) }
    viewModelScope.launch {
      try {
        if (isZip) {
          container.bookImporter.importZip(uri)
        } else {
          container.bookImporter.importTree(uri)
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (throwable: Throwable) {
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
      } finally {
        local.update { it.copy(importing = false) }
      }
    }
  }

  fun remove(entry: LibraryEntry) {
    viewModelScope.launch {
      try {
        withContext(Dispatchers.IO) {
          container.libraryRepository.remove(entry.id)
        }
        unloadBook(entry.id)
        withContext(Dispatchers.IO) {
          container.bookmarkRepository.removeBook(entry.id)
          container.bookRepository.forget(entry.id)
          container.bookRepository.deleteLocalFiles(entry)
        }
        if (entry.sourceType != LibrarySourceType.ZIP) {
          runCatching {
            container.context.contentResolver.releasePersistableUriPermission(
              Uri.parse(entry.treeUri),
              Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
          }
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        local.update {
          it.copy(
            error = error.message ?: "Het boek kon niet volledig worden verwijderd."
          )
        }
      }
    }
  }

  /** Stops a book in the playback service before its files are removed. */
  private suspend fun unloadBook(bookId: String) {
    val context = container.context
    val token =
      SessionToken(context, ComponentName(context, DaisyPlaybackService::class.java))
    val future =
      try {
        MediaController.Builder(context, token).buildAsync()
      } catch (error: Throwable) {
        Log.w(TAG, "Kon playback niet stoppen voor verwijderd boek $bookId", error)
        return
      }
    try {
      // MediaController methods must run on the main thread; only the waits happen on IO.
      val controller = withContext(Dispatchers.IO) { future.get(5, TimeUnit.SECONDS) }
      val args =
        Bundle().apply {
          putString(PlaybackCommands.KEY_BOOK_ID, bookId)
        }
      val command =
        withContext(Dispatchers.Main) {
          controller.sendCustomCommand(
            SessionCommand(PlaybackCommands.UNLOAD_BOOK, Bundle.EMPTY),
            args,
          )
        }
      withContext(Dispatchers.IO) { command.get(5, TimeUnit.SECONDS) }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      Log.w(TAG, "Kon playback niet stoppen voor verwijderd boek $bookId", error)
    } finally {
      withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
        MediaController.releaseFuture(future)
      }
    }
  }

  fun clearError() {
    local.update { it.copy(error = null) }
  }

  fun setSortOrder(order: LibrarySortOrder) {
    if (sortOrder.value == order) return
    sortOrder.value = order
    preferences.edit().putString(KEY_SORT_ORDER, order.name).apply()
  }

  private fun readSortOrder(): LibrarySortOrder {
    val stored = preferences.getString(KEY_SORT_ORDER, null) ?: return DEFAULT_SORT_ORDER
    return LibrarySortOrder.entries.firstOrNull { it.name == stored } ?: DEFAULT_SORT_ORDER
  }

  /**
   * Ensures a cover is present and repairs a cover path whose file has disappeared. The
   * repository checks the file itself, so callers no longer skip this when coverPath is set.
   */
  fun ensureCover(entry: LibraryEntry) {
    if (!coverRequests.add(entry.id)) return
    viewModelScope.launch {
      try {
        container.bookRepository.ensureCover(entry)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        // A later screen visit can retry; do not crash the library for a missing cover.
      } finally {
        coverRequests.remove(entry.id)
      }
    }
  }

  private companion object {
    const val TAG = "DaisyLibrary"
    const val PREFS_NAME = "daisy_library"
    const val KEY_SORT_ORDER = "sort_order"
    val DEFAULT_SORT_ORDER = LibrarySortOrder.LAST_READ
  }
}
