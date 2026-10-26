package nl.simon.daisyspeler.data.storage

import nl.simon.daisyspeler.data.book.BookPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class LibraryRepositoryTest {
  @Test
  fun `loads old entries without zip fields`() {
    val file = Files.createTempDirectory("library").resolve("library.json").toFile()
    try {
      file.writeText(
        """
        {"entries":[{"id":"old","treeUri":"content://book","title":"Oud boek","author":null,"durationMs":1000,"tocCount":2,"addedAtEpochMs":3}]}
        """.trimIndent()
      )

      val entry = LibraryRepository(file).get("old")

      assertEquals(LibrarySourceType.FOLDER, entry?.sourceType)
      assertNull(entry?.localDir)
      assertNull(entry?.coverPath)
    } finally {
      file.parentFile?.deleteRecursively()
    }
  }

  @Test
  fun `round trips zip metadata`() {
    val file = Files.createTempDirectory("library").resolve("library.json").toFile()
    try {
      val repository = LibraryRepository(file)
      repository.addOrUpdate(
        LibraryEntry(
          id = "zip",
          treeUri = "content://book.zip",
          title = "ZIP boek",
          author = "Auteur",
          durationMs = 1000,
          tocCount = 2,
          addedAtEpochMs = 3,
          sourceType = LibrarySourceType.ZIP,
          localDir = "/data/user/0/nl.simon.daisyspeler/files/books/zip",
          coverPath = "/data/user/0/nl.simon.daisyspeler/files/covers/zip.jpg",
        )
      )

      val entry = LibraryRepository(file).get("zip")
      assertEquals(LibrarySourceType.ZIP, entry?.sourceType)
      assertEquals("/data/user/0/nl.simon.daisyspeler/files/books/zip", entry?.localDir)
      assertEquals(
        "/data/user/0/nl.simon.daisyspeler/files/covers/zip.jpg",
        entry?.coverPath,
      )
    } finally {
      file.parentFile?.deleteRecursively()
    }
  }

  @Test
  fun `update last position records when the book was last read`() {
    val file = Files.createTempDirectory("library").resolve("library.json").toFile()
    try {
      val repository = LibraryRepository(file, now = { 1_000L })
      repository.addOrUpdate(
        LibraryEntry(
          id = "book",
          treeUri = "content://book",
          title = "Boek",
          author = null,
          durationMs = 1000,
          tocCount = 2,
          addedAtEpochMs = 3,
        )
      )

      repository.updateLastPosition(
        "book",
        BookPosition(sourcePath = "audio.mp3", sourceOffsetMs = 500, globalMs = 500),
      )

      val entry = LibraryRepository(file).get("book")
      assertEquals(1_000L, entry?.lastReadAtEpochMs)
      assertEquals(500L, entry?.lastPosition?.globalMs)
    } finally {
      file.parentFile?.deleteRecursively()
    }
  }

  @Test
  fun `re importing a book keeps the last read time`() {
    val file = Files.createTempDirectory("library").resolve("library.json").toFile()
    try {
      val repository = LibraryRepository(file, now = { 1_000L })
      repository.addOrUpdate(
        LibraryEntry(
          id = "book",
          treeUri = "content://book",
          title = "Boek",
          author = null,
          durationMs = 1000,
          tocCount = 2,
          addedAtEpochMs = 3,
        )
      )
      repository.updateLastPosition(
        "book",
        BookPosition(sourcePath = "audio.mp3", sourceOffsetMs = 500, globalMs = 500),
      )

      repository.addOrUpdate(
        LibraryEntry(
          id = "book",
          treeUri = "content://book",
          title = "Boek",
          author = null,
          durationMs = 1000,
          tocCount = 2,
          addedAtEpochMs = 4,
        )
      )

      val entry = LibraryRepository(file).get("book")
      assertEquals(1_000L, entry?.lastReadAtEpochMs)
    } finally {
      file.parentFile?.deleteRecursively()
    }
  }

  @Test
  fun `update cover can clear an invalid cover path`() {
    val file = Files.createTempDirectory("library-clear-cover").resolve("library.json").toFile()
    try {
      val repository = LibraryRepository(file)
      repository.addOrUpdate(
        LibraryEntry(
          id = "book",
          treeUri = "content://book",
          title = "Boek",
          author = null,
          durationMs = 1000,
          tocCount = 2,
          addedAtEpochMs = 3,
          coverPath = "/covers/book.jpg",
        )
      )

      repository.updateCover("book", null)

      val entry = repository.get("book")
      assertNull(entry?.coverPath)
      assertEquals("Boek", entry?.title)
    } finally {
      file.parentFile?.deleteRecursively()
    }
  }

  @Test
  fun `update cover keeps the rest of the entry`() {
    val file = Files.createTempDirectory("library").resolve("library.json").toFile()
    try {
      val repository = LibraryRepository(file)
      repository.addOrUpdate(
        LibraryEntry(
          id = "book",
          treeUri = "content://book",
          title = "Boek",
          author = null,
          durationMs = 1000,
          tocCount = 2,
          addedAtEpochMs = 3,
        )
      )

      repository.updateCover("book", "/covers/book.png")

      val entry = LibraryRepository(file).get("book")
      assertEquals("/covers/book.png", entry?.coverPath)
      assertEquals("Boek", entry?.title)
      assertEquals(2, entry?.tocCount)
    } finally {
      file.parentFile?.deleteRecursively()
    }
  }
}
