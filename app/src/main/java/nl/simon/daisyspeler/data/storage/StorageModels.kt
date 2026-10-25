package nl.simon.daisyspeler.data.storage

import kotlinx.serialization.Serializable
import nl.simon.daisyspeler.data.book.BookPosition

@Serializable
data class LibraryData(val entries: List<LibraryEntry> = emptyList())

@Serializable
enum class LibrarySourceType {
  FOLDER,
  ZIP,
}

@Serializable
data class LibraryEntry(
  val id: String,
  val treeUri: String,
  val title: String,
  val author: String?,
  val durationMs: Long,
  val tocCount: Int,
  val addedAtEpochMs: Long,
  val lastPosition: BookPosition? = null,
  /** When the book was last played, used for the "laatst gelezen" sort order. */
  val lastReadAtEpochMs: Long? = null,
  val sourceType: LibrarySourceType = LibrarySourceType.FOLDER,
  val localDir: String? = null,
  /** Absolute path of the cached cover image, if the book has one. */
  val coverPath: String? = null,
)

@Serializable
data class BookmarkData(
  val sessions: List<BookmarkSession> = emptyList(),
  val manual: List<ManualBookmark> = emptyList(),
)

@Serializable
data class BookmarkSession(
  val id: String,
  val bookId: String,
  val start: BookmarkPoint,
  val stop: BookmarkPoint? = null,
)

/** A bookmark that the user added by hand, optionally with a short note. */
@Serializable
data class ManualBookmark(
  val id: String,
  val bookId: String,
  val point: BookmarkPoint,
  val note: String? = null,
)

@Serializable
data class BookmarkPoint(
  val sourcePath: String,
  val sourceOffsetMs: Long,
  val globalMs: Long,
  val label: String? = null,
  val createdAtEpochMs: Long = 0L,
  val reason: String? = null,
)

@Serializable
data class SleepTimerData(val state: SleepTimerState? = null)

@Serializable
data class SleepTimerState(
  val bookId: String,
  val deadlineEpochMs: Long,
  val durationMs: Long,
  val startedAtEpochMs: Long,
)
