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
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.book.DaisyBook
import nl.simon.daisyspeler.data.book.NavPoint
import nl.simon.daisyspeler.data.storage.BookmarkPoint
import nl.simon.daisyspeler.data.storage.BookmarkSession
import nl.simon.daisyspeler.data.storage.ManualBookmark
import nl.simon.daisyspeler.playback.DaisyPlaybackService
import nl.simon.daisyspeler.playback.PlaybackCommands
import nl.simon.daisyspeler.playback.PlaybackSpeedOptions
import kotlin.math.max

data class PlaybackUiState(
  val isPlaying: Boolean = false,
  val isLoading: Boolean = true,
  val globalMs: Long = 0L,
  val durationMs: Long = 0L,
  val heading: String? = null,
  val page: String? = null,
  val speed: Float = PlaybackSpeedOptions.DEFAULT_SPEED,
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
  private var loadRequested = false
  private var pendingPlay = false
  private var reconnectAttempts = 0

  private val controllerListener =
    object : MediaController.Listener {
      override fun onDisconnected(controller: MediaController) {
        if (this@PlayerViewModel.controller !== controller) return
        controller.removeListener(playerListener)
        this@PlayerViewModel.controller = null
        loadRequested = false
        scheduleReconnect()
      }
    }

  private val playerListener =
    object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlaybackState()

      override fun onPlaybackStateChanged(playbackState: Int) = updatePlaybackState()

      override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) =
        updatePlaybackState()

      override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
        updatePlaybackState()

      override fun onPlayerError(error: PlaybackException) {
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
    if (entry.coverPath != null) return
    viewModelScope.launch {
      runCatching { container.bookRepository.ensureCover(entry) }
        .onSuccess { path -> if (path != null) _coverPath.value = path }
    }
  }

  private fun loadBook() {
    viewModelScope.launch {
      runCatching {
          val book = container.bookRepository.loadById(bookId)
          _book.value = book
          _playback.value =
            _playback.value.copy(durationMs = book.totalDurationMs, isLoading = true)
          maybeRequestLoad()
        }
        .onFailure {
          _error.value = it.message ?: "Boek kon niet worden geopend."
          _playback.value = _playback.value.copy(isLoading = false)
        }
    }
  }

  private fun connectController() {
    val context = container.context
    val token =
      SessionToken(context, ComponentName(context, DaisyPlaybackService::class.java))
    val future =
      MediaController.Builder(context, token)
        .setListener(controllerListener)
        .buildAsync()
    controllerFuture = future
    future.addListener(
      {
        runCatching { future.get() }
          .onSuccess { connected ->
            reconnectAttempts = 0
            controller = connected
            connected.addListener(playerListener)
            maybeRequestLoad()
            startTicker()
            viewModelScope.launch {
              delay(20_000)
              val current = controller
              if (current != null && current.mediaItemCount == 0) {
                _playback.value = _playback.value.copy(isLoading = false)
                _error.value = "De speler kon het boek niet laden."
              }
            }
          }
          .onFailure { _error.value = "Kon de speler niet starten: ${it.message}" }
      },
      ContextCompat.getMainExecutor(context),
    )
  }

  private fun scheduleReconnect() {
    if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
      _error.value = "De speler is de verbinding kwijtgeraakt."
      return
    }
    reconnectAttempts++
    viewModelScope.launch {
      delay(500)
      connectController()
    }
  }

  private fun maybeRequestLoad(force: Boolean = false) {
    val controller = controller ?: return
    if (_book.value == null) return
    if (loadRequested && !force) return
    loadRequested = true
    val args =
      Bundle().apply {
        putString(PlaybackCommands.KEY_BOOK_ID, bookId)
      }
    controller.sendCustomCommand(SessionCommand(PlaybackCommands.LOAD_BOOK, Bundle.EMPTY), args)
    updatePlaybackState()
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

  private fun updatePlaybackState() {
    val controller = controller ?: return
    val book = _book.value ?: return
    val mediaReady = controller.mediaItemCount > 0
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
        isLoading = !mediaReady,
        globalMs = global,
        durationMs = book.totalDurationMs,
        heading = heading,
        page = page,
        speed = controller.playbackParameters.speed,
      )
    maybeStartPendingPlay()
  }

  /**
   * Starts playback once the media items of the book are available. This makes a tap on play
   * while the book is still being loaded (for example right after importing it) stick.
   */
  private fun maybeStartPendingPlay() {
    if (!pendingPlay) return
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

  fun addBookmark(note: String? = null): Boolean {
    val book = _book.value ?: return false
    val (segment, offset) = book.segmentAtGlobal(_playback.value.globalMs) ?: return false
    val position = book.positionAt(segment.index, offset) ?: return false
    container.bookmarkRepository.addManualBookmark(bookId, position, note)
    return true
  }

  fun removeBookmark(sessionId: String) {
    container.bookmarkRepository.removeSession(bookId, sessionId)
  }

  fun removeAllBookmarks() {
    container.bookmarkRepository.removeSessions(bookId)
  }

  fun removeManualBookmark(bookmarkId: String) {
    container.bookmarkRepository.removeManualBookmark(bookId, bookmarkId)
  }

  fun removeAllManualBookmarks() {
    container.bookmarkRepository.removeManualBookmarks(bookId)
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
    tickerJob?.cancel()
    controller?.removeListener(playerListener)
    controllerFuture?.let { MediaController.releaseFuture(it) }
    controller = null
    controllerFuture = null
    super.onCleared()
  }

  private companion object {
    const val MAX_RECONNECT_ATTEMPTS = 5
  }
}
