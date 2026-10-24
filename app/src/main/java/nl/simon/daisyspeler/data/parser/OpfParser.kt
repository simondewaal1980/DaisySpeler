package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.data.source.BookSource
import org.w3c.dom.Element

data class OpfData(
  val title: String?,
  val author: String?,
  val language: String?,
  val smilSpine: List<String>,
  val nccPath: String?,
)

object OpfParser {
  fun parse(source: BookSource, opfPath: String): OpfData? {
    return runCatching {
      val bytes = source.open(opfPath).use { it.readBytes() }
      val document = XmlDocuments.parse(TextDecoding.decode(bytes))
      val baseDir = PathUtils.parent(opfPath)

      fun firstText(vararg names: String): String? {
        for (name in names) {
          val nodes = document.getElementsByTagName(name)
          for (i in 0 until nodes.length) {
            val text = nodes.item(i).textContent?.trim()
            if (!text.isNullOrBlank()) return text
          }
        }
        return null
      }

      val manifestItems =
        document.getElementsByTagName("item").let { nodes ->
          buildList {
            for (i in 0 until nodes.length) {
              val element = nodes.item(i) as? Element ?: continue
              val href = element.getAttribute("href").takeIf { it.isNotBlank() } ?: continue
              add(
                Triple(
                  element.getAttribute("id"),
                  PathUtils.join(baseDir, href),
                  element.getAttribute("media-type").lowercase(),
                )
              )
            }
          }
        }

      val byId = manifestItems.associateBy { it.first }
      val smilItems = manifestItems.filter {
        it.third.contains("smil") || PathUtils.extension(it.second) == "smil"
      }
      val spinePaths =
        document.getElementsByTagName("itemref").let { nodes ->
          buildList {
            for (i in 0 until nodes.length) {
              val element = nodes.item(i) as? Element ?: continue
              val idRef = element.getAttribute("idref")
              val href = byId[idRef]?.second
              if (href != null) {
                add(href)
              }
            }
          }
        }.filter { PathUtils.extension(it) == "smil" }

      val nccPath =
        manifestItems.firstOrNull {
          it.third.contains("dtbncx") || PathUtils.fileName(it.second).equals("ncc.html", true)
        }?.second

      OpfData(
        title = firstText("dc:title", "title"),
        author = firstText("dc:creator", "creator"),
        language = firstText("dc:language", "language"),
        smilSpine = (spinePaths + smilItems.map { it.second }).distinct(),
        nccPath = nccPath,
      )
    }.getOrNull()
  }
}
