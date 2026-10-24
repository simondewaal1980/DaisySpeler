package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.data.source.BookSource
import org.w3c.dom.Element

data class ParsedAudioClip(
  val id: String?,
  val sourcePath: String,
  val startMs: Long,
  val endMs: Long?,
)

data class ParsedPar(
  val id: String?,
  val elementIds: Set<String>,
  val clips: List<ParsedAudioClip>,
)

data class ParsedSmil(
  val path: String,
  val pars: List<ParsedPar>,
)

object SmilParser {
  fun parse(source: BookSource, smilPath: String): ParsedSmil {
    val bytes = source.open(smilPath).use { it.readBytes() }
    val document = XmlDocuments.parse(TextDecoding.decode(bytes))
    val baseDir = PathUtils.parent(smilPath)
    val pars = mutableListOf<ParsedPar>()

    val parNodes = document.getElementsByTagName("par")
    for (i in 0 until parNodes.length) {
      val par = parNodes.item(i) as? Element ?: continue
      val audioNodes = par.getElementsByTagName("audio")
      val clips = mutableListOf<ParsedAudioClip>()
      for (j in 0 until audioNodes.length) {
        val audio = audioNodes.item(j) as? Element ?: continue
        val rawSrc = audio.getAttribute("src").trim()
        if (rawSrc.isEmpty()) continue
        val resolved =
          PathUtils.resolveHref(baseDir, rawSrc)?.first
            ?: PathUtils.join(baseDir, rawSrc)
        val start =
          TimeParser.parseNpt(audio.getAttribute("clip-begin")) ?: 0L
        val end = TimeParser.parseNpt(audio.getAttribute("clip-end"))
        clips +=
          ParsedAudioClip(
            id = audio.getAttribute("id").takeIf { it.isNotBlank() },
            sourcePath = resolved,
            startMs = start.coerceAtLeast(0L),
            endMs = end?.takeIf { it >= start },
          )
      }
      if (clips.isEmpty()) continue

      val ids = mutableSetOf<String>()
      par.getAttribute("id").takeIf { it.isNotBlank() }?.let(ids::add)
      val descendants = par.getElementsByTagName("*")
      for (j in 0 until descendants.length) {
        val element = descendants.item(j) as? Element ?: continue
        element.getAttribute("id").takeIf { it.isNotBlank() }?.let(ids::add)
      }
      pars +=
        ParsedPar(
          id = par.getAttribute("id").takeIf { it.isNotBlank() },
          elementIds = ids,
          clips = clips,
        )
    }

    return ParsedSmil(smilPath, pars)
  }
}
