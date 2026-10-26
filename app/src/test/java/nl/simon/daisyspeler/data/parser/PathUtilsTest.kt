package nl.simon.daisyspeler.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class PathUtilsTest {
  @Test
  fun `plus in a path is not decoded as a space`() {
    assertEquals(
      ("book/hoofdstuk+1.mp3" to null),
      PathUtils.resolveHref("book", "hoofdstuk+1.mp3"),
    )
  }

  @Test
  fun `percent encoded plus is decoded as a plus`() {
    assertEquals(
      ("book/a+b.mp3" to null),
      PathUtils.resolveHref("book", "a%2Bb.mp3"),
    )
  }

  @Test
  fun `fragment is kept while the path is resolved`() {
    assertEquals(
      ("book/s.smil" to "p1"),
      PathUtils.resolveHref("book", "s.smil#p1"),
    )
  }
}
