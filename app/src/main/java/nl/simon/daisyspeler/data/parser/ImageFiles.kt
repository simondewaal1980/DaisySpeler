package nl.simon.daisyspeler.data.parser

/**
 * Helpers for recognising cover images inside a DAISY book. Only formats that Android's
 * BitmapFactory can decode are considered, so vector or unsupported images are ignored.
 */
object ImageFiles {
  private val BITMAP_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

  private val COVER_HINTS =
    listOf("cover", "omslag", "voorblad", "front", "kaft", "hoes", "voorzijde")

  fun isImage(path: String): Boolean = PathUtils.extension(path) in BITMAP_EXTENSIONS

  fun looksLikeCover(pathOrText: String): Boolean {
    val lower = pathOrText.lowercase()
    return COVER_HINTS.any { lower.contains(it) }
  }

  /** Image extension for a decoded payload, or null when the bytes are not a bitmap. */
  fun extensionOf(bytes: ByteArray): String? {
    fun at(index: Int): Int = bytes[index].toInt() and 0xFF
    if (bytes.size >= 3 && at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF) return "jpg"
    if (bytes.size >= 8 &&
      at(0) == 0x89 &&
      at(1) == 0x50 &&
      at(2) == 0x4E &&
      at(3) == 0x47
    ) {
      return "png"
    }
    if (bytes.size >= 6 && at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x38) {
      return "gif"
    }
    if (bytes.size >= 12 &&
      at(0) == 0x52 &&
      at(1) == 0x49 &&
      at(2) == 0x46 &&
      at(3) == 0x46 &&
      at(8) == 0x57 &&
      at(9) == 0x45 &&
      at(10) == 0x42 &&
      at(11) == 0x50
    ) {
      return "webp"
    }
    if (bytes.size >= 2 && at(0) == 0x42 && at(1) == 0x4D) return "bmp"
    return null
  }

  fun isBitmap(bytes: ByteArray): Boolean = extensionOf(bytes) != null
}
