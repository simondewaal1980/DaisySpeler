package nl.simon.daisyspeler.data.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class XmlDocumentsTest {
  @Test
  fun `simple doctype is still accepted with secure processing`() {
    val document =
      XmlDocuments.parse(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE package>
        <package><title>Test</title></package>
        """.trimIndent()
      )

    assertEquals("Test", document.getElementsByTagName("title").item(0).textContent)
  }
}
