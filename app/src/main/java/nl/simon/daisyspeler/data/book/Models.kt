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
  /** Path of the cover image inside the book, relative to the book root. */
  val coverPath: String? = null,
) {
  fun segmentAtGlobal(positionMs: Long): Pair<AudioSegment, Long>? {
    if (segments.isEmpty()) return null
    val safe = positionMs.coerceAtLeast(0L)
    // Segments are half-open intervals: [trackStart, trackStart + duration). This makes a
    // position exactly on a boundary belong to the next segment instead of the previous one.
    val segment =
      segments.firstOrNull { safe < it.trackStartMs + it.durationMs } ?: segments.last()
    return segment to (safe - segment.trackStartMs).coerceIn(0L, segment.durationMs)
  }

  fun segmentForSource(sourcePath: String, sourceOffsetMs: Long): Pair<AudioSegment, Long>? {
    val candidates = segments.filter { it.sourcePath.equals(sourcePath, ignoreCase = true) }
    if (candidates.isEmpty()) return null

    val segment =
      candidates.firstOrNull {
        sourceOffsetMs >= it.clipStartMs && sourceOffsetMs < it.clipEndMs
      } ?: candidates.firstOrNull { sourceOffsetMs < it.clipStartMs } ?: candidates.last()
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
      // Only fall back to the last heading when it is not behind the current position.
      headings.firstOrNull { it.globalMs > positionMs + 750 }
        ?: headings.last().takeIf { it.globalMs >= positionMs }
    } else {
      // Only fall back to the first heading when it is not ahead of the current position.
      headings.lastOrNull { it.globalMs < positionMs - 1_500 }
        ?: headings.first().takeIf { it.globalMs <= positionMs }
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
