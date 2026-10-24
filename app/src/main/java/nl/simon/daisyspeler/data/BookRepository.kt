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
  private val cache = mutableMapOf<String, DaisyBook>()
  private val sourceCache = mutableMapOf<String, BookSource>()
  private val booksRoot = File(context.filesDir, "books")

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
    LoadedBook(id, source, book)
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
        LoadedBook(id, source, book, target.absolutePath)
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
    load(entry)
    return sourceCache[bookId]
      ?: throw IllegalStateException("Boekbestanden zijn niet beschikbaar.")
  }

  fun cached(bookId: String): DaisyBook? = cache[bookId]

  fun forget(bookId: String) {
    cache.remove(bookId)
    sourceCache.remove(bookId)
  }

  /**
   * Removes the extracted copy of a ZIP-imported book. Folder imports are left untouched.
   */
  fun deleteLocalFiles(entry: LibraryEntry) {
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
}

data class LoadedBook(
  val id: String,
  val source: BookSource,
  val book: DaisyBook,
  val localDir: String? = null,
)
