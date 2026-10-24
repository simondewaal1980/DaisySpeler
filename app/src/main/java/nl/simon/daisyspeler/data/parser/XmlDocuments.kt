package nl.simon.daisyspeler.data.parser

import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

object XmlDocuments {
  fun parse(text: String): Document {
    val factory =
      DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        runCatching {
          setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        runCatching {
          setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
          setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
      }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    return builder.parse(InputSource(StringReader(text)))
  }
}
