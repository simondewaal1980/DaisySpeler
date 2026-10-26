package nl.simon.daisyspeler.data.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DaisyBookTest {
  private fun book(
    segments: List<AudioSegment> = defaultSegments(),
    headings: List<NavPoint> = defaultHeadings(),
  ): DaisyBook =
    DaisyBook(
      title = "Testboek",
      author = null,
      language = null,
      totalDurationMs = segments.sumOf { it.durationMs },
      segments = segments,
      toc = headings,
    )

  @Test
  fun `segment at an exact boundary uses the next segment`() {
    val (segment, offset) = requireNotNull(book().segmentAtGlobal(2_000L))

    assertEquals(1, segment.index)
    assertEquals(0L, offset)
  }

  @Test
  fun `segment at the end of the book uses the end of the last segment`() {
    val (segment, offset) = requireNotNull(book().segmentAtGlobal(3_000L))

    assertEquals(1, segment.index)
    assertEquals(1_000L, offset)
  }

  @Test
  fun `negative position is clamped to the start`() {
    val (segment, offset) = requireNotNull(book().segmentAtGlobal(-500L))

    assertEquals(0, segment.index)
    assertEquals(0L, offset)
  }

  @Test
  fun `source offset at a segment boundary uses the next segment`() {
    val (segment, offset) = requireNotNull(book().segmentForSource("a.mp3", 2_000L))

    assertEquals(1, segment.index)
    assertEquals(0L, offset)
  }

  @Test
  fun `unknown source path has no segment`() {
    assertNull(book().segmentForSource("missing.mp3", 0L))
  }

  @Test
  fun `next heading does not jump back after the last heading`() {
    assertNull(book().headingTarget(positionMs = 2_500L, forward = true))
  }

  @Test
  fun `previous heading does not jump forward before the first heading`() {
    assertNull(book().headingTarget(positionMs = -100L, forward = false))
  }

  @Test
  fun `heading navigation stays on the first and last heading at the edges`() {
    assertEquals(0L, book().headingTarget(positionMs = 0L, forward = false)?.globalMs)
    assertEquals(2_000L, book().headingTarget(positionMs = 2_000L, forward = true)?.globalMs)
  }

  @Test
  fun `single heading is reachable from before it and not behind it`() {
    val single = listOf(heading("Hoofdstuk", 5_000L, 1, 0L))
    assertEquals(5_000L, book(headings = single).headingTarget(0L, forward = true)?.globalMs)
    assertNull(book(headings = single).headingTarget(0L, forward = false))
  }

  @Test
  fun `position at the start of the second segment has the correct global time`() {
    val position = requireNotNull(book().positionAt(segmentIndex = 1, positionInSegmentMs = 0L))

    assertEquals(2_000L, position.globalMs)
    assertEquals(2_000L, position.sourceOffsetMs)
    assertEquals("a.mp3", position.sourcePath)
  }

  private fun defaultSegments(): List<AudioSegment> =
    listOf(
      AudioSegment(
        index = 0,
        sourcePath = "a.mp3",
        clipStartMs = 0L,
        clipEndMs = 2_000L,
        trackStartMs = 0L,
      ),
      AudioSegment(
        index = 1,
        sourcePath = "a.mp3",
        clipStartMs = 2_000L,
        clipEndMs = 3_000L,
        trackStartMs = 2_000L,
      ),
    )

  private fun defaultHeadings(): List<NavPoint> =
    listOf(
      heading("Eerste", 0L, 1, 0L),
      heading("Tweede", 2_000L, 2, 0L),
    )

  private fun heading(label: String, globalMs: Long, level: Int, offsetMs: Long): NavPoint =
    NavPoint(
      label = label,
      level = level,
      type = NavType.HEADING,
      smilPath = "s.smil",
      fragment = null,
      globalMs = globalMs,
      segmentIndex = if (globalMs >= 2_000L) 1 else 0,
      offsetMs = offsetMs,
    )
}
