package nl.simon.daisyspeler

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import nl.simon.daisyspeler.ui.about.AboutScreen
import nl.simon.daisyspeler.ui.library.LibraryScreen
import nl.simon.daisyspeler.ui.player.PlayerScreen

@Composable
fun MainNavigation(container: AppContainer) {
  val backStack = rememberNavBackStack(LibraryRoute)

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
