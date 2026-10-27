package nl.simon.daisyspeler.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDecodingTest {
  @Test
  fun `removes a utf-8 bom`() {
    val bytes =
      byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
        "<smil>tekst</smil>".toByteArray(Charsets.UTF_8)

    assertEquals("<smil>tekst</smil>", TextDecoding.decode(bytes))
  }

  @Test
  fun `removes a utf-16 little endian bom`() {
    val bytes =
      byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
        "<smil>tekst</smil>".toByteArray(Charsets.UTF_16LE)

    assertEquals("<smil>tekst</smil>", TextDecoding.decode(bytes))
  }

  @Test
  fun `keeps text without a bom unchanged`() {
    assertEquals(
      "<smil>tekst</smil>",
      TextDecoding.decode("<smil>tekst</smil>".toByteArray(Charsets.UTF_8)),
    )
  }
}
