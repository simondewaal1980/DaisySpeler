package nl.simon.daisyspeler.ui.library

import java.text.Collator
import java.util.Locale
import nl.simon.daisyspeler.data.storage.LibraryEntry

enum class LibrarySortOrder(val label: String) {
  ALPHABETICAL("Alfabetisch"),
  LAST_READ("Laatst gelezen"),
}

/** Sorts the bookshelf without changing the order in which the library is stored. */
fun sortLibraryEntries(
  entries: List<LibraryEntry>,
  sortOrder: LibrarySortOrder,
): List<LibraryEntry> {
  val collator =
    Collator.getInstance(Locale.forLanguageTag("nl-NL")).apply {
      strength = Collator.PRIMARY
    }
  val byTitle = Comparator<LibraryEntry> { left, right ->
    collator.compare(left.title, right.title)
  }
  val byAuthor = Comparator<LibraryEntry> { left, right ->
    collator.compare(left.author.orEmpty(), right.author.orEmpty())
  }
  return when (sortOrder) {
    LibrarySortOrder.ALPHABETICAL ->
      entries.sortedWith(byTitle.then(byAuthor).thenBy { it.id })

    LibrarySortOrder.LAST_READ ->
      entries.sortedWith(
        // Books that were read at least once come first, most recent first.
        compareByDescending<LibraryEntry> { it.lastReadAtEpochMs != null }
          .thenByDescending { it.lastReadAtEpochMs ?: Long.MIN_VALUE }
          .thenByDescending { it.addedAtEpochMs }
          .then(byTitle)
          .thenBy { it.id }
      )
  }
}
