package nl.simon.daisyspeler.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeParserTest {
  @Test
  fun `parses npt seconds with suffix`() {
    assertEquals(1_384L, TimeParser.parseNpt("npt=1.384s"))
    assertEquals(0L, TimeParser.parseNpt("npt=0.000s"))
  }

  @Test
  fun `parses npt minutes and hours`() {
    assertEquals(62_345L, TimeParser.parseNpt("npt=01:02.345"))
    assertEquals(3_723_456L, TimeParser.parseNpt("npt=01:02:03.456"))
  }

  @Test
  fun `parses milliseconds suffix`() {
    assertEquals(1_500L, TimeParser.parseNpt("npt=1500ms"))
  }

  @Test
  fun `returns null for invalid values`() {
    assertNull(TimeParser.parseNpt(null))
    assertNull(TimeParser.parseNpt("npt=smpte=1"))
    assertNull(TimeParser.parseNpt("npt=abc"))
  }

  @Test
  fun `parses clock times`() {
    assertEquals(5_460_000L, TimeParser.parseClock("01:31:00"))
    assertEquals(90_500L, TimeParser.parseClock("01:30.5"))
  }
}
