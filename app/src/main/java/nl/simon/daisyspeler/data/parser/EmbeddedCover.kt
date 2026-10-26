package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.data.source.BookSource
import java.io.InputStream

/** Cover art that was embedded in an audio file instead of stored as a separate image. */
data class EmbeddedCover(
  val bytes: ByteArray,
  val mimeType: String?,
) {
  /** Image extension BitmapFactory understands, or null when the payload is not a bitmap. */
  val extension: String?
    get() =
      when (mimeType?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        else -> ImageFiles.extensionOf(bytes)
      }
}

/**
 * Reads cover art from ID3v2 tags (APIC in v2.3/v2.4, PIC in v2.2). Only the tag header and the
 * tag body are read, never the audio data, so this is cheap even for large MP3 files.
 */
object EmbeddedCoverExtractor {
  private const val MAX_TAG_BYTES = 16 * 1024 * 1024
  private const val MAX_CANDIDATES = 24

  private val AUDIO_EXTENSIONS =
    setOf("mp3", "mp2", "mpga", "aac", "m4a", "m4b", "flac", "ogg", "oga", "opus", "wav", "wma")

  fun isAudio(path: String): Boolean = PathUtils.extension(path) in AUDIO_EXTENSIONS

  /**
   * Tries the audio files in playback order and returns the first embedded cover found. The
   * candidate list is bounded so a book with hundreds of files does not open all of them.
   */
  fun find(source: BookSource, audioPaths: List<String>): EmbeddedCover? {
    val candidates =
      audioPaths
        .asSequence()
        .filter(::isAudio)
        .distinctBy { PathUtils.normalize(it).lowercase() }
        .take(MAX_CANDIDATES)
        .toList()
    for (path in candidates) {
      val cover = runCatching { readTag(source, path) }.getOrNull()
      if (cover != null) return cover
    }
    return null
  }

  fun readTag(source: BookSource, path: String): EmbeddedCover? {
    source.open(path).use { input ->
      val header = readExactly(input, 10) ?: return null
      if (header[0] != 'I'.code.toByte() ||
        header[1] != 'D'.code.toByte() ||
        header[2] != '3'.code.toByte()
      ) {
        return null
      }

      val version = header[3].toInt() and 0xFF
      if (version !in 2..4) return null
      val flags = header[5].toInt() and 0xFF
      val size = syncSafeInt(header, 6, 4) ?: return null
      if (size <= 0 || size > MAX_TAG_BYTES) return null

      val raw = readExactly(input, size) ?: return null
      val tag = if (flags and 0x80 != 0) removeUnsynchronisation(raw) else raw
      return parseTag(tag, version, flags)
    }
  }

  private fun parseTag(tag: ByteArray, version: Int, flags: Int): EmbeddedCover? {
    var offset = 0
    if (version >= 3 && flags and 0x40 != 0) {
      if (tag.size < 4) return null
      val extendedHeaderSize =
        if (version == 4) {
          syncSafeInt(tag, 0, 4) ?: return null
        } else {
          val declared = bigEndianInt(tag, 0, 4) ?: return null
          declared + 4
        }
      if (extendedHeaderSize <= 0 || extendedHeaderSize > tag.size) return null
      offset = extendedHeaderSize
    }

    val headerSize = if (version == 2) 6 else 10
    while (offset + headerSize <= tag.size) {
      val frameId = ascii(tag, offset, if (version == 2) 3 else 4)
      if (frameId.all { it == '\u0000' }) break

      val size =
        when (version) {
          2 -> bigEndianInt(tag, offset + 3, 3)
          3 -> bigEndianInt(tag, offset + 4, 4)
          else -> syncSafeInt(tag, offset + 4, 4)
        } ?: break
      val dataStart = offset + headerSize
      if (size <= 0 || dataStart + size > tag.size) break

      if (frameId == "APIC" || frameId == "PIC") {
        var data = tag.copyOfRange(dataStart, dataStart + size)
        val playable =
          when (version) {
            2 -> true
            3 -> {
              val formatFlags = tag[offset + 9].toInt() and 0xFF
              if (formatFlags and 0xC0 != 0) {
                false
              } else {
                if (formatFlags and 0x20 != 0 && data.isNotEmpty()) {
                  data = data.copyOfRange(1, data.size)
                }
                true
              }
            }
            else -> {
              val payload = frameData24(data, tag[offset + 9].toInt() and 0xFF)
              if (payload == null) {
                false
              } else {
                data = payload
                true
              }
            }
          }
        if (playable) {
          val cover = if (version == 2) parsePic(data) else parseApic(data)
          if (cover != null) return cover
        }
      }

      offset = dataStart + size
    }
    return null
  }

  private fun frameData24(data: ByteArray, formatFlags: Int): ByteArray? {
    // Compression and encryption make the payload unreadable without extra work.
    if (formatFlags and 0x0C != 0) return null
    var start = 0
    if (formatFlags and 0x40 != 0) start += 1
    if (formatFlags and 0x01 != 0) start += 4
    if (start >= data.size) return null
    val payload = data.copyOfRange(start, data.size)
    return if (formatFlags and 0x02 != 0) removeUnsynchronisation(payload) else payload
  }

  private fun parseApic(data: ByteArray): EmbeddedCover? {
    if (data.size < 4) return null
    val encoding = data[0].toInt() and 0xFF
    val mimeEnd = indexOfZero(data, 1)
    if (mimeEnd < 0) return null
    val mime = String(data, 1, mimeEnd - 1, Charsets.ISO_8859_1).trim()
    var offset = mimeEnd + 1
    if (offset >= data.size) return null
    offset += 1 // picture type
    offset = skipDescription(data, offset, encoding) ?: return null
    if (offset >= data.size) return null
    return EmbeddedCover(
      bytes = data.copyOfRange(offset, data.size),
      mimeType = mime.takeIf { it.isNotEmpty() },
    )
  }

  private fun parsePic(data: ByteArray): EmbeddedCover? {
    if (data.size < 5) return null
    val encoding = data[0].toInt() and 0xFF
    val format = String(data, 1, 3, Charsets.ISO_8859_1).uppercase()
    var offset = 4
    if (offset >= data.size) return null
    offset += 1 // picture type
    offset = skipDescription(data, offset, encoding) ?: return null
    if (offset >= data.size) return null
    val mime =
      when (format) {
        "JPG" -> "image/jpeg"
        "PNG" -> "image/png"
        "GIF" -> "image/gif"
        "BMP" -> "image/bmp"
        else -> null
      }
    return EmbeddedCover(bytes = data.copyOfRange(offset, data.size), mimeType = mime)
  }

  private fun skipDescription(data: ByteArray, start: Int, encoding: Int): Int? {
    if (encoding == 1 || encoding == 2) {
      var offset = start
      while (offset + 1 < data.size) {
        if (data[offset].toInt() == 0 && data[offset + 1].toInt() == 0) return offset + 2
        offset += 2
      }
      return null
    }
    val end = indexOfZero(data, start)
    return if (end < 0) null else end + 1
  }

  private fun indexOfZero(data: ByteArray, start: Int): Int {
    for (index in start until data.size) {
      if (data[index].toInt() == 0) return index
    }
    return -1
  }

  private fun removeUnsynchronisation(input: ByteArray): ByteArray {
    val output = ByteArray(input.size)
    var read = 0
    var write = 0
    while (read < input.size) {
      val value = input[read]
      output[write++] = value
      if (value.toInt() and 0xFF == 0xFF && read + 1 < input.size && input[read + 1].toInt() == 0) {
        read += 2
      } else {
        read++
      }
    }
    return output.copyOf(write)
  }

  private fun syncSafeInt(bytes: ByteArray, start: Int, length: Int): Int? {
    if (start < 0 || length <= 0 || length > 4 || start + length > bytes.size) return null
    var value = 0
    for (index in 0 until length) {
      val byte = bytes[start + index].toInt() and 0xFF
      if (byte and 0x80 != 0) return null
      value = (value shl 7) or byte
    }
    return value
  }

  private fun bigEndianInt(bytes: ByteArray, start: Int, length: Int): Int? {
    if (start < 0 || length <= 0 || length > 4 || start + length > bytes.size) return null
    var value = 0
    for (index in 0 until length) {
      value = (value shl 8) or (bytes[start + index].toInt() and 0xFF)
    }
    return value
  }

  private fun ascii(bytes: ByteArray, start: Int, length: Int): String =
    String(bytes, start, length, Charsets.ISO_8859_1)

  private fun readExactly(input: InputStream, count: Int): ByteArray? {
    val buffer = ByteArray(count)
    var offset = 0
    while (offset < count) {
      val read = input.read(buffer, offset, count - offset)
      if (read < 0) return null
      offset += read
    }
    return buffer
  }

}
