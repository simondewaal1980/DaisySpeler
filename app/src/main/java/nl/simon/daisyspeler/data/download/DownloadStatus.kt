package nl.simon.daisyspeler.data.download

import android.app.DownloadManager

internal fun isDownloadInProgress(status: Int): Boolean =
  status == DownloadManager.STATUS_RUNNING ||
    status == DownloadManager.STATUS_PENDING ||
    status == DownloadManager.STATUS_PAUSED
