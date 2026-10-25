package nl.simon.daisyspeler.ui.library

import nl.simon.daisyspeler.data.storage.LibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortingTest {
  @Test
  fun `alphabetical sorts titles in Dutch order regardless of case`() {
    val entries =
      listOf(
        entry(id = "appel", title = "de appel"),
        entry(id = "banaan", title = "Banaan"),
        entry(id = "citroen", title = "citroen"),
      )

    val sorted = sortLibraryEntries(entries, LibrarySortOrder.ALPHABETICAL)

    assertEquals(listOf("banaan", "citroen", "appel"), sorted.map { it.id })
  }

  @Test
  fun `alphabetical uses the author when titles match`() {
    val entries =
      listOf(
        entry(id = "z", title = "Boek", author = "Zee"),
        entry(id = "a", title = "Boek", author = "Aap"),
      )

    val sorted = sortLibraryEntries(entries, LibrarySortOrder.ALPHABETICAL)

    assertEquals(listOf("a", "z"), sorted.map { it.id })
  }

  @Test
  fun `last read puts recently read books first and unread books after them`() {
    val entries =
      listOf(
        entry(id = "read-old", title = "Oud gelezen", addedAt = 1, lastReadAt = 100),
        entry(id = "read-new", title = "Net gelezen", addedAt = 2, lastReadAt = 300),
        entry(id = "unread-new", title = "Nieuw", addedAt = 500),
        entry(id = "unread-old", title = "Oud", addedAt = 50),
      )

    val sorted = sortLibraryEntries(entries, LibrarySortOrder.LAST_READ)

    assertEquals(
      listOf("read-new", "read-old", "unread-new", "unread-old"),
      sorted.map { it.id },
    )
  }

  private fun entry(
    id: String,
    title: String,
    author: String? = null,
    addedAt: Long = 0L,
    lastReadAt: Long? = null,
  ) =
    LibraryEntry(
      id = id,
      treeUri = "content://$id",
      title = title,
      author = author,
      durationMs = 0L,
      tocCount = 0,
      addedAtEpochMs = addedAt,
      lastReadAtEpochMs = lastReadAt,
    )
}
