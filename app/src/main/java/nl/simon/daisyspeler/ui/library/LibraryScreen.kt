package nl.simon.daisyspeler.ui.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import nl.simon.daisyspeler.AppContainer
import nl.simon.daisyspeler.data.storage.LibraryEntry
import nl.simon.daisyspeler.data.storage.LibrarySourceType
import nl.simon.daisyspeler.ui.components.BookCover
import nl.simon.daisyspeler.ui.theme.DefaultIconSize
import nl.simon.daisyspeler.ui.theme.scaledIconSize
import nl.simon.daisyspeler.util.formatDuration

private val ZIP_MIME_TYPES =
  arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/x-zip",
    "application/octet-stream",
  )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
  container: AppContainer,
  onOpenBook: (String) -> Unit,
  onOpenCoverBrowser: (String) -> Unit,
  onOpenAbout: () -> Unit,
) {
  val viewModel: LibraryViewModel = viewModel { LibraryViewModel(container) }
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val largeIcons by container.uiPreferences.largeIcons.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  var deleteTarget by remember { mutableStateOf<LibraryEntry?>(null) }
  var showAddSheet by remember { mutableStateOf(false) }

  val folderPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        runCatching {
          context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
          )
        }
        viewModel.importTree(uri)
      }
    }

  val zipPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        viewModel.importZip(uri)
      }
    }

  LaunchedEffect(state.error) {
    val message = state.error
    if (message != null) {
      snackbarHostState.showSnackbar(message)
      viewModel.clearError()
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("DaisySpeler") },
        actions = {
          IconButton(
            onClick = { container.uiPreferences.toggleLargeIcons() },
            modifier =
              Modifier.semantics {
                stateDescription = if (largeIcons) "Iconen vergroot" else "Iconen normaal"
              },
          ) {
            Icon(
              imageVector = if (largeIcons) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
              contentDescription =
                if (largeIcons) "Iconen verkleinen" else "Iconen vergroten",
              modifier = Modifier.size(scaledIconSize(DefaultIconSize)),
            )
          }
          IconButton(onClick = onOpenAbout) {
            Icon(
              Icons.Default.Info,
              contentDescription = "Over DaisySpeler",
              modifier = Modifier.size(scaledIconSize(DefaultIconSize)),
            )
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = { showAddSheet = true },
        icon = {
          Icon(
            Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.size(scaledIconSize(DefaultIconSize)),
          )
        },
        text = { Text("Boek toevoegen") },
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      if (state.entries.isEmpty()) {
        EmptyLibrary(onAdd = { showAddSheet = true })
      } else {
        LazyColumn(
          contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          item {
            Row(
              modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "Mijn boeken",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
              )
              SortButton(
                sortOrder = state.sortOrder,
                onSelect = viewModel::setSortOrder,
              )
            }
          }
          items(state.entries, key = { it.id }) { entry ->
            BookCard(
              entry = entry,
              onOpen = { onOpenBook(entry.id) },
              onFindCover = { onOpenCoverBrowser(entry.id) },
              onDelete = { deleteTarget = entry },
              onEnsureCover = { viewModel.ensureCover(entry) },
            )
          }
        }
      }

      if (state.importing) {
        Box(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Card(shape = RoundedCornerShape(24.dp)) {
            Row(
              modifier = Modifier.padding(24.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              CircularProgressIndicator()
              Column {
                Text("Boek inlezen", fontWeight = FontWeight.SemiBold)
                Text(
                  "De DAISY-structuur wordt uitgelezen…",
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }
        }
      }
    }
  }

  deleteTarget?.let { entry ->
    AlertDialog(
      onDismissRequest = { deleteTarget = null },
      title = { Text("Boek verwijderen?") },
      text = {
        Text(
          if (entry.sourceType == LibrarySourceType.ZIP) {
            "“${entry.title}” wordt uit de bibliotheek verwijderd, inclusief de uitgepakte bestanden."
          } else {
            "“${entry.title}” wordt uit de bibliotheek verwijderd. De bestanden blijven op je apparaat staan."
          }
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.remove(entry)
            deleteTarget = null
          }
        ) { Text("Verwijderen") }
      },
      dismissButton = {
        TextButton(onClick = { deleteTarget = null }) { Text("Annuleren") }
      },
    )
  }

  if (showAddSheet) {
    ModalBottomSheet(
      onDismissRequest = { showAddSheet = false },
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(
          "Boek toevoegen",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
          "Kies een uitgepakte DAISY 2.02-map of een ZIP-bestand met daarin het boek.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
          onClick = {
            showAddSheet = false
            folderPicker.launch(null)
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Map kiezen")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
          onClick = {
            showAddSheet = false
            zipPicker.launch(ZIP_MIME_TYPES)
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("ZIP-bestand kiezen")
        }
        Spacer(Modifier.height(24.dp))
      }
    }
  }
}

@Composable
private fun SortButton(
  sortOrder: LibrarySortOrder,
  onSelect: (LibrarySortOrder) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    TextButton(
      onClick = { expanded = true },
      modifier =
        Modifier.semantics { stateDescription = "Nu ${sortOrder.label.lowercase()}" },
    ) {
      Icon(
        Icons.AutoMirrored.Filled.Sort,
        contentDescription = null,
        modifier = Modifier.size(scaledIconSize(18.dp)),
      )
      Spacer(Modifier.width(6.dp))
      Text("Sorteren")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      LibrarySortOrder.entries.forEach { option ->
        DropdownMenuItem(
          text = { Text(option.label) },
          leadingIcon = {
            RadioButton(
              selected = option == sortOrder,
              onClick = null,
            )
          },
          onClick = {
            expanded = false
            onSelect(option)
          },
        )
      }
    }
  }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = Icons.Default.PlayArrow,
      contentDescription = null,
      modifier = Modifier.size(scaledIconSize(64.dp)),
      tint = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(16.dp))
    Text(
      "Nog geen boeken",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
      "Voeg een uitgepakt DAISY 2.02-boek of een ZIP-bestand toe.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onAdd) { Text("Map of ZIP toevoegen") }
  }
}

@Composable
private fun BookCard(
  entry: LibraryEntry,
  onOpen: () -> Unit,
  onFindCover: () -> Unit,
  onDelete: () -> Unit,
  onEnsureCover: () -> Unit,
) {
  val lastPosition = entry.lastPosition
  val progress =
    lastPosition?.let { position ->
      if (entry.durationMs > 0) (position.globalMs.toFloat() / entry.durationMs).coerceIn(0f, 1f)
      else 0f
    }

  LaunchedEffect(entry.id, entry.coverPath) {
    if (entry.coverPath == null) onEnsureCover()
  }

  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        BookCover(
          coverPath = entry.coverPath,
          contentDescription = "Omslag van ${entry.title}",
          modifier = Modifier.width(56.dp).height(80.dp),
        ) {
          Icon(
            Icons.Default.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(scaledIconSize(32.dp)),
          )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            entry.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          if (!entry.author.isNullOrBlank()) {
            Text(
              entry.author,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        IconButton(onClick = onFindCover) {
          Icon(
            Icons.Default.ImageSearch,
            contentDescription = "Omslag zoeken",
            modifier = Modifier.size(scaledIconSize(DefaultIconSize)),
          )
        }
        IconButton(onClick = onDelete) {
          Icon(
            Icons.Default.Delete,
            contentDescription = "Boek verwijderen",
            modifier = Modifier.size(scaledIconSize(DefaultIconSize)),
          )
        }
      }

      Spacer(Modifier.height(12.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          formatDuration(entry.durationMs),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
          "· ${entry.tocCount} navigatiepunten",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (entry.sourceType == LibrarySourceType.ZIP) {
          Spacer(Modifier.width(8.dp))
          Text(
            "ZIP",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Spacer(Modifier.weight(1f))
        Icon(
          Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(scaledIconSize(18.dp)),
        )
      }

      if (progress != null) {
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
          progress = { progress },
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
          "Verder vanaf ${formatDuration(lastPosition?.globalMs ?: 0L)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
