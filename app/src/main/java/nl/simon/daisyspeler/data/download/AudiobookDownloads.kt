package nl.simon.daisyspeler.data.download

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.simon.daisyspeler.data.BookImporter
import nl.simon.daisyspeler.data.storage.UiPreferences
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** A download request coming from the in-app browser. */
data class BrowserDownload(
  val url: String,
  val userAgent: String?,
  val contentDisposition: String?,
  val mimeType: String?,
  val referer: String?,
)

sealed interface AudiobookImportResult {
  data class Imported(val title: String) : AudiobookImportResult

  data class NotAnArchive(val fileName: String) : AudiobookImportResult

  data class Failed(val fileName: String, val message: String?) : AudiobookImportResult
}

/**
 * Downloads books from the in-app browser into the shared "Download/audioboeken" folder. The
 * folder name can be changed in the app and defaults to [DownloadFolders.DEFAULT_NAME].
 * DownloadManager keeps the transfer running when the app goes to the background. Completed ZIP
 * files are imported into the library on the next check, so leaving the browser does not lose a
 * finished download.
 */
class AudiobookDownloads(
  private val context: Context,
  private val uiPreferences: UiPreferences,
) {
  private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  private val importMutex = Mutex()

  /** Naam van de downloadmap, bijvoorbeeld 'audioboeken'. */
  val folderName: String
    get() = DownloadFolders.normalize(uiPreferences.downloadFolder.value)

  /** Leesbare locatie voor de interface, bijvoorbeeld 'Download/audioboeken'. */
  val folderLabel: String
    get() = "${Environment.DIRECTORY_DOWNLOADS}/$folderName"

  /** Creates the download folder as soon as possible, without requiring broad storage access. */
  fun ensureFolder(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ensureModernFolder()
    } else {
      ensureLegacyFolder()
    }
  }

  fun canDownloadToFolder(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
      ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
      ) == PackageManager.PERMISSION_GRANTED
  }

  fun enqueue(request: BrowserDownload): Long? {
    if (!request.url.startsWith("http://", ignoreCase = true) &&
      !request.url.startsWith("https://", ignoreCase = true)
    ) {
      return null
    }
    ensureFolder()
    if (!canDownloadToFolder()) return null

    val guessed = URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)
    var fileName = DownloadFileNames.sanitize(guessed)
    if (!fileName.contains('.') && request.mimeType?.contains("zip", ignoreCase = true) == true) {
      fileName += ".zip"
    }
    fileName = uniqueFileName(fileName)

    val downloadRequest =
      DownloadManager.Request(Uri.parse(request.url)).apply {
        setTitle(fileName)
        setDescription("Download naar $folderLabel")
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setDestinationInExternalPublicDir(
          Environment.DIRECTORY_DOWNLOADS,
          "$folderName/$fileName",
        )
        setAllowedOverMetered(true)
        setAllowedOverRoaming(true)
        request.mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
        request.userAgent?.takeIf { it.isNotBlank() }?.let {
          addRequestHeader("User-Agent", it)
        }
        request.referer?.takeIf { it.isNotBlank() }?.let {
          addRequestHeader("Referer", it)
        }
        CookieManager.getInstance().getCookie(request.url)?.takeIf { it.isNotBlank() }?.let {
          addRequestHeader("Cookie", it)
        }
      }

    val manager = context.getSystemService(DownloadManager::class.java)
    return runCatching {
        val id = manager.enqueue(downloadRequest)
        addPendingDownload(id)
        CookieManager.getInstance().flush()
        id
      }
      .getOrNull()
  }

  /**
   * Imports every finished audiobook download. Downloads that are still running stay pending.
   * Returns one result per finished download so the browser can show useful feedback.
   */
  suspend fun importCompleted(importer: BookImporter): List<AudiobookImportResult> =
    importMutex.withLock {
      withContext(Dispatchers.IO) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val results = mutableListOf<AudiobookImportResult>()
        for (rawId in pendingDownloads()) {
          val id = rawId.toLongOrNull()
          if (id == null) {
            removePendingDownload(rawId)
            continue
          }

          val info = downloadInfo(manager, id)
          if (info == null) {
            removePendingDownload(rawId)
            continue
          }
          if (info.status == DownloadManager.STATUS_RUNNING ||
            info.status == DownloadManager.STATUS_PENDING
          ) {
            continue
          }

          if (info.status != DownloadManager.STATUS_SUCCESSFUL) {
            // A failed DownloadManager transfer is terminal; retrying it would fail again.
            removePendingDownload(rawId)
            results += AudiobookImportResult.Failed(info.fileName, info.failureReason)
            continue
          }

          val uri =
            runCatching { manager.getUriForDownloadedFile(id) }.getOrNull()
              ?: info.localUri?.let(Uri::parse)
          if (uri == null) {
            removePendingDownload(rawId)
            results += AudiobookImportResult.Failed(info.fileName, "Het bestand is niet leesbaar.")
            continue
          }

          if (!isZipArchive(uri)) {
            removePendingDownload(rawId)
            results += AudiobookImportResult.NotAnArchive(info.fileName)
            continue
          }

          val loaded =
            try {
              importer.importZip(uri)
            } catch (cancelled: CancellationException) {
              throw cancelled
            } catch (error: Throwable) {
              // Keep a transiently failing import pending so the user can retry it, but stop
              // retrying after a few attempts to avoid an endless error loop.
              if (recordImportAttempt(rawId) >= MAX_IMPORT_ATTEMPTS) {
                removePendingDownload(rawId)
              }
              results += AudiobookImportResult.Failed(info.fileName, error.message)
              continue
            }
          removePendingDownload(rawId)
          results += AudiobookImportResult.Imported(loaded.book.title.ifBlank { info.fileName })
        }
        results
      }
    }

  private fun ensureLegacyFolder(): Boolean {
    if (!canDownloadToFolder()) return false
    val folder =
      File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folderName)
    return folder.isDirectory || folder.mkdirs()
  }

  @RequiresApi(Build.VERSION_CODES.Q)
  private fun ensureModernFolder(): Boolean {
    val folder =
      File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folderName)
    if (folder.isDirectory) return true

    // MediaStore does not create an empty directory row on its own. Inserting a temporary
    // pending file makes MediaProvider create the parent folder; the marker is removed again.
    val resolver = context.contentResolver
    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val values =
      ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, FOLDER_MARKER_NAME)
        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(
          MediaStore.MediaColumns.RELATIVE_PATH,
          "${Environment.DIRECTORY_DOWNLOADS}/$folderName/",
        )
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    val marker = runCatching { resolver.insert(collection, values) }.getOrNull()
    if (marker != null) {
      runCatching { resolver.delete(marker, null, null) }
      return true
    }
    // Some scoped-storage implementations reject the insert; the download itself can still
    // create the folder, so fall back to a direct check.
    return folder.isDirectory
  }

  private fun uniqueFileName(fileName: String): String {
    for (counter in 1..MAX_NAME_ATTEMPTS) {
      val candidate = DownloadFileNames.withCounter(fileName, counter)
      if (!downloadNameExists(candidate)) return candidate
    }
    val dot = fileName.lastIndexOf('.')
    val stamp = System.currentTimeMillis()
    return if (dot > 0) {
      fileName.substring(0, dot) + "-$stamp" + fileName.substring(dot)
    } else {
      "$fileName-$stamp"
    }
  }

  private fun downloadNameExists(fileName: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      val folder =
        File(
          Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
          folderName,
        )
      return File(folder, fileName).exists()
    }

    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    val selection =
      "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND " +
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
    return runCatching {
        context.contentResolver
          .query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            selection,
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$folderName/", fileName),
            null,
          )
          ?.use { it.moveToFirst() } == true
      }
      .getOrDefault(false)
  }

  private fun downloadInfo(
    manager: DownloadManager,
    id: Long,
  ): DownloadInfo? {
    val query = DownloadManager.Query().setFilterById(id)
    return runCatching {
        manager.query(query).use { cursor ->
          if (!cursor.moveToFirst()) return@use null
          val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
          val localUri =
            cursor
              .getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
              ?.takeIf { it.isNotBlank() }
          val fileName =
            cursor
              .getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE))
              ?.takeIf { it.isNotBlank() }
              ?: "audioboek"
          val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
          val reason =
            if (reasonIndex >= 0) {
              cursor.getString(reasonIndex)?.takeIf { it.isNotBlank() }
            } else {
              null
            }
          DownloadInfo(status, localUri, fileName, reason)
        }
      }
      .getOrNull()
  }

  private fun isZipArchive(uri: Uri): Boolean {
    val input = openStream(uri) ?: return false
    return input.use { stream ->
      val header = ByteArray(2)
      stream.read(header) == header.size && header[0] == 'P'.code.toByte() &&
        header[1] == 'K'.code.toByte()
    }
  }

  private fun openStream(uri: Uri): InputStream? {
    return runCatching {
        if (uri.scheme == "file") {
          FileInputStream(File(uri.path ?: return null))
        } else {
          context.contentResolver.openInputStream(uri)
        }
      }
      .getOrNull()
  }

  @Synchronized
  private fun addPendingDownload(id: Long) {
    val ids = pendingDownloads().toMutableSet()
    ids += id.toString()
    preferences.edit().putStringSet(KEY_PENDING_DOWNLOADS, ids).apply()
    clearImportAttempts(id.toString())
  }

  @Synchronized
  private fun removePendingDownload(id: String) {
    val ids = pendingDownloads().toMutableSet()
    if (ids.remove(id)) {
      preferences.edit().putStringSet(KEY_PENDING_DOWNLOADS, ids).apply()
    }
    clearImportAttempts(id)
  }

  private fun pendingDownloads(): Set<String> =
    preferences.getStringSet(KEY_PENDING_DOWNLOADS, emptySet()).orEmpty().toSet()

  @Synchronized
  private fun recordImportAttempt(id: String): Int {
    val attempts = importAttempts()
    val next = (attempts[id] ?: 0) + 1
    attempts[id] = next
    saveImportAttempts(attempts)
    return next
  }

  private fun importAttempts(): MutableMap<String, Int> =
    preferences
      .getStringSet(KEY_IMPORT_ATTEMPTS, emptySet())
      .orEmpty()
      .mapNotNull { entry ->
        val id = entry.substringBefore(':', "")
        val count = entry.substringAfter(':', "").toIntOrNull()
        if (id.isBlank() || count == null) null else id to count
      }
      .toMap()
      .toMutableMap()

  @Synchronized
  private fun clearImportAttempts(id: String) {
    val attempts = importAttempts()
    if (attempts.remove(id) != null) saveImportAttempts(attempts)
  }

  private fun saveImportAttempts(attempts: Map<String, Int>) {
    preferences
      .edit()
      .putStringSet(KEY_IMPORT_ATTEMPTS, attempts.map { (id, count) -> "$id:$count" }.toSet())
      .apply()
  }

  private data class DownloadInfo(
    val status: Int,
    val localUri: String?,
    val fileName: String,
    val failureReason: String?,
  )

  companion object {
    private const val PREFS_NAME = "daisy_downloads"
    private const val KEY_PENDING_DOWNLOADS = "pending_downloads"
    private const val KEY_IMPORT_ATTEMPTS = "import_attempts"
    private const val MAX_IMPORT_ATTEMPTS = 3
    private const val MAX_NAME_ATTEMPTS = 99
    private const val FOLDER_MARKER_NAME = "daisyspeler-map.txt"
  }
}
