package nl.simon.daisyspeler.data.parser

import nl.simon.daisyspeler.data.book.AudioSegment
import nl.simon.daisyspeler.data.book.DaisyBook
import nl.simon.daisyspeler.data.book.NavPoint
import nl.simon.daisyspeler.data.book.NavType
import nl.simon.daisyspeler.data.source.BookSource
import nl.simon.daisyspeler.data.source.SourceFile
import kotlin.math.max

class DaisyParseException(message: String) : Exception(message)

class DaisyBookLoader {
  fun load(source: BookSource): DaisyBook {
    val warnings = mutableListOf<String>()
    val allFiles = source.files().filterNot { it.isDirectory }
    if (allFiles.isEmpty()) throw DaisyParseException("Deze map is leeg.")

    val byLowerPath = allFiles.associateBy { PathUtils.normalize(it.path).lowercase() }
    fun resolveFile(path: String): SourceFile? =
      byLowerPath[PathUtils.normalize(path).lowercase()]

    val nccFile =
      findNccFile(allFiles)
        ?: throw DaisyParseException(
          "Geen ncc.html gevonden. Kies de map met de uitgepakte DAISY 2.02-bestanden."
        )

    val ncc = NccParser.parse(source, nccFile.path)
    warnings += ncc.warnings

    val opfFile = findOpfFile(allFiles, nccFile.path)
    val opf = opfFile?.let { OpfParser.parse(source, it.path) }
    opf?.let { data ->
      if (data.nccPath != null && resolveFile(data.nccPath)?.path != nccFile.path) {
        warnings += "Het OPF-bestand verwijst naar een andere NCC dan gevonden; ncc.html wordt gebruikt."
      }
      if (data.smilSpine.isEmpty()) warnings += "Geen SMIL-spine in het OPF-bestand gevonden."
    }

    val erBookInfoOrder = parseErBookInfo(source, allFiles) { path -> resolveFile(path) }
    val preferredOrder =
      buildList {
        addAll(opf?.smilSpine.orEmpty())
        addAll(ncc.navItems.map { it.smilPath })
        addAll(erBookInfoOrder)
      }.mapNotNull { resolveFile(it)?.path }

    val allSmilFiles =
      allFiles
        .filter { PathUtils.extension(it.path) == "smil" }
        .sortedBy { PathUtils.normalize(it.path).lowercase() }

    val smilOrder =
      (preferredOrder + allSmilFiles.map { it.path })
        .distinctBy { PathUtils.normalize(it).lowercase() }
        .mapNotNull { resolveFile(it)?.path }

    val parsedSmils = mutableListOf<ParsedSmil>()
    for (smilPath in smilOrder) {
      runCatching { SmilParser.parse(source, smilPath) }
        .onSuccess { parsed ->
          if (parsed.pars.any { it.clips.isNotEmpty() }) parsedSmils += parsed
        }
        .onFailure {
          warnings += "Kon ${PathUtils.fileName(smilPath)} niet lezen: ${it.message}"
        }
    }
    if (parsedSmils.isEmpty()) {
      throw DaisyParseException("Geen leesbare SMIL-bestanden met audio gevonden.")
    }

    // Resolve every clip in document order first. A missing clip-end can only be filled in with
    // the start of a later clip in the same audio file, which requires the complete list.
    val pendingClips = mutableListOf<PendingClip>()
    val pendingPars = mutableListOf<PendingPar>()
    for (smil in parsedSmils) {
      for (par in smil.pars) {
        val pendingPar = PendingPar(smil.path, par)
        for (audio in par.clips) {
          val actualSource = resolveFile(audio.sourcePath)
          if (actualSource == null) {
            warnings += "Audiobestand ${audio.sourcePath} niet gevonden."
            continue
          }
          val pending = PendingClip(smil.path, audio, actualSource.path)
          pendingPar.clips += pending
          pendingClips += pending
        }
        if (pendingPar.clips.isNotEmpty()) pendingPars += pendingPar
      }
    }

    val clips = mutableListOf<TimelineClip>()
    val timelineByPending = mutableMapOf<PendingClip, TimelineClip>()
    for ((index, pending) in pendingClips.withIndex()) {
      val audio = pending.audio
      val end =
        audio.endMs ?: findNextStart(pendingClips, index, pending.sourcePath, audio.startMs)
      if (end == null || end <= audio.startMs) {
        warnings += "Clip in ${PathUtils.fileName(pending.smilPath)} heeft geen geldige eindtijd."
        continue
      }
      val clip =
        TimelineClip(
          smilPath = pending.smilPath,
          sourcePath = pending.sourcePath,
          startMs = audio.startMs,
          endMs = end,
        )
      clips += clip
      timelineByPending[pending] = clip
    }

    if (clips.isEmpty()) throw DaisyParseException("Geen afspeelbare audioclips gevonden.")

    val navIdMap = mutableMapOf<String, TimelineClip>()
    val firstClipBySmil = mutableMapOf<String, TimelineClip>()
    for (pendingPar in pendingPars) {
      val smilKey = PathUtils.normalize(pendingPar.smilPath).lowercase()
      val parClips = pendingPar.clips.mapNotNull { timelineByPending[it] }
      if (parClips.isEmpty()) continue

      for (pending in pendingPar.clips) {
        val clip = timelineByPending[pending] ?: continue
        pending.audio.id?.let { navIdMap["$smilKey#$it"] = clip }
      }
      firstClipBySmil.getOrPut(smilKey) { parClips.first() }
      for (id in pendingPar.par.elementIds) {
        navIdMap.putIfAbsent("$smilKey#$id", parClips.first())
      }
    }

    val segments = buildSegments(clips)
    var trackStart = 0L
    val finalSegments =
      segments.mapIndexed { index, segment ->
        segment.trackStartMs = trackStart
        val audioSegment =
          AudioSegment(
            index = index,
            sourcePath = segment.sourcePath,
            clipStartMs = segment.startMs,
            clipEndMs = segment.endMs,
            trackStartMs = trackStart,
          )
        trackStart += audioSegment.durationMs
        audioSegment
      }
    if (finalSegments.isEmpty()) throw DaisyParseException("Geen afspeelbare audio gevonden.")

    val navPoints = resolveNavigation(
      ncc.navItems,
      { path -> resolveFile(path) },
      navIdMap,
      firstClipBySmil,
      warnings,
    )
    val toc =
      navPoints.ifEmpty {
        warnings += "Geen navigatiepunten gevonden; eenvoudige indeling per audiobestand gemaakt."
        finalSegments.mapIndexed { index, segment ->
          NavPoint(
            label = "Deel ${index + 1}",
            level = 1,
            type = NavType.HEADING,
            smilPath = parsedSmils.firstOrNull()?.path.orEmpty(),
            fragment = null,
            globalMs = segment.trackStartMs,
            segmentIndex = index,
            offsetMs = 0L,
          )
        }
      }

    val totalDuration = finalSegments.sumOf { it.durationMs }
    if (ncc.totalTimeMs != null && kotlin.math.abs(ncc.totalTimeMs - totalDuration) > 10_000) {
      warnings +=
        "NCC meldt ${formatTime(ncc.totalTimeMs)}, maar de clips zijn samen ${formatTime(totalDuration)}."
    }

    return DaisyBook(
      title =
        ncc.title
          ?: opf?.title
          ?: PathUtils.parent(nccFile.path)
            .substringAfterLast('/')
            .ifBlank { "Naamloos boek" },
      author = ncc.author ?: opf?.author,
      language = ncc.language ?: opf?.language,
      totalDurationMs = totalDuration,
      segments = finalSegments,
      toc = toc.sortedBy { it.globalMs },
      warnings = warnings.distinct(),
      coverPath =
        resolveCoverPath(
          allFiles = allFiles,
          nccPath = nccFile.path,
          nccCoverPath = ncc.coverPath,
          opfCoverPath = opf?.coverPath,
          resolveFile = ::resolveFile,
        ),
    )
  }

  /**
   * Finds the cover without parsing audio or SMIL files. Used to backfill covers for books that
   * were imported before cover support existed.
   */
  fun findCover(source: BookSource): String? {
    val allFiles = source.files().filterNot { it.isDirectory }
    if (allFiles.isEmpty()) return null

    val byLowerPath = allFiles.associateBy { PathUtils.normalize(it.path).lowercase() }
    fun resolveFile(path: String): SourceFile? =
      byLowerPath[PathUtils.normalize(path).lowercase()]

    val nccFile = findNccFile(allFiles)
    val opfFile = findOpfFile(allFiles, nccFile?.path)
    val nccCover =
      nccFile?.let { file ->
        runCatching { NccParser.parse(source, file.path).coverPath }.getOrNull()
      }
    val opfCover =
      opfFile?.let { file ->
        runCatching { OpfParser.parse(source, file.path)?.coverPath }.getOrNull()
      }

    return resolveCoverPath(
      allFiles = allFiles,
      nccPath = nccFile?.path ?: opfFile?.path.orEmpty(),
      nccCoverPath = nccCover,
      opfCoverPath = opfCover,
      resolveFile = ::resolveFile,
    )
  }

  private fun findNccFile(allFiles: List<SourceFile>): SourceFile? =
    allFiles
      .filter {
        val name = PathUtils.fileName(it.path).lowercase()
        name == "ncc.html" || name == "ncc.htm"
      }
      .minByOrNull { PathUtils.normalize(it.path).count { char -> char == '/' } }

  private fun findOpfFile(allFiles: List<SourceFile>, nccPath: String?): SourceFile? {
    val nccDir = nccPath?.let(PathUtils::parent)
    return allFiles
      .filter { PathUtils.extension(it.path) == "opf" }
      .minByOrNull {
        val sameDir = nccDir != null && PathUtils.parent(it.path).equals(nccDir, true)
        (if (sameDir) 0 else 1_000) + PathUtils.normalize(it.path).count { char -> char == '/' }
      }
  }

  /**
   * Prefers the cover declared in the OPF, then an image referenced from the NCC and finally a
   * file named like a cover. Only real bitmap files are accepted.
   */
  private fun resolveCoverPath(
    allFiles: List<SourceFile>,
    nccPath: String,
    nccCoverPath: String?,
    opfCoverPath: String?,
    resolveFile: (String) -> SourceFile?,
  ): String? {
    val declared =
      listOfNotNull(opfCoverPath, nccCoverPath).firstNotNullOfOrNull { candidate ->
        resolveFile(candidate)?.path?.takeIf(ImageFiles::isImage)
      }
    if (declared != null) return declared

    val nccDir = PathUtils.parent(nccPath)
    val images = allFiles.filter { !it.isDirectory && ImageFiles.isImage(it.path) }
    val hinted = images.filter { ImageFiles.looksLikeCover(it.path) }
    val candidates = if (hinted.isNotEmpty()) hinted else if (images.size == 1) images else emptyList()
    return candidates
      .sortedWith(
        compareBy(
          { if (PathUtils.parent(it.path).equals(nccDir, true)) 0 else 1 },
          { PathUtils.normalize(it.path).count { char -> char == '/' } },
          { it.path.lowercase() },
        )
      )
      .map { it.path }
      .firstOrNull()
  }

  private fun resolveNavigation(
    rawItems: List<RawNavItem>,
    resolveFile: (String) -> SourceFile?,
    navIdMap: Map<String, TimelineClip>,
    firstClipBySmil: Map<String, TimelineClip>,
    warnings: MutableList<String>,
  ): List<NavPoint> {
    val result = mutableListOf<NavPoint>()
    for (item in rawItems) {
      val actualSmil = resolveFile(item.smilPath)?.path ?: continue
      val smilKey = PathUtils.normalize(actualSmil).lowercase()
      val clip =
        item.fragment?.let { navIdMap["$smilKey#$it"] } ?: firstClipBySmil[smilKey] ?: continue
      val segment = clip.segment ?: continue
      val global = segment.trackStartMs + (clip.startMs - segment.startMs)
      val offset = (clip.startMs - segment.startMs).coerceAtLeast(0L)
      result +=
        NavPoint(
          label = item.label,
          level = item.level,
          type = item.type,
          smilPath = actualSmil,
          fragment = item.fragment,
          globalMs = global,
          segmentIndex = segment.index,
          offsetMs = offset,
        )
    }
    if (result.isEmpty() && rawItems.isNotEmpty()) {
      warnings += "Geen enkel NCC-navigatiepunt kon aan audio worden gekoppeld."
    }
    return result
  }

  /**
   * Uses the start of a later clip in the same audio file as the end of a clip without
   * clip-end. DAISY 2.02 normally provides clip-end; this fallback only fills a missing value
   * and never reuses the end of an earlier clip.
   */
  private fun findNextStart(
    clips: List<PendingClip>,
    currentIndex: Int,
    sourcePath: String,
    startMs: Long,
  ): Long? {
    for (index in currentIndex + 1 until clips.size) {
      val next = clips[index]
      if (next.sourcePath.equals(sourcePath, true) && next.audio.startMs > startMs) {
        return next.audio.startMs
      }
    }
    return null
  }

  private fun buildSegments(clips: List<TimelineClip>): List<MutableSegment> {
    val segments = mutableListOf<MutableSegment>()
    for (clip in clips) {
      val last = segments.lastOrNull()
      if (last != null &&
        last.sourcePath.equals(clip.sourcePath, true) &&
        clip.startMs <= last.endMs + 60 &&
        clip.endMs > last.startMs
      ) {
        last.endMs = max(last.endMs, clip.endMs)
        clip.segment = last
      } else {
        val segment = MutableSegment(segments.size, clip.sourcePath, clip.startMs, clip.endMs)
        segments += segment
        clip.segment = segment
      }
    }
    return segments
  }

  private fun parseErBookInfo(
    source: BookSource,
    files: List<SourceFile>,
    resolveFile: (String) -> SourceFile?,
  ): List<String> {
    val file =
      files.firstOrNull { PathUtils.fileName(it.path).equals("er_book_info.xml", true) }
        ?: return emptyList()
    return runCatching {
      val document = XmlDocuments.parse(TextDecoding.decode(source.open(file.path).use { it.readBytes() }))
      val nodes = document.getElementsByTagName("smil")
      buildList {
        for (i in 0 until nodes.length) {
          val element = nodes.item(i) as? org.w3c.dom.Element ?: continue
          val name = element.getAttribute("Name").takeIf { it.isNotBlank() } ?: continue
          val resolved = resolveFile(PathUtils.join(PathUtils.parent(file.path), name))
          if (resolved != null) add(resolved.path)
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
  }

  private class TimelineClip(
    val smilPath: String,
    val sourcePath: String,
    val startMs: Long,
    val endMs: Long,
  ) {
    var segment: MutableSegment? = null
  }

  private class MutableSegment(
    val index: Int,
    val sourcePath: String,
    val startMs: Long,
    var endMs: Long,
  ) {
    var trackStartMs: Long = 0L
  }

  private class PendingClip(
    val smilPath: String,
    val audio: ParsedAudioClip,
    val sourcePath: String,
  )

  private class PendingPar(
    val smilPath: String,
    val par: ParsedPar,
    val clips: MutableList<PendingClip> = mutableListOf(),
  )
}
