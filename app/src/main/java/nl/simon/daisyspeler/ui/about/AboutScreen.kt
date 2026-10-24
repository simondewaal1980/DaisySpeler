package nl.simon.daisyspeler.ui.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.simon.daisyspeler.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val versionName = remember(context) { context.appVersionName() }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Over DaisySpeler") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
          }
        },
      )
    },
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      AppIcon()
      Spacer(Modifier.height(20.dp))
      Text(
        "DaisySpeler",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        "door Simon de Waal van Breukelen",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (!versionName.isNullOrBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
          "Versie $versionName",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      Spacer(Modifier.height(28.dp))
      Text(
        "DaisySpeler is een eenvoudige speler voor DAISY 2.02-luisterboeken, met een " +
          "Nederlandse interface. Importeer een uitgepakte map of een ZIP-bestand en ga " +
          "verder waar je gebleven was.",
        style = MaterialTheme.typography.bodyLarge,
      )

      Spacer(Modifier.height(24.dp))
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
          CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
      ) {
        Column(modifier = Modifier.padding(20.dp)) {
          Text(
            "Licentie",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Spacer(Modifier.height(8.dp))
          Text(
            "DaisySpeler is open source software onder de GPLv3-licentie " +
              "(GNU General Public License, versie 3).",
            style = MaterialTheme.typography.bodyMedium,
          )
          Spacer(Modifier.height(8.dp))
          Text(
            "Je mag de app gebruiken, aanpassen en verspreiden volgens de voorwaarden van " +
              "deze licentie.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun AppIcon() {
  Box(modifier = Modifier.size(96.dp).clip(CircleShape)) {
    Image(
      painter = painterResource(R.drawable.ic_launcher_background),
      contentDescription = null,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
    )
    Image(
      painter = painterResource(R.drawable.ic_launcher_foreground),
      contentDescription = null,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

private fun Context.appVersionName(): String? =
  runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    } else {
      @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName
    }
  }
    .getOrNull()
