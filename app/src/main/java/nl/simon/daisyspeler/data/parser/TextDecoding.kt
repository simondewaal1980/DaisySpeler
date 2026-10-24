package nl.simon.daisyspeler.data.parser

import java.nio.charset.Charset

object TextDecoding {
  private val declarationRegex =
    Regex("""(?:encoding|charset)\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

  fun decode(bytes: ByteArray): String {
    detectCharset(bytes)?.let { charset ->
      return runCatching { String(bytes, charset) }.getOrElse { String(bytes, Charsets.UTF_8) }
    }
    if (isValidUtf8(bytes)) return String(bytes, Charsets.UTF_8)
    // DAISY 2.02 books are commonly produced on Windows. When a document has no charset
    // declaration and is not valid UTF-8, windows-1252 gives much better results than UTF-8
    // with replacement characters.
    return runCatching { String(bytes, Charset.forName("windows-1252")) }
      .getOrElse { String(bytes, Charsets.UTF_8) }
  }

  private fun isValidUtf8(bytes: ByteArray): Boolean {
    val decoder =
      Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)) }.isSuccess
  }

  private fun detectCharset(bytes: ByteArray): Charset? {
    if (bytes.size >= 3 &&
      bytes[0] == 0xEF.toByte() &&
      bytes[1] == 0xBB.toByte() &&
      bytes[2] == 0xBF.toByte()
    ) {
      return Charsets.UTF_8
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
      return Charsets.UTF_16LE
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
      return Charsets.UTF_16BE
    }
    val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
    val match = declarationRegex.find(head) ?: return null
    val name = match.groupValues[1]
    return runCatching { Charset.forName(name) }.getOrNull()
  }
}
