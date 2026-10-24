package nl.simon.daisyspeler.data.source

import java.io.InputStream

data class SourceFile(
  val path: String,
  val isDirectory: Boolean,
  val sizeBytes: Long = 0L,
)

/**
 * Abstraction over a DAISY book folder. The app uses SAF on Android, while parser tests use a
 * plain in-memory or file implementation.
 */
interface BookSource {
  fun files(): List<SourceFile>

  fun open(path: String): InputStream

  /** Returns a URI string that ExoPlayer can open. */
  fun playableUri(path: String): String
}
