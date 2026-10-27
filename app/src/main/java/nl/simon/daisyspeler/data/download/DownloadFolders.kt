package nl.simon.daisyspeler.data.download

/**
 * Pure helpers for the folder that the in-app book browser downloads into. The folder always
 * lives inside the public Downloads folder, so a user-supplied name is reduced to a single,
 * safe folder name.
 */
object DownloadFolders {
  const val DEFAULT_NAME = "audioboeken"

  fun normalize(proposed: String): String {
    val path = proposed.trim().trimEnd('/', '\\')
    val leaf =
      path
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filterNot { it.isISOControl() }
        .trim()
    val replaced = leaf.replace(Regex("""[\\/:*?"<>|]"""), "_")
    val cleaned = replaced.take(MAX_NAME_LENGTH).trim('.', ' ')
    return cleaned.ifBlank { DEFAULT_NAME }
  }

  private const val MAX_NAME_LENGTH = 64
}
