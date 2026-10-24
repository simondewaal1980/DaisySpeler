package nl.simon.daisyspeler.ui.player

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.book.DaisyBook
import nl.simon.daisyspeler.data.book.NavPoint
import nl.simon.daisyspeler.data.book.NavType
import nl.simon.daisyspeler.data.storage.BookmarkPoint
import nl.simon.daisyspeler.data.storage.BookmarkSession
import nl.simon.daisyspeler.data.storage.SleepTimerOptions
import nl.simon.daisyspeler.data.storage.SleepTimerState
import nl.simon.daisyspeler.util.formatDate
import nl.simon.daisyspeler.util.formatDuration
import nl.simon.daisyspeler.util.formatTimer

private enum class PlayerSheet {
  TOC,
  BOOKMARKS,
  SLEEP,
}

private enum class TocFilter(val label: String) {
  HEADINGS("Koppen"),
  HEADINGS_PAGES("Koppen + pagina's"),
  ALL("Alles"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
  container: AppContainer,
  bookId: String,
  onBack: () -> Unit,
) {
  val viewModel: PlayerViewModel = viewModel(key = bookId) { PlayerViewModel(container, bookId) }
  val book by viewModel.book.collectAsStateWithLifecycle()
  val playback by viewModel.playback.collectAsStateWithLifecycle()
  val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
  val timer by viewModel.sleepTimer.collectAsStateWithLifecycle()
  val error by viewModel.error.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
  val context = LocalContext.current
  val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

  LaunchedEffect(error) {
    error?.let {
      snackbarHostState.showSnackbar(it)
      viewModel.consumeError()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(
            book?.title ?: "Speler",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { padding ->
    val currentBook = book
    if (currentBook == null) {
      Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        contentAlignment = Alignment.Center,
      ) {
        if (playback.isLoading) {
          CircularProgressIndicator()
        } else {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              "Dit boek kon niet worden geopend.",
              style = MaterialTheme.typography.titleMedium,
            )
            Text(
              "Ga terug naar de bibliotheek en probeer het opnieuw.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onBack) { Text("Terug naar bibliotheek") }
          }
        }
      }
      return@Scaffold
    }

    PlayerContent(
      book = currentBook,
      playback = playback,
      timer = timer,
      onTogglePlay = viewModel::togglePlay,
      onSeekRelative = viewModel::seekRelative,
      onSeekGlobal = viewModel::seekGlobal,
      onPreviousHeading = viewModel::previousHeading,
      onNextHeading = viewModel::nextHeading,
      onOpenToc = { sheet = PlayerSheet.TOC },
      onOpenBookmarks = { sheet = PlayerSheet.BOOKMARKS },
      onOpenSleepTimer = { sheet = PlayerSheet.SLEEP },
      modifier = Modifier.fillMaxSize().padding(padding),
    )
  }

  when (sheet) {
    PlayerSheet.TOC -> {
      book?.let { currentBook ->
        TocSheet(
          book = currentBook,
          onDismiss = { sheet = null },
          onSelect = { nav ->
            viewModel.seekToNav(nav)
            sheet = null
          },
        )
      }
    }
    PlayerSheet.BOOKMARKS -> {
      BookmarksSheet(
        sessions = bookmarks,
        currentLabel = playback.heading,
        onDismiss = { sheet = null },
        onSelect = { point ->
          viewModel.seekToBookmark(point)
          sheet = null
        },
        onDelete = viewModel::removeBookmark,
        onDeleteAll = viewModel::removeAllBookmarks,
      )
    }
    PlayerSheet.SLEEP -> {
      SleepTimerSheet(
        timer = timer,
        debuggable = debuggable,
        onDismiss = { sheet = null },
        onSet = { durationMs ->
          viewModel.setSleepTimer(durationMs)
          sheet = null
        },
        onCancel = {
          viewModel.cancelSleepTimer()
          sheet = null
        },
      )
    }
    null -> Unit
  }
}

@Composable
private fun PlayerContent(
  book: DaisyBook,
  playback: PlaybackUiState,
  timer: SleepTimerState?,
  onTogglePlay: () -> Unit,
  onSeekRelative: (Long) -> Unit,
  onSeekGlobal: (Long) -> Unit,
  onPreviousHeading: () -> Unit,
  onNextHeading: () -> Unit,
  onOpenToc: () -> Unit,
  onOpenBookmarks: () -> Unit,
  onOpenSleepTimer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var scrubPosition by remember { mutableStateOf<Float?>(null) }
  val duration = playback.durationMs.coerceAtLeast(1L)
  val sliderValue = scrubPosition ?: (playback.globalMs.toFloat() / duration).coerceIn(0f, 1f)
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

  LaunchedEffect(timer?.deadlineEpochMs) {
    while (isActive) {
      now = System.currentTimeMillis()
      delay(1_000)
    }
  }
  val remaining = timer?.let { (it.deadlineEpochMs - now).coerceAtLeast(0L) }

  Column(
    modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(12.dp))
    Box(
      modifier = Modifier
        .size(132.dp)
        .padding(4.dp),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        Icons.AutoMirrored.Filled.MenuBook,
        contentDescription = null,
        modifier = Modifier.size(96.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
    }
    Text(
      book.title,
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )
    if (!book.author.isNullOrBlank()) {
      Text(
        book.author,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.height(20.dp))
    Text(
      playback.heading ?: "Begin van het boek",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Medium,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    if (playback.page != null) {
      Text(
        "Pagina ${playback.page}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.height(16.dp))

    Slider(
      value = sliderValue,
      onValueChange = { scrubPosition = it },
      onValueChangeFinished = {
        scrubPosition?.let { onSeekGlobal((it * duration).toLong()) }
        scrubPosition = null
      },
      enabled = playback.durationMs > 0,
      modifier = Modifier.fillMaxWidth(),
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        formatDuration((sliderValue * duration).toLong()),
        style = MaterialTheme.typography.labelMedium,
      )
      Text(
        formatDuration(duration),
        style = MaterialTheme.typography.labelMedium,
      )
    }
    Spacer(Modifier.height(16.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalButton(onClick = { onSeekRelative(-15_000) }) { Text("−15") }
      FilledIconButton(
        onClick = onTogglePlay,
        modifier = Modifier.size(76.dp),
      ) {
        Icon(
          if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
          contentDescription = if (playback.isPlaying) "Pauzeren" else "Afspelen",
          modifier = Modifier.size(40.dp),
        )
      }
      FilledTonalButton(onClick = { onSeekRelative(30_000) }) { Text("+30") }
    }

    Spacer(Modifier.height(20.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalButton(
        onClick = onPreviousHeading,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp),
      ) {
        Icon(Icons.Default.SkipPrevious, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text("Vorige", maxLines = 1)
      }
      FilledTonalButton(
        onClick = onOpenToc,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp),
      ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(4.dp))
        Text("Inhoud", maxLines = 1)
      }
      FilledTonalButton(
        onClick = onNextHeading,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp),
      ) {
        Text("Volgende", maxLines = 1)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(20.dp))
      }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedButton(
        onClick = onOpenSleepTimer,
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(
          if (timer != null) "Slaap ${formatTimer(remaining ?: 0L)}" else "Slaaptimer",
          maxLines = 1,
        )
      }
      OutlinedButton(
        onClick = onOpenBookmarks,
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Default.BookmarkBorder, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Bookmarks", maxLines = 1)
      }
    }
    Spacer(Modifier.height(32.dp))
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocSheet(
  book: DaisyBook,
  onDismiss: () -> Unit,
  onSelect: (NavPoint) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var filter by remember { mutableStateOf(TocFilter.HEADINGS_PAGES) }
  val visible =
    book.toc.filter { nav ->
      when (filter) {
        TocFilter.HEADINGS -> nav.type == NavType.HEADING
        TocFilter.HEADINGS_PAGES -> nav.type == NavType.HEADING || nav.type == NavType.PAGE
        TocFilter.ALL -> true
      }
    }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
      Text(
        "Inhoudsopgave",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(12.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TocFilter.entries.forEach { option ->
          FilterChip(
            selected = filter == option,
            onClick = { filter = option },
            label = { Text(option.label) },
          )
        }
      }
      Spacer(Modifier.height(8.dp))
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
      ) {
        items(visible, key = { "${it.globalMs}-${it.label}-${it.type}-${it.fragment}" }) { nav ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onSelect(nav) }
              .padding(
                start = (8 + (nav.level.coerceAtLeast(1) - 1) * 18).dp,
                top = 12.dp,
                bottom = 12.dp,
                end = 8.dp,
              ),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (nav.type == NavType.PAGE) {
              Text(
                "p. ${nav.label}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(52.dp),
              )
            } else {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  nav.label,
                  style = if (nav.level <= 1) MaterialTheme.typography.titleSmall
                  else MaterialTheme.typography.bodyMedium,
                  fontWeight = if (nav.level <= 1) FontWeight.SemiBold else FontWeight.Normal,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
                if (nav.type != NavType.HEADING) {
                  Text(
                    navTypeLabel(nav.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
            Text(
              formatDuration(nav.globalMs),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
  sessions: List<BookmarkSession>,
  currentLabel: String?,
  onDismiss: () -> Unit,
  onSelect: (BookmarkPoint) -> Unit,
  onDelete: (String) -> Unit,
  onDeleteAll: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var pendingDelete by remember { mutableStateOf<BookmarkSession?>(null) }
  var pendingDeleteAll by remember { mutableStateOf(false) }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          "Automatische bookmarks",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.weight(1f),
        )
        if (sessions.isNotEmpty()) {
          TextButton(onClick = { pendingDeleteAll = true }) {
            Icon(
              Icons.Default.DeleteSweep,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Alles wissen")
          }
        }
      }
      Text(
        "Bij de play/pause-knop en de slaaptimer wordt automatisch een markering gemaakt.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      if (sessions.isEmpty()) {
        Text(
          "Nog geen luistersessies. Start het boek om de eerste bookmark te maken.",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(vertical = 24.dp),
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
          contentPadding = PaddingValues(bottom = 24.dp),
        ) {
          items(sessions, key = { it.id }) { session ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  "Sessie ${formatDate(session.start.createdAtEpochMs)}",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { pendingDelete = session }) {
                  Icon(
                    Icons.Default.Delete,
                    contentDescription = "Bookmark verwijderen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }
              Spacer(Modifier.height(6.dp))
              BookmarkRow(
                label = "Start",
                point = session.start,
                onClick = { onSelect(session.start) },
              )
              session.stop?.let { stop ->
                BookmarkRow(
                  label =
                    when (stop.reason) {
                      "SLEEP_TIMER" -> "Stop (slaaptimer)"
                      "END" -> "Einde boek"
                      else -> "Stop"
                    },
                  point = stop,
                  onClick = { onSelect(stop) },
                )
              } ?: Text(
                "Nu bezig${currentLabel?.let { " · $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp),
              )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
          }
        }
      }
    }
  }

  pendingDelete?.let { session ->
    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Bookmark verwijderen?") },
      text = {
        Text(
          "De luistersessie van ${formatDate(session.start.createdAtEpochMs)} " +
            "en de bijbehorende markeringen worden verwijderd."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDelete(session.id)
            pendingDelete = null
          }
        ) {
          Text("Verwijderen")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDelete = null }) { Text("Annuleren") }
      },
    )
  }

  if (pendingDeleteAll) {
    AlertDialog(
      onDismissRequest = { pendingDeleteAll = false },
      title = { Text("Alle bookmarks verwijderen?") },
      text = {
        Text(
          "Alle ${sessions.size} bookmarks van dit boek worden verwijderd. " +
            "Dit kan niet ongedaan worden gemaakt."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteAll()
            pendingDeleteAll = false
          }
        ) {
          Text("Alles verwijderen")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteAll = false }) { Text("Annuleren") }
      },
    )
  }
}

@Composable
private fun BookmarkRow(
  label: String,
  point: BookmarkPoint,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      label,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.width(128.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(
        formatDuration(point.globalMs),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
      )
      point.label?.let {
        Text(
          it,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
  timer: SleepTimerState?,
  debuggable: Boolean,
  onDismiss: () -> Unit,
  onSet: (Long) -> Unit,
  onCancel: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(timer?.deadlineEpochMs) {
    while (isActive) {
      now = System.currentTimeMillis()
      delay(1_000)
    }
  }
  val remaining = timer?.let { (it.deadlineEpochMs - now).coerceAtLeast(0L) }

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
      Text(
        "Slaaptimer",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        "Pauzeer automatisch na de gekozen tijd.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (timer != null) {
        Spacer(Modifier.height(12.dp))
        Text(
          "Nog ${formatTimer(remaining ?: 0L)}",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Spacer(Modifier.height(16.dp))
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SleepTimerOptions.MINUTES.chunked(2).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { minutes ->
              AssistChip(
                onClick = { onSet(minutes * 60_000L) },
                label = { Text("$minutes min") },
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
        if (debuggable) {
          OutlinedButton(
            onClick = { onSet(SleepTimerOptions.DEBUG_DURATION_MS) },
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("10 seconden (debugtest)")
          }
        }
        if (timer != null) {
          TextButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColors(
              contentColor = MaterialTheme.colorScheme.error
            ),
          ) {
            Text("Slaaptimer uitzetten")
          }
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

private fun navTypeLabel(type: NavType): String =
  when (type) {
    NavType.PAGE -> "Pagina"
    NavType.SIDEBAR -> "Zijbalk"
    NavType.FOOTNOTE -> "Voetnoot"
    NavType.PRODNOTE -> "Producentnotitie"
    NavType.OTHER -> "Extra"
    NavType.HEADING -> ""
  }
