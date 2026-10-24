package nl.simon.daisyspeler.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.simon.daisyspeler.DaisyApplication
import nl.simon.daisyspeler.MainActivity
import nl.simon.daisyspeler.R
import nl.simon.daisyspeler.data.book.BookPosition
import nl.simon.daisyspeler.data.book.DaisyBook
import nl.simon.daisyspeler.data.storage.BookmarkRepository
import nl.simon.daisyspeler.data.storage.LibraryRepository
import nl.simon.daisyspeler.data.storage.SleepTimerManager
import nl.simon.daisyspeler.data.source.BookSource

@OptIn(UnstableApi::class)
class DaisyPlaybackService : MediaSessionService() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private lateinit var player: ExoPlayer
  private lateinit var libraryRepository: LibraryRepository
  private lateinit var bookmarkRepository: BookmarkRepository
  private lateinit var sleepTimerManager: SleepTimerManager
  private lateinit var bookRepository: nl.simon.daisyspeler.data.BookRepository
  private val playbackPrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

  private var mediaSession: MediaSession? = null
  private var loadedBook: DaisyBook? = null
  private var loadedBookId: String? = null
  private var loadJob: Job? = null

  override fun onCreate() {
    super.onCreate()
    val container = (application as DaisyApplication).container
    libraryRepository = container.libraryRepository
    bookmarkRepository = container.bookmarkRepository
    sleepTimerManager = container.sleepTimerManager
    bookRepository = container.bookRepository

    player =
      ExoPlayer.Builder(this)
        .build()
        .apply {
          setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(C.USAGE_MEDIA)
              .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
              .build(),
            true,
          )
          setHandleAudioBecomingNoisy(true)
          setWakeMode(C.WAKE_MODE_LOCAL)
          addListener(playerListener)
        }

    val session =
      MediaSession.Builder(this, player)
        .setCallback(SessionCallback())
        .build()
    mediaSession = session

    val notificationProvider =
      DefaultMediaNotificationProvider.Builder(this)
        .setChannelName(R.string.notification_channel_name)
        .build()
        .apply { setSmallIcon(R.drawable.ic_notification) }
    setMediaNotificationProvider(notificationProvider)

    startSleepTimerLoop()
    startPositionSaver()
  }

  override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
      val lastBookId = playbackPrefs.getString(KEY_LAST_BOOK_ID, null)
      val keyEvent =
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
      val isPlayCommand =
        keyEvent == null ||
          keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
          keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
          keyEvent.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
      if (isPlayCommand && lastBookId != null &&
        (loadedBookId == null || player.mediaItemCount == 0)
      ) {
        loadBook(lastBookId, autoPlay = true)
      }
    }
    return super.onStartCommand(intent, flags, startId)
  }

  override fun onDestroy() {
    loadedBookId?.let { bookId ->
      currentPosition()?.let { position ->
        bookmarkRepository.recordStop(bookId, position, reason = "STOP")
        libraryRepository.updateLastPosition(bookId, position)
      }
    }
    loadJob?.cancel()
    serviceScope.cancel()
    mediaSession?.release()
    mediaSession = null
    player.release()
    super.onDestroy()
  }

  private fun startSleepTimerLoop() {
    serviceScope.launch {
      while (isActive) {
        val timer = sleepTimerManager.state.value
        if (timer != null) {
          if (timer.bookId != loadedBookId) {
            sleepTimerManager.cancel()
          } else if (sleepTimerManager.isExpired()) {
            if (player.isPlaying) {
              recordStop("SLEEP_TIMER")
              player.pause()
            }
            sleepTimerManager.cancel()
          }
        }
        delay(1_000)
      }
    }
  }

  private fun startPositionSaver() {
    serviceScope.launch {
      while (isActive) {
        if (player.isPlaying) {
          saveLastPosition()
        }
        delay(10_000)
      }
    }
  }

  private fun loadBook(bookId: String, autoPlay: Boolean = false) {
    if (loadedBookId == bookId && player.mediaItemCount > 0) return
    loadJob?.cancel()
    loadJob =
      serviceScope.launch {
        runCatching {
            val entry =
              libraryRepository.get(bookId)
                ?: error("Boek niet gevonden in de bibliotheek.")
            val book = bookRepository.loadById(bookId)
            val source = bookRepository.sourceById(bookId)
            val items = buildMediaItems(book, source, bookId)
            withContext(Dispatchers.Main.immediate) {
              if (items.isEmpty()) error("Geen audio gevonden.")
              val timer = sleepTimerManager.state.value
              if (timer != null && timer.bookId != bookId) sleepTimerManager.cancel()
              loadedBook = book
              loadedBookId = bookId
              playbackPrefs.edit().putString(KEY_LAST_BOOK_ID, bookId).apply()
              player.playWhenReady = false
              player.setMediaItems(items)
              player.prepare()
              entry.lastPosition?.let { position ->
                val target = book.segmentForSource(position.sourcePath, position.sourceOffsetMs)
                if (target != null) {
                  player.seekTo(target.first.index, target.second)
                }
              }
              updateSessionIntent()
              if (autoPlay) {
                recordStart()
                player.play()
              }
            }
          }
          .onFailure {
            Log.e(TAG, "Kon boek $bookId niet laden", it)
            withContext(Dispatchers.Main.immediate) {
              loadedBook = null
              loadedBookId = null
            }
          }
      }
  }

  private fun buildMediaItems(
    book: DaisyBook,
    source: BookSource,
    bookId: String,
  ): List<MediaItem> =
    book.segments.map { segment ->
      val chapter = book.headingAt(segment.trackStartMs)?.label ?: book.title
      MediaItem.Builder()
        .setUri(Uri.parse(source.playableUri(segment.sourcePath)))
        .setMediaId("$bookId:${segment.index}")
        .setClippingConfiguration(
          MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(segment.clipStartMs)
            .setEndPositionMs(segment.clipEndMs)
            .build()
        )
        .setMediaMetadata(
          MediaMetadata.Builder()
            .setTitle(chapter)
            .setArtist(book.title)
            .setAlbumTitle(book.title)
            .setAlbumArtist(book.author ?: "")
            .build()
        )
        .build()
    }

  private fun updateSessionIntent() {
    mediaSession?.setSessionActivity(
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    )
  }

  private fun currentPosition(): BookPosition? {
    val book = loadedBook ?: return null
    if (player.mediaItemCount == 0) return null
    val segment = book.segments.getOrNull(player.currentMediaItemIndex) ?: return null
    return book.positionAt(segment.index, player.currentPosition)
  }

  private fun saveLastPosition() {
    val bookId = loadedBookId ?: return
    val position = currentPosition() ?: return
    libraryRepository.updateLastPosition(bookId, position)
  }

  private fun recordStart() {
    val bookId = loadedBookId ?: return
    val position = currentPosition() ?: return
    bookmarkRepository.recordStart(bookId, position)
    libraryRepository.updateLastPosition(bookId, position)
  }

  private fun recordStop(reason: String) {
    val bookId = loadedBookId ?: return
    val position = currentPosition() ?: return
    bookmarkRepository.recordStop(bookId, position, reason)
    libraryRepository.updateLastPosition(bookId, position)
  }

  private fun togglePlayback() {
    if (player.isPlaying) {
      pausePlayback()
    } else {
      if (player.playbackState == Player.STATE_ENDED) {
        player.seekTo(0, 0L)
      }
      startPlayback()
    }
  }

  private fun startPlayback() {
    recordStart()
    player.play()
  }

  private fun pausePlayback(reason: String = "PAUSE") {
    recordStop(reason)
    player.pause()
  }

  private fun jumpHeading(forward: Boolean) {
    val book = loadedBook ?: return
    val current = currentPosition()?.globalMs ?: 0L
    val target = book.headingTarget(current, forward) ?: return
    player.seekTo(target.segmentIndex, target.offsetMs)
  }

  private val playerListener =
    object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        // Bookmarks are only created by explicit play/pause actions (button, media key or
        // sleep timer). Save the resume position here, without creating a bookmark.
        if (!isPlaying) saveLastPosition()
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
          recordStop("END")
          saveLastPosition()
          sleepTimerManager.cancel()
        }
      }

      override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
      ) {
        // Keep "verder luisteren" in sync when the user seeks while playback is paused.
        saveLastPosition()
      }

      override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "Playback error", error)
        recordStop("ERROR")
      }
    }

  private inner class SessionCallback : MediaSession.Callback {
    override fun onConnect(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
      val commands =
        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
          .buildUpon()
          .add(SessionCommand(PlaybackCommands.LOAD_BOOK, Bundle.EMPTY))
          .add(SessionCommand(PlaybackCommands.START_PLAYBACK, Bundle.EMPTY))
          .add(SessionCommand(PlaybackCommands.PAUSE_PLAYBACK, Bundle.EMPTY))
          .add(SessionCommand(PlaybackCommands.SET_SLEEP_TIMER, Bundle.EMPTY))
          .add(SessionCommand(PlaybackCommands.CANCEL_SLEEP_TIMER, Bundle.EMPTY))
          .build()
      return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
        .setAvailableSessionCommands(commands)
        .build()
    }

    override fun onMediaButtonEvent(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      intent: Intent,
    ): Boolean {
      val event =
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
          ?: return false
      val handled =
        when (event.keyCode) {
          KeyEvent.KEYCODE_MEDIA_NEXT,
          KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
          KeyEvent.KEYCODE_MEDIA_PREVIOUS,
          KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
          KeyEvent.KEYCODE_MEDIA_PLAY,
          KeyEvent.KEYCODE_MEDIA_PAUSE,
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
          KeyEvent.KEYCODE_MEDIA_STOP -> true
          else -> false
        }
      if (!handled) return false

      if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
        when (event.keyCode) {
          KeyEvent.KEYCODE_MEDIA_NEXT,
          KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> jumpHeading(forward = true)
          KeyEvent.KEYCODE_MEDIA_PREVIOUS,
          KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> jumpHeading(forward = false)
          KeyEvent.KEYCODE_MEDIA_PLAY -> if (!player.isPlaying) startPlayback()
          KeyEvent.KEYCODE_MEDIA_PAUSE,
          KeyEvent.KEYCODE_MEDIA_STOP -> if (player.isPlaying) pausePlayback()
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlayback()
        }
      }
      return true
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onPlayerCommandRequest(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      playerCommand: Int,
    ): Int {
      // Play/pause from external controllers (for example the media notification) should
      // create a bookmark too. Previous/next commands intentionally do not.
      if (playerCommand == Player.COMMAND_PLAY_PAUSE) {
        if (player.isPlaying) {
          recordStop("PAUSE")
        } else {
          recordStart()
        }
      }
      return SessionResult.RESULT_SUCCESS
    }

    override fun onPlaybackResumption(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
      val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
      serviceScope.launch {
        runCatching {
            val bookId = playbackPrefs.getString(KEY_LAST_BOOK_ID, null)
              ?: error("Er is nog geen boek afgespeeld.")
            val entry = libraryRepository.get(bookId)
              ?: error("Het laatst afgespeelde boek is niet meer beschikbaar.")
            val book = bookRepository.loadById(bookId)
            val source = bookRepository.sourceById(bookId)
            val items = buildMediaItems(book, source, bookId)
            if (items.isEmpty()) error("Geen audio gevonden.")

            val start =
              entry.lastPosition?.let { position ->
                book.segmentForSource(position.sourcePath, position.sourceOffsetMs)
                  ?: book.segmentAtGlobal(position.globalMs)
              } ?: (book.segments.first() to 0L)

            loadedBook = book
            loadedBookId = bookId
            playbackPrefs.edit().putString(KEY_LAST_BOOK_ID, bookId).apply()
            updateSessionIntent()
            future.set(
              MediaSession.MediaItemsWithStartPosition(items, start.first.index, start.second)
            )
          }
          .onFailure { future.setException(it) }
      }
      return future
    }

    override fun onCustomCommand(
      session: MediaSession,
      controller: MediaSession.ControllerInfo,
      customCommand: SessionCommand,
      args: Bundle,
    ): ListenableFuture<SessionResult> {
      when (customCommand.customAction) {
        PlaybackCommands.LOAD_BOOK -> {
          args.getString(PlaybackCommands.KEY_BOOK_ID)?.let(::loadBook)
        }
        PlaybackCommands.START_PLAYBACK -> startPlayback()
        PlaybackCommands.PAUSE_PLAYBACK -> pausePlayback()
        PlaybackCommands.SET_SLEEP_TIMER -> {
          val bookId = args.getString(PlaybackCommands.KEY_BOOK_ID)
          val duration = args.getLong(PlaybackCommands.KEY_DURATION_MS, 0L)
          if (bookId != null && duration > 0) {
            sleepTimerManager.start(bookId, duration)
          }
        }
        PlaybackCommands.CANCEL_SLEEP_TIMER -> sleepTimerManager.cancel()
      }
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
  }

  private companion object {
    const val TAG = "DaisyPlayback"
    const val PREFS_NAME = "daisy_playback"
    const val KEY_LAST_BOOK_ID = "last_book_id"
  }
}
