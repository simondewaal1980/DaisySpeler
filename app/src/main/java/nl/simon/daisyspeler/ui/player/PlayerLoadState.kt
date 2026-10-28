package nl.simon.daisyspeler.ui.player

import nl.simon.daisyspeler.playback.PlaybackLoadState

/** The user-visible phase of opening a book. Kept free of Android types so it is unit-testable. */
enum class PlayerLoadPhase {
  CONNECTING,
  READING_BOOK,
  PREPARING_PLAYER,
  READY,
  ERROR,
}

/** Work the player must perform after a timeout check. */
enum class PlayerLoadEffect {
  NONE,
  RELOAD_READY_BOOK,
}

data class PlayerLoadSnapshot(
  val requestId: Long,
  val bookId: String,
  val phase: PlayerLoadPhase = PlayerLoadPhase.CONNECTING,
  val error: String? = null,
) {
  val isLoading: Boolean
    get() = phase != PlayerLoadPhase.READY && phase != PlayerLoadPhase.ERROR

  val isReady: Boolean
    get() = phase == PlayerLoadPhase.READY

  val isError: Boolean
    get() = phase == PlayerLoadPhase.ERROR

  val loadState: PlaybackLoadState
    get() =
      when (phase) {
        PlayerLoadPhase.READY -> PlaybackLoadState.READY
        PlayerLoadPhase.ERROR -> PlaybackLoadState.ERROR
        PlayerLoadPhase.CONNECTING,
        PlayerLoadPhase.READING_BOOK,
        PlayerLoadPhase.PREPARING_PLAYER -> PlaybackLoadState.LOADING
      }
}

/**
 * Tracks one attempt to open a book. The request id makes late service answers harmless: only the
 * active attempt may change the state. A READY status without media is repaired once and otherwise
 * becomes a clear error, so the loading screen can never spin forever.
 */
class PlayerLoadStateMachine(
  private val timeoutMs: Long,
  private val readyGraceMs: Long,
  private val timeoutMessage: String = "Het boek kon niet op tijd worden geopend.",
  private val inconsistentMediaMessage: String =
    "De speler heeft het boek niet volledig kunnen openen. Probeer het opnieuw.",
) {
  var state: PlayerLoadSnapshot =
    PlayerLoadSnapshot(requestId = 0L, bookId = "", phase = PlayerLoadPhase.ERROR)
    private set

  private var deadlineMs: Long = 0L
  private var readyWithoutMediaSinceMs: Long? = null
  private var readyRecoveryAttempted = false

  fun begin(bookId: String, nowMs: Long): PlayerLoadSnapshot {
    readyRecoveryAttempted = false
    return startNewRequest(bookId, nowMs)
  }

  fun retry(nowMs: Long): PlayerLoadSnapshot {
    readyRecoveryAttempted = false
    return startNewRequest(state.bookId, nowMs)
  }

  fun markReading(): PlayerLoadSnapshot {
    if (state.phase != PlayerLoadPhase.CONNECTING &&
      state.phase != PlayerLoadPhase.READING_BOOK
    ) {
      return state
    }
    readyWithoutMediaSinceMs = null
    state = state.copy(phase = PlayerLoadPhase.READING_BOOK, error = null)
    return state
  }

  fun markPreparing(): PlayerLoadSnapshot {
    if (!state.isLoading) return state
    readyWithoutMediaSinceMs = null
    state = state.copy(phase = PlayerLoadPhase.PREPARING_PLAYER, error = null)
    return state
  }

  fun onMediaAvailability(hasBookMedia: Boolean, nowMs: Long): PlayerLoadSnapshot {
    if (hasBookMedia) {
      readyWithoutMediaSinceMs = null
      if (!state.isReady) {
        state = state.copy(phase = PlayerLoadPhase.READY, error = null)
      }
      return state
    }
    if (state.isReady) {
      readyWithoutMediaSinceMs = readyWithoutMediaSinceMs ?: nowMs
      state = state.copy(phase = PlayerLoadPhase.PREPARING_PLAYER)
    }
    return state
  }

  /**
   * Applies a service status. Returns null when the status belongs to an older request or another
   * book, in which case the caller must not change the UI.
   */
  fun onServiceStatus(
    requestId: Long,
    loadState: PlaybackLoadState,
    statusBookId: String?,
    hasBookMedia: Boolean,
    error: String?,
    nowMs: Long,
  ): PlayerLoadSnapshot? {
    if (statusBookId != null && statusBookId != state.bookId) return null
    if (requestId != 0L && requestId != state.requestId) {
      // A committed READY for the same book is reconciled with the actual media and adopted.
      // Other late answers must not overwrite a newer attempt.
      if (loadState != PlaybackLoadState.READY || !hasBookMedia) return null
      state = state.copy(requestId = requestId)
    }

    when (loadState) {
      PlaybackLoadState.LOADING -> {
        readyWithoutMediaSinceMs = null
        state = state.copy(phase = PlayerLoadPhase.PREPARING_PLAYER, error = null)
      }
      PlaybackLoadState.READY -> {
        if (hasBookMedia) {
          readyWithoutMediaSinceMs = null
          state = state.copy(phase = PlayerLoadPhase.READY, error = null)
        } else {
          readyWithoutMediaSinceMs = readyWithoutMediaSinceMs ?: nowMs
          state = state.copy(phase = PlayerLoadPhase.PREPARING_PLAYER, error = null)
        }
      }
      PlaybackLoadState.ERROR -> {
        readyWithoutMediaSinceMs = null
        state = state.copy(
          phase = PlayerLoadPhase.ERROR,
          error = error ?: "Boek kon niet worden geladen.",
        )
      }
      PlaybackLoadState.IDLE -> {
        readyWithoutMediaSinceMs = null
        if (hasBookMedia) {
          state = state.copy(phase = PlayerLoadPhase.READY, error = null)
        } else {
          state = state.copy(phase = PlayerLoadPhase.CONNECTING, error = null)
        }
      }
    }
    return state
  }

  fun fail(message: String): PlayerLoadSnapshot {
    readyWithoutMediaSinceMs = null
    state = state.copy(phase = PlayerLoadPhase.ERROR, error = message)
    return state
  }

  /**
   * Advances timers. [PlayerLoadEffect.RELOAD_READY_BOOK] is returned exactly once when the service
   * claimed READY without media; the caller performs one targeted reload.
   */
  fun tick(nowMs: Long): PlayerLoadEffect {
    if (state.isReady || state.isError) return PlayerLoadEffect.NONE

    if (nowMs >= deadlineMs) {
      fail(timeoutMessage)
      return PlayerLoadEffect.NONE
    }

    val inconsistentSince = readyWithoutMediaSinceMs ?: return PlayerLoadEffect.NONE
    if (nowMs - inconsistentSince < readyGraceMs) return PlayerLoadEffect.NONE
    if (!readyRecoveryAttempted) {
      readyRecoveryAttempted = true
      readyWithoutMediaSinceMs = null
      state = state.copy(phase = PlayerLoadPhase.PREPARING_PLAYER, error = null)
      return PlayerLoadEffect.RELOAD_READY_BOOK
    }
    fail(inconsistentMediaMessage)
    return PlayerLoadEffect.NONE
  }

  private fun startNewRequest(bookId: String, nowMs: Long): PlayerLoadSnapshot {
    readyWithoutMediaSinceMs = null
    deadlineMs = if (nowMs > Long.MAX_VALUE - timeoutMs) Long.MAX_VALUE else nowMs + timeoutMs
    state =
      PlayerLoadSnapshot(
        requestId = state.requestId + 1,
        bookId = bookId,
        phase = PlayerLoadPhase.CONNECTING,
      )
    return state
  }
}
