package nl.simon.daisyspeler

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import nl.simon.daisyspeler.ui.about.AboutScreen
import nl.simon.daisyspeler.ui.library.LibraryScreen
import nl.simon.daisyspeler.ui.player.PlayerScreen
import nl.simon.daisyspeler.ui.theme.LARGER_ICON_SCALE
import nl.simon.daisyspeler.ui.theme.LocalIconScale

@Composable
fun MainNavigation(container: AppContainer) {
  val backStack = rememberNavBackStack(LibraryRoute)
  val largeIcons by container.uiPreferences.largeIcons.collectAsStateWithLifecycle()

  CompositionLocalProvider(
    LocalIconScale provides if (largeIcons) LARGER_ICON_SCALE else 1f,
  ) {
    NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
        entryProvider {
          entry<LibraryRoute> {
            LibraryScreen(
              container = container,
              onOpenBook = { bookId -> backStack.add(PlayerRoute(bookId)) },
              onOpenAbout = { backStack.add(AboutRoute) },
            )
          }
          entry<AboutRoute> {
            AboutScreen(onBack = { backStack.removeLastOrNull() })
          }
          entry<PlayerRoute> { route ->
            PlayerScreen(
              container = container,
              bookId = route.bookId,
              onBack = { backStack.removeLastOrNull() },
            )
          }
        },
    )
  }
}
