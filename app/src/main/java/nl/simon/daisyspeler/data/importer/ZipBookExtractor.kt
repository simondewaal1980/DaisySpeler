package nl.simon.daisyspeler.data.importer

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

class ZipImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Extracts a DAISY ZIP archive into an app-private directory.
 *
 * Entry names are treated as untrusted input: absolute paths, parent traversal and hidden
 * metadata entries are rejected or skipped. Extraction is bounded to avoid filling the device
 * with an unexpectedly large archive.
 */
class ZipBookExtractor(private val targetDir: File) {
  fun extract(inputStream: InputStream) {
    if (!targetDir.isDirectory && !targetDir.mkdirs()) {
      throw ZipImportException("De uitgepakte boekmap kon niet worden aangemaakt.")
    }
    val canonicalRoot = targetDir.canonicalFile
    val availableBytes = targetDir.usableSpace
    var totalWritten = 0L
    var fileCount = 0
    var extractedFiles = 0

    try {
      ZipInputStream(BufferedInputStream(inputStream)).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          if (entry.isDirectory || entry.name.endsWith("/")) continue

          val relativePath = safeRelativePath(entry.name) ?: continue
          fileCount++
          if (fileCount > MAX_FILES) {
            throw ZipImportException("Het ZIP-bestand bevat te veel bestanden.")
          }

          val output = File(canonicalRoot, relativePath)
          val canonicalOutput = output.canonicalFile
          if (!canonicalOutput.path.startsWith(canonicalRoot.path + File.separator)) {
            throw ZipImportException("Het ZIP-bestand bevat een onveilig bestandspad.")
          }

          if (availableBytes > 0 && entry.size > 0 &&
            totalWritten + entry.size > availableBytes
          ) {
            throw ZipImportException("Onvoldoende vrije opslagruimte voor het ZIP-bestand.")
          }

          val parent = canonicalOutput.parentFile
          if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw ZipImportException("De map voor ${entry.name} kon niet worden aangemaakt.")
          }

          FileOutputStream(canonicalOutput).use { outputStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
              val read = zip.read(buffer)
              if (read < 0) break
              totalWritten += read
              if (totalWritten > MAX_TOTAL_BYTES) {
                throw ZipImportException("Het ZIP-bestand is te groot om uit te pakken.")
              }
              if (availableBytes > 0 && totalWritten > availableBytes) {
                throw ZipImportException("Onvoldoende vrije opslagruimte voor het ZIP-bestand.")
              }
              outputStream.write(buffer, 0, read)
            }
          }
          extractedFiles++
        }
      }
    } catch (error: ZipImportException) {
      throw error
    } catch (error: ZipException) {
      throw ZipImportException("Dit is geen geldig ZIP-bestand.", error)
    } catch (error: IOException) {
      throw ZipImportException(
        "Het ZIP-bestand kon niet worden uitgepakt: ${error.message ?: "onbekende fout"}",
        error,
      )
    }

    if (extractedFiles == 0) {
      throw ZipImportException("Het ZIP-bestand bevat geen leesbare bestanden.")
    }
  }

  private fun safeRelativePath(rawName: String): String? {
    var name = rawName.replace('\\', '/').trim()
    while (name.startsWith("./")) name = name.removePrefix("./")
    if (name.isBlank() || name.endsWith("/")) return null
    if (name.startsWith("/")) {
      throw ZipImportException("Het ZIP-bestand bevat een onveilig bestandspad.")
    }

    val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
    if (parts.isEmpty()) return null
    if (parts.any { it == ".." }) {
      throw ZipImportException("Het ZIP-bestand bevat een onveilig bestandspad.")
    }
    if (parts.first().equals("__MACOSX", ignoreCase = true)) return null
    if (parts.any { it.startsWith(".") }) return null
    return parts.joinToString("/")
  }

  private companion object {
    const val BUFFER_SIZE = 128 * 1024
    const val MAX_FILES = 50_000
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
  }
}
