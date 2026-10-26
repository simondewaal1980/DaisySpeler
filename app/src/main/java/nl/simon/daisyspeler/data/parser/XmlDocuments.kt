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
        runCatching { isXIncludeAware = false }
        runCatching { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching {
          setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        runCatching {
          setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
          setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        runCatching {
          setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        }
        runCatching {
          setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
      }
    val builder = factory.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    return builder.parse(InputSource(StringReader(text)))
  }
}
