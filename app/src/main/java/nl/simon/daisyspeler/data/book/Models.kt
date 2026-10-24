package nl.simon.daisyspeler.data.book

import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
enum class NavType {
  HEADING,
  PAGE,
  SIDEBAR,
  FOOTNOTE,
  PRODNOTE,
  OTHER,
}

/**
 * A continuous piece of audio from one source file. Consecutive SMIL clips that point at the
 * same file and follow each other are merged, so the ExoPlayer playlist stays small.
 */
@Serializable
data class AudioSegment(
  val index: Int,
  val sourcePath: String,
  val clipStartMs: Long,
  val clipEndMs: Long,
  val trackStartMs: Long,
) {
  val durationMs: Long
    get() = max(0L, clipEndMs - clipStartMs)
}

@Serializable
data class NavPoint(
  val label: String,
  val level: Int,
  val type: NavType,
  val smilPath: String,
  val fragment: String?,
  val globalMs: Long,
  val segmentIndex: Int,
  val offsetMs: Long,
)

@Serializable
data class DaisyBook(
  val title: String,
  val author: String?,
  val language: String?,
  val totalDurationMs: Long,
  val segments: List<AudioSegment>,
  val toc: List<NavPoint>,
  val warnings: List<String> = emptyList(),
) {
  fun segmentAtGlobal(positionMs: Long): Pair<AudioSegment, Long>? {
    val safe = positionMs.coerceAtLeast(0L)
    for (segment in segments) {
      if (safe <= segment.trackStartMs + segment.durationMs || segment === segments.last()) {
        return segment to (safe - segment.trackStartMs).coerceIn(0L, segment.durationMs)
      }
    }
    return null
  }

  fun segmentForSource(sourcePath: String, sourceOffsetMs: Long): Pair<AudioSegment, Long>? {
    val segment =
      segments.firstOrNull {
        it.sourcePath.equals(sourcePath, ignoreCase = true) &&
          sourceOffsetMs >= it.clipStartMs &&
          sourceOffsetMs <= it.clipEndMs
      } ?: segments.firstOrNull { it.sourcePath.equals(sourcePath, ignoreCase = true) }
        ?: return null
    return segment to (sourceOffsetMs - segment.clipStartMs).coerceIn(0L, segment.durationMs)
  }

  fun headingAt(positionMs: Long): NavPoint? =
    toc.lastOrNull { it.type == NavType.HEADING && it.globalMs <= positionMs }

  /**
   * Returns the heading to jump to for next/previous navigation, matching the in-app buttons.
   */
  fun headingTarget(positionMs: Long, forward: Boolean): NavPoint? {
    val headings = toc.filter { it.type == NavType.HEADING }
    if (headings.isEmpty()) return null
    return if (forward) {
      headings.firstOrNull { it.globalMs > positionMs + 750 } ?: headings.last()
    } else {
      headings.lastOrNull { it.globalMs < positionMs - 1_500 } ?: headings.first()
    }
  }

  fun pageAt(positionMs: Long): NavPoint? =
    toc.lastOrNull { it.type == NavType.PAGE && it.globalMs <= positionMs }

  fun labelAt(positionMs: Long): String? {
    val heading = headingAt(positionMs)?.label
    val page = pageAt(positionMs)?.label
    return when {
      heading != null && page != null -> "$heading · pagina $page"
      heading != null -> heading
      page != null -> "Pagina $page"
      else -> null
    }
  }

  fun positionAt(segmentIndex: Int, positionInSegmentMs: Long): BookPosition? {
    val segment = segments.getOrNull(segmentIndex) ?: return null
    val offset = positionInSegmentMs.coerceIn(0L, segment.durationMs)
    val sourceOffset = (segment.clipStartMs + offset).coerceAtMost(segment.clipEndMs)
    val global = (segment.trackStartMs + offset).coerceAtMost(totalDurationMs)
    return BookPosition(
      sourcePath = segment.sourcePath,
      sourceOffsetMs = sourceOffset,
      globalMs = global,
      label = labelAt(global),
    )
  }
}

@Serializable
data class BookPosition(
  val sourcePath: String,
  val sourceOffsetMs: Long,
  val globalMs: Long,
  val label: String? = null,
)
