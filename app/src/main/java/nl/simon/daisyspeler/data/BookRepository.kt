package nl.simon.daisyspeler.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
  private val cache = ConcurrentHashMap<String, DaisyBook>()
  private val sourceCache = ConcurrentHashMap<String, BookSource>()
  private val coverChecked =
    Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
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
    // An explicit import must reflect the current contents of the folder. Build a fresh source
    // and parse it instead of reusing the cache, but only replace the cache after success.
    val source =
      withContext(Dispatchers.IO) { SafBookSource(context, Uri.parse(treeUri)) }
    val book = withContext(Dispatchers.IO) { loader.load(source) }
    cache[id] = book
    sourceCache[id] = source
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
      val target = File(booksRoot, id)
      val backup = File(booksRoot, "$id.backup")
      temp.deleteRecursively()
      if (!temp.mkdirs()) {
        throw IOException("De tijdelijke map voor het ZIP-bestand kon niet worden aangemaakt.")
      }
      try {
        // Recover a replacement that was interrupted after the old folder was moved aside.
        if (!target.exists() && backup.exists() && !backup.renameTo(target)) {
          throw IOException("De vorige uitgepakte boekbestanden konden niet worden hersteld.")
        }
        backup.deleteRecursively()

        val input = context.contentResolver.openInputStream(zipUri)
          ?: throw FileNotFoundException("Het ZIP-bestand kon niet worden geopend.")
        input.use { ZipBookExtractor(temp).extract(it) }

        val parsedSource = FileBookSource(temp)
        val book = loader.load(parsedSource)

        if (target.exists() && !target.renameTo(backup)) {
          throw IOException("De oude uitgepakte boekbestanden konden niet worden verplaatst.")
        }
        if (!temp.renameTo(target)) {
          if (backup.exists() && !backup.renameTo(target)) {
            throw IOException("De oude boekbestanden konden niet worden teruggezet.")
          }
          throw IOException("De uitgepakte boekbestanden konden niet worden opgeslagen.")
        }
        backup.deleteRecursively()

        val source = FileBookSource(target)
        cache[id] = book
        sourceCache[id] = source
        val coverPath =
          cacheCover(id, book.coverPath, source)
            ?: cacheEmbeddedCover(id, source, book.segments.map { it.sourcePath })
        LoadedBook(id, source, book, target.absolutePath, coverPath)
      } catch (error: Throwable) {
        temp.deleteRecursively()
        if (!target.exists() && backup.exists()) {
          backup.renameTo(target)
        }
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
  suspend fun ensureCover(entry: LibraryEntry): String? = withContext(Dispatchers.IO) {
    entry.coverPath?.let { path -> if (File(path).isFile) return@withContext path }

    coverMutex.withLock {
      // Another caller may have repaired the cover while this one was waiting.
      val latest = libraryRepository.get(entry.id) ?: entry
      latest.coverPath?.let { path ->
        if (File(path).isFile) return@withLock path
        libraryRepository.updateCover(entry.id, null)
        coverChecked.remove(entry.id)
      }
      if (entry.id in coverChecked) return@withLock null

      try {
        val source = sourceForEntry(latest)
        val fileCover = loader.findCover(source)
        val coverPath =
          cacheCover(entry.id, fileCover, source)
            ?: cacheEmbeddedCover(entry.id, source, audioPaths(source))
        coverChecked += entry.id
        libraryRepository.updateCover(entry.id, coverPath)
        coverPath
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        // A temporary SAF/IO failure should not disable cover repair until the next restart.
        coverChecked.remove(entry.id)
        throw error
      }
    }
  }

  /**
   * Stores an image that the user picked in the cover browser as the cover of this book. A unique
   * file name is used so the displayed cover is refreshed immediately, even when the previous
   * cover had the same format.
   */
  suspend fun setCover(bookId: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
    if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return@withContext null
    val extension = ImageFiles.extensionOf(bytes) ?: return@withContext null
    if (!ensureCoversRoot()) return@withContext null

    coverMutex.withLock {
      val target =
        File(coversRoot, "$bookId-${System.currentTimeMillis()}-${System.nanoTime()}.$extension")
      try {
        target.outputStream().use { it.write(bytes) }
        coversRoot.listFiles { file -> file != target && isCoverFile(file, bookId) }
          ?.forEach { it.delete() }
        libraryRepository.updateCover(bookId, target.absolutePath)
        target.absolutePath
      } catch (cancelled: CancellationException) {
        target.delete()
        throw cancelled
      } catch (_: Throwable) {
        target.delete()
        null
      }
    }
  }

  private fun cacheCover(bookId: String, relativePath: String?, source: BookSource): String? {
    val coverPath = relativePath ?: return null
    if (!ImageFiles.isImage(coverPath)) return null
    val extension = coverPath.substringAfterLast('.', "").lowercase()
    if (extension.isBlank()) return null
    if (!ensureCoversRoot()) return null

    val temp = File(coversRoot, "$bookId-${System.nanoTime()}.tmp")
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

    val temp = File(coversRoot, "$bookId-${System.nanoTime()}.tmp")
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
    // A unique name means Compose never keeps showing an old bitmap for a replaced cover.
    val target =
      File(coversRoot, "$bookId-${System.currentTimeMillis()}-${System.nanoTime()}.$extension")
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

    // Remove covers of an earlier import or a previous manual choice.
    coversRoot.listFiles { file -> file != target && isCoverFile(file, bookId) }?.forEach {
      it.delete()
    }

    return target.absolutePath
  }

  private fun isCoverFile(file: File, bookId: String): Boolean =
    file.name.startsWith("$bookId.") || file.name.startsWith("$bookId-")

  fun forget(bookId: String) {
    cache.remove(bookId)
    sourceCache.remove(bookId)
    coverChecked.remove(bookId)
  }

  /**
   * Removes the extracted copy of a ZIP-imported book. Folder imports are left untouched.
   */
  fun deleteLocalFiles(entry: LibraryEntry) {
    coversRoot.listFiles { file -> isCoverFile(file, entry.id) }?.forEach { it.delete() }
    File(booksRoot, "${entry.id}.tmp").deleteRecursively()
    File(booksRoot, "${entry.id}.backup").deleteRecursively()
    val localDir = entry.localDir ?: return
    val root = booksRoot.canonicalFile
    val target = File(localDir).canonicalFile
    if (target != root && target.path.startsWith(root.path + File.separator)) {
      target.deleteRecursively()
    }
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
