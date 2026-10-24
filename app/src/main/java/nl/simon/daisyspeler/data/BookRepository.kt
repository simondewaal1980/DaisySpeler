package nl.simon.daisyspeler.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.simon.daisyspeler.data.book.DaisyBook
import nl.simon.daisyspeler.data.importer.ZipBookExtractor
import nl.simon.daisyspeler.data.parser.DaisyBookLoader
import nl.simon.daisyspeler.data.parser.EmbeddedCoverExtractor
import nl.simon.daisyspeler.data.parser.ImageFiles
import nl.simon.daisyspeler.data.source.BookSource
import nl.simon.daisyspeler.data.source.FileBookSource
import nl.simon.daisyspeler.data.source.SafBookSource
import nl.simon.daisyspeler.data.storage.LibraryEntry
import nl.simon.daisyspeler.data.storage.LibraryRepository
import nl.simon.daisyspeler.data.storage.LibrarySourceType
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

object BookIds {
  fun fromTreeUri(treeUri: String): String = hash(treeUri)

  fun fromZipUri(zipUri: String): String = hash("zip:$zipUri")

  private fun hash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.take(12).joinToString("") { "%02x".format(it) }
  }
}

class BookRepository(
  private val context: Context,
  private val libraryRepository: LibraryRepository,
) {
  private val loader = DaisyBookLoader()
  private val mutex = Mutex()
  private val coverMutex = Mutex()
  private val cache = mutableMapOf<String, DaisyBook>()
  private val sourceCache = mutableMapOf<String, BookSource>()
  private val coverChecked = mutableSetOf<String>()
  private val booksRoot = File(context.filesDir, "books")
  private val coversRoot = File(context.filesDir, "covers")

  suspend fun load(entry: LibraryEntry): DaisyBook = mutex.withLock {
    cache[entry.id]
      ?: run {
          val source =
            sourceCache[entry.id]
              ?: withContext(Dispatchers.IO) {
                sourceFor(entry).also { sourceCache[entry.id] = it }
              }
          withContext(Dispatchers.IO) { loader.load(source) }.also { cache[entry.id] = it }
        }
  }

  suspend fun importTree(treeUri: String): LoadedBook = mutex.withLock {
    val id = BookIds.fromTreeUri(treeUri)
    val source =
      sourceCache[id]
        ?: withContext(Dispatchers.IO) {
          SafBookSource(context, Uri.parse(treeUri)).also { sourceCache[id] = it }
        }
    val book =
      cache[id]
        ?: withContext(Dispatchers.IO) { loader.load(source) }.also { cache[id] = it }
    val coverPath =
      withContext(Dispatchers.IO) {
        cacheCover(id, book.coverPath, source)
          ?: cacheEmbeddedCover(id, source, book.segments.map { it.sourcePath })
      }
    LoadedBook(id, source, book, coverPath = coverPath)
  }

  /**
   * Extracts a ZIP archive into app-private storage and loads the DAISY book from there.
   * The extracted copy is kept so playback can continue after the app restarts.
   */
  suspend fun importZip(zipUri: Uri): LoadedBook = mutex.withLock {
    val id = BookIds.fromZipUri(zipUri.toString())
    withContext(Dispatchers.IO) {
      val temp = File(booksRoot, "$id.tmp")
      temp.deleteRecursively()
      if (!temp.mkdirs()) {
        throw IOException("De tijdelijke map voor het ZIP-bestand kon niet worden aangemaakt.")
      }
      try {
        val input = context.contentResolver.openInputStream(zipUri)
          ?: throw FileNotFoundException("Het ZIP-bestand kon niet worden geopend.")
        input.use { ZipBookExtractor(temp).extract(it) }

        val parsedSource = FileBookSource(temp)
        val book = loader.load(parsedSource)

        val target = File(booksRoot, id)
        if (target.exists() && !target.deleteRecursively()) {
          throw IOException("De oude uitgepakte boekbestanden konden niet worden verwijderd.")
        }
        if (!temp.renameTo(target)) {
          throw IOException("De uitgepakte boekbestanden konden niet worden opgeslagen.")
        }

        val source = FileBookSource(target)
        cache[id] = book
        sourceCache[id] = source
        val coverPath =
          cacheCover(id, book.coverPath, source)
            ?: cacheEmbeddedCover(id, source, book.segments.map { it.sourcePath })
        LoadedBook(id, source, book, target.absolutePath, coverPath)
      } catch (error: Throwable) {
        temp.deleteRecursively()
        cache.remove(id)
        sourceCache.remove(id)
        throw error
      }
    }
  }

  suspend fun loadById(bookId: String): DaisyBook {
    cache[bookId]?.let { return it }
    val entry =
      libraryRepository.get(bookId)
        ?: throw IllegalStateException("Boek niet gevonden in de bibliotheek.")
    return load(entry)
  }

  suspend fun sourceById(bookId: String): BookSource {
    sourceCache[bookId]?.let { return it }
    val entry =
      libraryRepository.get(bookId)
        ?: throw IllegalStateException("Boek niet gevonden in de bibliotheek.")
    return sourceForEntry(entry)
  }

  private suspend fun sourceForEntry(entry: LibraryEntry): BookSource = mutex.withLock {
    sourceCache[entry.id]
      ?: withContext(Dispatchers.IO) { sourceFor(entry) }.also { sourceCache[entry.id] = it }
  }

  fun cached(bookId: String): DaisyBook? = cache[bookId]

  /**
   * Ensures the cover of a book is available as a local file. Covers of folder imports live
   * behind a SAF permission and may disappear, so they are copied to app-private storage. This
   * also backfills books that were imported before cover support existed.
   */
  suspend fun ensureCover(entry: LibraryEntry): String? {
    entry.coverPath?.let { path -> if (File(path).isFile) return path }
    return coverMutex.withLock {
      entry.coverPath?.let { path -> if (File(path).isFile) return@withLock path }
      if (entry.id in coverChecked) return@withLock null

      val source = sourceForEntry(entry)
      val fileCover = withContext(Dispatchers.IO) { loader.findCover(source) }
      val coverPath =
        cacheCover(entry.id, fileCover, source)
          ?: cacheEmbeddedCover(entry.id, source, audioPaths(source))
      coverChecked += entry.id
      if (coverPath != null) {
        libraryRepository.updateCover(entry.id, coverPath)
      }
      coverPath
    }
  }

  private fun cacheCover(bookId: String, relativePath: String?, source: BookSource): String? {
    val coverPath = relativePath ?: return null
    if (!ImageFiles.isImage(coverPath)) return null
    val extension = coverPath.substringAfterLast('.', "").lowercase()
    if (extension.isBlank()) return null
    if (!ensureCoversRoot()) return null

    val temp = File(coversRoot, "$bookId.tmp")
    return try {
      source.open(coverPath).use { input ->
        temp.outputStream().use { output -> copyCover(input, output) }
      }
      finishCover(bookId, extension, temp)
    } catch (_: Throwable) {
      temp.delete()
      null
    }
  }

  /** Falls back to artwork embedded in the audio files when the book has no image file. */
  private fun cacheEmbeddedCover(
    bookId: String,
    source: BookSource,
    audioPaths: List<String>,
  ): String? {
    val cover = EmbeddedCoverExtractor.find(source, audioPaths) ?: return null
    val extension = cover.extension ?: return null
    if (cover.bytes.isEmpty() || cover.bytes.size > MAX_COVER_BYTES) return null
    if (!ensureCoversRoot()) return null

    val temp = File(coversRoot, "$bookId.tmp")
    return try {
      temp.outputStream().use { it.write(cover.bytes) }
      finishCover(bookId, extension, temp)
    } catch (_: Throwable) {
      temp.delete()
      null
    }
  }

  private fun audioPaths(source: BookSource): List<String> =
    source.files()
      .asSequence()
      .filter { !it.isDirectory && EmbeddedCoverExtractor.isAudio(it.path) }
      .map { it.path }
      .toList()

  private fun copyCover(input: java.io.InputStream, output: java.io.OutputStream) {
    val buffer = ByteArray(COVER_BUFFER_SIZE)
    var total = 0L
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      total += read
      if (total > MAX_COVER_BYTES) {
        throw IOException("De omslagafbeelding is te groot.")
      }
      output.write(buffer, 0, read)
    }
  }

  private fun ensureCoversRoot(): Boolean = coversRoot.isDirectory || coversRoot.mkdirs()

  private fun finishCover(bookId: String, extension: String, temp: File): String? {
    val target = File(coversRoot, "$bookId.$extension")
    if (temp.length() == 0L) {
      temp.delete()
      return null
    }
    if (target.exists() && !target.delete()) {
      temp.delete()
      return null
    }
    if (!temp.renameTo(target)) {
      temp.delete()
      return null
    }

    // Remove covers with a different extension from an earlier import.
    coversRoot.listFiles { file ->
      file.name.startsWith("$bookId.") && file.name != target.name
    }?.forEach { it.delete() }

    return target.absolutePath
  }

  fun forget(bookId: String) {
    cache.remove(bookId)
    sourceCache.remove(bookId)
    coverChecked.remove(bookId)
  }

  /**
   * Removes the extracted copy of a ZIP-imported book. Folder imports are left untouched.
   */
  fun deleteLocalFiles(entry: LibraryEntry) {
    coversRoot.listFiles { file -> file.name.startsWith("${entry.id}.") }?.forEach { it.delete() }
    val localDir = entry.localDir ?: return
    val root = booksRoot.canonicalFile
    val target = File(localDir).canonicalFile
    if (target != root && target.path.startsWith(root.path + File.separator)) {
      target.deleteRecursively()
    }
    File(booksRoot, "${entry.id}.tmp").deleteRecursively()
  }

  private fun sourceFor(entry: LibraryEntry): BookSource {
    if (entry.sourceType == LibrarySourceType.ZIP) {
      val localDir = entry.localDir
        ?: throw IllegalStateException("De uitgepakte boekbestanden ontbreken.")
      return FileBookSource(File(localDir))
    }
    return SafBookSource(context, Uri.parse(entry.treeUri))
  }

  private companion object {
    const val COVER_BUFFER_SIZE = 64 * 1024
    const val MAX_COVER_BYTES = 12L * 1024 * 1024
  }
}

data class LoadedBook(
  val id: String,
  val source: BookSource,
  val book: DaisyBook,
  val localDir: String? = null,
  val coverPath: String? = null,
)
