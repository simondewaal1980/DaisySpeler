package nl.simon.daisyspeler.data.storage

import nl.simon.daisyspeler.data.book.BookPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BookmarkRepositoryTest {
  @Test
  fun `records start and stop as one session and persists it`() {
    val file = Files.createTempDirectory("bookmarks").resolve("bookmarks.json").toFile()
    val repository = BookmarkRepository(file)
    val start = BookPosition("book/a.mp3", 1_000L, 1_000L, "Hoofdstuk 1")
    val stop = BookPosition("book/a.mp3", 5_000L, 5_000L, "Hoofdstuk 1 · pagina 2")

    repository.recordStart("book", start, now = 100L)
    repository.recordStart("book", start, now = 200L)
    assertEquals(1, repository.sessionsForBook("book").size)

    repository.recordStop("book", stop, reason = "PAUSE", now = 300L)
    val session = repository.sessionsForBook("book").single()
    assertNotNull(session.stop)
    assertEquals("PAUSE", session.stop?.reason)
    assertEquals(5_000L, session.stop?.globalMs)
    assertNull(repository.sessionsForBook("other").firstOrNull())

    val reloaded = BookmarkRepository(file)
    val persisted = reloaded.sessionsForBook("book").single()
    assertEquals("Hoofdstuk 1", persisted.start.label)
    assertEquals("PAUSE", persisted.stop?.reason)
  }

  @Test
  fun `removes only the selected session and persists the deletion`() {
    val file = Files.createTempDirectory("bookmarks3").resolve("bookmarks.json").toFile()
    val repository = BookmarkRepository(file)
    repository.recordStart("book", BookPosition("a.mp3", 100L, 100L, null), now = 1L)
    repository.recordStop(
      "book",
      BookPosition("a.mp3", 200L, 200L, null),
      reason = "PAUSE",
      now = 2L,
    )
    repository.recordStart("book", BookPosition("a.mp3", 300L, 300L, null), now = 3L)
    repository.recordStart("other", BookPosition("b.mp3", 400L, 400L, null), now = 4L)

    val sessionId = repository.sessionsForBook("book").last().id
    repository.removeSession("other", sessionId)
    assertEquals(2, repository.sessionsForBook("book").size)

    repository.removeSession("book", sessionId)
    assertEquals(1, repository.sessionsForBook("book").size)
    assertEquals(1, repository.sessionsForBook("other").size)
    assertEquals(1, BookmarkRepository(file).sessionsForBook("book").size)
  }

  @Test
  fun `removes all sessions of one book and keeps other books`() {
    val file = Files.createTempDirectory("bookmarks4").resolve("bookmarks.json").toFile()
    val repository = BookmarkRepository(file)
    repository.recordStart("book", BookPosition("a.mp3", 100L, 100L, null), now = 1L)
    repository.recordStop(
      "book",
      BookPosition("a.mp3", 200L, 200L, null),
      reason = "PAUSE",
      now = 2L,
    )
    repository.recordStart("book", BookPosition("a.mp3", 300L, 300L, null), now = 3L)
    repository.recordStart("other", BookPosition("b.mp3", 400L, 400L, null), now = 4L)

    repository.removeSessions("book")

    assertEquals(0, repository.sessionsForBook("book").size)
    assertEquals(1, repository.sessionsForBook("other").size)
    assertEquals(0, BookmarkRepository(file).sessionsForBook("book").size)
    assertEquals(1, BookmarkRepository(file).sessionsForBook("other").size)
  }

  @Test
  fun `adds a manual bookmark with a trimmed note and persists it`() {
    val file = Files.createTempDirectory("bookmarks-manual").resolve("bookmarks.json").toFile()
    val repository = BookmarkRepository(file)
    val position = BookPosition("book/a.mp3", 2_000L, 3_000L, "Hoofdstuk 2")

    repository.addManualBookmark("book", position, note = "  Belangrijk stuk  ", now = 100L)

    val bookmark = repository.manualBookmarksForBook("book").single()
    assertEquals("Belangrijk stuk", bookmark.note)
    assertEquals(3_000L, bookmark.point.globalMs)
    assertEquals("Hoofdstuk 2", bookmark.point.label)
    assertEquals(0, repository.sessionsForBook("book").size)

    val reloaded = BookmarkRepository(file).manualBookmarksForBook("book").single()
    assertEquals("Belangrijk stuk", reloaded.note)
    assertEquals(3_000L, reloaded.point.globalMs)
  }

  @Test
  fun `removes manual bookmarks without touching automatic sessions`() {
    val file = Files.createTempDirectory("bookmarks-manual2").resolve("bookmarks.json").toFile()
    val repository = BookmarkRepository(file)
    repository.recordStart("book", BookPosition("a.mp3", 100L, 100L, null), now = 1L)
    repository.addManualBookmark("book", BookPosition("a.mp3", 200L, 200L, null), now = 2L)
    repository.addManualBookmark("book", BookPosition("a.mp3", 300L, 300L, null), now = 3L)
    repository.addManualBookmark("other", BookPosition("b.mp3", 400L, 400L, null), now = 4L)

    val bookmarkId = repository.manualBookmarksForBook("book").first().id
    repository.removeManualBookmark("other", bookmarkId)
    assertEquals(2, repository.manualBookmarksForBook("book").size)

    repository.removeManualBookmark("book", bookmarkId)
    assertEquals(1, repository.manualBookmarksForBook("book").size)
    assertEquals(1, repository.manualBookmarksForBook("other").size)
    assertEquals(1, repository.sessionsForBook("book").size)

    repository.removeManualBookmarks("book")
    assertEquals(0, repository.manualBookmarksForBook("book").size)
    assertEquals(1, repository.manualBookmarksForBook("other").size)
    assertEquals(1, repository.sessionsForBook("book").size)

    val reloaded = BookmarkRepository(file)
    assertEquals(0, reloaded.manualBookmarksForBook("book").size)
    assertEquals(1, reloaded.manualBookmarksForBook("other").size)
    assertEquals(1, reloaded.sessionsForBook("book").size)
  }

  @Test
  fun `removing a book also removes its manual bookmarks`() {
    val file = Files.createTempDirectory("bookmarks-manual3").resolve("bookmarks.json").toFile()
    val repository = BookmarkRepository(file)
    repository.recordStart("book", BookPosition("a.mp3", 100L, 100L, null), now = 1L)
    repository.addManualBookmark("book", BookPosition("a.mp3", 200L, 200L, null), now = 2L)
    repository.addManualBookmark("other", BookPosition("b.mp3", 300L, 300L, null), now = 3L)

    repository.removeBook("book")

    assertEquals(0, repository.sessionsForBook("book").size)
    assertEquals(0, repository.manualBookmarksForBook("book").size)
    assertEquals(1, repository.manualBookmarksForBook("other").size)
  }

  @Test
  fun `loads bookmark files from before manual bookmarks existed`() {
    val file = Files.createTempDirectory("bookmarks-legacy").resolve("bookmarks.json").toFile()
    file.writeText("""{"sessions":[]}""")

    val repository = BookmarkRepository(file)

    assertTrue(repository.manualBookmarksForBook("book").isEmpty())
    repository.addManualBookmark("book", BookPosition("a.mp3", 100L, 100L, null), now = 1L)
    assertEquals(1, BookmarkRepository(file).manualBookmarksForBook("book").size)
  }

  @Test
  fun `does not create orphan stop bookmarks`() {
    val file = Files.createTempDirectory("bookmarks2").resolve("bookmarks.json").toFile()
    val repository = BookmarkRepository(file)
    repository.recordStop(
      "book",
      BookPosition("a.mp3", 100L, 100L, null),
      reason = "PAUSE",
    )
    assertEquals(0, repository.sessionsForBook("book").size)
  }

  @Test
  fun `keeps only the five most recent automatic bookmarks per book`() {
    val file = Files.createTempDirectory("bookmarks-limit").resolve("bookmarks.json").toFile()
    val repository = BookmarkRepository(file)

    for (index in 1..7) {
      val offset = index * 1_000L
      repository.recordStart("book", BookPosition("a.mp3", offset, offset, null), now = offset)
      repository.recordStop(
        "book",
        BookPosition("a.mp3", offset + 500L, offset + 500L, null),
        reason = "PAUSE",
        now = offset + 1L,
      )
    }
    repository.recordStart("other", BookPosition("b.mp3", 1_000L, 1_000L, null), now = 100L)

    val sessions = repository.sessionsForBook("book")
    assertEquals(5, sessions.size)
    assertEquals(7_000L, sessions.first().start.globalMs)
    assertEquals(3_000L, sessions.last().start.globalMs)
    assertEquals(1, repository.sessionsForBook("other").size)
    assertEquals(5, BookmarkRepository(file).sessionsForBook("book").size)
  }

  @Test
  fun `cleans up extra automatic bookmarks when loading an older file`() {
    val file = Files.createTempDirectory("bookmarks-limit-load").resolve("bookmarks.json").toFile()
    val sessions =
      (1..8).joinToString(",") { index ->
        val start =
          """{"sourcePath":"a.mp3","sourceOffsetMs":$index,""" +
            """"globalMs":${index * 1_000},"createdAtEpochMs":$index}"""
        """{"id":"s$index","bookId":"book","start":$start}"""
      }
    file.writeText("""{"sessions":[$sessions]}""")

    val repository = BookmarkRepository(file)

    val loaded = repository.sessionsForBook("book")
    assertEquals(5, loaded.size)
    assertEquals(8_000L, loaded.first().start.globalMs)
    assertEquals(4_000L, loaded.last().start.globalMs)
    assertEquals(5, BookmarkRepository(file).sessionsForBook("book").size)
  }
}
