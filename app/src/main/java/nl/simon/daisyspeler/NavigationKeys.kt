package nl.simon.daisyspeler

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import nl.simon.daisyspeler.data.website.AudiobookSite

@Serializable data object LibraryRoute : NavKey

@Serializable data object AboutRoute : NavKey

@Serializable data class PlayerRoute(val bookId: String) : NavKey

@Serializable data class CoverBrowserRoute(val bookId: String) : NavKey

@Serializable data class BookBrowserRoute(val site: AudiobookSite) : NavKey
