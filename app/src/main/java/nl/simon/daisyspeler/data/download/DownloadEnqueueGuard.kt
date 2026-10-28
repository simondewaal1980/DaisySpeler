package nl.simon.daisyspeler.data.download

sealed interface DownloadEnqueueResult {
  data class Started(val id: Long) : DownloadEnqueueResult

  data object AlreadyDownloading : DownloadEnqueueResult

  data object Failed : DownloadEnqueueResult
}

internal data class DownloadIdentity(val url: String, val fileName: String)

/** Checks and starts under one lock, including when several browser callbacks arrive together. */
internal class DownloadEnqueueGuard(
  private val pendingDownloads: () -> List<DownloadIdentity>,
) {
  @Synchronized
  fun enqueue(request: DownloadIdentity, startDownload: () -> Long?): DownloadEnqueueResult {
    if (pendingDownloads().any { it.url == request.url || it.fileName == request.fileName }) {
      return DownloadEnqueueResult.AlreadyDownloading
    }
    val id = startDownload() ?: return DownloadEnqueueResult.Failed
    return DownloadEnqueueResult.Started(id)
  }
}
