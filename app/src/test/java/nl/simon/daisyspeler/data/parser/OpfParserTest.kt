package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.testutil.MemoryBookSource
import org.junit.Assert.assertEquals
import org.junit.Test

class OpfParserTest {
  @Test
  fun `uses opf metadata and spine order`() {
    val source =
      MemoryBookSource.fromText(
        mapOf(
          "book/ncc.html" to
            """
            <html><head></head><body>
              <h1><a href="s1.smil#p1">Eerste</a></h1>
              <h1><a href="s2.smil#p2">Tweede</a></h1>
            </body></html>
            """.trimIndent(),
          "book/book.opf" to
            """
            <package>
              <metadata>
                <dc:title>OPF Titel</dc:title>
                <dc:creator>OPF Auteur</dc:creator>
                <dc:language>nl</dc:language>
              </metadata>
              <manifest>
                <item id="s1" href="s1.smil" media-type="application/smil"/>
                <item id="s2" href="s2.smil" media-type="application/smil"/>
              </manifest>
              <spine>
                <itemref idref="s2"/>
                <itemref idref="s1"/>
              </spine>
            </package>
            """.trimIndent(),
          "book/s1.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p1" id="p1"/>
              <audio src="a1.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent(),
          "book/s2.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p2" id="p2"/>
              <audio src="a2.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent(),
          "book/a1.mp3" to "",
          "book/a2.mp3" to "",
        )
      )

    val book = DaisyBookLoader().load(source)
    assertEquals("OPF Titel", book.title)
    assertEquals("OPF Auteur", book.author)
    assertEquals("nl", book.language)
    assertEquals(2, book.segments.size)
    assertEquals("a2.mp3", book.segments[0].sourcePath.substringAfterLast("/"))
    assertEquals("Tweede", book.toc.first().label)
    assertEquals(0L, book.toc.first().globalMs)
    assertEquals("Eerste", book.toc[1].label)
    assertEquals(1_000L, book.toc[1].globalMs)
  }
}
