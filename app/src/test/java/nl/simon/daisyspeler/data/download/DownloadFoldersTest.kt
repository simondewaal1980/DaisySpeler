package nl.simon.daisyspeler.data.download

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadFoldersTest {
  @Test
  fun `defaults to audioboeken`() {
    assertEquals(DownloadFolders.DEFAULT_NAME, DownloadFolders.normalize(""))
    assertEquals(DownloadFolders.DEFAULT_NAME, DownloadFolders.normalize("   "))
    assertEquals(DownloadFolders.DEFAULT_NAME, DownloadFolders.normalize("."))
  }

  @Test
  fun `keeps a simple folder name`() {
    assertEquals("audioboeken", DownloadFolders.normalize("audioboeken"))
    assertEquals("Mijn boeken", DownloadFolders.normalize("  Mijn boeken  "))
  }

  @Test
  fun `reduces a path to the last folder`() {
    assertEquals("audioboeken", DownloadFolders.normalize("Download/audioboeken"))
    assertEquals("boeken", DownloadFolders.normalize("/storage/emulated/0/Download/boeken/"))
    assertEquals("boeken", DownloadFolders.normalize("Download\\boeken"))
  }

  @Test
  fun `replaces characters that are not allowed in a folder name`() {
    assertEquals("a_b_c_d_e_f_g_h", DownloadFolders.normalize("""a:b*c?d"e<f>g|h"""))
  }
}
