package com.remmi.browser.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoView
import java.io.File
import java.io.FileOutputStream

/**
 * TabThumbnailManager
 * Memory-efficient, lifecycle-aware thumbnail caching system for tab switcher.
 * - In-Memory LruCache for instant rendering
 * - Disk file persistence in app cache directory
 * - Reactive state flow to trigger Composable recompositions
 * - Non-blocking async I/O
 */
class TabThumbnailManager private constructor(private val context: Context) {

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val thumbnailDir = File(context.cacheDir, "tab_thumbnails").apply { mkdirs() }

  // LRU memory cache: 1/8th of available app memory
  private val memoryCache: LruCache<String, Bitmap>

  // Version tracker to notify Jetpack Compose when a thumbnail is updated
  private val _thumbnailVersions = MutableStateFlow<Map<String, Long>>(emptyMap())
  val thumbnailVersions: StateFlow<Map<String, Long>> = _thumbnailVersions.asStateFlow()

  init {
    val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    val cacheSizeKb = (maxMemoryKb / 8).coerceIn(1024 * 8, 1024 * 40) // 8MB to 40MB
    memoryCache = object : LruCache<String, Bitmap>(cacheSizeKb) {
      override fun sizeOf(key: String, bitmap: Bitmap): Int {
        return bitmap.byteCount / 1024
      }
    }
  }

  fun getThumbnail(tabId: String): Bitmap? {
    // 1. Check in-memory LRU Cache
    val cached = memoryCache.get(tabId)
    if (cached != null && !cached.isRecycled) {
      return cached
    }

    // 2. Try loading from disk synchronously if exists (lightweight thumbnail)
    val file = File(thumbnailDir, "thumb_$tabId.jpg")
    if (file.exists() && file.length() > 0) {
      try {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        if (bitmap != null) {
          memoryCache.put(tabId, bitmap)
          return bitmap
        }
      } catch (e: Throwable) {
        Log.w(TAG, "Failed to load disk thumbnail for $tabId: ${e.message}")
      }
    }
    return null
  }

  fun saveThumbnail(tabId: String, rawBitmap: Bitmap) {
    if (rawBitmap.isRecycled) return

    val scaled = scaleToThumbnail(rawBitmap, TARGET_WIDTH)
    memoryCache.put(tabId, scaled)

    // Update reactive version trigger
    val now = System.currentTimeMillis()
    _thumbnailVersions.value = _thumbnailVersions.value + (tabId to now)

    // Asynchronously persist compressed JPEG to disk
    scope.launch {
      try {
        val file = File(thumbnailDir, "thumb_$tabId.jpg")
        FileOutputStream(file).use { out ->
          scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        }
      } catch (e: Exception) {
        Log.w(TAG, "Failed to save thumbnail to disk for $tabId: ${e.message}")
      }
    }
  }

  fun captureGeckoView(tabId: String, geckoView: GeckoView) {
    try {
      geckoView.capturePixels()
        .accept(
          { bitmap ->
            if (bitmap != null && !bitmap.isRecycled) {
              saveThumbnail(tabId, bitmap)
            }
          },
          { error ->
            Log.d(TAG, "capturePixels notice on tab $tabId: ${error?.message}")
          }
        )
    } catch (e: Exception) {
      Log.d(TAG, "captureGeckoView error on tab $tabId: ${e.message}")
    }
  }

  fun removeThumbnail(tabId: String) {
    memoryCache.remove(tabId)
    _thumbnailVersions.value = _thumbnailVersions.value - tabId
    scope.launch {
      try {
        val file = File(thumbnailDir, "thumb_$tabId.jpg")
        if (file.exists()) file.delete()
      } catch (_: Exception) {}
    }
  }

  fun clearAll() {
    memoryCache.evictAll()
    _thumbnailVersions.value = emptyMap()
    scope.launch {
      try {
        thumbnailDir.listFiles()?.forEach { it.delete() }
      } catch (_: Exception) {}
    }
  }

  private fun scaleToThumbnail(source: Bitmap, targetWidth: Int): Bitmap {
    if (source.width <= targetWidth && source.height <= targetWidth * 2) {
      return source
    }
    val aspect = source.height.toFloat() / source.width.toFloat()
    val targetHeight = (targetWidth * aspect).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
  }

  companion object {
    private const val TAG = "TabThumbnailManager"
    private const val TARGET_WIDTH = 480

    @Volatile
    private var INSTANCE: TabThumbnailManager? = null

    fun getInstance(context: Context): TabThumbnailManager {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: TabThumbnailManager(context.applicationContext).also { INSTANCE = it }
      }
    }
  }
}
