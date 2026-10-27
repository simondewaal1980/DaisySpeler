package nl.simon.daisyspeler.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.download.AudiobookImportResult
import nl.simon.daisyspeler.data.download.BrowserDownload

data class BookBrowserUiState(
  val checkingDownloads: Boolean = false,
  val message: String? = null,
)

/** Drives downloads from the in-app book browser and turns finished ZIPs into library books. */
class BookBrowserViewModel(private val container: AppContainer) : ViewModel() {
  private val _state = MutableStateFlow(BookBrowserUiState())
  val state: StateFlow<BookBrowserUiState> = _state.asStateFlow()

  /** Map onder Download waar gedownloade boeken terechtkomen. */
  val downloadFolder: StateFlow<String> = container.uiPreferences.downloadFolder

  val folderLabel: String
    get() = container.audiobookDownloads.folderLabel

  fun download(request: BrowserDownload) {
    viewModelScope.launch {
      if (!container.audiobookDownloads.canDownloadToFolder()) {
        reportStoragePermissionDenied()
        return@launch
      }
      val id =
        withContext(Dispatchers.IO) { container.audiobookDownloads.enqueue(request) }
      _state.value =
        if (id == null) {
          _state.value.copy(
            message = "De download kon niet worden gestart. Probeer de link opnieuw te openen.",
          )
        } else {
          _state.value.copy(
            message = "Download gestart. Het boek wordt opgeslagen in $folderLabel.",
          )
        }
    }
  }

  fun reportUnsupportedDownload() {
    _state.value =
      _state.value.copy(
        message = "Deze downloadlink wordt niet ondersteund. Probeer de download opnieuw te starten.",
      )
  }

  fun reportStoragePermissionDenied() {
    _state.value =
      _state.value.copy(
        message =
          "Zonder opslagtoestemming kan het boek niet in $folderLabel worden opgeslagen.",
      )
  }

  /** Past de downloadmap aan en maakt hem meteen aan. */
  fun setDownloadFolder(name: String) {
    container.uiPreferences.setDownloadFolder(name)
    ensureDownloadFolder()
    _state.value = _state.value.copy(message = "Downloads worden opgeslagen in $folderLabel.")
  }

  fun ensureDownloadFolder() {
    viewModelScope.launch {
      val allowed = container.audiobookDownloads.canDownloadToFolder()
      val created =
        allowed && withContext(Dispatchers.IO) { container.audiobookDownloads.ensureFolder() }
      if (!created) {
        _state.value =
          _state.value.copy(
            message =
              if (allowed) {
                "De downloadmap kon niet worden aangemaakt. Controleer de opslag van je apparaat."
              } else {
                "Zonder opslagtoestemming kan het boek niet in $folderLabel worden opgeslagen."
              },
          )
      }
    }
  }

  fun checkCompletedDownloads() {
    if (_state.value.checkingDownloads) return
    _state.value = _state.value.copy(checkingDownloads = true)
    viewModelScope.launch {
      val results =
        try {
          container.audiobookDownloads.importCompleted(container.bookImporter)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Throwable) {
          _state.value =
            _state.value.copy(
              checkingDownloads = false,
              message = "Gedownloade boeken konden niet worden gecontroleerd.",
            )
          return@launch
        }
      val messages = results.mapNotNull { messageFor(it, folderLabel) }
      _state.value =
        _state.value.copy(
          checkingDownloads = false,
          message = messages.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
    }
  }

  fun consumeMessage() {
    _state.value = _state.value.copy(message = null)
  }

  private fun messageFor(result: AudiobookImportResult, folder: String): String? {
    return when (result) {
      is AudiobookImportResult.Imported -> "Toegevoegd aan de bibliotheek: ${result.title}."
      is AudiobookImportResult.NotAnArchive ->
        "${result.fileName} is gedownload naar $folder, maar is geen ZIP- of DAISY-archief. " +
          "Alleen ZIP-bestanden worden automatisch toegevoegd."
      is AudiobookImportResult.Failed ->
        "Het gedownloade boek ${result.fileName} kon niet worden toegevoegd" +
          (result.message?.let { ": $it" } ?: ".")
    }
  }
}
