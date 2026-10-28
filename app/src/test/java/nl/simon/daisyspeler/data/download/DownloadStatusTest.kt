package nl.simon.daisyspeler.data.download

import android.app.DownloadManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatusTest {
  @Test
  fun `paused downloads remain pending for automatic import after resuming`() {
    assertTrue(isDownloadInProgress(DownloadManager.STATUS_PAUSED))
    assertTrue(isDownloadInProgress(DownloadManager.STATUS_RUNNING))
    assertFalse(isDownloadInProgress(DownloadManager.STATUS_SUCCESSFUL))
  }

  @Test
  fun `only unfinished downloads remain in progress`() {
    assertTrue(isDownloadInProgress(DownloadManager.STATUS_PENDING))
    assertTrue(isDownloadInProgress(DownloadManager.STATUS_RUNNING))
    assertFalse(isDownloadInProgress(DownloadManager.STATUS_SUCCESSFUL))
    assertFalse(isDownloadInProgress(DownloadManager.STATUS_FAILED))
  }
}
