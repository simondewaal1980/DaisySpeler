package nl.simon.daisyspeler

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import nl.simon.daisyspeler.ui.theme.DaisySpelerTheme

class MainActivity : ComponentActivity() {
  private val notificationPermissionLauncher =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    maybeAskForNotifications()
    val container = (application as DaisyApplication).container
    setContent {
      DaisySpelerTheme {
        MainNavigation(
          container = container,
          onExit = { finishAndRemoveTask() },
        )
      }
    }
  }

  private fun maybeAskForNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    if (preferences.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) return
    preferences.edit { putBoolean(KEY_NOTIFICATIONS_ASKED, true) }
    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
  }

  private companion object {
    const val PREFS_NAME = "daisy_permissions"
    const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
  }
}
