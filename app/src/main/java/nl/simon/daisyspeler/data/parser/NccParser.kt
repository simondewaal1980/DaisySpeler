package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.data.book.NavType
import nl.simon.daisyspeler.data.source.BookSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

data class RawNavItem(
  val label: String,
  val type: NavType,
  val level: Int,
  val smilPath: String,
  val fragment: String?,
)

data class NccData(
  val title: String?,
  val author: String?,
  val language: String?,
  val totalTimeMs: Long?,
  val format: String?,
  val navItems: List<RawNavItem>,
  val warnings: List<String>,
  val coverPath: String? = null,
)

object NccParser {
  fun parse(source: BookSource, nccPath: String): NccData {
    val warnings = mutableListOf<String>()
    val bytes = source.open(nccPath).use { it.readBytes() }
    val html = TextDecoding.decode(bytes)
    val document = Jsoup.parse(html, "https://daisy.local/${PathUtils.parent(nccPath)}/")

    fun meta(name: String): String? =
      document.selectFirst("meta[name=$name]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

    val baseDir = PathUtils.parent(nccPath)
    val rawItems = mutableListOf<RawNavItem>()
    val seen = mutableSetOf<String>()

    for (element in document.body().allElements) {
      val tag = element.tagName().lowercase()
      val type = navType(element, tag) ?: continue
      val target = targetOf(element) ?: continue
      val resolved = PathUtils.resolveHref(baseDir, target) ?: continue
      val label = element.text().replace(Regex("\\s+"), " ").trim()
      if (label.isEmpty()) continue
      if (resolved.first.substringAfterLast('.', "").lowercase() != "smil") {
        warnings += "Navigatiepunt '$label' verwijst niet naar een SMIL-bestand."
        continue
      }
      val key = "$label|${resolved.first}|${resolved.second}|$type"
      if (!seen.add(key)) continue
      rawItems +=
        RawNavItem(
          label = label,
          type = type,
          level = if (type == NavType.HEADING && tag.length == 2) tag[1].digitToIntOrNull() ?: 1 else 0,
          smilPath = resolved.first,
          fragment = resolved.second,
        )
    }

    if (rawItems.isEmpty()) warnings += "Geen navigatiepunten in ncc.html gevonden."

    return NccData(
      title = meta("dc:title") ?: document.title().takeIf { it.isNotBlank() },
      author = meta("dc:creator"),
      language = meta("dc:language"),
      totalTimeMs = TimeParser.parseClock(meta("ncc:totalTime")),
      format = meta("dc:format"),
      navItems = rawItems,
      warnings = warnings,
      coverPath = findCoverPath(document, baseDir),
    )
  }

  /**
   * DAISY 2.02 books usually show their cover as the first image in ncc.html. Prefer an image
   * whose name, alt text or surrounding element mentions "cover", otherwise accept the only
   * image on the page.
   */
  private fun findCoverPath(document: org.jsoup.nodes.Document, baseDir: String): String? {
    val metaCover =
      document.selectFirst("meta[name=cover]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
    if (metaCover != null) {
      val resolved = PathUtils.resolveHref(baseDir, metaCover)?.first ?: PathUtils.join(baseDir, metaCover)
      if (ImageFiles.isImage(resolved)) return resolved
    }

    val images = document.select("img[src]")
    val preferred =
      images.firstOrNull { image ->
        val own =
          listOf("src", "alt", "class", "id", "title").joinToString(" ") { image.attr(it) }
        val surroundings =
          generateSequence(image.parent()) { it.parent() }
            .take(3)
            .joinToString(" ") { "${it.className()} ${it.id()}" }
        ImageFiles.looksLikeCover(own) || ImageFiles.looksLikeCover(surroundings)
      }
    val chosen = preferred ?: images.singleOrNull() ?: return null
    return PathUtils.resolveHref(baseDir, chosen.attr("src"))?.first?.takeIf(ImageFiles::isImage)
  }

  private fun targetOf(element: Element): String? {
    element.attr("smilref").trim().takeIf { it.isNotEmpty() }?.let { return it }
    val anchor = element.selectFirst("a[href]") ?: return null
    return anchor.attr("href").trim().takeIf { it.isNotEmpty() }
  }

  private fun navType(element: Element, tag: String): NavType? {
    val classes = element.classNames().map { it.lowercase() }
    if (classes.any { it.startsWith("page") }) return NavType.PAGE
    if (classes.any { it == "sidebar" }) return NavType.SIDEBAR
    if (classes.any { it == "footnote" || it == "noteref" }) return NavType.FOOTNOTE
    if (classes.any { it == "prodnote" || it == "optional-prodnote" || it == "producer-note" }) {
      return NavType.PRODNOTE
    }
    if (tag.matches(Regex("h[1-6]"))) return NavType.HEADING
    if (classes.any { it == "annotation" || it == "note" }) return NavType.OTHER
    return null
  }
}
