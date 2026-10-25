package nl.simon.daisyspeler.data.storage

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Gebruikersvoorkeuren voor de interface, zoals de weergavegrootte van iconen. */
class UiPreferences(context: Context) {
  private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _largeIcons = MutableStateFlow(preferences.getBoolean(KEY_LARGE_ICONS, false))
  val largeIcons: StateFlow<Boolean> = _largeIcons.asStateFlow()

  fun toggleLargeIcons() {
    setLargeIcons(!_largeIcons.value)
  }

  fun setLargeIcons(enabled: Boolean) {
    if (_largeIcons.value == enabled) return
    _largeIcons.value = enabled
    preferences.edit().putBoolean(KEY_LARGE_ICONS, enabled).apply()
  }

  private companion object {
    const val PREFS_NAME = "daisy_ui"
    const val KEY_LARGE_ICONS = "large_icons"
  }
}
