package nl.simon.daisyspeler.data.parser

/**
 * Helpers for recognising cover images inside a DAISY book. Only formats that Android's
 * BitmapFactory can decode are considered, so vector or unsupported images are ignored.
 */
object ImageFiles {
  private val BITMAP_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

  private val COVER_HINTS = listOf("cover", "omslag", "voorblad", "front")

  fun isImage(path: String): Boolean = PathUtils.extension(path) in BITMAP_EXTENSIONS

  fun looksLikeCover(pathOrText: String): Boolean {
    val lower = pathOrText.lowercase()
    return COVER_HINTS.any { lower.contains(it) }
  }
}
