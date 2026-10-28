package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.data.book.NavType
import kotlinx.coroutines.runBlocking
import nl.simon.daisyspeler.testutil.MemoryBookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class DaisyBookLoaderTest {
  @Test
  fun `backwards overlapping clips keep their order duration and navigation`() {
    val book = loadClips(10_000L to 20_000L, 0L to 15_000L)

    assertEquals(25_000L, book.totalDurationMs)
    assertEquals(listOf(10_000L, 0L), book.segments.map { it.clipStartMs })
    assertEquals(listOf(0L, 10_000L), book.toc.map { it.globalMs })
    assertEquals(listOf(0, 1), book.toc.map { it.segmentIndex })
    assertEquals(listOf(0L, 0L), book.toc.map { it.offsetMs })
  }

  @Test
  fun `repeated clips are played twice instead of being merged`() {
    val book = loadClips(0L to 1_000L, 0L to 1_000L)

    assertEquals(2_000L, book.totalDurationMs)
    assertEquals(2, book.segments.size)
    assertEquals(listOf(0L, 1_000L), book.toc.map { it.globalMs })
  }

  @Test
  fun `only small boundary differences are tolerated when merging clips`() {
    for (boundary in listOf(940L, 1_000L, 1_060L)) {
      val book = loadClips(0L to 1_000L, boundary to 2_000L)
      assertEquals(1, book.segments.size)
      assertEquals(2_000L, book.totalDurationMs)
    }
    val overlapping = loadClips(0L to 10_000L, 5_000L to 15_000L)
    assertEquals(2, overlapping.segments.size)
    assertEquals(20_000L, overlapping.totalDurationMs)
  }

  private fun loadBook(source: nl.simon.daisyspeler.data.source.BookSource) =
    runBlocking { DaisyBookLoader().load(source) }

  private fun findCover(source: nl.simon.daisyspeler.data.source.BookSource) =
    runBlocking { DaisyBookLoader().findCover(source) }

  private fun loadClips(vararg clips: Pair<Long, Long>) =
    loadBook(
      MemoryBookSource.fromText(
        mapOf(
          "ncc.html" to "<html><head><title>Test</title></head><body>" +
            clips.indices.joinToString("") { "<h1><a href='s.smil#p$it'>Deel $it</a></h1>" } +
            "</body></html>",
          "s.smil" to "<smil><body><seq>" +
            clips.mapIndexed { index, (start, end) ->
              "<par id='p$index'><audio src='a.mp3' clip-begin='${start}ms' clip-end='${end}ms'/></par>"
            }.joinToString("") + "</seq></body></smil>",
          "a.mp3" to "",
        )
      )
    )

  @Test
  fun `loads daisy 202 without opf and resolves nested audio`() {
    val book = loadBook(sampleBook())

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
    val book = loadBook(sampleBook())

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

    val book = loadBook(source)
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

    val book = loadBook(source)

    assertEquals("book/images/cover.jpg", book.coverPath)
    assertEquals("book/images/cover.jpg", findCover(source))
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

    assertEquals("book/images/001.jpg", loadBook(source).coverPath)
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

    assertEquals("book/plaatje.jpg", loadBook(source).coverPath)
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

    assertEquals("book/kaft.jpg", loadBook(source).coverPath)
  }

  @Test
  fun `falls back to a file named like a cover`() {
    val book =
      loadBook(
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

    assertEquals("Naamloos boek", loadBook(source).title)
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

    assertEquals("mijn-boek", loadBook(source).title)
  }

  @Test
  fun `loads a smil file that starts with a utf-8 bom`() {
    val smil =
      """
      <smil><body><par>
        <text src="ncc.html#p1" id="p1"/>
        <audio src="a.mp3" clip-begin="npt=0s" clip-end="npt=1s"/>
      </par></body></smil>
      """.trimIndent()
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    val source =
      MemoryBookSource(
        mapOf(
          "book/ncc.html" to
            """
            <html><head><meta name="dc:title" content="BOM"/></head><body>
              <h1><a href="s.smil#p1">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent().toByteArray(),
          "book/s.smil" to bom + smil.toByteArray(Charsets.UTF_8),
          "book/a.mp3" to ByteArray(0),
        )
      )

    val book = loadBook(source)

    assertEquals(1, book.segments.size)
    assertEquals(1_000L, book.totalDurationMs)
  }

  @Test
  fun `fills a missing clip end with the next start in the same file`() {
    val source =
      MemoryBookSource.fromText(
        mapOf(
          "book/ncc.html" to
            """
            <html><head><meta name="dc:title" content="Zonder eindtijd"/></head><body>
              <h1><a href="s.smil#p1">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent(),
          "book/s.smil" to
            """
            <smil><body>
              <par>
                <text src="ncc.html#p1" id="p1"/>
                <audio src="a.mp3" clip-begin="npt=0s"/>
              </par>
              <par>
                <text src="ncc.html#p2" id="p2"/>
                <audio src="a.mp3" clip-begin="npt=1s" clip-end="npt=2s"/>
              </par>
            </body></smil>
            """.trimIndent(),
          "book/a.mp3" to "",
        )
      )

    val book = loadBook(source)

    assertEquals(1, book.segments.size)
    assertEquals(0L, book.segments.single().clipStartMs)
    assertEquals(2_000L, book.segments.single().clipEndMs)
    assertEquals(2_000L, book.totalDurationMs)
  }

  @Test
  fun `many clips without an end time are completed in linear-ish time`() {
    val clipCount = 6_000
    val pars =
      (0 until clipCount).joinToString("") { index ->
        "<par id='p$index'><audio src='a.mp3' clip-begin='${index * 1_000}ms'/></par>"
      }
    val source =
      MemoryBookSource.fromText(
        mapOf(
          "book/ncc.html" to
            """
            <html><head><meta name="dc:title" content="Veel clips"/></head><body>
              <h1><a href="s.smil#p0">Hoofdstuk</a></h1>
            </body></html>
            """.trimIndent(),
          "book/s.smil" to "<smil><body><seq>$pars</seq></body></smil>",
          "book/a.mp3" to "",
        )
      )

    val started = System.nanoTime()
    val book = loadBook(source)
    val elapsedMs = (System.nanoTime() - started) / 1_000_000

    assertEquals(1, book.segments.size)
    assertEquals((clipCount - 1) * 1_000L, book.totalDurationMs)
    assertTrue("Parser took ${elapsedMs}ms for $clipCount clips", elapsedMs < 5_000L)
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
