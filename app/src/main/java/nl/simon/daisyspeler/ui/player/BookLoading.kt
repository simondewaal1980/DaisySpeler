package nl.simon.daisyspeler.ui.player

import nl.simon.daisyspeler.playback.PlaybackLoadState

internal fun isBookLoading(loadState: PlaybackLoadState, hasBookMedia: Boolean): Boolean =
  loadState != PlaybackLoadState.ERROR &&
    (loadState == PlaybackLoadState.LOADING || !hasBookMedia)
