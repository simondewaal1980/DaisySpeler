package nl.simon.daisyspeler.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows the cached cover of a book. The image is decoded off the main thread and kept in a small
 * in-memory cache so scrolling through the library does not decode the same file repeatedly.
 */
@Composable
fun BookCover(
  coverPath: String?,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
  fallback: @Composable () -> Unit = { DefaultCoverFallback() },
) {
  val bitmap by produceState<ImageBitmap?>(
    initialValue = coverPath?.let(CoverBitmapCache::get),
    coverPath,
  ) {
    val path = coverPath
    if (path == null) {
      value = null
      return@produceState
    }
    value =
      CoverBitmapCache.get(path)
        ?: withContext(Dispatchers.IO) { loadCoverBitmap(path) }?.also {
          CoverBitmapCache.put(path, it)
        }
  }

  Box(
    modifier =
      modifier
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    val image = bitmap
    if (image != null) {
      Image(
        bitmap = image,
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      fallback()
    }
  }
}

@Composable
private fun DefaultCoverFallback() {
  Icon(
    Icons.AutoMirrored.Filled.MenuBook,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.primary,
    modifier = Modifier.fillMaxSize(0.45f),
  )
}

private object CoverBitmapCache {
  private val cache =
    object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
      override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

  fun get(path: String): ImageBitmap? = cache.get(path)

  fun put(path: String, bitmap: ImageBitmap) {
    cache.put(path, bitmap)
  }

  private const val CACHE_BYTES = 8 * 1024 * 1024
}

private fun loadCoverBitmap(path: String): ImageBitmap? {
  if (!File(path).isFile) return null

  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeFile(path, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

  var sampleSize = 1
  val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
  while (largestSide / (sampleSize * 2) >= MAX_BITMAP_SIDE) {
    sampleSize *= 2
  }

  val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
  return BitmapFactory.decodeFile(path, options)?.asImageBitmap()
}

private const val MAX_BITMAP_SIDE = 720
