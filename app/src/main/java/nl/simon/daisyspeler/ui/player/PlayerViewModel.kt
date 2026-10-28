package nl.simon.daisyspeler.ui.player

import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.book.DaisyBook
import nl.simon.daisyspeler.data.book.NavPoint
import nl.simon.daisyspeler.data.storage.BookmarkPoint
import nl.simon.daisyspeler.data.storage.BookmarkSession
import nl.simon.daisyspeler.data.storage.ManualBookmark
import nl.simon.daisyspeler.playback.DaisyPlaybackService
import nl.simon.daisyspeler.playback.PlaybackCommands
import nl.simon.daisyspeler.playback.PlaybackLoadState
import nl.simon.daisyspeler.playback.PlaybackSpeedOptions
import java.io.File
import java.util.concurrent.TimeUnit

data class PlaybackUiState(
  val isPlaying: Boolean = false,
  val playWhenReady: Boolean = false,
  val playbackSuppressionReason: Int = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
  val audioFocusLost: Boolean = false,
  val playbackState: Int = Player.STATE_IDLE,
  val isLoading: Boolean = true,
  val globalMs: Long = 0L,
  val durationMs: Long = 0L,
  val heading: String? = null,
  val page: String? = null,
  val speed: Float = PlaybackSpeedOptions.DEFAULT_SPEED,
  val loadState: PlaybackLoadState = PlaybackLoadState.LOADING,
  val loadPhase: PlayerLoadPhase = PlayerLoadPhase.CONNECTING,
  val loadError: String? = null,
)

class PlayerViewModel(
  private val container: AppContainer,
  private val bookId: String,
  private val loadTimeoutMs: Long = 30_000L,
  private val readyGraceMs: Long = 2_000L,
  private val connectTimeoutMs: Long = 5_000L,
  private val commandTimeoutMs: Long = 5_000L,
  private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {
  private val _book = MutableStateFlow<DaisyBook?>(null)
  val book: StateFlow<DaisyBook?> = _book.asStateFlow()

  private val _coverPath = MutableStateFlow<String?>(null)
  val coverPath: StateFlow<String?> = _coverPath.asStateFlow()

  private val _playback = MutableStateFlow(PlaybackUiState())
  val playback: StateFlow<PlaybackUiState> = _playback.asStateFlow()

  private val _error = MutableStateFlow<String?>(null)
  val error: StateFlow<String?> = _error.asStateFlow()

  val sleepTimer = container.sleepTimerManager.state

  val bookmarks: StateFlow<List<BookmarkSession>> =
    container.bookmarkRepository.sessions
      .map { sessions -> sessions.filter { it.bookId == bookId }.sortedByDescending { it.start.createdAtEpochMs } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  val manualBookmarks: StateFlow<List<ManualBookmark>> =
    container.bookmarkRepository.manualBookmarks
      .map { bookmarks -> bookmarks.filter { it.bookId == bookId }.sortedByDescending { it.point.createdAtEpochMs } }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val loadMachine = PlayerLoadStateMachine(loadTimeoutMs, readyGraceMs)

  private var controller: MediaController? = null
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var connectJob: Job? = null
  private var reconnectJob: Job? = null
  private var tickerJob: Job? = null
  private var bookLoadJob: Job? = null
  private var loadWatchdogJob: Job? = null
  private var loadRequested = false
  private var pendingPlay = false
  private var retryForcePending = false
  private var serviceAudioFocusLost = false
  private var reconnectAttempts = 0
  private var connectionGeneration = 0

  private val controllerListener =
    object : MediaController.Listener {
      override fun onDisconnected(controller: MediaController) {
        if (this@PlayerViewModel.controller !== controller) return
        Log.d(TAG, "controller disconnected book=$bookId")
        controller.removeListener(playerListener)
        controller.release()
        this@PlayerViewModel.controller = null
        controllerFuture = null
        tickerJob?.cancel()
        loadRequested = false
        _playback.value =
          _playback.value.copy(
            isPlaying = false,
            playWhenReady = false,
            playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_NONE,
          )
        _error.value = "Verbinding met de speler verbroken. Opnieuw proberen…"
        scheduleReconnect()
      }

      override fun onExtrasChanged(controller: MediaController, extras: Bundle) {
        if (this@PlayerViewModel.controller !== controller) return
        applySessionExtras(extras)
      }
    }

  private val playerListener =
    object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlaybackState()

      override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
        updatePlaybackState()

      override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) =
        updatePlaybackState()

      override fun onPlaybackStateChanged(playbackState: Int) = updatePlaybackState()

      override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) =
        updatePlaybackState()

      override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
        updatePlaybackState()

      override fun onPlayerError(error: PlaybackException) {
        _error.value = "Afspelen mislukt: ${error.message}. Tik op afspelen om het opnieuw te proberen."
        updatePlaybackState()
      }
    }

  init {
    _coverPath.value = container.libraryRepository.get(bookId)?.coverPath
    loadMachine.begin(bookId, clock())
    updateLoadState(reason = "book opened")
    loadBook()
    ensureCover()
    connectController()
    startLoadWatchdog()
  }

  private fun ensureCover() {
    val entry = container.libraryRepository.get(bookId) ?: return
    viewModelScope.launch {
      try {
        val path = container.bookRepository.ensureCover(entry)
        _coverPath.value =
          path ?: container.libraryRepository.get(bookId)?.coverPath?.takeIf { File(it).isFile }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        // The player can still be used without a cover.
      }
    }
  }

  /** Picks up a cover that was chosen while the player was in the background. */
  fun refreshCover() {
    val path = container.libraryRepository.get(bookId)?.coverPath?.takeIf { File(it).isFile }
    _coverPath.value = path
    if (path == null) ensureCover()
  }

  /** Called by the screen on every resume, also after a permanent focus loss or disconnect. */
  fun onResume() {
    refreshCover()
    val current = controller
    if (current == null) {
      reconnectAttempts = 0
      scheduleReconnect(immediate = true)
      return
    }
    loadRequested = false
    applySessionExtras(current.sessionExtras)
    reconcileWithController()
    if (pendingPlay) maybeStartPendingPlay()
    startLoadWatchdogIfNeeded()
  }

  private fun loadBook() {
    bookLoadJob?.cancel()
    val requestId = loadMachine.state.requestId
    bookLoadJob =
      viewModelScope.launch {
        try {
          loadMachine.markReading()
          updateLoadState(reason = "repository load")
          val loaded = container.bookRepository.loadById(bookId)
          if (requestId != loadMachine.state.requestId) {
            Log.d(TAG, "ignore repository result request=$requestId current=${loadMachine.state.requestId}")
            return@launch
          }
          _book.value = loaded
          _playback.value = _playback.value.copy(durationMs = loaded.totalDurationMs)
          maybeRequestLoad()
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Throwable) {
          if (requestId != loadMachine.state.requestId) return@launch
          failLoad(error.message ?: "Boek kon niet worden geopend.", cancelBookWork = false)
        }
      }
  }

  private fun connectController() {
    val context = container.context
    val generation = ++connectionGeneration
    connectJob?.cancel()
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controllerFuture = null
    val token =
      SessionToken(context, ComponentName(context, DaisyPlaybackService::class.java))
    val future =
      MediaController.Builder(context, token)
        .setListener(controllerListener)
        .buildAsync()
    controllerFuture = future
    connectJob =
      viewModelScope.launch {
        try {
          val connected =
            withContext(Dispatchers.IO) {
              future.get(connectTimeoutMs, TimeUnit.MILLISECONDS)
            }
          if (generation != connectionGeneration || !isActive) {
            MediaController.releaseFuture(future)
            connected.release()
            return@launch
          }
          controllerFuture = null
          reconnectAttempts = 0
          controller = connected
          connected.addListener(playerListener)
          loadRequested = false
          Log.d(TAG, "controller connected book=$bookId request=${loadMachine.state.requestId}")
          applySessionExtras(connected.sessionExtras)
          reconcileWithController()
          startTicker()
          if (pendingPlay) maybeStartPendingPlay()
        } catch (cancelled: CancellationException) {
          mediaControllerFutureRelease(future)
          throw cancelled
        } catch (error: Throwable) {
          mediaControllerFutureRelease(future)
          if (generation != connectionGeneration) return@launch
          controllerFuture = null
          controller = null
          _error.value = "Kon de speler niet starten: ${error.message}"
          Log.w(TAG, "controller connection failed book=$bookId", error)
          scheduleReconnect()
        }
      }
  }

  private fun reconcileWithController() {
    val current = controller ?: return
    val mediaReady = hasBookMedia()
    loadMachine.onMediaAvailability(mediaReady, clock())
    if (mediaReady) {
      loadRequested = false
    } else {
      val state = loadMachine.state
      when {
        state.isError -> Unit
        state.phase == PlayerLoadPhase.PREPARING_PLAYER -> startLoadWatchdogIfNeeded()
        else -> maybeRequestLoad()
      }
    }
    updateLoadState(reason = "controller reconciled media=$mediaReady")
    maybeStartPendingPlay()
  }

  private fun scheduleReconnect(immediate: Boolean = false) {
    if (controller != null) return
    if (immediate) {
      reconnectAttempts = 0
    }
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      Log.w(TAG, "reconnect attempts exhausted book=$bookId")
      _error.value =
        "De speler is de verbinding kwijtgeraakt. Tik op afspelen of open het scherm opnieuw."
      updatePlaybackState()
      return
    }
    reconnectAttempts++
    val waitMs =
      if (immediate) 0L
      else minOf(RECONNECT_BASE_MS shl (reconnectAttempts - 1), RECONNECT_MAX_MS)
    reconnectJob?.cancel()
    reconnectJob =
      viewModelScope.launch {
        delay(waitMs)
        if (controller == null) connectController()
      }
  }

  private fun applySessionExtras(extras: Bundle?) {
    val rawState = extras?.getString(PlaybackCommands.KEY_LOAD_STATE) ?: return
    val state = PlaybackLoadState.entries.firstOrNull { it.name == rawState } ?: return
    val statusBookId = extras.getString(PlaybackCommands.KEY_LOAD_BOOK_ID)
    val requestId = extras.getLong(PlaybackCommands.KEY_LOAD_REQUEST_ID, 0L)
    val mediaReady = hasBookMedia()
    val previous = loadMachine.state
    val accepted =
      loadMachine.onServiceStatus(
        requestId = requestId,
        loadState = state,
        statusBookId = statusBookId,
        hasBookMedia = mediaReady,
        error = extras.getString(PlaybackCommands.KEY_LOAD_ERROR),
        nowMs = clock(),
      )
    if (accepted == null) {
      Log.d(
        TAG,
        "ignore stale status request=$requestId state=$state book=${statusBookId ?: "-"} " +
          "current=${previous.requestId}/${previous.phase}",
      )
      return
    }
    serviceAudioFocusLost = extras.getBoolean(PlaybackCommands.KEY_AUDIO_FOCUS_LOST, false)
    if (state == PlaybackLoadState.ERROR || state == PlaybackLoadState.READY) {
      loadRequested = false
    }
    if (state == PlaybackLoadState.ERROR) pendingPlay = false
    updateLoadState(reason = "service status ${state.name}")
    if (loadMachine.state.isLoading) startLoadWatchdogIfNeeded()
    if (state == PlaybackLoadState.READY && mediaReady) maybeStartPendingPlay()
  }

  private fun maybeRequestLoad(force: Boolean = false) {
    val current = controller ?: return
    val effectiveForce = force || retryForcePending
    if (!effectiveForce && loadRequested) return
    if (!effectiveForce && hasBookMedia()) {
      loadRequested = false
      loadMachine.onMediaAvailability(true, clock())
      updateLoadState(reason = "media already present")
      return
    }
    val requestId = loadMachine.state.requestId
    loadRequested = true
    loadMachine.markPreparing()
    updateLoadState(reason = "send LOAD_BOOK request=$requestId force=$effectiveForce")
    val args =
      Bundle().apply {
        putString(PlaybackCommands.KEY_BOOK_ID, bookId)
        putLong(PlaybackCommands.KEY_LOAD_REQUEST_ID, requestId)
        putBoolean(PlaybackCommands.KEY_LOAD_FORCE, effectiveForce)
      }
    val future =
      try {
        current.sendCustomCommand(
          SessionCommand(PlaybackCommands.LOAD_BOOK, Bundle.EMPTY),
          args,
        )
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        failLoad("Kon het boek niet laden: ${error.message}")
        return
      }
    retryForcePending = false
    viewModelScope.launch {
      try {
        val result =
          withContext(Dispatchers.IO) {
            future.get(commandTimeoutMs, TimeUnit.MILLISECONDS)
          }
        if (result.resultCode != SessionResult.RESULT_SUCCESS &&
          requestId == loadMachine.state.requestId
        ) {
          failLoad("De speler kon het boek niet laden.")
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        if (requestId == loadMachine.state.requestId) {
          failLoad("De speler kon het boek niet laden: ${error.message}")
        }
      }
    }
  }

  private fun startLoadWatchdog() {
    loadWatchdogJob?.cancel()
    loadWatchdogJob =
      viewModelScope.launch {
        while (isActive) {
          val before = loadMachine.state
          if (!before.isLoading) break
          delay(WATCHDOG_INTERVAL_MS)
          val effect = loadMachine.tick(clock())
          if (effect == PlayerLoadEffect.RELOAD_READY_BOOK) {
            Log.w(
              TAG,
              "READY without media, targeted reload book=$bookId request=${loadMachine.state.requestId}",
            )
            loadRequested = false
            maybeRequestLoad(force = true)
          }
          val after = loadMachine.state
          if (after != before) {
            updateLoadState(reason = "watchdog ${before.phase}->${after.phase}")
          }
          if (after.isError) {
            loadRequested = false
            pendingPlay = false
            bookLoadJob?.cancel()
            break
          }
          if (after.isReady) break
        }
      }
  }

  private fun startLoadWatchdogIfNeeded() {
    if (loadMachine.state.isLoading && loadWatchdogJob?.isActive != true) startLoadWatchdog()
  }

  private fun updateLoadState(reason: String? = null) {
    val state = loadMachine.state
    val previous = _playback.value
    _playback.value =
      previous.copy(
        isLoading = state.isLoading,
        loadState = state.loadState,
        loadPhase = state.phase,
        loadError = state.error,
      )
    if (reason != null && previous.loadPhase != state.phase) {
      Log.d(
        TAG,
        "load ${previous.loadPhase}->${state.phase} request=${state.requestId} book=$bookId " +
          "reason=$reason",
      )
    }
    if (state.isError && previous.loadError != state.error) {
      _error.value = state.error
    }
  }

  private fun failLoad(message: String, cancelBookWork: Boolean = true) {
    loadRequested = false
    pendingPlay = false
    loadMachine.fail(message)
    if (cancelBookWork) bookLoadJob?.cancel()
    updateLoadState(reason = "load failed")
    Log.w(TAG, "load failed request=${loadMachine.state.requestId} book=$bookId")
  }

  private fun startTicker() {
    tickerJob?.cancel()
    tickerJob =
      viewModelScope.launch {
        while (isActive) {
          updatePlaybackState()
          delay(500)
        }
      }
  }

  private fun hasBookMedia(): Boolean =
    controller?.let {
      it.mediaItemCount > 0 && it.currentMediaItem?.mediaId?.startsWith("$bookId:") == true
    } == true

  private fun updatePlaybackState() {
    val current = controller ?: return
    val prev = _playback.value
    val mediaReady = hasBookMedia()
    loadMachine.onMediaAvailability(mediaReady, clock())
    val loaded = _book.value
    val segment = loaded?.segments?.getOrNull(current.currentMediaItemIndex)
    val global =
      if (loaded != null && segment != null) {
        (segment.trackStartMs + current.currentPosition.coerceAtLeast(0L))
          .coerceIn(0L, loaded.totalDurationMs)
      } else {
        0L
      }
    _playback.value =
      PlaybackUiState(
        isPlaying = current.isPlaying,
        playWhenReady = current.playWhenReady,
        playbackSuppressionReason = current.playbackSuppressionReason,
        audioFocusLost = serviceAudioFocusLost,
        playbackState = current.playbackState,
        isLoading = loadMachine.state.isLoading,
        globalMs = global,
        durationMs = loaded?.totalDurationMs ?: prev.durationMs,
        heading = loaded?.headingAt(global)?.label,
        page = loaded?.pageAt(global)?.label,
        speed = current.playbackParameters.speed,
        loadState = loadMachine.state.loadState,
        loadPhase = loadMachine.state.phase,
        loadError = loadMachine.state.error,
      )
    if (loadMachine.state.isError && prev.loadError != loadMachine.state.error) {
      _error.value = loadMachine.state.error
    }
    if (loadMachine.state.isLoading) startLoadWatchdogIfNeeded()
    maybeStartPendingPlay()
  }

  /**
   * Starts playback once the media items of this book are available. This makes a tap on play
   * while the book is still being loaded stick.
   */
  private fun maybeStartPendingPlay() {
    if (!pendingPlay) return
    if (loadMachine.state.isError) {
      pendingPlay = false
      return
    }
    val current = controller ?: return
    if (!hasBookMedia()) {
      maybeRequestLoad()
      return
    }
    pendingPlay = false
    sendPlayCommand()
  }

  fun togglePlay() {
    val current = controller
    if (current == null) {
      pendingPlay = true
      serviceAudioFocusLost = false
      reconnectAttempts = 0
      scheduleReconnect(immediate = true)
      return
    }
    val suppressed =
      current.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
    if (suppressed || serviceAudioFocusLost) {
      // An explicit play during suppression must ask for audio focus again. The service drops
      // and re-arms playWhenReady around play() to trigger that request.
      pendingPlay = true
      serviceAudioFocusLost = false
      if (hasBookMedia()) {
        pendingPlay = false
        sendPlayCommand()
      } else {
        maybeRequestLoad(force = true)
      }
      return
    }
    if (current.playWhenReady || current.isPlaying) {
      pendingPlay = false
      sendCommand(PlaybackCommands.PAUSE_PLAYBACK)
      return
    }
    pendingPlay = true
    if (!hasBookMedia()) {
      maybeRequestLoad()
      return
    }
    pendingPlay = false
    sendPlayCommand()
  }

  private fun sendPlayCommand() {
    // ENDED is rewound by the service; IDLE is prepared there before play().
    sendCommand(PlaybackCommands.START_PLAYBACK)
  }

  private fun sendCommand(action: String, args: Bundle = Bundle.EMPTY) {
    val current = controller ?: return
    val future =
      try {
        current.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        _error.value = "De speler reageert niet: ${error.message}"
        return
      }
    viewModelScope.launch {
      try {
        val result =
          withContext(Dispatchers.IO) {
            future.get(commandTimeoutMs, TimeUnit.MILLISECONDS)
          }
        if (result.resultCode != SessionResult.RESULT_SUCCESS) {
          _error.value = "De speler kon de opdracht niet uitvoeren."
        }
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        _error.value = "De speler kon de opdracht niet uitvoeren: ${error.message}"
      }
    }
  }

  fun retryLoad() {
    bookLoadJob?.cancel()
    loadMachine.retry(clock())
    loadRequested = false
    pendingPlay = false
    retryForcePending = true
    serviceAudioFocusLost = false
    _error.value = null
    updateLoadState(reason = "manual retry")
    startLoadWatchdog()
    if (controller == null) {
      reconnectAttempts = 0
      scheduleReconnect(immediate = true)
    }
    loadBook()
    maybeRequestLoad(force = true)
  }

  fun seekRelative(deltaMs: Long) {
    seekGlobal(_playback.value.globalMs + deltaMs)
  }

  fun seekGlobal(positionMs: Long) {
    val controller = controller ?: return
    val book = _book.value ?: return
    val target = book.segmentAtGlobal(positionMs) ?: return
    controller.seekTo(target.first.index, target.second)
    updatePlaybackState()
  }

  fun seekToNav(nav: NavPoint) {
    controller?.seekTo(nav.segmentIndex, nav.offsetMs)
    updatePlaybackState()
  }

  suspend fun addBookmark(note: String? = null): Boolean =
    addBookmarkAt(_playback.value.globalMs, note)

  /**
   * Adds a manual bookmark at the position the user saw when opening the dialog. Using the
   * current playback position here would move the bookmark while the note is being typed.
   *
   * The repository writes JSON synchronously, so this runs on the IO dispatcher instead of the
   * main thread. The caller receives false when the bookmark could not be saved.
   */
  suspend fun addBookmarkAt(positionMs: Long, note: String? = null): Boolean =
    withContext(Dispatchers.IO) {
      val book = _book.value ?: return@withContext false
      val (segment, offset) = book.segmentAtGlobal(positionMs) ?: return@withContext false
      val position = book.positionAt(segment.index, offset) ?: return@withContext false
      try {
        container.bookmarkRepository.addManualBookmark(bookId, position, note)
        true
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        false
      }
    }

  fun removeBookmark(sessionId: String) {
    persistBookmarks("Bookmark kon niet worden verwijderd.") {
      container.bookmarkRepository.removeSession(bookId, sessionId)
    }
  }

  fun removeAllBookmarks() {
    persistBookmarks("Bookmarks konden niet worden verwijderd.") {
      container.bookmarkRepository.removeSessions(bookId)
    }
  }

  fun removeManualBookmark(bookmarkId: String) {
    persistBookmarks("Bookmark kon niet worden verwijderd.") {
      container.bookmarkRepository.removeManualBookmark(bookId, bookmarkId)
    }
  }

  fun removeAllManualBookmarks() {
    persistBookmarks("Bookmarks konden niet worden verwijderd.") {
      container.bookmarkRepository.removeManualBookmarks(bookId)
    }
  }

  private fun persistBookmarks(errorMessage: String, block: () -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Throwable) {
        _error.value = errorMessage
      }
    }
  }

  fun seekToBookmark(point: BookmarkPoint) {
    val controller = controller ?: return
    val book = _book.value ?: return
    val target = book.segmentForSource(point.sourcePath, point.sourceOffsetMs, point.globalMs)
    if (target != null) {
      controller.seekTo(target.first.index, target.second)
    } else {
      seekGlobal(point.globalMs)
    }
    updatePlaybackState()
  }

  fun nextHeading() {
    jumpHeading(forward = true)
  }

  fun previousHeading() {
    jumpHeading(forward = false)
  }

  private fun jumpHeading(forward: Boolean) {
    val book = _book.value ?: return
    val target = book.headingTarget(_playback.value.globalMs, forward) ?: return
    seekToNav(target)
  }

  fun setSleepTimer(durationMs: Long) {
    val args =
      Bundle().apply {
        putString(PlaybackCommands.KEY_BOOK_ID, bookId)
        putLong(PlaybackCommands.KEY_DURATION_MS, durationMs)
      }
    sendCommand(PlaybackCommands.SET_SLEEP_TIMER, args)
  }

  fun cancelSleepTimer() {
    sendCommand(PlaybackCommands.CANCEL_SLEEP_TIMER)
  }

  fun setSpeed(speed: Float) {
    val value = PlaybackSpeedOptions.sanitize(speed)
    val args =
      Bundle().apply {
        putFloat(PlaybackCommands.KEY_SPEED, value)
      }
    sendCommand(PlaybackCommands.SET_PLAYBACK_SPEED, args)
    _playback.value = _playback.value.copy(speed = value)
  }

  fun consumeError() {
    _error.value = null
  }

  override fun onCleared() {
    connectionGeneration++
    tickerJob?.cancel()
    loadWatchdogJob?.cancel()
    bookLoadJob?.cancel()
    connectJob?.cancel()
    reconnectJob?.cancel()
    val current = controller
    controller = null
    if (current != null) {
      current.removeListener(playerListener)
      current.release()
    } else {
      controllerFuture?.let { MediaController.releaseFuture(it) }
    }
    controllerFuture = null
    super.onCleared()
  }

  private fun mediaControllerFutureRelease(future: ListenableFuture<MediaController>) {
    runCatching { MediaController.releaseFuture(future) }
  }

  private companion object {
    const val TAG = "DaisyPlayer"
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val RECONNECT_BASE_MS = 500L
    const val RECONNECT_MAX_MS = 8_000L
    const val WATCHDOG_INTERVAL_MS = 250L
  }
}
