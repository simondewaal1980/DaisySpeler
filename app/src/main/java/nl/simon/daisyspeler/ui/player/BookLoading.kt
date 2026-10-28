package nl.simon.daisyspeler.ui.player

/**
 * The loading screen follows the attempt state, not only the presence of media. A READY service
 * answer without media is first repaired and eventually becomes an error in [PlayerLoadStateMachine].
 */
internal fun isBookLoading(loadState: PlayerLoadSnapshot, hasBookMedia: Boolean): Boolean =
  loadState.isLoading || (loadState.isReady && !hasBookMedia)
