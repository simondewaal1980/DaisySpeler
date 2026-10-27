package nl.simon.daisyspeler.data.download

/** Pure helpers for turning website-provided names into safe download file names. */
object DownloadFileNames {
  fun sanitize(proposed: String): String {
    val leaf =
      proposed
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot { it.isISOControl() }
        .trim()
    val replaced = leaf.replace(Regex("""[\\/:*?"<>|]"""), "_")
    val cleaned = replaced.trim('.', ' ').ifBlank { DEFAULT_NAME }
    if (cleaned.length <= MAX_NAME_LENGTH) return cleaned

    val extension = cleaned.substringAfterLast('.', "")
    if (extension.isNotBlank() && extension.length <= MAX_EXTENSION_LENGTH) {
      val stem = cleaned.dropLast(extension.length + 1).trimEnd('.', ' ')
      if (stem.isNotBlank()) {
        return stem.take(MAX_NAME_LENGTH - extension.length - 1).trimEnd('.', ' ') +
          "." + extension
      }
    }
    return cleaned.take(MAX_NAME_LENGTH).trimEnd('.', ' ')
  }

  fun withCounter(fileName: String, counter: Int): String {
    if (counter <= 1) return fileName
    val dot = fileName.lastIndexOf('.')
    return if (dot > 0) {
      fileName.substring(0, dot) + " ($counter)" + fileName.substring(dot)
    } else {
      "$fileName ($counter)"
    }
  }

  private const val DEFAULT_NAME = "audioboek"
  private const val MAX_NAME_LENGTH = 160
  private const val MAX_EXTENSION_LENGTH = 12
}
