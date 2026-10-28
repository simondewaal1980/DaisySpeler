package nl.simon.daisyspeler.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.simon.daisyspeler.data.book.BookPosition
import nl.simon.daisyspeler.data.storage.BookmarkRepository
import nl.simon.daisyspeler.data.storage.LibraryEntry
import nl.simon.daisyspeler.data.storage.LibraryRepository
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStorageTest {
  @Test
  fun `shutdown persists final position after queued starts and older positions`() = runTest {
    val directory = Files.createTempDirectory("playback-storage").toFile()
    val storage = PlaybackStorage(StandardTestDispatcher(testScheduler)) { _, error -> throw error }
    try {
      val libraryFile = directory.resolve("library.json")
      val bookmarksFile = directory.resolve("bookmarks.json")
      val library = LibraryRepository(libraryFile)
      val bookmarks = BookmarkRepository(bookmarksFile)
      library.addOrUpdate(LibraryEntry("book", "content://book", "Boek", null, 1000L, 1, 1L))
      val start = BookPosition("a.mp3", 100L, 100L)
      val stop = BookPosition("a.mp3", 900L, 900L)
      storage.enqueue("start") { bookmarks.recordStart("book", start, now = 1L) }
      storage.enqueue("position") { library.updateLastPosition("book", start) }

      storage.finish("stop") {
        bookmarks.recordStop("book", stop, "STOP", now = 2L)
        library.updateLastPosition("book", stop)
      }

      assertEquals(stop, LibraryRepository(libraryFile).get("book")?.lastPosition)
      assertEquals("STOP", BookmarkRepository(bookmarksFile).sessionsForBook("book").single().stop?.reason)
      assertEquals(900L, bookmarks.sessionsForBook("book").single().stop?.globalMs)
    } finally {
      storage.cancel()
      directory.deleteRecursively()
    }
  }

  @Test
  fun `failed writes do not prevent later queued writes and final snapshot`() = runTest {
    val errors = mutableListOf<String>()
    val writes = mutableListOf<String>()
    val storage = PlaybackStorage(StandardTestDispatcher(testScheduler)) { description, _ -> errors += description }
    try {
      storage.enqueue("failed") { error("disk failure") }
      storage.enqueue("next") { writes += "next" }
      storage.finish("stop") { writes += "stop" }
      assertEquals(listOf("failed"), errors)
      assertEquals(listOf("next", "stop"), writes)
    } finally {
      storage.cancel()
    }
  }
}
