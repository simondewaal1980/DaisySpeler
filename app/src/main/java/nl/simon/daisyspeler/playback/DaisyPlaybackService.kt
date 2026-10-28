package nl.simon.daisyspeler.playback

import android.app.PendingIntent
import android.content.Context
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
import androidx.media3.common.PlaybackParameters
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

  private val storage = PlaybackStorage { description, error -> Log.e(TAG, description, error) }
  private lateinit var player: ExoPlayer
  private lateinit var libraryRepository: LibraryRepository
  private lateinit var bookmarkRepository: BookmarkRepository
  private lateinit var sleepTimerManager: SleepTimerManager
  private lateinit var bookRepository: nl.simon.daisyspeler.data.BookRepository
  private val playbackPrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

  private var mediaSession: MediaSession? = null
  private var loadedBook: DaisyBook? = null
  private var loadedBookId: String? = null
  private var loadingBookId: String? = null
  private var loadJob: Job? = null
  private var sleepFadeJob: Job? = null

  /** LOAD_BOOK and onPlaybackResumption both build and commit media under this mutex. */
  private val loadMutex = Mutex()
  private var loadGeneration = 0L
  private var internalRequestSequence = 0L
  private var activeLoadRequestId = 0L
  private var activeLoadBookId: String? = null
  private var pendingResumption: PendingResumption? = null

  /** Keeps a permanent focus loss visible to the controller instead of looking like a pause. */
  private var audioFocusLostPermanently = false

  private var publishedLoadState = PlaybackLoadState.IDLE
  private var publishedLoadBookId: String? = null
  private var publishedLoadError: String? = null
  private var lastLoggedStatus: String? = null

  /**
   * Set when the user asks to play while the book is still being loaded. The load finishes by
   * starting playback, so a tap on play is never silently ignored.
   */
  private var pendingPlayBookId: String? = null

  /**
   * Set when playback is resumed from a media controller (for example the notification after
   * process death). The session starts playback itself, so the normal play path does not run and
   * the start bookmark is created on the first isPlaying transition instead.
   */
  private var pendingResumeStart = false

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
          setPlaybackSpeed(savedPlaybackSpeed())
        }

    val session =
      MediaSession.Builder(this, player)
        .setCallback(SessionCallback())
        .build()
    mediaSession = session
    publishLoadState(PlaybackLoadState.IDLE)

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
    if (intent?.action == PlaybackCommands.STOP_FOR_BOOK_SWITCH) {
      stopForBookSwitch()
    }
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
    try {
      loadJob?.cancel()
      serviceScope.cancel()
      val bookId = loadedBookId.takeIf { loadingBookId == null }
      val position = currentPosition()
      // Capture on the player thread, but write after all older queued snapshots and bookmarks.
      val completed = runBlocking {
        withTimeoutOrNull(STORAGE_DRAIN_TIMEOUT_MS) {
          storage.finish("Kon laatste stoppositie niet opslaan") {
            if (bookId != null && position != null) {
              bookmarkRepository.recordStop(bookId, position, "STOP")
              libraryRepository.updateLastPosition(bookId, position)
            }
          }
          true
        }
      }
      if (completed != true) Log.w(TAG, "Opslagwachtrij kon niet op tijd worden afgehandeld")
    } finally {
      sleepTimerManager.flush()
      storage.cancel()
      mediaSession?.release()
      mediaSession = null
      player.release()
      super.onDestroy()
    }
  }

  private fun startSleepTimerLoop() {
    serviceScope.launch {
      while (isActive) {
        val timer = sleepTimerManager.state.value
        if (timer != null) {
          // While a new book is loading, the old book is no longer the target. Prefer the
          // loading book so a timer that was just set for it is not cancelled immediately.
          val bookId = loadingBookId ?: loadedBookId
          if (bookId != null && timer.bookId != bookId) {
            runCatching { sleepTimerManager.cancel() }
              .onFailure { Log.e(TAG, "Kon slaaptimer niet annuleren", it) }
          } else if (bookId == timer.bookId && sleepTimerManager.isExpired()) {
            fadeOutForSleepTimer()
          }
        }
        delay(1_000)
      }
    }
  }

  /**
   * Fades the volume out over a few seconds before pausing, so the audio does not stop
   * abruptly when the sleep timer expires.
   */
  private fun fadeOutForSleepTimer() {
    runCatching { sleepTimerManager.cancel() }
      .onFailure { Log.e(TAG, "Kon slaaptimer niet annuleren", it) }
    if (!player.isPlaying) return

    sleepFadeJob?.cancel()
    val job =
      serviceScope.launch {
        val steps = (SLEEP_FADE_DURATION_MS / SLEEP_FADE_STEP_MS).toInt()
        try {
          for (step in 1..steps) {
            if (!player.isPlaying) return@launch
            player.volume = 1f - step.toFloat() / steps
            delay(SLEEP_FADE_STEP_MS)
          }
          if (player.isPlaying) {
            recordStop("SLEEP_TIMER")
            player.pause()
          }
        } finally {
          // Always restore the volume: a cancelled fade must not leave playback muted, and
          // after a completed fade the next playback session starts at full volume.
          player.volume = 1f
          if (sleepFadeJob === coroutineContext[Job]) sleepFadeJob = null
        }
      }
    sleepFadeJob = job
  }

  private fun cancelSleepFade() {
    val job = sleepFadeJob
    sleepFadeJob = null
    job?.cancel()
    // Restore the volume immediately, also when the fade was interrupted by pressing play.
    player.volume = 1f
  }

  private fun startPositionSaver() {
    serviceScope.launch {
      while (isActive) {
        if (player.isPlaying) {
          saveLastPosition()
        }
        // A slower interval means fewer full library JSON writes. Pausing, seeking and
        // stopping still save the position immediately through the player listener.
        delay(POSITION_SAVE_INTERVAL_MS)
      }
    }
  }

  private fun loadBook(
    bookId: String,
    autoPlay: Boolean = false,
    requestId: Long = 0L,
    force: Boolean = false,
  ) {
    activeLoadRequestId = normalizeRequestId(requestId)
    activeLoadBookId = bookId

    // An explicit retry or a forced repair must start a fresh attempt instead of joining a load
    // that may be stuck. A resumption waiting for Media3 to commit is superseded as well.
    if (loadingBookId == bookId && (force || pendingResumption != null)) {
      loadGeneration++
      pendingResumption = null
      loadingBookId = null
    }
    // A load for this book is already running. Remember the play request and echo the newest
    // request id so an older UI attempt cannot overwrite this one.
    if (loadingBookId == bookId) {
      if (autoPlay) pendingPlayBookId = bookId
      publishLoadState(PlaybackLoadState.LOADING, bookId, reason = "join in-flight load")
      return
    }
    if (!force && loadedBookId == bookId && hasMediaFor(bookId)) {
      pendingResumption = null
      publishLoadState(PlaybackLoadState.READY, bookId, reason = "reuse committed media")
      if (autoPlay) startPlayback()
      return
    }
    if (pendingPlayBookId != bookId) pendingPlayBookId = null

    // Stop the current book before spending time on a different one. Its state is kept until
    // the new book has been parsed successfully, so a failed load does not break playback.
    if (loadedBookId != null && loadedBookId != bookId) {
      pausePlayback(reason = STOP_REASON_BOOK_SWITCH)
    }

    val generation = ++loadGeneration
    loadJob?.cancel()
    pendingResumption = null
    pendingResumeStart = false
    loadingBookId = bookId
    publishLoadState(PlaybackLoadState.LOADING, bookId, reason = "request")
    loadJob =
      serviceScope.launch {
        var mediaCommitted = false
        try {
          val prepared =
            loadMutex.withLock {
              if (generation != loadGeneration) {
                throw CancellationException("Boekload vervangen door een nieuwere aanvraag.")
              }
              val entry =
                libraryRepository.get(bookId)
                  ?: error("Boek niet gevonden in de bibliotheek.")
              val book = bookRepository.loadById(bookId)
              val source = bookRepository.sourceById(bookId)
              val items = buildMediaItems(book, source, bookId)
              if (items.isEmpty()) error("Geen audio gevonden.")
              PreparedBook(book, items, entry)
            }
          if (generation != loadGeneration) {
            throw CancellationException("Boekload vervangen door een nieuwere aanvraag.")
          }
          val timer = sleepTimerManager.state.value
          if (timer != null && timer.bookId != bookId) {
            runCatching { sleepTimerManager.cancel() }
              .onFailure { Log.e(TAG, "Kon slaaptimer niet annuleren", it) }
          }
          val playAfterLoad = autoPlay || pendingPlayBookId == bookId
          withContext(Dispatchers.Main.immediate) {
            if (generation != loadGeneration || activeLoadBookId != bookId) {
              return@withContext
            }
            player.playWhenReady = false
            player.setMediaItems(prepared.items)
            mediaCommitted = true
            player.prepare()
            loadedBook = prepared.book
            loadedBookId = bookId
            pendingResumption = null
            playbackPrefs.edit().putString(KEY_LAST_BOOK_ID, bookId).apply()
            prepared.entry.lastPosition?.let { position ->
              val target =
                prepared.book.segmentForSource(
                  position.sourcePath,
                  position.sourceOffsetMs,
                  position.globalMs,
                ) ?: prepared.book.segmentAtGlobal(position.globalMs)
              if (target != null) {
                player.seekTo(target.first.index, target.second)
              }
            }
            updateSessionIntent()
            publishLoadState(PlaybackLoadState.READY, bookId, reason = "media committed")
            if (playAfterLoad) {
              pendingPlayBookId = null
              startPlayback()
            }
          }
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          Log.e(TAG, "Kon boek $bookId niet laden", error)
          withContext(Dispatchers.Main.immediate) {
            if (generation != loadGeneration || activeLoadBookId != bookId) {
              return@withContext
            }
            // Keep the previous book when there was one and the player was not replaced.
            // If new media was committed, clear it so the player and book state stay in sync.
            if (mediaCommitted || loadedBookId == null || loadedBookId == bookId) {
              player.stop()
              player.clearMediaItems()
              loadedBook = null
              loadedBookId = null
            }
            if (pendingPlayBookId == bookId) pendingPlayBookId = null
            publishLoadState(
              PlaybackLoadState.ERROR,
              bookId,
              error.message ?: "Boek kon niet worden geladen.",
              reason = "load failed",
            )
          }
        } finally {
          if (generation == loadGeneration && loadingBookId == bookId) {
            loadingBookId = null
          }
        }
      }
  }

  private fun normalizeRequestId(requestId: Long): Long =
    if (requestId != 0L) requestId else -(++internalRequestSequence)

  private class PreparedBook(
    val book: DaisyBook,
    val items: List<MediaItem>,
    val entry: nl.simon.daisyspeler.data.storage.LibraryEntry,
  )

  private class PendingResumption(
    val book: DaisyBook,
    val bookId: String,
    val generation: Long,
    val requestId: Long,
    val startForPlayback: Boolean,
  )

  private class ResumptionMedia(
    val book: DaisyBook,
    val bookId: String,
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
  )

  /**
   * Stops and releases the current book when the user returns to the library. Loading a new book
   * from the shelf is only safe when the previous book no longer occupies the player, so the
   * listening session is closed with reason "boekwissel" and the media is cleared.
   */
  private fun stopForBookSwitch() {
    if (loadedBookId == null && loadingBookId == null) return

    val cachedBookIds = listOfNotNull(loadedBookId, loadingBookId).distinct()
    if (loadedBookId != null) {
      pausePlayback(reason = STOP_REASON_BOOK_SWITCH)
    }
    pendingPlayBookId = null
    pendingResumeStart = false
    loadGeneration++
    loadJob?.cancel()
    loadJob = null
    loadingBookId = null
    pendingResumption = null
    activeLoadBookId = null
    activeLoadRequestId = 0L
    audioFocusLostPermanently = false
    player.stop()
    player.clearMediaItems()
    loadedBook = null
    loadedBookId = null
    // Free parsed books and SAF file indexes when the player is no longer using them.
    cachedBookIds.forEach(bookRepository::forget)
    if (sleepTimerManager.state.value != null) {
      runCatching { sleepTimerManager.cancel() }
        .onFailure { Log.e(TAG, "Kon slaaptimer niet annuleren", it) }
    }
    publishLoadState(PlaybackLoadState.IDLE, reason = "book switch")
  }

  /**
   * Stops and clears a book that is about to be removed from the library. If another book is
   * still loading, that load is cancelled but a previously loaded book is left untouched.
   */
  private fun unloadBook(bookId: String) {
    val isLoaded = loadedBookId == bookId
    val isLoading = loadingBookId == bookId
    val isLastBook = playbackPrefs.getString(KEY_LAST_BOOK_ID, null) == bookId
    if (!isLoaded && !isLoading && !isLastBook) return

    if (isLoading) {
      loadGeneration++
      loadJob?.cancel()
      loadJob = null
      loadingBookId = null
      pendingResumption = null
    }
    if (isLoaded) {
      pausePlayback(reason = "REMOVE")
      player.stop()
      player.clearMediaItems()
      loadedBook = null
      loadedBookId = null
    }
    if (playbackPrefs.getString(KEY_LAST_BOOK_ID, null) == bookId) {
      playbackPrefs.edit().remove(KEY_LAST_BOOK_ID).apply()
    }
    if (pendingPlayBookId == bookId) pendingPlayBookId = null
    pendingResumeStart = false
    if (activeLoadBookId == bookId) {
      activeLoadBookId = null
      activeLoadRequestId = 0L
    }
    if (sleepTimerManager.state.value?.bookId == bookId) {
      runCatching { sleepTimerManager.cancel() }
        .onFailure { Log.e(TAG, "Kon slaaptimer niet annuleren", it) }
    }

    when {
      loadingBookId != null -> publishLoadState(PlaybackLoadState.LOADING, loadingBookId)
      loadedBookId != null -> publishLoadState(PlaybackLoadState.READY, loadedBookId)
      else -> publishLoadState(PlaybackLoadState.IDLE)
    }
  }

  private fun publishLoadState(
    state: PlaybackLoadState,
    bookId: String? = null,
    error: String? = null,
    reason: String? = null,
  ) {
    val previous = publishedLoadState
    publishedLoadState = state
    publishedLoadBookId = bookId
    publishedLoadError = error
    publishStatus(
      reason = reason ?: "load ${previous.name}->${state.name}"
    )
  }

  /**
   * The session extras are the single source of truth for the UI. Media availability is derived
   * from the actual player, so READY can never be published for a book that is not committed.
   */
  private fun publishStatus(reason: String? = null) {
    val mediaAvailable = publishedLoadBookId?.let(::hasMediaFor) == true
    val extras =
      Bundle().apply {
        putString(PlaybackCommands.KEY_LOAD_STATE, publishedLoadState.name)
        publishedLoadBookId?.let { putString(PlaybackCommands.KEY_LOAD_BOOK_ID, it) }
        publishedLoadError?.let { putString(PlaybackCommands.KEY_LOAD_ERROR, it) }
        putLong(PlaybackCommands.KEY_LOAD_REQUEST_ID, activeLoadRequestId)
        putBoolean(PlaybackCommands.KEY_MEDIA_AVAILABLE, mediaAvailable)
        putBoolean(PlaybackCommands.KEY_PLAY_WHEN_READY, player.playWhenReady)
        putInt(PlaybackCommands.KEY_SUPPRESSION_REASON, player.playbackSuppressionReason)
        putBoolean(PlaybackCommands.KEY_AUDIO_FOCUS_LOST, audioFocusLostPermanently)
        player.playerError?.message?.let { putString(PlaybackCommands.KEY_PLAYBACK_ERROR, it) }
      }
    val snapshot =
      "${publishedLoadState.name}|${publishedLoadBookId}|$activeLoadRequestId|" +
        "media=$mediaAvailable|pwr=${player.playWhenReady}|" +
        "suppression=${player.playbackSuppressionReason}|focusLost=$audioFocusLostPermanently"
    if (snapshot != lastLoggedStatus || reason != null) {
      lastLoggedStatus = snapshot
      Log.d(
        TAG,
        "state ${publishedLoadState.name} request=$activeLoadRequestId book=${publishedLoadBookId ?: "-"} " +
          "media=$mediaAvailable pwr=${player.playWhenReady} " +
          "suppression=${player.playbackSuppressionReason} focusLost=$audioFocusLostPermanently " +
          "reason=${reason ?: "-"}",
      )
    }
    runCatching { mediaSession?.setSessionExtras(extras) }
      .onFailure { Log.e(TAG, "Kon laadstatus niet publiceren", it) }
  }

  private fun hasMediaFor(bookId: String): Boolean =
    loadedBookId == bookId && playerHasMediaFor(bookId)

  private fun playerHasMediaFor(bookId: String): Boolean =
    player.mediaItemCount > 0 &&
      player.currentMediaItem?.mediaId?.startsWith("$bookId:") == true

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

  private fun savedPlaybackSpeed(): Float =
    PlaybackSpeedOptions.sanitize(
      playbackPrefs.getFloat(KEY_PLAYBACK_SPEED, PlaybackSpeedOptions.DEFAULT_SPEED)
    )

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
    // While a new book is being loaded, player callbacks may still refer to the old media.
    // Do not write that transient state over the previous book's saved position.
    if (loadingBookId != null) return
    val bookId = loadedBookId ?: return
    val position = currentPosition() ?: return
    persist("Kon laatste positie niet opslaan") {
      libraryRepository.updateLastPosition(bookId, position)
    }
  }

  private fun recordStart() {
    val bookId = loadedBookId ?: return
    val position = currentPosition() ?: return
    persist("Kon startbookmark niet opslaan") {
      bookmarkRepository.recordStart(bookId, position)
      libraryRepository.updateLastPosition(bookId, position)
    }
  }

  private fun recordStop(reason: String) {
    val bookId = loadedBookId ?: return
    val position = currentPosition() ?: return
    persist("Kon stopbookmark niet opslaan") {
      bookmarkRepository.recordStop(bookId, position, reason)
      libraryRepository.updateLastPosition(bookId, position)
    }
  }

  private fun persist(description: String, block: () -> Unit) = storage.enqueue(description, block)

  private fun togglePlayback() {
    // During buffering isPlaying is false while the user intent is still "play". During a
    // transient focus loss the suppression reason is the reliable signal. Only a real user
    // pause request clears playWhenReady.
    val suppressed =
      player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
    if (suppressed || audioFocusLostPermanently) {
      startPlayback()
    } else if (player.playWhenReady || player.isPlaying) {
      pausePlayback()
    } else {
      startPlayback()
    }
  }

  private fun startPlayback() {
    cancelSleepFade()

    // While another book is loading, remember the request so the new book starts playing as
    // soon as its media items are ready. Otherwise the play command would be lost.
    val loading = loadingBookId
    if (loading != null && loading != loadedBookId) {
      pendingPlayBookId = loading
      publishStatus(reason = "play pending load")
      return
    }

    if (player.mediaItemCount == 0) {
      val bookId =
        loadedBookId ?: loading ?: playbackPrefs.getString(KEY_LAST_BOOK_ID, null)
      if (bookId != null) {
        pendingPlayBookId = bookId
        loadBook(bookId)
      }
      return
    }

    // A finished book has to rewind first; play() alone does nothing while it is ENDED.
    if (player.playbackState == Player.STATE_ENDED) {
      player.seekTo(0, 0L)
    }

    val recovering = player.playerError != null || player.playbackState == Player.STATE_IDLE
    if (recovering) {
      Log.d(TAG, "Herstel afspelen: prepare() na fout of idle-toestand")
      player.prepare()
    }

    val suppressed =
      player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
    if (player.playWhenReady && (suppressed || audioFocusLostPermanently)) {
      // play() is a no-op when playWhenReady is already true and ExoPlayer holds a suppressed
      // audio focus request. Drop the intent first so play() asks for focus again.
      player.playWhenReady = false
    }
    audioFocusLostPermanently = false

    // An explicit user play opens exactly one bookmark session. The repository ignores a second
    // start while the same session is still open, so recovery cannot create duplicates.
    pendingResumeStart = false
    recordStart()
    player.play()
    publishStatus(reason = if (recovering) "recover play" else "user play")
  }

  private fun pausePlayback(reason: String = "PAUSE") {
    pendingPlayBookId = null
    pendingResumeStart = false
    audioFocusLostPermanently = false
    cancelSleepFade()
    recordStop(reason)
    player.pause()
    publishStatus(reason = "pause $reason")
  }

  private fun jumpHeading(forward: Boolean) {
    val book = loadedBook ?: return
    val current = currentPosition()?.globalMs ?: 0L
    val target = book.headingTarget(current, forward) ?: return
    player.seekTo(target.segmentIndex, target.offsetMs)
  }

  private val playerListener =
    object : Player.Listener {
      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
          audioFocusLostPermanently = false
          publishStatus(reason = "playWhenReady true")
          return
        }
        pendingResumeStart = false
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
          audioFocusLostPermanently = true
        }
        if (loadingBookId == null &&
          (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY ||
            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
        ) {
          cancelSleepFade()
          recordStop("PAUSE")
        }
        publishStatus(reason = "playWhenReady false reason=$reason")
      }

      override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
        if (playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS &&
          player.playWhenReady && loadingBookId == null
        ) {
          // A transient focus loss suppresses audio without changing playWhenReady. Buffering
          // does not trigger this callback, and regaining focus starts exactly one new session.
          cancelSleepFade()
          recordStop("PAUSE")
          pendingResumeStart = true
        }
        publishStatus(reason = "suppression=$playbackSuppressionReason")
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        // Bookmarks are normally created by explicit play/pause actions (button, media key or
        // sleep timer). A resumed session starts playback internally, so close that gap here.
        if (isPlaying) {
          if (pendingResumeStart) {
            pendingResumeStart = false
            recordStart()
          }
          publishStatus(reason = "isPlaying true")
          return
        }
        saveLastPosition()
        // Undo a (partially) faded out volume after the sleep timer.
        player.volume = 1f
        publishStatus(reason = "isPlaying false")
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && loadingBookId == null) {
          recordStop("END")
          saveLastPosition()
          runCatching { sleepTimerManager.cancel() }
            .onFailure { Log.e(TAG, "Kon slaaptimer niet annuleren", it) }
        }
        commitResumptionIfReady()
        publishStatus(reason = "playbackState=$playbackState")
      }

      override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
      ) {
        commitResumptionIfReady()
        publishStatus(reason = "mediaItemTransition=$reason")
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
        pendingResumeStart = false
        recordStop("ERROR")
        publishStatus(reason = "player error")
      }

      override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        playbackPrefs
          .edit()
          .putFloat(KEY_PLAYBACK_SPEED, playbackParameters.speed)
          .apply()
      }
    }

  /**
   * onPlaybackResumption only returns media to Media3; the session commits them afterwards.
   * READY is therefore delayed until the player actually contains this book's media.
   */
  private fun commitResumptionIfReady() {
    val pending = pendingResumption ?: return
    if (pending.generation != loadGeneration) {
      pendingResumption = null
      return
    }
    if (activeLoadBookId != pending.bookId || !playerHasMediaFor(pending.bookId)) return

    loadedBook = pending.book
    loadedBookId = pending.bookId
    loadingBookId = null
    pendingResumption = null
    pendingResumeStart = pending.startForPlayback
    playbackPrefs.edit().putString(KEY_LAST_BOOK_ID, pending.bookId).apply()
    updateSessionIntent()
    publishLoadState(PlaybackLoadState.READY, pending.bookId, reason = "resumption media committed")
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
          .add(SessionCommand(PlaybackCommands.UNLOAD_BOOK, Bundle.EMPTY))
          .add(SessionCommand(PlaybackCommands.START_PLAYBACK, Bundle.EMPTY))
          .add(SessionCommand(PlaybackCommands.PAUSE_PLAYBACK, Bundle.EMPTY))
          .add(SessionCommand(PlaybackCommands.SET_SLEEP_TIMER, Bundle.EMPTY))
          .add(SessionCommand(PlaybackCommands.CANCEL_SLEEP_TIMER, Bundle.EMPTY))
          .add(SessionCommand(PlaybackCommands.SET_PLAYBACK_SPEED, Bundle.EMPTY))
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
          KeyEvent.KEYCODE_MEDIA_PLAY -> startPlayback()
          KeyEvent.KEYCODE_MEDIA_PAUSE,
          KeyEvent.KEYCODE_MEDIA_STOP -> pausePlayback()
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
      if (playerCommand == Player.COMMAND_PLAY_PAUSE && loadingBookId == null) {
        cancelSleepFade()
        // The default play/pause command flips playWhenReady, not isPlaying. Buffering and a
        // suppressed focus request must therefore be treated as an active listening session.
        if (player.playWhenReady || player.isPlaying) {
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
      val generation = ++loadGeneration
      loadJob?.cancel()
      pendingResumption = null
      loadingBookId = null
      activeLoadRequestId = normalizeRequestId(0L)
      serviceScope.launch {
        var resumptionBookId: String? = null
        try {
          val prepared =
            loadMutex.withLock {
              if (generation != loadGeneration) {
                throw CancellationException("Hervatten vervangen door een nieuwere aanvraag.")
              }
              val bookId = playbackPrefs.getString(KEY_LAST_BOOK_ID, null)
                ?: error("Er is nog geen boek afgespeeld.")
              resumptionBookId = bookId
              activeLoadBookId = bookId
              loadingBookId = bookId
              publishLoadState(
                PlaybackLoadState.LOADING,
                bookId,
                reason = "playback resumption",
              )
              val entry = libraryRepository.get(bookId)
                ?: error("Het laatst afgespeelde boek is niet meer beschikbaar.")
              val book = bookRepository.loadById(bookId)
              val source = bookRepository.sourceById(bookId)
              val items = buildMediaItems(book, source, bookId)
              if (items.isEmpty()) error("Geen audio gevonden.")

              val start =
                entry.lastPosition?.let { position ->
                  book.segmentForSource(
                    position.sourcePath,
                    position.sourceOffsetMs,
                    position.globalMs,
                  ) ?: book.segmentAtGlobal(position.globalMs)
                } ?: (book.segments.first() to 0L)

              ResumptionMedia(
                book = book,
                bookId = bookId,
                items = items,
                startIndex = start.first.index,
                startPositionMs = start.second,
              )
            }
          if (generation != loadGeneration) {
            throw CancellationException("Hervatten vervangen door een nieuwere aanvraag.")
          }
          // Media3 commits these items after this future completes. READY follows only from the
          // player listener once the media are actually present.
          pendingResumption =
            PendingResumption(
              book = prepared.book,
              bookId = prepared.bookId,
              generation = generation,
              requestId = activeLoadRequestId,
              startForPlayback = isForPlayback,
            )
          future.set(
            MediaSession.MediaItemsWithStartPosition(
              prepared.items,
              prepared.startIndex,
              prepared.startPositionMs,
            )
          )
        } catch (cancelled: CancellationException) {
          if (generation == loadGeneration) {
            loadingBookId = null
            publishLoadState(
              PlaybackLoadState.ERROR,
              resumptionBookId,
              "Het hervatten is geannuleerd.",
              reason = "resumption cancelled",
            )
          }
          future.setException(cancelled)
        } catch (error: Throwable) {
          if (generation == loadGeneration) {
            loadingBookId = null
            pendingResumption = null
            publishLoadState(
              PlaybackLoadState.ERROR,
              resumptionBookId,
              error.message ?: "Het boek kon niet worden hervat.",
              reason = "resumption failed",
            )
          }
          future.setException(error)
        }
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
          args.getString(PlaybackCommands.KEY_BOOK_ID)?.let { bookId ->
            loadBook(
              bookId = bookId,
              requestId = args.getLong(PlaybackCommands.KEY_LOAD_REQUEST_ID, 0L),
              force = args.getBoolean(PlaybackCommands.KEY_LOAD_FORCE, false),
            )
          }
        }
        PlaybackCommands.UNLOAD_BOOK -> {
          args.getString(PlaybackCommands.KEY_BOOK_ID)?.let(::unloadBook)
        }
        PlaybackCommands.START_PLAYBACK -> startPlayback()
        PlaybackCommands.PAUSE_PLAYBACK -> pausePlayback()
        PlaybackCommands.SET_SLEEP_TIMER -> {
          val bookId = args.getString(PlaybackCommands.KEY_BOOK_ID)
          val duration = args.getLong(PlaybackCommands.KEY_DURATION_MS, 0L)
          if (bookId != null && duration > 0) {
            cancelSleepFade()
            runCatching { sleepTimerManager.start(bookId, duration) }
              .onFailure { Log.e(TAG, "Kon slaaptimer niet starten", it) }
          }
        }
        PlaybackCommands.CANCEL_SLEEP_TIMER -> {
          cancelSleepFade()
          runCatching { sleepTimerManager.cancel() }
            .onFailure { Log.e(TAG, "Kon slaaptimer niet annuleren", it) }
        }
        PlaybackCommands.SET_PLAYBACK_SPEED -> {
          val speed = args.getFloat(PlaybackCommands.KEY_SPEED, Float.NaN)
          if (!speed.isNaN()) {
            player.setPlaybackSpeed(PlaybackSpeedOptions.sanitize(speed))
          }
        }
      }
      return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }
  }

  companion object {
    private const val TAG = "DaisyPlayback"
    private const val PREFS_NAME = "daisy_playback"
    private const val KEY_LAST_BOOK_ID = "last_book_id"
    private const val KEY_PLAYBACK_SPEED = "playback_speed"
    private const val SLEEP_FADE_DURATION_MS = 3_000L
    private const val SLEEP_FADE_STEP_MS = 100L
    private const val STORAGE_DRAIN_TIMEOUT_MS = 2_000L
    private const val POSITION_SAVE_INTERVAL_MS = 30_000L
    private const val STOP_REASON_BOOK_SWITCH = "boekwissel"

    /**
     * Asks the service to stop the current book when the user leaves the player for the library.
     * An intent is used instead of a media controller so the stop also arrives when the player
     * screen is closed before its controller has processed a custom command.
     */
    fun requestStopForBookSwitch(context: Context) {
      val intent =
        Intent(context, DaisyPlaybackService::class.java)
          .setAction(PlaybackCommands.STOP_FOR_BOOK_SWITCH)
      runCatching { context.startService(intent) }
        .onFailure { Log.w(TAG, "Kon playback niet stoppen voor boekwissel", it) }
    }

    /**
     * Stops the playback service so it can close the listening session and release the player
     * before the app is closed. [onDestroy] drains the final bookmark after older storage writes.
     */
    fun requestShutdown(context: Context) {
      runCatching { context.stopService(Intent(context, DaisyPlaybackService::class.java)) }
        .onFailure { Log.w(TAG, "Kon de speler niet veilig afsluiten", it) }
    }
  }
}
