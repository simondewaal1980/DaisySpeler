package nl.simon.daisyspeler.ui.cover

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSearchTest {
  @Test
  fun `builds a Google Images search url`() {
    assertEquals(
      "https://www.google.com/search?tbm=isch&hl=nl&q=" +
        "Ik+moet+het+nog+even+verwerken%2C+maar+hoe+dan%3F",
      imageSearchUrl("Ik moet het nog even verwerken, maar hoe dan?"),
    )
  }

  @Test
  fun `trims the query`() {
    assertEquals(
      "https://www.google.com/search?tbm=isch&hl=nl&q=Nora+%26+Co",
      imageSearchUrl("  Nora & Co  "),
    )
  }
}
