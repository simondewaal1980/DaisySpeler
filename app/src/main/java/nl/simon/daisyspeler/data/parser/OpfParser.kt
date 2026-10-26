package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.data.source.BookSource
import org.w3c.dom.Element

data class OpfData(
  val title: String?,
  val author: String?,
  val language: String?,
  val smilSpine: List<String>,
  val nccPath: String?,
  val coverPath: String? = null,
)

private data class OpfManifestItem(
  val id: String,
  val path: String,
  val mediaType: String,
  val properties: String,
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

      fun attribute(collection: String, name: String, valueAttribute: String): String? {
        val nodes = document.getElementsByTagName(collection)
        for (i in 0 until nodes.length) {
          val element = nodes.item(i) as? Element ?: continue
          if (!element.getAttribute("name").equals(name, ignoreCase = true)) continue
          val value = element.getAttribute(valueAttribute).trim()
          if (value.isNotEmpty()) return value
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
                OpfManifestItem(
                  id = element.getAttribute("id"),
                  path = PathUtils.join(baseDir, href),
                  mediaType = element.getAttribute("media-type").lowercase(),
                  properties = element.getAttribute("properties").lowercase(),
                )
              )
            }
          }
        }

      val byId = manifestItems.associateBy { it.id }
      val smilItems = manifestItems.filter {
        it.mediaType.contains("smil") || PathUtils.extension(it.path) == "smil"
      }
      val spinePaths =
        document.getElementsByTagName("itemref").let { nodes ->
          buildList {
            for (i in 0 until nodes.length) {
              val element = nodes.item(i) as? Element ?: continue
              val idRef = element.getAttribute("idref")
              val href = byId[idRef]?.path
              if (href != null) {
                add(href)
              }
            }
          }
        }.filter { PathUtils.extension(it) == "smil" }

      val nccPath =
        manifestItems.firstOrNull {
          it.mediaType.contains("dtbncx") || PathUtils.fileName(it.path).equals("ncc.html", true)
        }?.path

      val coverMeta = attribute("meta", "cover", "content")
      val guideCover =
        document.getElementsByTagName("reference").let { nodes ->
          (0 until nodes.length).firstNotNullOfOrNull { index ->
            val element = nodes.item(index) as? Element ?: return@firstNotNullOfOrNull null
            if (!element.getAttribute("type").equals("cover", ignoreCase = true)) {
              return@firstNotNullOfOrNull null
            }
            element.getAttribute("href").takeIf { it.isNotBlank() }
          }
        }

      val coverPath =
        coverMeta?.let { value ->
          byId[value]?.path
            ?: PathUtils.resolveHref(baseDir, value)?.first
            ?: PathUtils.join(baseDir, value)
        }?.takeIf(ImageFiles::isImage)
          ?: manifestItems.firstOrNull {
            it.properties.split(' ', '\t').any { property -> property == "cover-image" } &&
              ImageFiles.isImage(it.path)
          }?.path
          ?: guideCover?.let { PathUtils.resolveHref(baseDir, it)?.first }
            ?.takeIf(ImageFiles::isImage)
          ?: manifestItems.firstOrNull {
            it.mediaType.startsWith("image/") &&
              ImageFiles.isImage(it.path) &&
              ImageFiles.looksLikeCover(it.path)
          }?.path
          ?: manifestItems
            .filter { it.mediaType.startsWith("image/") && ImageFiles.isImage(it.path) }
            .singleOrNull()
            ?.path

      OpfData(
        title = firstText("dc:title", "title"),
        author = firstText("dc:creator", "creator"),
        language = firstText("dc:language", "language"),
        smilSpine =
          (if (spinePaths.isNotEmpty()) spinePaths else smilItems.map { it.path }).distinct(),
        nccPath = nccPath,
        coverPath = coverPath,
      )
    }.getOrNull()
  }
}
