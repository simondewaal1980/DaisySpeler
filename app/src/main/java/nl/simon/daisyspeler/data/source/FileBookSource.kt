package nl.simon.daisyspeler.data.source

import nl.simon.daisyspeler.data.parser.PathUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Book source for a DAISY book that was extracted from a ZIP file into app-private storage.
 */
class FileBookSource(rootDir: File) : BookSource {
  private val root = rootDir.canonicalFile
  private val fileIndex = LinkedHashMap<String, File>()
  private val fileList = mutableListOf<SourceFile>()

  init {
    if (!root.isDirectory) {
      throw FileNotFoundException("De uitgepakte boekmap is niet leesbaar.")
    }
    walk(root, "", 0)
  }

  private fun walk(directory: File, prefix: String, depth: Int) {
    if (depth > 8 || fileIndex.size > 20_000) return
    val children = directory.listFiles()?.sortedBy { it.name.lowercase() } ?: return
    for (child in children) {
      val name = child.name
      if (name.startsWith(".")) continue
      val path = PathUtils.join(prefix, name)
      if (child.isDirectory) {
        fileList += SourceFile(path, isDirectory = true)
        walk(child, path, depth + 1)
      } else if (child.isFile) {
        fileIndex[path.lowercase()] = child
        fileList += SourceFile(path, isDirectory = false, sizeBytes = child.length())
      }
    }
  }

  override fun files(): List<SourceFile> = fileList.toList()

  override fun open(path: String): InputStream {
    val file = fileIndex[PathUtils.normalize(path).lowercase()]
      ?: throw FileNotFoundException(path)
    return FileInputStream(file)
  }

  override fun playableUri(path: String): String {
    val file = fileIndex[PathUtils.normalize(path).lowercase()]
      ?: throw FileNotFoundException(path)
    return file.toURI().toString()
  }
}
