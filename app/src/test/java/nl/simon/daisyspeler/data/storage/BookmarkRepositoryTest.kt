package nl.simon.daisyspeler.data.storage

import nl.simon.daisyspeler.data.book.BookPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
