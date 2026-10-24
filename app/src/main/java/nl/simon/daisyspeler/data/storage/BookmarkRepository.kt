package nl.simon.daisyspeler.data.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.simon.daisyspeler.data.book.BookPosition
import java.io.File
import java.util.UUID

class BookmarkRepository(file: File) {
  private val store = JsonFileStore(file, BookmarkData.serializer(), BookmarkData())
  private val _sessions = MutableStateFlow(store.read().sessions)
  val sessions: StateFlow<List<BookmarkSession>> = _sessions.asStateFlow()

  @Synchronized
  fun recordStart(bookId: String, position: BookPosition, now: Long = System.currentTimeMillis()) {
    val current = _sessions.value
    val active = current.lastOrNull { it.bookId == bookId && it.stop == null }
    if (active != null) return
    val point = position.toBookmarkPoint(now)
    val session =
      BookmarkSession(
        id = UUID.randomUUID().toString(),
        bookId = bookId,
        start = point,
      )
    val updated = current + session
    persist(updated)
  }

  @Synchronized
  fun recordStop(
    bookId: String,
    position: BookPosition,
    reason: String,
    now: Long = System.currentTimeMillis(),
  ) {
    val current = _sessions.value
    val index = current.indexOfLast { it.bookId == bookId && it.stop == null }
    if (index < 0) return
    val updated = current.toMutableList()
    updated[index] = updated[index].copy(stop = position.toBookmarkPoint(now, reason))
    persist(updated)
  }

  @Synchronized
  fun removeBook(bookId: String) {
    persist(_sessions.value.filterNot { it.bookId == bookId })
  }

  @Synchronized
  fun removeSession(bookId: String, sessionId: String) {
    persist(_sessions.value.filterNot { it.bookId == bookId && it.id == sessionId })
  }

  @Synchronized
  fun removeSessions(bookId: String) {
    persist(_sessions.value.filterNot { it.bookId == bookId })
  }

  fun sessionsForBook(bookId: String): List<BookmarkSession> =
    _sessions.value.filter { it.bookId == bookId }.sortedByDescending { it.start.createdAtEpochMs }

  private fun persist(sessions: List<BookmarkSession>) {
    val trimmed =
      sessions
        .groupBy { it.bookId }
        .flatMap { (_, list) ->
          list.sortedByDescending { it.start.createdAtEpochMs }.take(MAX_SESSIONS_PER_BOOK)
        }
        .sortedBy { it.start.createdAtEpochMs }
    _sessions.value = trimmed
    store.write(BookmarkData(trimmed))
  }

  private fun BookPosition.toBookmarkPoint(now: Long, reason: String? = null): BookmarkPoint =
    BookmarkPoint(
      sourcePath = sourcePath,
      sourceOffsetMs = sourceOffsetMs,
      globalMs = globalMs,
      label = label,
      createdAtEpochMs = now,
      reason = reason,
    )

  private companion object {
    const val MAX_SESSIONS_PER_BOOK = 100
  }
}
