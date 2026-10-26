package nl.simon.daisyspeler.playback

object PlaybackCommands {
  const val LOAD_BOOK = "nl.simon.daisyspeler.LOAD_BOOK"
  const val UNLOAD_BOOK = "nl.simon.daisyspeler.UNLOAD_BOOK"
  const val STOP_FOR_BOOK_SWITCH = "nl.simon.daisyspeler.STOP_FOR_BOOK_SWITCH"
  const val START_PLAYBACK = "nl.simon.daisyspeler.START_PLAYBACK"
  const val PAUSE_PLAYBACK = "nl.simon.daisyspeler.PAUSE_PLAYBACK"
  const val SET_SLEEP_TIMER = "nl.simon.daisyspeler.SET_SLEEP_TIMER"
  const val CANCEL_SLEEP_TIMER = "nl.simon.daisyspeler.CANCEL_SLEEP_TIMER"
  const val SET_PLAYBACK_SPEED = "nl.simon.daisyspeler.SET_PLAYBACK_SPEED"

  const val KEY_BOOK_ID = "bookId"
  const val KEY_DURATION_MS = "durationMs"
  const val KEY_SPEED = "speed"

  const val KEY_LOAD_STATE = "loadState"
  const val KEY_LOAD_BOOK_ID = "loadBookId"
  const val KEY_LOAD_ERROR = "loadError"
}

enum class PlaybackLoadState {
  IDLE,
  LOADING,
  READY,
  ERROR,
}
