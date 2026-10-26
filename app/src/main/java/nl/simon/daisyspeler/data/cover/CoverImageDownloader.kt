package nl.simon.daisyspeler.data.cover

import android.util.Base64
import nl.simon.daisyspeler.data.parser.ImageFiles
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Downloads an image that the user picked in the cover browser. Handles normal http(s) links,
 * protocol-relative links and inline `data:` images. The payload is only accepted when it is a
 * bitmap that BitmapFactory understands.
 */
object CoverImageDownloader {
  private const val MAX_BYTES = 12 * 1024 * 1024
  private const val CONNECT_TIMEOUT_MS = 8_000
  private const val READ_TIMEOUT_MS = 15_000

  fun download(source: String, referer: String? = null): ByteArray? {
    val trimmed = source.trim()
    if (trimmed.isEmpty()) return null

    val bytes =
      when {
        trimmed.startsWith("data:", ignoreCase = true) -> decodeDataUri(trimmed)
        trimmed.startsWith("//") -> downloadHttp("https:$trimmed", referer)
        trimmed.startsWith("http://", ignoreCase = true) ||
          trimmed.startsWith("https://", ignoreCase = true) -> downloadHttp(trimmed, referer)
        else -> null
      } ?: return null

    if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
    return bytes.takeIf(ImageFiles::isBitmap)
  }

  private fun downloadHttp(url: String, referer: String?): ByteArray? {
    val connection =
      try {
        (URL(url).openConnection() as? HttpURLConnection)?.apply {
          connectTimeout = CONNECT_TIMEOUT_MS
          readTimeout = READ_TIMEOUT_MS
          instanceFollowRedirects = true
          setRequestProperty("User-Agent", USER_AGENT)
          referer?.takeIf { it.startsWith("http", ignoreCase = true) }?.let {
            setRequestProperty("Referer", it)
          }
        }
      } catch (_: Exception) {
        null
      } ?: return null

    return try {
      if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
      connection.inputStream.use { readLimited(it) }
    } catch (_: Exception) {
      null
    } finally {
      connection.disconnect()
    }
  }

  private fun decodeDataUri(uri: String): ByteArray? {
    val body = uri.substringAfter("data:", "")
    val comma = body.indexOf(',')
    if (comma < 0) return null
    val metadata = body.substring(0, comma)
    val payload = body.substring(comma + 1)
    if (!metadata.startsWith("image/", ignoreCase = true)) return null

    return runCatching {
      if (metadata.contains(";base64", ignoreCase = true)) {
        Base64.decode(payload, Base64.DEFAULT)
      } else {
        URLDecoder.decode(payload, Charsets.UTF_8.name()).toByteArray(Charsets.ISO_8859_1)
      }
    }.getOrNull()
  }

  private fun readLimited(input: InputStream): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      if (output.size() + read > MAX_BYTES) return null
      output.write(buffer, 0, read)
    }
    return output.toByteArray()
  }

  private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
      "Chrome/131.0.0.0 Mobile Safari/537.36"
}
