package nl.simon.daisyspeler.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nl.simon.daisyspeler.data.book.DaisyBook
import nl.simon.daisyspeler.data.source.BookSource
import nl.simon.daisyspeler.data.source.SourceFile
import nl.simon.daisyspeler.data.storage.LibraryEntry
import nl.simon.daisyspeler.data.storage.LibraryRepository
import nl.simon.daisyspeler.data.storage.LibrarySourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class BookRepositoryConcurrencyTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  private val libraryFile: File by lazy { temporaryFolder.newFile("library.json") }
  private val booksRoot: File by lazy { temporaryFolder.newFolder("books") }
  private val coversRoot: File by lazy { temporaryFolder.newFolder("covers") }

  @Test
  fun `opening book B does not wait for a slow import of book A`() = runBlocking {
    val importStarted = CompletableDeferred<Unit>()
    val releaseImport = CompletableDeferred<Unit>()
    val repository = repository(loadSource = { source ->
      if (source is FakeSource && source.id == "A") {
        importStarted.complete(Unit)
        releaseImport.await()
      }
      bookFor((source as FakeSource).id)
    })
    val importJob = launch(Dispatchers.Default) { repository.importTree("file://book-a") }
    importStarted.await()

    val bookB = withTimeout(1_000L) { repository.load(entry("B")) }

    assertEquals("B", bookB.title)
    releaseImport.complete(Unit)
    withTimeout(2_000L) { importJob.join() }
  }

  @Test
  fun `opening book B does not wait for a slow cover repair of book A`() = runBlocking {
    val coverStarted = CompletableDeferred<Unit>()
    val releaseCover = CompletableDeferred<Unit>()
    val repository = repository(
      loadSource = { source -> bookFor((source as FakeSource).id) },
      findCover = { source ->
        if ((source as FakeSource).id == "A") {
          coverStarted.complete(Unit)
          releaseCover.await()
        }
        null
      },
    )
    val coverJob = launch(Dispatchers.Default) { repository.ensureCover(entry("A")) }
    coverStarted.await()

    val bookB = withTimeout(1_000L) { repository.load(entry("B")) }

    assertEquals("B", bookB.title)
    releaseCover.complete(Unit)
    coverJob.join()
    assertNull(repository.cached("A"))
  }

  @Test
  fun `cancelling a load releases the per-book lock for a retry`() = runBlocking {
    val firstCallStarted = CompletableDeferred<Unit>()
    val calls = AtomicInteger()
    val repository = repository(loadSource = { source ->
      if (calls.incrementAndGet() == 1) {
        firstCallStarted.complete(Unit)
        CompletableDeferred<Unit>().await()
      }
      bookFor((source as FakeSource).id)
    })
    val first = async(Dispatchers.Default) { repository.load(entry("A")) }
    firstCallStarted.await()

    first.cancelAndJoin()

    val retried = withTimeout(1_000L) { repository.load(entry("A")) }
    assertEquals("A", retried.title)
  }

  @Test
  fun `a failed import does not corrupt the cache for another book`() = runBlocking {
    val repository = repository(loadSource = { source ->
      val id = (source as FakeSource).id
      if (id == "broken") error("kapot boek") else bookFor(id)
    })

    val failure = runCatching { repository.importTree("file://broken") }
    assertEquals("kapot boek", failure.exceptionOrNull()?.message)

    val loaded = withTimeout(1_000L) { repository.load(entry("B")) }
    assertEquals("B", loaded.title)
    assertNull(repository.cached(BookIds.fromTreeUri("file://broken")))
  }

  private fun repository(
    loadSource: suspend (BookSource) -> DaisyBook,
    findCover: suspend (BookSource) -> String? = { null },
  ) = BookRepository(
    libraryRepository = LibraryRepository(libraryFile),
    booksRoot = booksRoot,
    coversRoot = coversRoot,
    entrySourceFactory = { entry -> FakeSource(entry.id) },
    treeSourceFactory = { uri ->
      when (val name = uri.substringAfterLast('/')) {
        "book-a" -> FakeSource("A")
        else -> FakeSource(name)
      }
    },
    loadSource = loadSource,
    findCover = findCover,
    ioDispatcher = Dispatchers.Default,
  )

  private fun entry(id: String) =
    LibraryEntry(
      id = id,
      treeUri = "file://$id",
      title = id,
      author = null,
      durationMs = 1_000L,
      tocCount = 0,
      addedAtEpochMs = 1L,
      sourceType = LibrarySourceType.FOLDER,
    )

  private fun bookFor(id: String) =
    DaisyBook(
      title = id,
      author = null,
      language = null,
      totalDurationMs = 1_000L,
      segments = emptyList(),
      toc = emptyList(),
    )

  private class FakeSource(val id: String) : BookSource {
    override fun files(): List<SourceFile> = emptyList()

    override fun open(path: String): InputStream = throw FileNotFoundException(path)

    override fun playableUri(path: String): String = "file://$id/$path"
  }
}
