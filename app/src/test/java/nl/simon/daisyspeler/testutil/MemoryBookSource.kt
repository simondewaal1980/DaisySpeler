package nl.simon.daisyspeler.testutil

import nl.simon.daisyspeler.data.source.BookSource
import nl.simon.daisyspeler.data.source.SourceFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset

class MemoryBookSource(
  private val entries: Map<String, ByteArray>,
) : BookSource {
  companion object {
    fun fromText(
      textFiles: Map<String, String>,
      charset: Charset = Charsets.UTF_8,
    ): MemoryBookSource =
      MemoryBookSource(textFiles.mapValues { (_, value) -> value.toByteArray(charset) })
  }

  override fun files(): List<SourceFile> =
    entries.keys.map { SourceFile(it, isDirectory = false, sizeBytes = entries[it]?.size?.toLong() ?: 0L) }

  override fun open(path: String): InputStream {
    val bytes = entries[path] ?: entries.entries.firstOrNull {
      it.key.equals(path, ignoreCase = true)
    }?.value ?: throw java.io.FileNotFoundException(path)
    return ByteArrayInputStream(bytes)
  }

  override fun playableUri(path: String): String = "file:///tmp/$path"
}
