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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.book.DaisyBook
import nl.simon.daisyspeler.data.book.NavPoint
import nl.simon.daisyspeler.data.book.NavType
import nl.simon.daisyspeler.data.storage.BookmarkPoint
import nl.simon.daisyspeler.data.storage.BookmarkSession
import nl.simon.daisyspeler.data.storage.ManualBookmark
import nl.simon.daisyspeler.data.storage.SleepTimerOptions
import nl.simon.daisyspeler.data.storage.SleepTimerState
import nl.simon.daisyspeler.playback.PlaybackLoadState
import nl.simon.daisyspeler.playback.PlaybackSpeedOptions
import nl.simon.daisyspeler.ui.components.BookCover
import nl.simon.daisyspeler.ui.theme.DefaultIconSize
import nl.simon.daisyspeler.ui.theme.scaledIconSize
import nl.simon.daisyspeler.util.formatDate
import nl.simon.daisyspeler.util.formatDuration
import nl.simon.daisyspeler.util.formatTimer
import kotlin.math.abs

private enum class PlayerSheet {
  TOC,
  BOOKMARKS,
  SLEEP,
  SPEED,
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
  onOpenCoverBrowser: () -> Unit,
) {
  val viewModel: PlayerViewModel = viewModel(key = bookId) { PlayerViewModel(container, bookId) }
  val entry = remember(bookId) { container.libraryRepository.get(bookId) }
  val book by viewModel.book.collectAsStateWithLifecycle()
  val coverPath by viewModel.coverPath.collectAsStateWithLifecycle()
  val playback by viewModel.playback.collectAsStateWithLifecycle()
  val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
  val manualBookmarks by viewModel.manualBookmarks.collectAsStateWithLifecycle()
  val timer by viewModel.sleepTimer.collectAsStateWithLifecycle()
  val error by viewModel.error.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
  var showAddBookmark by remember { mutableStateOf(false) }
  var bookmarkPositionMs by remember { mutableStateOf<Long?>(null) }
  var bookmarkLabel by remember { mutableStateOf<String?>(null) }
  val context = LocalContext.current
  val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
  val loading =
    playback.isLoading || (book == null && playback.loadState != PlaybackLoadState.ERROR)

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshCover() }

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
            book?.title ?: entry?.title ?: "Speler",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Terug",
              modifier = Modifier.size(scaledIconSize(DefaultIconSize)),
            )
          }
        },
        actions = {
          IconButton(onClick = onOpenCoverBrowser, enabled = !loading) {
            Icon(
              Icons.Default.ImageSearch,
              contentDescription = "Omslag zoeken",
              modifier = Modifier.size(scaledIconSize(DefaultIconSize)),
            )
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { padding ->
    val currentBook = book
    if (loading) {
      BookLoadingContent(
        title = currentBook?.title ?: entry?.title,
        onBack = onBack,
        modifier = Modifier.fillMaxSize().padding(padding),
      )
      return@Scaffold
    }
    if (currentBook == null || playback.loadState == PlaybackLoadState.ERROR) {
      Box(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        contentAlignment = Alignment.Center,
      ) {
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
      return@Scaffold
    }

    PlayerContent(
      book = currentBook,
      coverPath = coverPath,
      playback = playback,
      timer = timer,
      onTogglePlay = viewModel::togglePlay,
      onSeekRelative = viewModel::seekRelative,
      onSeekGlobal = viewModel::seekGlobal,
      onPreviousHeading = viewModel::previousHeading,
      onNextHeading = viewModel::nextHeading,
      onAddBookmark = {
        bookmarkPositionMs = playback.globalMs
        bookmarkLabel = playback.heading
        showAddBookmark = true
      },
      onOpenToc = { sheet = PlayerSheet.TOC },
      onOpenBookmarks = { sheet = PlayerSheet.BOOKMARKS },
      onOpenSleepTimer = { sheet = PlayerSheet.SLEEP },
      onOpenSpeed = { sheet = PlayerSheet.SPEED },
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
        manual = manualBookmarks,
        currentLabel = playback.heading,
        onDismiss = { sheet = null },
        onSelect = { point ->
          viewModel.seekToBookmark(point)
          sheet = null
        },
        onDelete = viewModel::removeBookmark,
        onDeleteAll = viewModel::removeAllBookmarks,
        onDeleteManual = viewModel::removeManualBookmark,
        onDeleteAllManual = viewModel::removeAllManualBookmarks,
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
    PlayerSheet.SPEED -> {
      SpeedSheet(
        speed = playback.speed,
        onDismiss = { sheet = null },
        onSelect = viewModel::setSpeed,
      )
    }
    null -> Unit
  }

  if (showAddBookmark) {
    AddBookmarkDialog(
      positionMs = bookmarkPositionMs ?: playback.globalMs,
      positionLabel = bookmarkLabel ?: playback.heading,
      onDismiss = {
        showAddBookmark = false
        bookmarkPositionMs = null
        bookmarkLabel = null
      },
      onConfirm = { note ->
        val position = bookmarkPositionMs ?: playback.globalMs
        showAddBookmark = false
        bookmarkPositionMs = null
        bookmarkLabel = null
        scope.launch {
          val added = viewModel.addBookmarkAt(position, note)
          snackbarHostState.showSnackbar(
            if (added) "Bookmark toegevoegd" else "Bookmark kon niet worden toegevoegd"
          )
        }
      },
    )
  }
}

@Composable
private fun BookLoadingContent(
  title: String?,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.verticalScroll(rememberScrollState()).padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
  ) {
    CircularProgressIndicator(modifier = Modifier.size(56.dp))
    Text(
      "Boek laden…",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (!title.isNullOrBlank()) {
      Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
      )
    }
    Text(
      "Het boek wordt klaargemaakt om te beluisteren. Even geduld.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
    TextButton(onClick = onBack) { Text("Terug naar bibliotheek") }
  }
}

@Composable
private fun PlayerContent(
  book: DaisyBook,
  coverPath: String?,
  playback: PlaybackUiState,
  timer: SleepTimerState?,
  onTogglePlay: () -> Unit,
  onSeekRelative: (Long) -> Unit,
  onSeekGlobal: (Long) -> Unit,
  onPreviousHeading: () -> Unit,
  onNextHeading: () -> Unit,
  onAddBookmark: () -> Unit,
  onOpenToc: () -> Unit,
  onOpenBookmarks: () -> Unit,
  onOpenSleepTimer: () -> Unit,
  onOpenSpeed: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var scrubPosition by remember { mutableStateOf<Float?>(null) }
  val duration = playback.durationMs.coerceAtLeast(1L)
  val sliderValue = scrubPosition ?: (playback.globalMs.toFloat() / duration).coerceIn(0f, 1f)
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

  LaunchedEffect(timer?.deadlineEpochMs) {
    if (timer == null) return@LaunchedEffect
    while (isActive) {
      now = System.currentTimeMillis()
      delay(1_000)
    }
  }
  val remaining = timer?.let { (it.deadlineEpochMs - now).coerceAtLeast(0L) }

  Column(
    modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Spacer(Modifier.height(8.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      BookCover(
        coverPath = coverPath,
        contentDescription = "Omslag van ${book.title}",
        modifier = Modifier.width(88.dp).height(132.dp),
      ) {
        Icon(
          Icons.AutoMirrored.Filled.MenuBook,
          contentDescription = null,
          modifier = Modifier.size(scaledIconSize(36.dp)),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
      Spacer(Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          book.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (!book.author.isNullOrBlank()) {
          Text(
            book.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        Spacer(Modifier.height(6.dp))
        Text(
          playback.heading ?: "Begin van het boek",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (playback.page != null) {
          Text(
            "Pagina ${playback.page}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    Spacer(Modifier.height(12.dp))

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
    Spacer(Modifier.height(8.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FilledTonalButton(onClick = { onSeekRelative(-15_000) }) { Text("−15") }
      FilledIconButton(
        onClick = onTogglePlay,
        modifier = Modifier.size(64.dp),
      ) {
        Icon(
          if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
          contentDescription = if (playback.isPlaying) "Pauzeren" else "Afspelen",
          modifier = Modifier.size(scaledIconSize(36.dp)),
        )
      }
      FilledTonalButton(onClick = { onSeekRelative(30_000) }) { Text("+30") }
    }

    Spacer(Modifier.height(8.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompactAction(
        icon = Icons.Default.SkipPrevious,
        label = "Vorige",
        onClick = onPreviousHeading,
        modifier = Modifier.weight(1f),
      )
      CompactAction(
        icon = Icons.AutoMirrored.Filled.List,
        label = "Inhoud",
        onClick = onOpenToc,
        modifier = Modifier.weight(1f),
      )
      CompactAction(
        icon = Icons.Default.SkipNext,
        label = "Volgende",
        onClick = onNextHeading,
        modifier = Modifier.weight(1f),
      )
      CompactAction(
        icon = Icons.Default.Speed,
        label = "Snelheid ${PlaybackSpeedOptions.format(playback.speed)}",
        onClick = onOpenSpeed,
        modifier = Modifier.weight(1.25f),
      )
    }
    Spacer(Modifier.height(4.dp))
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompactAction(
        icon = Icons.Default.BookmarkAdd,
        label = "Markeren",
        onClick = onAddBookmark,
        modifier = Modifier.weight(1f),
      )
      CompactAction(
        icon = Icons.Default.Timer,
        label = timer?.let { "Slaap ${formatTimer(remaining ?: 0L)}" } ?: "Slaaptimer",
        onClick = onOpenSleepTimer,
        active = timer != null,
        modifier = Modifier.weight(1f),
      )
      CompactAction(
        icon = Icons.Default.BookmarkBorder,
        label = "Bookmarks",
        onClick = onOpenBookmarks,
        modifier = Modifier.weight(1f),
      )
    }
    Spacer(Modifier.height(16.dp))
  }
}

@Composable
private fun CompactAction(
  icon: ImageVector,
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  active: Boolean = false,
) {
  val contentColor =
    if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 2.dp, vertical = 6.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = contentColor,
      modifier = Modifier.size(scaledIconSize(24.dp)),
    )
    Spacer(Modifier.height(2.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = contentColor,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
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
        itemsIndexed(
          visible,
          key = { index, nav ->
            "toc-$index-${nav.globalMs}-${nav.smilPath}-${nav.fragment}"
          },
        ) { _, nav ->
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
  manual: List<ManualBookmark>,
  currentLabel: String?,
  onDismiss: () -> Unit,
  onSelect: (BookmarkPoint) -> Unit,
  onDelete: (String) -> Unit,
  onDeleteAll: () -> Unit,
  onDeleteManual: (String) -> Unit,
  onDeleteAllManual: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var pendingDelete by remember { mutableStateOf<BookmarkSession?>(null) }
  var pendingDeleteManual by remember { mutableStateOf<ManualBookmark?>(null) }
  var pendingDeleteAll by remember { mutableStateOf(false) }
  var pendingDeleteAllManual by remember { mutableStateOf(false) }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
      Text(
        "Bookmarks",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        "Eigen bookmarks bewaar je op de huidige plek. Daarnaast maakt de speler " +
          "automatisch een markering bij play/pause, de slaaptimer en het wisselen van boek.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(12.dp))
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
      ) {
        item(key = "manual-header") {
          BookmarkSectionHeader(
            title = "Mijn bookmarks",
            count = manual.size,
            onDeleteAll = if (manual.isNotEmpty()) {
              { pendingDeleteAllManual = true }
            } else {
              null
            },
          )
        }
        if (manual.isEmpty()) {
          item(key = "manual-empty") {
            Text(
              "Nog geen eigen bookmarks. Tik in de speler op ‘Markeren’ " +
                "om de huidige plek te bewaren.",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(vertical = 12.dp),
            )
          }
        } else {
          items(manual, key = { "manual-${it.id}" }) { bookmark ->
            ManualBookmarkRow(
              bookmark = bookmark,
              onClick = { onSelect(bookmark.point) },
              onDelete = { pendingDeleteManual = bookmark },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
          }
        }

        item(key = "sessions-header") {
          Spacer(Modifier.height(16.dp))
          BookmarkSectionHeader(
            title = "Automatische bookmarks",
            count = sessions.size,
            onDeleteAll = if (sessions.isNotEmpty()) {
              { pendingDeleteAll = true }
            } else {
              null
            },
          )
          Text(
            "Bij de play/pause-knop, de slaaptimer en het teruggaan naar de boekenplank " +
              "wordt automatisch een markering gemaakt. De vijf meest recente sessies " +
              "worden bewaard.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(4.dp))
        }
        if (sessions.isEmpty()) {
          item(key = "sessions-empty") {
            Text(
              "Nog geen luistersessies. Start het boek om de eerste bookmark te maken.",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(vertical = 12.dp),
            )
          }
        } else {
          items(sessions, key = { "session-${it.id}" }) { session ->
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
                    modifier = Modifier.size(scaledIconSize(20.dp)),
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
                      "boekwissel" -> "Stop (boekwissel)"
                      "SWITCH" -> "Stop (ander boek)"
                      "REMOVE" -> "Stop (boek verwijderd)"
                      "STALE" -> "Stop (oude sessie)"
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

  pendingDeleteManual?.let { bookmark ->
    AlertDialog(
      onDismissRequest = { pendingDeleteManual = null },
      title = { Text("Bookmark verwijderen?") },
      text = {
        Text(
          "De bookmark van ${formatDate(bookmark.point.createdAtEpochMs)} " +
            "wordt verwijderd."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteManual(bookmark.id)
            pendingDeleteManual = null
          }
        ) {
          Text("Verwijderen")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteManual = null }) { Text("Annuleren") }
      },
    )
  }

  if (pendingDeleteAll) {
    AlertDialog(
      onDismissRequest = { pendingDeleteAll = false },
      title = { Text("Alle automatische bookmarks verwijderen?") },
      text = {
        Text(
          "Alle ${sessions.size} automatische bookmarks van dit boek worden verwijderd. " +
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

  if (pendingDeleteAllManual) {
    AlertDialog(
      onDismissRequest = { pendingDeleteAllManual = false },
      title = { Text("Alle eigen bookmarks verwijderen?") },
      text = {
        Text(
          "Alle ${manual.size} eigen bookmarks van dit boek worden verwijderd. " +
            "Dit kan niet ongedaan worden gemaakt."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteAllManual()
            pendingDeleteAllManual = false
          }
        ) {
          Text("Alles verwijderen")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteAllManual = false }) { Text("Annuleren") }
      },
    )
  }
}

@Composable
private fun BookmarkSectionHeader(
  title: String,
  count: Int,
  onDeleteAll: (() -> Unit)?,
) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(
      "$title ($count)",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.weight(1f),
    )
    if (onDeleteAll != null) {
      TextButton(onClick = onDeleteAll) {
        Icon(
          Icons.Default.DeleteSweep,
          contentDescription = null,
          modifier = Modifier.size(scaledIconSize(18.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text("Alles wissen")
      }
    }
  }
}

@Composable
private fun ManualBookmarkRow(
  bookmark: ManualBookmark,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      Icons.Default.Bookmark,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(scaledIconSize(20.dp)),
    )
    Spacer(Modifier.width(12.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        formatDuration(bookmark.point.globalMs),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
      )
      bookmark.note?.let { note ->
        Text(
          note,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      val contextLabel = bookmark.point.label
      if (!contextLabel.isNullOrBlank() && contextLabel != bookmark.note) {
        Text(
          contextLabel,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        "Toegevoegd ${formatDate(bookmark.point.createdAtEpochMs)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    IconButton(onClick = onDelete) {
      Icon(
        Icons.Default.Delete,
        contentDescription = "Bookmark verwijderen",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(scaledIconSize(20.dp)),
      )
    }
  }
}

@Composable
private fun AddBookmarkDialog(
  positionMs: Long,
  positionLabel: String?,
  onDismiss: () -> Unit,
  onConfirm: (String?) -> Unit,
) {
  var note by remember { mutableStateOf("") }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Bookmark toevoegen") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          buildString {
            append("Op ")
            append(formatDuration(positionMs))
            positionLabel?.let { append(" · $it") }
          },
          style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
          value = note,
          onValueChange = { note = it },
          label = { Text("Notitie (optioneel)") },
          maxLines = 3,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onConfirm(note) }) { Text("Toevoegen") }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Annuleren") }
    },
  )
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
    if (timer == null) return@LaunchedEffect
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedSheet(
  speed: Float,
  onDismiss: () -> Unit,
  onSelect: (Float) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
      Text(
        "Afspeelsnelheid",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        "0,5× is twee keer zo langzaam, 2,0× twee keer zo snel. " +
          "De stem blijft op dezelfde toonhoogte.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(16.dp))
      Text(
        "Nu: ${PlaybackSpeedOptions.format(speed)}",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(12.dp))
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PlaybackSpeedOptions.PRESETS.chunked(4).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { option ->
              FilterChip(
                selected = abs(speed - option) < 0.01f,
                onClick = { onSelect(option) },
                label = { Text(PlaybackSpeedOptions.format(option), maxLines = 1) },
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }
      Spacer(Modifier.height(8.dp))
      TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text("Klaar")
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
