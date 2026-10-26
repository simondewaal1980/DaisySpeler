package nl.simon.daisyspeler.ui.cover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.cover.CoverImageDownloader
import java.net.URLEncoder

data class CoverBrowserUiState(
  val fetching: Boolean = false,
  val saving: Boolean = false,
  val pendingCover: ByteArray? = null,
  val error: String? = null,
  val saved: Boolean = false,
)

/**
 * Drives the small Google Images browser that lets the user pick a cover by hand. Only the
 * selected image is downloaded; the WebView itself is managed by the screen.
 */
class CoverBrowserViewModel(
  private val container: AppContainer,
  private val bookId: String,
) : ViewModel() {
  val initialQuery: String =
    container.libraryRepository
      .get(bookId)
      ?.let { listOfNotNull(it.title, it.author).joinToString(" ") }
      .orEmpty()
      .trim()

  private val _state = MutableStateFlow(CoverBrowserUiState())
  val state: StateFlow<CoverBrowserUiState> = _state.asStateFlow()

  fun selectImage(url: String, referer: String?) {
    val current = _state.value
    if (current.fetching || current.saving) return

    _state.value = current.copy(fetching = true, error = null)
    viewModelScope.launch {
      val bytes =
        withContext(Dispatchers.IO) {
          CoverImageDownloader.download(url, referer)
        }
      _state.value =
        if (bytes != null) {
          _state.value.copy(fetching = false, pendingCover = bytes)
        } else {
          _state.value.copy(
            fetching = false,
            error =
              "Deze afbeelding kon niet worden opgehaald. Open de afbeelding eerst groot en houd " +
                "haar daarna opnieuw ingedrukt.",
          )
        }
    }
  }

  fun cancelPending() {
    if (_state.value.saving) return
    _state.value = _state.value.copy(pendingCover = null)
  }

  fun confirmPending() {
    val bytes = _state.value.pendingCover ?: return
    if (_state.value.saving) return

    _state.value = _state.value.copy(saving = true, error = null)
    viewModelScope.launch {
      val path =
        withContext(Dispatchers.IO) {
          container.bookRepository.setCover(bookId, bytes)
        }
      _state.value =
        if (path != null) {
          _state.value.copy(saving = false, pendingCover = null, saved = true)
        } else {
          _state.value.copy(
            saving = false,
            error = "De afbeelding kon niet als omslag worden opgeslagen.",
          )
        }
    }
  }

  fun consumeError() {
    _state.value = _state.value.copy(error = null)
  }
}

/** Google Images search URL for a free-text query. */
fun imageSearchUrl(query: String): String =
  "https://www.google.com/search?tbm=isch&hl=nl&q=" +
    URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
