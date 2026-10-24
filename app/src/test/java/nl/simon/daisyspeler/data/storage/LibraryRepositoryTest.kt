package nl.simon.daisyspeler.data.storage

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
