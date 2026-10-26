package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.data.book.NavType
import nl.simon.daisyspeler.testutil.MemoryBookSource
import org.junit.Assert.assertEquals
import org.junit.Test

class NccParserTest {
  @Test
  fun `pagebreak class is not treated as a page navigation point`() {
    val source =
      MemoryBookSource.fromText(
        mapOf(
          "book/ncc.html" to
            """
            <html><body>
              <span class="pagebreak"><a href="s.smil#p1">Geen pagina</a></span>
              <span class="page-normal"><a href="s.smil#p2">1</a></span>
            </body></html>
            """.trimIndent(),
        )
      )

    val data = NccParser.parse(source, "book/ncc.html")

    assertEquals(1, data.navItems.count { it.type == NavType.PAGE })
    assertEquals("1", data.navItems.first { it.type == NavType.PAGE }.label)
  }
}
