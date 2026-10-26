package nl.simon.daisyspeler.data.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.simon.daisyspeler.data.book.BookPosition
import java.io.File
import java.util.UUID

class BookmarkRepository(file: File) {
  private val store = JsonFileStore(file, BookmarkData.serializer(), BookmarkData())
  private val stored = store.read()
  private val initial = stored.trimmed()
  private val _sessions = MutableStateFlow(initial.sessions)
  val sessions: StateFlow<List<BookmarkSession>> = _sessions.asStateFlow()
  private val _manual = MutableStateFlow(initial.manual)
  val manualBookmarks: StateFlow<List<ManualBookmark>> = _manual.asStateFlow()

  init {
    // Files from older versions can hold more bookmarks than the current limits allow.
    if (initial != stored) store.write(initial)
  }

  @Synchronized
  fun recordStart(bookId: String, position: BookPosition, now: Long = System.currentTimeMillis()) {
    val current = _sessions.value
    val activeIndex = current.indexOfLast { it.bookId == bookId && it.stop == null }
    if (activeIndex >= 0) {
      val active = current[activeIndex]
      if (now - active.start.createdAtEpochMs < MAX_ACTIVE_SESSION_MS) return

      // The app was probably killed before it could close this session. Close it at its start
      // so it cannot swallow the new listening session, then create a fresh one.
      val closed = active.copy(stop = active.start.copy(reason = "STALE"))
      val updated = current.toMutableList()
      updated[activeIndex] = closed
      val session =
        BookmarkSession(
          id = UUID.randomUUID().toString(),
          bookId = bookId,
          start = position.toBookmarkPoint(now),
        )
      persist(updated + session)
      return
    }

    val session =
      BookmarkSession(
        id = UUID.randomUUID().toString(),
        bookId = bookId,
        start = position.toBookmarkPoint(now),
      )
    persist(current + session)
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
  fun addManualBookmark(
    bookId: String,
    position: BookPosition,
    note: String? = null,
    now: Long = System.currentTimeMillis(),
  ): ManualBookmark {
    val bookmark =
      ManualBookmark(
        id = UUID.randomUUID().toString(),
        bookId = bookId,
        point = position.toBookmarkPoint(now),
        note = note?.trim()?.takeIf { it.isNotEmpty() },
      )
    persist(manual = _manual.value + bookmark)
    return bookmark
  }

  @Synchronized
  fun removeBook(bookId: String) {
    persist(
      sessions = _sessions.value.filterNot { it.bookId == bookId },
      manual = _manual.value.filterNot { it.bookId == bookId },
    )
  }

  @Synchronized
  fun removeSession(bookId: String, sessionId: String) {
    persist(sessions = _sessions.value.filterNot { it.bookId == bookId && it.id == sessionId })
  }

  @Synchronized
  fun removeSessions(bookId: String) {
    persist(sessions = _sessions.value.filterNot { it.bookId == bookId })
  }

  @Synchronized
  fun removeManualBookmark(bookId: String, bookmarkId: String) {
    persist(manual = _manual.value.filterNot { it.bookId == bookId && it.id == bookmarkId })
  }

  @Synchronized
  fun removeManualBookmarks(bookId: String) {
    persist(manual = _manual.value.filterNot { it.bookId == bookId })
  }

  fun sessionsForBook(bookId: String): List<BookmarkSession> =
    _sessions.value.filter { it.bookId == bookId }.sortedByDescending { it.start.createdAtEpochMs }

  fun manualBookmarksForBook(bookId: String): List<ManualBookmark> =
    _manual.value.filter { it.bookId == bookId }.sortedByDescending { it.point.createdAtEpochMs }

  private fun persist(
    sessions: List<BookmarkSession> = _sessions.value,
    manual: List<ManualBookmark> = _manual.value,
  ) {
    val trimmed = BookmarkData(sessions = sessions, manual = manual).trimmed()
    _sessions.value = trimmed.sessions
    _manual.value = trimmed.manual
    store.write(trimmed)
  }

  private fun BookmarkData.trimmed(): BookmarkData =
    BookmarkData(sessions = sessions.trimSessions(), manual = manual.trimManual())

  /** Keeps only the most recent automatic bookmarks per book and drops the older ones. */
  private fun List<BookmarkSession>.trimSessions(): List<BookmarkSession> =
    groupBy { it.bookId }
      .values
      .flatMap { list ->
        list.sortedByDescending { it.start.createdAtEpochMs }.take(MAX_AUTOMATIC_PER_BOOK)
      }
      .sortedBy { it.start.createdAtEpochMs }

  private fun List<ManualBookmark>.trimManual(): List<ManualBookmark> =
    groupBy { it.bookId }
      .values
      .flatMap { list ->
        list.sortedByDescending { it.point.createdAtEpochMs }.take(MAX_MANUAL_PER_BOOK)
      }
      .sortedBy { it.point.createdAtEpochMs }

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
    /** Only the five most recent listening sessions per book are kept. */
    const val MAX_AUTOMATIC_PER_BOOK = 5
    const val MAX_MANUAL_PER_BOOK = 200

    /** A session that stays open longer than this is treated as left behind by a crash. */
    const val MAX_ACTIVE_SESSION_MS = 12 * 60 * 60 * 1000L
  }
}
