package nl.simon.daisyspeler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import nl.simon.daisyspeler.playback.DaisyPlaybackService
import nl.simon.daisyspeler.ui.about.AboutScreen
import nl.simon.daisyspeler.ui.cover.CoverBrowserScreen
import nl.simon.daisyspeler.ui.library.LibraryScreen
import nl.simon.daisyspeler.ui.player.PlayerScreen
import nl.simon.daisyspeler.ui.theme.LARGER_ICON_SCALE
import nl.simon.daisyspeler.ui.theme.LocalIconScale

@Composable
fun MainNavigation(container: AppContainer, onExit: () -> Unit) {
  val backStack = rememberNavBackStack(LibraryRoute)
  val largeIcons by container.uiPreferences.largeIcons.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val stopForBookSwitch = { DaisyPlaybackService.requestStopForBookSwitch(context) }

  CompositionLocalProvider(
    LocalIconScale provides if (largeIcons) LARGER_ICON_SCALE else 1f,
  ) {
    NavDisplay(
      backStack = backStack,
      onBack = {
        // Leaving a book for the library must release it before another book can be loaded.
        if (backStack.lastOrNull() is PlayerRoute) stopForBookSwitch()
        backStack.removeLastOrNull()
      },
      entryProvider =
        entryProvider {
          entry<LibraryRoute> {
            LibraryScreen(
              container = container,
              onOpenBook = { bookId -> backStack.add(PlayerRoute(bookId)) },
              onOpenCoverBrowser = { bookId -> backStack.add(CoverBrowserRoute(bookId)) },
              onOpenAbout = { backStack.add(AboutRoute) },
              onExit = onExit,
            )
          }
          entry<AboutRoute> {
            AboutScreen(onBack = { backStack.removeLastOrNull() })
          }
          entry<PlayerRoute> { route ->
            PlayerScreen(
              container = container,
              bookId = route.bookId,
              onBack = {
                stopForBookSwitch()
                backStack.removeLastOrNull()
              },
              onOpenCoverBrowser = {
                backStack.add(CoverBrowserRoute(route.bookId))
              },
            )
          }
          entry<CoverBrowserRoute> { route ->
            CoverBrowserScreen(
              container = container,
              bookId = route.bookId,
              onBack = { backStack.removeLastOrNull() },
            )
          }
        },
    )
  }
}
