package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.data.book.NavType
import nl.simon.daisyspeler.testutil.MemoryBookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class DaisyBookLoaderTest {
  @Test
  fun `loads daisy 202 without opf and resolves nested audio`() {
    val book = DaisyBookLoader().load(sampleBook())

    assertEquals("Café aan de berg", book.title)
    assertEquals("Per Tester", book.author)
    assertEquals(3_000L, book.totalDurationMs)
    assertEquals(2, book.segments.size)
    assertEquals(0L, book.segments[0].trackStartMs)
    assertEquals(2_000L, book.segments[1].trackStartMs)

    val headings = book.toc.filter { it.type == NavType.HEADING }
    assertEquals(2, headings.size)
    assertEquals(1, headings[0].level)
    assertEquals(0L, headings[0].globalMs)
    assertEquals(2, headings[1].level)
    assertEquals(2_000L, headings[1].globalMs)
    assertEquals(1, book.toc.count { it.type == NavType.PAGE })
    assertEquals(1, book.toc.count { it.type == NavType.SIDEBAR })
  }

  @Test
  fun `resolves next and previous heading targets`() {
    val book = DaisyBookLoader().load(sampleBook())

    assertEquals(2_000L, book.headingTarget(positionMs = 0L, forward = true)?.globalMs)
    assertEquals(2_000L, book.headingTarget(positionMs = 2_000L, forward = true)?.globalMs)
    assertEquals(0L, book.headingTarget(positionMs = 2_500L, forward = false)?.globalMs)
    assertEquals(0L, book.headingTarget(positionMs = 0L, forward = false)?.globalMs)
  }

  @Test
  fun `parses windows-1252 ncc`() {
    val source =
      MemoryBookSource.fromText(
        mapOf(
          "book/ncc.html" to
            """
            <html><head>
              <meta name="dc:title" content="Café"/>
              <meta name="dc:creator" content="Auteur"/>
              <meta name="dc:format" content="Daisy 2.02"/>
            </head><body>
              <h1><a href="s.smil#p1">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent(),
          "book/s.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p1" id="p1"/>
              <audio src="a.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent(),
          "book/a.mp3" to "",
        ),
        charset = Charset.forName("windows-1252"),
      )

    val book = DaisyBookLoader().load(source)
    assertEquals("Café", book.title)
    assertEquals(1, book.segments.size)
  }

  @Test
  fun `uses image referenced from ncc as cover`() {
    val source =
      MemoryBookSource(
        mapOf(
          "book/ncc.html" to
            """
            <html><head><meta name="dc:title" content="Met omslag"/></head><body>
              <div class="cover"><img src="images/cover.jpg" alt="Omslag"/></div>
              <h1><a href="s1.smil#p1">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent().toByteArray(),
          "book/s1.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p1" id="p1"/>
              <audio src="a1.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent().toByteArray(),
          "book/a1.mp3" to ByteArray(0),
          "book/images/cover.jpg" to byteArrayOf(1, 2, 3, 4),
        )
      )

    val book = DaisyBookLoader().load(source)

    assertEquals("book/images/cover.jpg", book.coverPath)
    assertEquals("book/images/cover.jpg", DaisyBookLoader().findCover(source))
  }

  @Test
  fun `uses the first image in ncc when multiple images have no cover hint`() {
    val source =
      MemoryBookSource(
        mapOf(
          "book/ncc.html" to
            """
            <html><head><meta name="dc:title" content="Meerdere plaatjes"/></head><body>
              <div><img src="images/001.jpg" alt="Eerste"/></div>
              <div class="logo"><img src="images/002.jpg" alt="Tweede"/></div>
              <h1><a href="s1.smil#p1">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent().toByteArray(),
          "book/s1.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p1" id="p1"/>
              <audio src="a1.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent().toByteArray(),
          "book/a1.mp3" to ByteArray(0),
          "book/images/001.jpg" to byteArrayOf(1, 2, 3),
          "book/images/002.jpg" to byteArrayOf(4, 5, 6),
        )
      )

    assertEquals("book/images/001.jpg", DaisyBookLoader().load(source).coverPath)
  }

  @Test
  fun `uses a single image with a neutral name when ncc has no image`() {
    val source =
      MemoryBookSource(
        mapOf(
          "book/ncc.html" to
            """
            <html><head><meta name="dc:title" content="Eén plaatje"/></head><body>
              <h1><a href="s1.smil#p1">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent().toByteArray(),
          "book/s1.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p1" id="p1"/>
              <audio src="a1.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent().toByteArray(),
          "book/a1.mp3" to ByteArray(0),
          "book/plaatje.jpg" to byteArrayOf(1, 2, 3),
        )
      )

    assertEquals("book/plaatje.jpg", DaisyBookLoader().load(source).coverPath)
  }

  @Test
  fun `recognises Dutch kaft as a cover name`() {
    val source =
      MemoryBookSource(
        mapOf(
          "book/ncc.html" to
            """
            <html><head><meta name="dc:title" content="Kaft"/></head><body>
              <h1><a href="s1.smil#p1">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent().toByteArray(),
          "book/s1.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p1" id="p1"/>
              <audio src="a1.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent().toByteArray(),
          "book/a1.mp3" to ByteArray(0),
          "book/kaft.jpg" to byteArrayOf(1, 2, 3),
          "book/illustratie.jpg" to byteArrayOf(4, 5, 6),
        )
      )

    assertEquals("book/kaft.jpg", DaisyBookLoader().load(source).coverPath)
  }

  @Test
  fun `falls back to a file named like a cover`() {
    val book =
      DaisyBookLoader().load(
        MemoryBookSource(
          mapOf(
            "book/ncc.html" to
              """
              <html><head><meta name="dc:title" content="Zonder plaatje"/></head><body>
                <h1><a href="s1.smil#p1">Hoofdstuk</a></h1>
              </body></html>
              """.trimIndent().toByteArray(),
            "book/s1.smil" to
              """
              <smil><body><par>
                <text src="ncc.html#p1" id="p1"/>
                <audio src="a1.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
              </par></body></smil>
              """.trimIndent().toByteArray(),
            "book/a1.mp3" to ByteArray(0),
            "book/voorblad.png" to byteArrayOf(1, 2, 3),
          )
        )
      )

    assertEquals("book/voorblad.png", book.coverPath)
  }

  @Test
  fun `uses a neutral title when ncc in the root has no title metadata`() {
    val source =
      MemoryBookSource.fromText(
        mapOf(
          "ncc.html" to
            """
            <html><head></head><body>
              <h1><a href="s.smil#p1">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent(),
          "s.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p1" id="p1"/>
              <audio src="a.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent(),
          "a.mp3" to "",
        )
      )

    assertEquals("Naamloos boek", DaisyBookLoader().load(source).title)
  }

  @Test
  fun `uses the folder name when ncc in a folder has no title metadata`() {
    val source =
      MemoryBookSource.fromText(
        mapOf(
          "mijn-boek/ncc.html" to
            """
            <html><head></head><body>
              <h1><a href="s.smil#p1">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent(),
          "mijn-boek/s.smil" to
            """
            <smil><body><par>
              <text src="ncc.html#p1" id="p1"/>
              <audio src="a.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
            </par></body></smil>
            """.trimIndent(),
          "mijn-boek/a.mp3" to "",
        )
      )

    assertEquals("mijn-boek", DaisyBookLoader().load(source).title)
  }

  private fun sampleBook(): MemoryBookSource =
    MemoryBookSource.fromText(
      mapOf(
        "book/ncc.html" to
          """
          <html><head>
            <meta name="dc:title" content="Café aan de berg"/>
            <meta name="dc:creator" content="Per Tester"/>
            <meta name="dc:format" content="Daisy 2.02"/>
            <meta name="ncc:totalTime" content="00:00:03"/>
          </head><body>
            <h1 id="t1"><a href="s1.smil#p1">Hoofdstuk 1</a></h1>
            <h2 id="t2"><a href="s2.smil#p2">Sectie 1.1</a></h2>
            <span class="page-normal" id="pg1"><a href="s2.smil#p2">1</a></span>
            <span class="sidebar" id="sb1"><a href="s1.smil#p1">Zijbalk</a></span>
          </body></html>
          """.trimIndent(),
        "book/s1.smil" to
          """
          <smil><body><seq>
            <par>
              <text src="ncc.html#t1" id="p1"/>
              <seq>
                <audio src="a1.mp3" clip-begin="npt=0.000s" clip-end="npt=1.000s" id="au1"/>
                <audio src="a1.mp3" clip-begin="npt=1.000s" clip-end="npt=2.000s" id="au2"/>
              </seq>
            </par>
          </seq></body></smil>
          """.trimIndent(),
        "book/s2.smil" to
          """
          <smil><body><seq>
            <par>
              <text src="ncc.html#t2" id="p2"/>
              <seq>
                <audio src="a2.mp3" clip-begin="npt=0.5s" clip-end="npt=1.5s" id="au3"/>
              </seq>
            </par>
          </seq></body></smil>
          """.trimIndent(),
        "book/master.smil" to
          """
          <smil><body>
            <ref src="s1.smil"/>
            <ref src="s2.smil"/>
          </body></smil>
          """.trimIndent(),
        "book/a1.mp3" to "",
        "book/a2.mp3" to "",
      )
    )
}
