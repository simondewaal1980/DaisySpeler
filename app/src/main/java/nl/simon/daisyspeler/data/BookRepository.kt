package nl.simon.daisyspeler.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.ConcurrentHashMap

object BookIds {
  fun fromTreeUri(treeUri: String): String = hash(treeUri)

  fun fromZipUri(zipUri: String): String = hash("zip:$zipUri")

  private fun hash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.take(12).joinToString("") { "%02x".format(it) }
  }
}

class BookRepository internal constructor(
  private val libraryRepository: LibraryRepository,
  private val booksRoot: File,
  private val coversRoot: File,
  private val entrySourceFactory: suspend (LibraryEntry) -> BookSource,
  private val treeSourceFactory: suspend (String) -> BookSource,
  private val loadSource: suspend (BookSource) -> DaisyBook,
  private val findCover: suspend (BookSource) -> String?,
  private val openZipStream: (Uri) -> java.io.InputStream? = { null },
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  constructor(context: Context, libraryRepository: LibraryRepository) : this(
    libraryRepository = libraryRepository,
    booksRoot = File(context.filesDir, "books"),
    coversRoot = File(context.filesDir, "covers"),
    entrySourceFactory = { entry -> defaultSourceFor(context, entry) },
    treeSourceFactory = { treeUri ->
      val contextJob = currentCoroutineContext()
      SafBookSource(context, Uri.parse(treeUri)) { !contextJob.isActive }
    },
    loadSource = { source -> DaisyBookLoader().load(source) },
    findCover = { source -> DaisyBookLoader().findCover(source) },
    openZipStream = { uri -> context.contentResolver.openInputStream(uri) },
  )

  private val cache = ConcurrentHashMap<String, DaisyBook>()
  private val sourceCache = ConcurrentHashMap<String, BookSource>()
  private val bookLocks = ConcurrentHashMap<String, Mutex>()
  private val coverLocks = ConcurrentHashMap<String, Mutex>()
  private val coverChecked =
    ConcurrentHashMap<String, Boolean>()
  /** ZIP and manual imports are serialized separately and never hold a lock needed to open a book. */
  private val importGate = Mutex()
  /** Cover backfills run one at a time so a large library cannot flood the IO pool. */
  private val coverGate = Semaphore(1)

  suspend fun load(entry: LibraryEntry): DaisyBook = loadForEntry(entry)

  suspend fun importTree(treeUri: String): LoadedBook {
    val id = BookIds.fromTreeUri(treeUri)
    val (source, book) =
      importGate.withLock {
        lockFor(id).withLock {
          withContext(ioDispatcher) {
            currentCoroutineContext().ensureActive()
            // An explicit import must reflect the current contents of the folder. Build a fresh
            // source and parse it instead of reusing the cache, but only replace the cache after
            // success.
            val importedSource = treeSourceFactory(treeUri)
            val importedBook = loadSource(importedSource)
            cache[id] = importedBook
            sourceCache[id] = importedSource
            importedSource to importedBook
          }
        }
      }
    val coverPath = cacheBookCover(id, book, source)
    return LoadedBook(id, source, book, coverPath = coverPath)
  }

  /**
   * Extracts a ZIP archive into app-private storage and loads the DAISY book from there.
   * The extracted copy is kept so playback can continue after the app restarts.
   */
  suspend fun importZip(zipUri: Uri): LoadedBook {
    val id = BookIds.fromZipUri(zipUri.toString())
    val (source, book, targetPath) =
      importGate.withLock {
        lockFor(id).withLock {
          withContext(ioDispatcher) {
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

              val input = openZipStream(zipUri)
                ?: throw FileNotFoundException("Het ZIP-bestand kon niet worden geopend.")
              input.use { ZipBookExtractor(temp).extract(it) }

              val sourceJob = currentCoroutineContext()
              val parsedSource = FileBookSource(temp) { !sourceJob.isActive }
              val parsedBook = loadSource(parsedSource)

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

              val committedSource = FileBookSource(target) { !sourceJob.isActive }
              cache[id] = parsedBook
              sourceCache[id] = committedSource
              Triple(committedSource, parsedBook, target.absolutePath)
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
      }
    val coverPath = cacheBookCover(id, book, source)
    return LoadedBook(id, source, book, targetPath, coverPath)
  }

  suspend fun loadById(bookId: String): DaisyBook {
    cache[bookId]?.let { return it }
    val entry =
      libraryRepository.get(bookId)
        ?: throw IllegalStateException("Boek niet gevonden in de bibliotheek.")
    return loadForEntry(entry)
  }

  suspend fun sourceById(bookId: String): BookSource {
    sourceCache[bookId]?.let { return it }
    val entry =
      libraryRepository.get(bookId)
        ?: throw IllegalStateException("Boek niet gevonden in de bibliotheek.")
    return sourceForEntry(entry)
  }

  fun cached(bookId: String): DaisyBook? = cache[bookId]

  /**
   * Ensures the cover of a book is available as a local file. Covers of folder imports live
   * behind a SAF permission and may disappear, so they are copied to app-private storage. This
   * also backfills books that were imported before cover support existed.
   */
  suspend fun ensureCover(entry: LibraryEntry): String? =
    withContext(ioDispatcher) {
      currentCoroutineContext().ensureActive()
      entry.coverPath?.let { path -> if (File(path).isFile) return@withContext path }

      coverLockFor(entry.id).withLock {
        currentCoroutineContext().ensureActive()
        // Another caller may have repaired the cover while this one was waiting.
        val latest = libraryRepository.get(entry.id) ?: entry
        latest.coverPath?.let { path ->
          if (File(path).isFile) return@withLock path
          libraryRepository.updateCover(entry.id, null)
          coverChecked.remove(entry.id)
        }
        if (coverChecked[entry.id] == true) return@withLock null

        coverGate.withPermit {
          try {
            val source = sourceForEntry(latest)
            val fileCover = findCover(source)
            currentCoroutineContext().ensureActive()
            val coverPath = cacheCover(entry.id, fileCover, source)
              ?: cacheEmbeddedCover(entry.id, source, audioPaths(source))
            currentCoroutineContext().ensureActive()
            coverChecked[entry.id] = true
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
    }

  /**
   * Stores an image that the user picked in the cover browser as the cover of this book. A unique
   * file name is used so the displayed cover is refreshed immediately, even when the previous
   * cover had the same format.
   */
  suspend fun setCover(bookId: String, bytes: ByteArray): String? = withContext(ioDispatcher) {
    if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return@withContext null
    val extension = ImageFiles.extensionOf(bytes) ?: return@withContext null
    if (!ensureCoversRoot()) return@withContext null

    coverLockFor(bookId).withLock {
      currentCoroutineContext().ensureActive()
      val target =
        File(coversRoot, "$bookId-${System.currentTimeMillis()}-${System.nanoTime()}.$extension")
      try {
        target.outputStream().use { it.write(bytes) }
        // Keep the previous image until its replacement is durably registered in the library.
        libraryRepository.updateCover(bookId, target.absolutePath)
        coversRoot.listFiles { file -> file != target && isCoverFile(file, bookId) }
          ?.forEach { it.delete() }
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

  private suspend fun loadForEntry(entry: LibraryEntry): DaisyBook {
    cache[entry.id]?.let { return it }
    return lockFor(entry.id).withLock {
      cache[entry.id] ?: run {
        val (source, book) =
          withContext(ioDispatcher) {
            currentCoroutineContext().ensureActive()
            val resolvedSource = sourceCache[entry.id] ?: entrySourceFactory(entry)
            currentCoroutineContext().ensureActive()
            val loadedBook = loadSource(resolvedSource)
            resolvedSource to loadedBook
          }
        sourceCache[entry.id] = source
        cache[entry.id] = book
        book
      }
    }
  }

  private suspend fun sourceForEntry(entry: LibraryEntry): BookSource =
    sourceCache[entry.id] ?: lockFor(entry.id).withLock {
      sourceCache[entry.id] ?: withContext(ioDispatcher) {
        currentCoroutineContext().ensureActive()
        entrySourceFactory(entry)
      }.also { sourceCache[entry.id] = it }
    }

  private suspend fun cacheBookCover(
    bookId: String,
    book: DaisyBook,
    source: BookSource,
  ): String? =
    coverLockFor(bookId).withLock {
      // A newer import may have replaced the source while this cover scan was queued. Do not
      // publish a cover for the old version over the new one.
      if (sourceCache[bookId] != null && sourceCache[bookId] !== source) {
        return@withLock null
      }
      withContext(ioDispatcher) {
        cacheCover(bookId, book.coverPath, source)
          ?: cacheEmbeddedCover(bookId, source, book.segments.map { it.sourcePath })
      }
    }

  private suspend fun cacheCover(
    bookId: String,
    relativePath: String?,
    source: BookSource,
  ): String? = withContext(ioDispatcher) {
    currentCoroutineContext().ensureActive()
    val coverPath = relativePath ?: return@withContext null
    if (!ImageFiles.isImage(coverPath)) return@withContext null
    val extension = coverPath.substringAfterLast('.', "").lowercase()
    if (extension.isBlank()) return@withContext null
    if (!ensureCoversRoot()) return@withContext null

    val temp = File(coversRoot, "$bookId-${System.nanoTime()}.tmp")
    try {
      source.open(coverPath).use { input ->
        temp.outputStream().use { output -> copyCover(input, output) }
      }
      currentCoroutineContext().ensureActive()
      finishCover(bookId, extension, temp)
    } catch (cancelled: CancellationException) {
      temp.delete()
      throw cancelled
    } catch (_: Throwable) {
      temp.delete()
      null
    }
  }

  /** Falls back to artwork embedded in the audio files when the book has no image file. */
  private suspend fun cacheEmbeddedCover(
    bookId: String,
    source: BookSource,
    audioPaths: List<String>,
  ): String? = withContext(ioDispatcher) {
    currentCoroutineContext().ensureActive()
    val contextJob = currentCoroutineContext()
    val cover =
      EmbeddedCoverExtractor.find(source, audioPaths) { !contextJob.isActive }
        ?: return@withContext null
    currentCoroutineContext().ensureActive()
    val extension = cover.extension ?: return@withContext null
    if (cover.bytes.isEmpty() || cover.bytes.size > MAX_COVER_BYTES) return@withContext null
    if (!ensureCoversRoot()) return@withContext null

    val temp = File(coversRoot, "$bookId-${System.nanoTime()}.tmp")
    try {
      temp.outputStream().use { it.write(cover.bytes) }
      currentCoroutineContext().ensureActive()
      finishCover(bookId, extension, temp)
    } catch (cancelled: CancellationException) {
      temp.delete()
      throw cancelled
    } catch (_: Throwable) {
      temp.delete()
      null
    }
  }

  private suspend fun audioPaths(source: BookSource): List<String> =
    withContext(ioDispatcher) {
      currentCoroutineContext().ensureActive()
      source.files()
        .asSequence()
        .filter { !it.isDirectory && EmbeddedCoverExtractor.isAudio(it.path) }
        .map { it.path }
        .toList()
    }

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

  private fun lockFor(bookId: String): Mutex =
    bookLocks.computeIfAbsent(bookId) { Mutex() }

  private fun coverLockFor(bookId: String): Mutex =
    coverLocks.computeIfAbsent(bookId) { Mutex() }

  private companion object {
    const val COVER_BUFFER_SIZE = 64 * 1024
    const val MAX_COVER_BYTES = 12L * 1024 * 1024

    suspend fun defaultSourceFor(context: Context, entry: LibraryEntry): BookSource {
      val contextJob = currentCoroutineContext()
      if (entry.sourceType == LibrarySourceType.ZIP) {
        val localDir = entry.localDir
          ?: throw IllegalStateException("De uitgepakte boekbestanden ontbreken.")
        return FileBookSource(File(localDir)) { !contextJob.isActive }
      }
      return SafBookSource(context, Uri.parse(entry.treeUri)) { !contextJob.isActive }
    }
  }
}

data class LoadedBook(
  val id: String,
  val source: BookSource,
  val book: DaisyBook,
  val localDir: String? = null,
  val coverPath: String? = null,
)
