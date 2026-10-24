package nl.simon.daisyspeler

import android.app.Application

class DaisyApplication : Application() {
  lateinit var container: AppContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = AppContainer(this)
  }
}
