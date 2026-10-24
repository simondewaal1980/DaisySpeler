package nl.simon.daisyspeler.data.parser

import java.net.URLDecoder

object PathUtils {
  fun normalize(path: String): String {
    val cleaned = path.replace('\\', '/').trim()
    val parts = ArrayDeque<String>()
    for (part in cleaned.split('/')) {
      when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeLast()
        else -> parts.addLast(part)
      }
    }
    return parts.joinToString("/")
  }

  fun parent(path: String): String {
    val normalized = normalize(path)
    val index = normalized.lastIndexOf('/')
    return if (index < 0) "" else normalized.substring(0, index)
  }

  fun join(baseDir: String, path: String): String {
    if (path.startsWith("/")) return normalize(path)
    return normalize(if (baseDir.isBlank()) path else "$baseDir/$path")
  }

  fun resolveHref(baseDir: String, href: String): Pair<String, String?>? {
    val trimmed = href.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.startsWith("http://", ignoreCase = true) ||
      trimmed.startsWith("https://", ignoreCase = true) ||
      trimmed.startsWith("mailto:", ignoreCase = true)
    ) {
      return null
    }
    val withoutQuery = trimmed.substringBefore('?')
    val path = withoutQuery.substringBefore('#')
    val fragment = withoutQuery.substringAfter('#', "").takeIf { it.isNotEmpty() }
    val decoded =
      runCatching { URLDecoder.decode(path, Charsets.UTF_8.name()) }.getOrDefault(path)
    if (decoded.isBlank()) return null
    return join(baseDir, decoded) to fragment
  }

  fun extension(path: String): String =
    path.substringAfterLast('.', "").lowercase()

  fun fileName(path: String): String = normalize(path).substringAfterLast('/')
}
