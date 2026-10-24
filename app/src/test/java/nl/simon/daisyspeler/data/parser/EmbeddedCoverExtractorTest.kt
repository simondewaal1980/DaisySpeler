package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.testutil.MemoryBookSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedCoverExtractorTest {
  private val png =
    byteArrayOf(
      0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
      0x01, 0x02, 0x03,
    )

  @Test
  fun `reads APIC cover from ID3v2_4 tag`() {
    val source = MemoryBookSource(mapOf("book/a1.mp3" to id3Tag(4, apicFrame24(apicPayload()))))

    val cover = EmbeddedCoverExtractor.find(source, listOf("book/a1.mp3"))

    assertArrayEquals(png, cover?.bytes)
    assertEquals("image/png", cover?.mimeType)
    assertEquals("png", cover?.extension)
  }

  @Test
  fun `reads APIC cover from ID3v2_3 tag`() {
    val source = MemoryBookSource(mapOf("book/a1.mp3" to id3Tag(3, apicFrame23(apicPayload()))))

    val cover = EmbeddedCoverExtractor.readTag(source, "book/a1.mp3")

    assertArrayEquals(png, cover?.bytes)
    assertEquals("png", cover?.extension)
  }

  @Test
  fun `reads PIC cover from ID3v2_2 tag`() {
    val source = MemoryBookSource(mapOf("book/a1.mp3" to id3Tag(2, picFrame22())))

    val cover = EmbeddedCoverExtractor.find(source, listOf("book/a1.mp3"))

    assertArrayEquals(png, cover?.bytes)
    assertEquals("image/png", cover?.mimeType)
    assertEquals("png", cover?.extension)
  }

  @Test
  fun `returns null without an ID3 tag`() {
    val source = MemoryBookSource(mapOf("book/a1.mp3" to byteArrayOf(0x49, 0x44, 0x33)))

    assertNull(EmbeddedCoverExtractor.find(source, listOf("book/a1.mp3")))
  }

  @Test
  fun `skips candidates that are not audio files`() {
    val source =
      MemoryBookSource(
        mapOf(
          "book/cover.jpg" to id3Tag(4, apicFrame24(apicPayload())),
          "book/a1.mp3" to byteArrayOf(1, 2, 3),
        )
      )

    assertNull(EmbeddedCoverExtractor.find(source, listOf("book/cover.jpg")))
  }

  @Test
  fun `sniffs the image type when the APIC mime type is empty`() {
    val source =
      MemoryBookSource(mapOf("book/a1.mp3" to id3Tag(4, apicFrame24(apicPayload(mime = "")))))

    val cover = EmbeddedCoverExtractor.find(source, listOf("book/a1.mp3"))

    assertEquals("png", cover?.extension)
  }

  private fun apicPayload(mime: String = "image/png"): ByteArray {
    val mimeBytes = mime.toByteArray(Charsets.ISO_8859_1)
    return byteArrayOf(0) + mimeBytes + byteArrayOf(0, 3, 0) + png
  }

  private fun apicFrame24(payload: ByteArray): ByteArray =
    "APIC".toByteArray(Charsets.ISO_8859_1) +
      syncSafe(payload.size) +
      byteArrayOf(0, 0) +
      payload

  private fun apicFrame23(payload: ByteArray): ByteArray =
    "APIC".toByteArray(Charsets.ISO_8859_1) +
      bigEndian(payload.size, 4) +
      byteArrayOf(0, 0) +
      payload

  private fun picFrame22(): ByteArray {
    val payload = byteArrayOf(0) + "PNG".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(3, 0) + png
    return "PIC".toByteArray(Charsets.ISO_8859_1) + bigEndian(payload.size, 3) + payload
  }

  private fun id3Tag(version: Int, frames: ByteArray): ByteArray =
    byteArrayOf(
      'I'.code.toByte(),
      'D'.code.toByte(),
      '3'.code.toByte(),
      version.toByte(),
      0,
      0,
    ) + syncSafe(frames.size) + frames

  private fun syncSafe(value: Int): ByteArray =
    byteArrayOf(
      ((value shr 21) and 0x7F).toByte(),
      ((value shr 14) and 0x7F).toByte(),
      ((value shr 7) and 0x7F).toByte(),
      (value and 0x7F).toByte(),
    )

  private fun bigEndian(value: Int, length: Int): ByteArray =
    ByteArray(length) { index -> (value shr ((length - index - 1) * 8)).toByte() }
}
