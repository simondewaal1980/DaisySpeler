package nl.simon.daisyspeler.ui.player

import android.content.ComponentName
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
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
import kotlin.math.max

data class PlaybackUiState(
  val isPlaying: Boolean = false,
  val isLoading: Boolean = true,
  val globalMs: Long = 0L,
  val durationMs: Long = 0L,
  val heading: String? = null,
  val page: String? = null,
  val speed: Float = PlaybackSpeedOptions.DEFAULT_SPEED,
  val loadState: PlaybackLoadState = PlaybackLoadState.IDLE,
)

class PlayerViewModel(
  private val container: AppContainer,
  private val bookId: String,
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

  private var controller: MediaController? = null
  private var controllerFuture: ListenableFuture<MediaController>? = null
  private var tickerJob: Job? = null
  private var loadTimeoutJob: Job? = null
  private var loadRequested = false
  private var pendingPlay = false
  private var reconnectAttempts = 0
  private var connectionGeneration = 0
  private var loadState = PlaybackLoadState.IDLE

  private val controllerListener =
    object : MediaController.Listener {
      override fun onDisconnected(controller: MediaController) {
        if (this@PlayerViewModel.controller !== controller) return
        cancelLoadTimeout()
        controller.removeListener(playerListener)
        controller.release()
        this@PlayerViewModel.controller = null
        controllerFuture = null
        loadRequested = false
        loadState = PlaybackLoadState.IDLE
        _playback.value =
          _playback.value.copy(isPlaying = false, isLoading = false, loadState = loadState)
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
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying && loadState == PlaybackLoadState.ERROR) {
          loadState = PlaybackLoadState.READY
        }
        updatePlaybackState()
      }

      override fun onPlaybackStateChanged(playbackState: Int) = updatePlaybackState()

      override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) =
        updatePlaybackState()

      override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
        updatePlaybackState()

      override fun onPlayerError(error: PlaybackException) {
        cancelLoadTimeout()
        loadState = PlaybackLoadState.ERROR
        _playback.value =
          _playback.value.copy(isLoading = false, loadState = loadState)
        _error.value = "Afspelen mislukt: ${error.message}"
      }
    }

  init {
    _coverPath.value = container.libraryRepository.get(bookId)?.coverPath
    loadBook()
    ensureCover()
    connectController()
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

  private fun loadBook() {
    viewModelScope.launch {
      try {
        val book = container.bookRepository.loadById(bookId)
        _book.value = book
        _playback.value =
          _playback.value.copy(durationMs = book.totalDurationMs, isLoading = true)
        maybeRequestLoad()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (error: Throwable) {
        cancelLoadTimeout()
        loadState = PlaybackLoadState.ERROR
        _playback.value =
          _playback.value.copy(isLoading = false, loadState = loadState)
        _error.value = error.message ?: "Boek kon niet worden geopend."
      }
    }
  }

  private fun connectController() {
    val context = container.context
    val generation = ++connectionGeneration
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controllerFuture = null
    val token =
      SessionToken(context, ComponentName(context, DaisyPlaybackService::class.java))
    val future =
      MediaController.Builder(context, token)
        .setListener(controllerListener)
        .buildAsync()
    controllerFuture = future
    future.addListener(
      {
        if (generation != connectionGeneration) {
          runCatching { future.get().release() }
          return@addListener
        }
        runCatching { future.get() }
          .onSuccess { connected ->
            if (generation != connectionGeneration) {
              connected.release()
              return@onSuccess
            }
            reconnectAttempts = 0
            controller = connected
            connected.addListener(playerListener)
            applySessionExtras(connected.sessionExtras)
            maybeRequestLoad()
            startTicker()
          }
          .onFailure { error ->
            if (generation != connectionGeneration) return@onFailure
            cancelLoadTimeout()
            loadState = PlaybackLoadState.ERROR
            _playback.value =
              _playback.value.copy(isLoading = false, loadState = loadState)
            _error.value = "Kon de speler niet starten: ${error.message}"
            scheduleReconnect()
          }
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  private fun scheduleReconnect() {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      cancelLoadTimeout()
      loadState = PlaybackLoadState.ERROR
      _playback.value =
        _playback.value.copy(isLoading = false, loadState = loadState)
      _error.value = "De speler is de verbinding kwijtgeraakt."
      return
    }
    reconnectAttempts++
    viewModelScope.launch {
      delay(500)
      if (controller == null) connectController()
    }
  }

  private fun applySessionExtras(extras: Bundle?) {
    val rawState = extras?.getString(PlaybackCommands.KEY_LOAD_STATE) ?: return
    val state = PlaybackLoadState.entries.firstOrNull { it.name == rawState } ?: return
    val statusBookId = extras.getString(PlaybackCommands.KEY_LOAD_BOOK_ID)
    if (statusBookId != null && statusBookId != bookId) return

    loadState = state
    cancelLoadTimeout()
    _playback.value =
      _playback.value.copy(isLoading = isBookLoading(state, hasBookMedia()), loadState = state)
    if (state == PlaybackLoadState.ERROR) {
      _error.value =
        extras.getString(PlaybackCommands.KEY_LOAD_ERROR) ?: "Boek kon niet worden geladen."
    }
  }

  private fun maybeRequestLoad(force: Boolean = false) {
    val controller = controller ?: return
    if (_book.value == null) return
    if (loadRequested && !force) return
    if (!force &&
      controller.mediaItemCount > 0 &&
      controller.currentMediaItem?.mediaId?.startsWith("$bookId:") == true
    ) {
      loadState = PlaybackLoadState.READY
      _playback.value = _playback.value.copy(isLoading = false, loadState = loadState)
      return
    }
    loadRequested = true
    loadState = PlaybackLoadState.LOADING
    _playback.value = _playback.value.copy(isLoading = true, loadState = loadState)
    val args =
      Bundle().apply {
        putString(PlaybackCommands.KEY_BOOK_ID, bookId)
      }
    val sent =
      runCatching {
          controller.sendCustomCommand(SessionCommand(PlaybackCommands.LOAD_BOOK, Bundle.EMPTY), args)
        }
        .onFailure { error ->
          cancelLoadTimeout()
          loadState = PlaybackLoadState.ERROR
          _playback.value =
            _playback.value.copy(isLoading = false, loadState = loadState)
          _error.value = "Kon het boek niet laden: ${error.message}"
        }
        .isSuccess
    if (sent) startLoadTimeout()
    updatePlaybackState()
  }

  private fun startLoadTimeout() {
    cancelLoadTimeout()
    loadTimeoutJob =
      viewModelScope.launch {
        delay(LOAD_TIMEOUT_MS)
        val current = controller ?: return@launch
        if (current.mediaItemCount == 0 &&
          loadState != PlaybackLoadState.READY &&
          loadState != PlaybackLoadState.ERROR
        ) {
          loadState = PlaybackLoadState.ERROR
          _playback.value =
            _playback.value.copy(isLoading = false, loadState = loadState)
          _error.value = "De speler kon het boek niet laden."
        }
      }
  }

  private fun cancelLoadTimeout() {
    loadTimeoutJob?.cancel()
    loadTimeoutJob = null
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
    val controller = controller ?: return
    val book = _book.value ?: return
    val mediaReady = hasBookMedia()
    val segment = book.segments.getOrNull(controller.currentMediaItemIndex)
    val global =
      segment?.let {
        (it.trackStartMs + controller.currentPosition.coerceAtLeast(0L))
          .coerceIn(0L, book.totalDurationMs)
      } ?: 0L
    val heading = book.headingAt(global)?.label
    val page = book.pageAt(global)?.label
    _playback.value =
      PlaybackUiState(
        isPlaying = controller.isPlaying,
        isLoading = isBookLoading(loadState, mediaReady),
        globalMs = global,
        durationMs = book.totalDurationMs,
        heading = heading,
        page = page,
        speed = controller.playbackParameters.speed,
        loadState = loadState,
      )
    maybeStartPendingPlay()
  }

  /**
   * Starts playback once the media items of the book are available. This makes a tap on play
   * while the book is still being loaded (for example right after importing it) stick.
   */
  private fun maybeStartPendingPlay() {
    if (!pendingPlay) return
    if (loadState == PlaybackLoadState.ERROR) {
      pendingPlay = false
      return
    }
    val controller = controller ?: return
    if (controller.mediaItemCount == 0) return
    pendingPlay = false
    if (controller.isPlaying) return
    if (controller.playbackState == Player.STATE_ENDED) {
      controller.seekTo(0, 0L)
    }
    controller.sendCustomCommand(
      SessionCommand(PlaybackCommands.START_PLAYBACK, Bundle.EMPTY),
      Bundle.EMPTY,
    )
  }

  fun togglePlay() {
    val controller = controller
    if (controller == null) {
      pendingPlay = true
      return
    }
    if (controller.isPlaying) {
      pendingPlay = false
      controller.sendCustomCommand(
        SessionCommand(PlaybackCommands.PAUSE_PLAYBACK, Bundle.EMPTY),
        Bundle.EMPTY,
      )
      return
    }
    if (controller.mediaItemCount == 0) {
      // The book is still loading. Remember the request; playback starts when it is ready.
      pendingPlay = true
      maybeRequestLoad(force = true)
      return
    }
    pendingPlay = false
    if (controller.playbackState == Player.STATE_ENDED ||
      (book.value?.let { _playback.value.globalMs >= it.totalDurationMs - 1_000 } == true)
    ) {
      seekGlobal(0L)
    }
    controller.sendCustomCommand(
      SessionCommand(PlaybackCommands.START_PLAYBACK, Bundle.EMPTY),
      Bundle.EMPTY,
    )
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
    val target = book.segmentForSource(point.sourcePath, point.sourceOffsetMs)
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
    val controller = controller ?: return
    val args =
      Bundle().apply {
        putString(PlaybackCommands.KEY_BOOK_ID, bookId)
        putLong(PlaybackCommands.KEY_DURATION_MS, durationMs)
      }
    controller.sendCustomCommand(
      SessionCommand(PlaybackCommands.SET_SLEEP_TIMER, Bundle.EMPTY),
      args,
    )
  }

  fun cancelSleepTimer() {
    controller?.sendCustomCommand(
      SessionCommand(PlaybackCommands.CANCEL_SLEEP_TIMER, Bundle.EMPTY),
      Bundle.EMPTY,
    )
  }

  fun setSpeed(speed: Float) {
    val controller = controller ?: return
    val value = PlaybackSpeedOptions.sanitize(speed)
    val args =
      Bundle().apply {
        putFloat(PlaybackCommands.KEY_SPEED, value)
      }
    controller.sendCustomCommand(
      SessionCommand(PlaybackCommands.SET_PLAYBACK_SPEED, Bundle.EMPTY),
      args,
    )
    _playback.value = _playback.value.copy(speed = value)
  }

  fun consumeError() {
    _error.value = null
  }

  override fun onCleared() {
    connectionGeneration++
    tickerJob?.cancel()
    cancelLoadTimeout()
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

  private companion object {
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val LOAD_TIMEOUT_MS = 20_000L
  }
}
