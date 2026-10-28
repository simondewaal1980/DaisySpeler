package nl.simon.daisyspeler.playback

import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import nl.simon.daisyspeler.DaisyApplication
import nl.simon.daisyspeler.MainActivity
import nl.simon.daisyspeler.data.storage.LibraryEntry
import nl.simon.daisyspeler.data.storage.LibrarySourceType
import nl.simon.daisyspeler.data.book.BookPosition
import nl.simon.daisyspeler.ui.player.PlayerViewModel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs with a separate applicationId during review; never uses real books or accounts. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class PlaybackRegressionTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val application = ApplicationProvider.getApplicationContext<DaisyApplication>()
  private val container get() = application.container
  private lateinit var activity: ActivityScenario<MainActivity>
  private lateinit var controller: MediaController
  private var audioFocus: AudioFocusRequest? = null

  @Before
  fun setUp() {
    if (Build.VERSION.SDK_INT >= 33) {
      instrumentation.uiAutomation.executeShellCommand(
        "pm grant ${application.packageName} android.permission.POST_NOTIFICATIONS"
      ).use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes() }
    }
    activity = ActivityScenario.launch(MainActivity::class.java)
    val future = onMain {
      MediaController.Builder(
        application,
        SessionToken(application, ComponentName(application, DaisyPlaybackService::class.java)),
      ).buildAsync()
    }
    controller = future.get(10, TimeUnit.SECONDS)
    writeBook()
    command(PlaybackCommands.LOAD_BOOK, Bundle().apply { putString(PlaybackCommands.KEY_BOOK_ID, BOOK_ID) })
    await { onMain { controller.mediaItemCount > 0 && controller.playbackState == Player.STATE_READY } }
  }

  @After
  fun tearDown() {
    audioFocus?.let { application.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
    if (::controller.isInitialized) {
      command(PlaybackCommands.CANCEL_SLEEP_TIMER)
      command(PlaybackCommands.UNLOAD_BOOK, Bundle().apply { putString(PlaybackCommands.KEY_BOOK_ID, BOOK_ID) })
      onMain { controller.release() }
    }
    if (::activity.isInitialized) activity.close()
    val entry = container.libraryRepository.get(BOOK_ID)
    container.bookmarkRepository.removeBook(BOOK_ID)
    container.libraryRepository.remove(BOOK_ID)
    container.bookRepository.forget(BOOK_ID)
    entry?.let(container.bookRepository::deleteLocalFiles)
  }

  @Test
  fun replacingTimerDuringFadeKeepsPlayingAtFullVolume() {
    startPlayback()
    setTimer(100L)
    await { onMain { controller.volume < 0.95f } }
    setTimer(900_000L)

    SystemClock.sleep(3_500L)
    assertTrue(onMain { controller.isPlaying })
    assertEquals(1f, onMain { controller.volume }, 0.001f)
    assertEquals(900_000L, container.sleepTimerManager.state.value?.durationMs)
    assertNull(container.bookmarkRepository.sessionsForBook(BOOK_ID).first().stop)
  }

  @Test
  fun cancellingTimerDuringFadeKeepsPlayingAtFullVolume() {
    startPlayback()
    setTimer(100L)
    await { onMain { controller.volume < 0.95f } }
    command(PlaybackCommands.CANCEL_SLEEP_TIMER)

    SystemClock.sleep(3_500L)
    assertTrue(onMain { controller.isPlaying })
    assertEquals(1f, onMain { controller.volume }, 0.001f)
    assertNull(container.sleepTimerManager.state.value)
    assertNull(container.bookmarkRepository.sessionsForBook(BOOK_ID).first().stop)
  }

  @Test
  fun expiredTimerPausesOnceWithSleepReasonAndRestoresVolume() {
    startPlayback()
    setTimer(100L)
    await { !onMain { controller.isPlaying } }
    await { container.bookmarkRepository.sessionsForBook(BOOK_ID).firstOrNull()?.stop != null }

    val sessions = container.bookmarkRepository.sessionsForBook(BOOK_ID)
    assertEquals(1, sessions.size)
    assertEquals("SLEEP_TIMER", sessions.single().stop?.reason)
    assertEquals(1f, onMain { controller.volume }, 0.001f)
    assertNull(container.sleepTimerManager.state.value)
  }

  @Test
  fun audioFocusLossClosesSessionAndExplicitResumeStartsANewOne() {
    startPlayback()
    requestAudioFocus(AudioManager.AUDIOFOCUS_GAIN)
    await { !onMain { controller.isPlaying } }
    await { container.bookmarkRepository.sessionsForBook(BOOK_ID).firstOrNull()?.stop != null }
    assertEquals("PAUSE", container.bookmarkRepository.sessionsForBook(BOOK_ID).first().stop?.reason)

    application.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(audioFocus!!)
    audioFocus = null
    startPlayback()
    await { container.bookmarkRepository.sessionsForBook(BOOK_ID).size == 2 }
  }

  @Test
  fun transientAudioFocusLossClosesAndReopensSessionWithoutAnExtraTap() {
    startPlayback()
    requestAudioFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    await { !onMain { controller.isPlaying } }
    await { container.bookmarkRepository.sessionsForBook(BOOK_ID).firstOrNull()?.stop != null }

    application.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(audioFocus!!)
    audioFocus = null
    await { onMain { controller.isPlaying } }
    await { container.bookmarkRepository.sessionsForBook(BOOK_ID).size == 2 }
    val sessions = container.bookmarkRepository.sessionsForBook(BOOK_ID)
    assertEquals(1, sessions.count { it.stop == null })
    assertEquals(1, sessions.count { it.stop?.reason == "PAUSE" })
  }

  @Test
  fun bufferingAfterSeekingDoesNotSplitTheListeningSession() {
    startPlayback()
    val sessionId = container.bookmarkRepository.sessionsForBook(BOOK_ID).single().id
    for (position in listOf(40_000L, 5_000L, 30_000L)) {
      onMain { controller.seekTo(position) }
      await { onMain { controller.isPlaying } }
    }
    val sessions = container.bookmarkRepository.sessionsForBook(BOOK_ID)
    assertEquals(1, sessions.size)
    assertEquals(sessionId, sessions.single().id)
    assertNull(sessions.single().stop)
  }

  @Test
  fun loadingStatusDoesNotCancelTheLoadWatchdog() {
    val model = onMain { PlayerViewModel(container, BOOK_ID) }
    try {
      val controllerField = PlayerViewModel::class.java.getDeclaredField("controller").apply { isAccessible = true }
      await { onMain { controllerField.get(model) != null } }
      command(PlaybackCommands.UNLOAD_BOOK, Bundle().apply { putString(PlaybackCommands.KEY_BOOK_ID, BOOK_ID) })
      await { onMain { controller.mediaItemCount == 0 } }
      onMain {
        PlayerViewModel::class.java.getDeclaredMethod("startLoadTimeout").apply { isAccessible = true }.invoke(model)
        PlayerViewModel::class.java.getDeclaredMethod("applySessionExtras", Bundle::class.java)
          .apply { isAccessible = true }
          .invoke(model, Bundle().apply {
            putString(PlaybackCommands.KEY_LOAD_BOOK_ID, BOOK_ID)
            putString(PlaybackCommands.KEY_LOAD_STATE, PlaybackLoadState.LOADING.name)
          })
      }
      await(22_000L) { model.playback.value.loadState == PlaybackLoadState.ERROR }
      assertFalse(model.playback.value.isLoading)
      assertNotNull(model.error.value)
    } finally {
      onMain {
        PlayerViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(model)
      }
    }
  }

  @Test
  fun restoringPositionInRepeatedAudioKeepsTheCorrectOccurrence() {
    command(PlaybackCommands.UNLOAD_BOOK, Bundle().apply { putString(PlaybackCommands.KEY_BOOK_ID, BOOK_ID) })
    File(application.filesDir, "books/$BOOK_ID/s.smil").writeText("""
      <smil><body><seq>
      <par id="p1"><audio src="audio.wav" clip-begin="0s" clip-end="30s"/></par>
      <par id="p2"><audio src="audio.wav" clip-begin="0s" clip-end="30s"/></par>
      </seq></body></smil>
    """.trimIndent())
    container.bookRepository.forget(BOOK_ID)
    container.libraryRepository.updateLastPosition(BOOK_ID, BookPosition("audio.wav", 10_000L, 40_000L))

    command(PlaybackCommands.LOAD_BOOK, Bundle().apply { putString(PlaybackCommands.KEY_BOOK_ID, BOOK_ID) })
    await { onMain { controller.mediaItemCount == 2 && controller.playbackState == Player.STATE_READY } }

    assertEquals(1, onMain { controller.currentMediaItemIndex })
    assertEquals(10_000L, onMain { controller.currentPosition })
  }

  @Test
  fun failedCoverUpdateKeepsThePreviousImageAndAllowsRetry() {
    val bytes = ByteArrayOutputStream().use { output ->
      val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
      try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
      finally { bitmap.recycle() }
    }
    val oldPath = runBlocking { container.bookRepository.setCover(BOOK_ID, bytes) }!!
    val blocker = File(application.filesDir, "library.json.tmp")
    check(blocker.mkdir())
    try {
      assertNull(runBlocking { container.bookRepository.setCover(BOOK_ID, bytes) })
      assertEquals(oldPath, container.libraryRepository.get(BOOK_ID)?.coverPath)
      assertTrue("The old cover must survive an unsuccessful replacement", File(oldPath).isFile)
    } finally {
      blocker.delete()
    }
    val newPath = runBlocking { container.bookRepository.setCover(BOOK_ID, bytes) }!!
    assertTrue(File(newPath).isFile)
    assertFalse(File(oldPath).exists())
  }

  @Test
  fun zipReimportPreservesBookmarksAndLastPosition() {
    val root = File(application.filesDir, "books/$BOOK_ID")
    val archive = File(application.cacheDir, "regression-import.zip")
    var imported: nl.simon.daisyspeler.data.LoadedBook? = null
    try {
      ZipOutputStream(archive.outputStream()).use { zip ->
        for (name in listOf("ncc.html", "s.smil", "audio.wav")) {
          zip.putNextEntry(ZipEntry(name))
          root.resolve(name).inputStream().use { it.copyTo(zip) }
          zip.closeEntry()
        }
      }
      val first = runBlocking { container.bookImporter.importZip(Uri.fromFile(archive)) }
      imported = first
      val position = first.book.positionAt(0, 12_000L)!!
      container.libraryRepository.updateLastPosition(first.id, position)
      container.bookmarkRepository.addManualBookmark(first.id, position, "Bewaren")
      val second = runBlocking { container.bookImporter.importZip(Uri.fromFile(archive)) }

      assertEquals(first.id, second.id)
      assertEquals(60_000L, second.book.totalDurationMs)
      assertEquals(position, container.libraryRepository.get(first.id)?.lastPosition)
      assertEquals("Bewaren", container.bookmarkRepository.manualBookmarksForBook(first.id).single().note)
    } finally {
      imported?.let {
        container.libraryRepository.get(it.id)?.let(container.bookRepository::deleteLocalFiles)
        container.libraryRepository.remove(it.id)
        container.bookmarkRepository.removeBook(it.id)
        container.bookRepository.forget(it.id)
      }
      archive.delete()
    }
  }

  @Test
  fun unloadingAndReloadingPreservesPositionAndBackgroundPlayback() {
    onMain { controller.seekTo(32_000L) }
    await { container.libraryRepository.get(BOOK_ID)?.lastPosition?.globalMs == 32_000L }
    command(PlaybackCommands.UNLOAD_BOOK, Bundle().apply { putString(PlaybackCommands.KEY_BOOK_ID, BOOK_ID) })
    await { onMain { controller.mediaItemCount == 0 } }
    command(PlaybackCommands.LOAD_BOOK, Bundle().apply { putString(PlaybackCommands.KEY_BOOK_ID, BOOK_ID) })
    await { onMain { controller.playbackState == Player.STATE_READY && controller.currentPosition == 32_000L } }
    startPlayback()
    activity.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)

    SystemClock.sleep(250L)
    assertTrue(onMain { controller.isPlaying })
    onMain { controller.pause() } // Same standard command used by notification controllers.
    await { container.bookmarkRepository.sessionsForBook(BOOK_ID).firstOrNull()?.stop != null }
    assertEquals("PAUSE", container.bookmarkRepository.sessionsForBook(BOOK_ID).single().stop?.reason)
  }

  @Test
  fun seekSpeedManualBookmarkAndPauseKeepTheirBehaviour() {
    onMain { controller.seekTo(10_000L) }
    command(PlaybackCommands.SET_PLAYBACK_SPEED, Bundle().apply { putFloat(PlaybackCommands.KEY_SPEED, 1.3f) })
    await { onMain { controller.playbackParameters.speed == 1.3f } }
    startPlayback()
    command(PlaybackCommands.PAUSE_PLAYBACK)
    await { !onMain { controller.isPlaying } }
    await { container.bookmarkRepository.sessionsForBook(BOOK_ID).firstOrNull()?.stop != null }
    assertEquals("PAUSE", container.bookmarkRepository.sessionsForBook(BOOK_ID).single().stop?.reason)
    assertTrue(container.libraryRepository.get(BOOK_ID)!!.lastPosition!!.globalMs >= 10_000L)
    val book = runBlocking { container.bookRepository.loadById(BOOK_ID) }
    container.bookmarkRepository.addManualBookmark(BOOK_ID, book.positionAt(0, 12_000L)!!, "Notitie")
    assertEquals("Notitie", container.bookmarkRepository.manualBookmarksForBook(BOOK_ID).single().note)
  }

  private fun requestAudioFocus(gain: Int) {
    val request = AudioFocusRequest.Builder(gain)
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
      .setOnAudioFocusChangeListener { }
      .build()
    audioFocus = request
    assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
      application.getSystemService(AudioManager::class.java).requestAudioFocus(request))
  }

  private fun setTimer(durationMs: Long) = command(PlaybackCommands.SET_SLEEP_TIMER, Bundle().apply {
    putString(PlaybackCommands.KEY_BOOK_ID, BOOK_ID)
    putLong(PlaybackCommands.KEY_DURATION_MS, durationMs)
  })

  private fun startPlayback() {
    command(PlaybackCommands.START_PLAYBACK)
    await { onMain { controller.isPlaying } }
    await { container.bookmarkRepository.sessionsForBook(BOOK_ID).any { it.stop == null } }
  }

  private fun command(action: String, args: Bundle = Bundle.EMPTY) {
    val future = onMain { controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
    assertEquals(SessionResult.RESULT_SUCCESS, future.get(10, TimeUnit.SECONDS).resultCode)
  }

  private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    instrumentation.runOnMainSync { result = runCatching(block) }
    return result!!.getOrThrow()
  }

  private fun await(timeoutMs: Long = 8_000L, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (!condition()) {
      if (SystemClock.elapsedRealtime() >= deadline) fail("Condition not met within ${timeoutMs}ms")
      SystemClock.sleep(25L)
    }
  }

  private fun writeBook() {
    container.bookmarkRepository.removeBook(BOOK_ID)
    val root = File(application.filesDir, "books/$BOOK_ID").apply { mkdirs() }
    root.resolve("ncc.html").writeText("""
      <html><head><title>Regressietest</title></head><body>
      <h1><a href="s.smil#p1">Een</a></h1><h1><a href="s.smil#p2">Twee</a></h1>
      </body></html>
    """.trimIndent())
    root.resolve("s.smil").writeText("""
      <smil><body><seq>
      <par id="p1"><audio src="audio.wav" clip-begin="0s" clip-end="30s"/></par>
      <par id="p2"><audio src="audio.wav" clip-begin="30s" clip-end="60s"/></par>
      </seq></body></smil>
    """.trimIndent())
    val dataSize = 8_000 * 2 * 60
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
      .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000).putShort(2).putShort(16)
      .put("data".toByteArray()).putInt(dataSize).array()
    root.resolve("audio.wav").outputStream().use { it.write(header); it.write(ByteArray(dataSize)) }
    container.bookRepository.forget(BOOK_ID)
    container.libraryRepository.addOrUpdate(LibraryEntry(
      id = BOOK_ID, treeUri = "file://regression", title = "Regressietest", author = null,
      durationMs = 60_000L, tocCount = 2, addedAtEpochMs = 1L,
      sourceType = LibrarySourceType.ZIP, localDir = root.absolutePath,
    ))
  }

  private companion object {
    const val BOOK_ID = "playback-regression"
  }
}
