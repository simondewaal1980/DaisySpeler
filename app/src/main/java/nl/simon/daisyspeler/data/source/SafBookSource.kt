package nl.simon.daisyspeler.data.source

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import nl.simon.daisyspeler.data.parser.PathUtils
import java.io.FileNotFoundException
import java.io.InputStream

class SafBookSource(
  private val context: Context,
  treeUri: Uri,
) : BookSource {
  private val root = DocumentFile.fromTreeUri(context, treeUri)
  private val fileIndex = LinkedHashMap<String, DocumentFile>()
  private val fileList = mutableListOf<SourceFile>()

  init {
    if (root == null || !root.isDirectory) {
      throw FileNotFoundException("De gekozen map is niet leesbaar.")
    }
    walk(root, "", 0)
  }

  private fun walk(directory: DocumentFile, prefix: String, depth: Int) {
    if (depth > 8 || fileIndex.size > 20_000) return
    val children = directory.listFiles()
    for (child in children) {
      val name = child.name ?: continue
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
    return context.contentResolver.openInputStream(file.uri)
      ?: throw FileNotFoundException(path)
  }

  override fun playableUri(path: String): String {
    val file = fileIndex[PathUtils.normalize(path).lowercase()]
      ?: throw FileNotFoundException(path)
    return file.uri.toString()
  }
}
