package nl.simon.daisyspeler.data.importer

import nl.simon.daisyspeler.data.parser.DaisyBookLoader
import nl.simon.daisyspeler.data.source.FileBookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipBookExtractorTest {
  @Test
  fun `extracts a daisy book and loads it from the extracted folder`() {
    val target = Files.createTempDirectory("daisy-zip").toFile()
    try {
      val archive =
        zip(
          "book/ncc.html" to
            """
            <html><head>
              <meta name="dc:title" content="ZIP boek"/>
              <meta name="dc:creator" content="Testauteur"/>
              <meta name="dc:format" content="Daisy 2.02"/>
            </head><body>
              <h1><a href="s.smil#p1">Hoofdstuk 1</a></h1>
            </body></html>
            """.trimIndent(),
          "book/s.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p1" id="p1"/>
              <audio src="a.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent(),
          "book/a.mp3" to "audio",
          "__MACOSX/._ncc.html" to "metadata",
          ".DS_Store" to "metadata",
        )

      ZipBookExtractor(target).extract(ByteArrayInputStream(archive))

      assertTrue(target.resolve("book/ncc.html").isFile)
      assertTrue(target.resolve("book/s.smil").isFile)
      assertTrue(target.resolve("book/a.mp3").isFile)
      assertFalse(target.resolve(".DS_Store").exists())
      assertFalse(target.resolve("__MACOSX").exists())

      val book = DaisyBookLoader().load(FileBookSource(target))
      assertEquals("ZIP boek", book.title)
      assertEquals("Testauteur", book.author)
      assertEquals(1, book.segments.size)
      assertEquals(1, book.toc.count { it.type.name == "HEADING" })
    } finally {
      target.deleteRecursively()
    }
  }

  @Test
  fun `rejects path traversal in zip entries`() {
    val target = Files.createTempDirectory("daisy-zip-slip").toFile()
    val outside = requireNotNull(target.parentFile).resolve("outside.txt")
    outside.delete()
    try {
      val archive = zip("../outside.txt" to "evil")
      val error =
        runCatching {
            ZipBookExtractor(target).extract(ByteArrayInputStream(archive))
          }
          .exceptionOrNull()

      assertTrue(error is ZipImportException)
      assertFalse(outside.exists())
    } finally {
      target.deleteRecursively()
      outside.delete()
    }
  }

  private fun zip(vararg entries: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
      for ((name, content) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray())
        zip.closeEntry()
      }
    }
    return bytes.toByteArray()
  }
}
