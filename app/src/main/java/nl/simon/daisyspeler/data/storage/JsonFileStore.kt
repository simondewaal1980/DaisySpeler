package nl.simon.daisyspeler.data.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

class JsonFileStore<T>(
  private val file: File,
  private val serializer: KSerializer<T>,
  private val default: T,
) {
  private val json =
    Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      prettyPrint = false
    }

  @Synchronized
  fun read(): T {
    if (!file.exists() || file.length() == 0L) return default
    return runCatching { json.decodeFromString(serializer, file.readText()) }
      .getOrDefault(default)
  }

  @Synchronized
  fun write(value: T) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    val text = json.encodeToString(serializer, value)
    temp.writeText(text)
    if (!temp.renameTo(file)) {
      file.writeText(text)
      temp.delete()
    }
  }
}
