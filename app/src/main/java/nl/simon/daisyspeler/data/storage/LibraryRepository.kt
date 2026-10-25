package nl.simon.daisyspeler.data.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.simon.daisyspeler.data.book.BookPosition
import java.io.File

class LibraryRepository(
  file: File,
  private val now: () -> Long = System::currentTimeMillis,
) {
  private val store = JsonFileStore(file, LibraryData.serializer(), LibraryData())
  private val _entries = MutableStateFlow(store.read().entries)
  val entries: StateFlow<List<LibraryEntry>> = _entries.asStateFlow()

  @Synchronized
  fun get(id: String): LibraryEntry? = _entries.value.firstOrNull { it.id == id }

  @Synchronized
  fun addOrUpdate(entry: LibraryEntry): LibraryEntry {
    val current = _entries.value
    val previous = current.firstOrNull { it.id == entry.id }
    val merged =
      entry.copy(
        addedAtEpochMs = previous?.addedAtEpochMs ?: entry.addedAtEpochMs,
        lastPosition = previous?.lastPosition,
        lastReadAtEpochMs = previous?.lastReadAtEpochMs ?: entry.lastReadAtEpochMs,
        coverPath = entry.coverPath ?: previous?.coverPath,
      )
    val updated = current.filterNot { it.id == entry.id } + merged
    persist(updated)
    return merged
  }

  @Synchronized
  fun updateCover(bookId: String, coverPath: String?) {
    if (coverPath.isNullOrBlank()) return
    val current = _entries.value
    val index = current.indexOfFirst { it.id == bookId }
    if (index < 0 || current[index].coverPath == coverPath) return
    val updated = current.toMutableList()
    updated[index] = updated[index].copy(coverPath = coverPath)
    persist(updated)
  }

  @Synchronized
  fun updateLastPosition(bookId: String, position: BookPosition) {
    val current = _entries.value
    val index = current.indexOfFirst { it.id == bookId }
    if (index < 0) return
    val updated = current.toMutableList()
    updated[index] =
      updated[index].copy(lastPosition = position, lastReadAtEpochMs = now())
    persist(updated)
  }

  @Synchronized
  fun remove(bookId: String) {
    persist(_entries.value.filterNot { it.id == bookId })
  }

  private fun persist(entries: List<LibraryEntry>) {
    val sorted = entries.sortedByDescending { it.addedAtEpochMs }
    _entries.value = sorted
    store.write(LibraryData(sorted))
  }
}
