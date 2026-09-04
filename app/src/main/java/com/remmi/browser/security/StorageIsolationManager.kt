package com.remmi.browser.security

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.StorageController

/**
 * StorageIsolationManager
 * Manages first-party storage isolation, context boundaries, and granular site data clearing.
 */
class StorageIsolationManager private constructor(private val context: Context) {

  companion object {
    private const val TAG = "StorageIsolationMgr"

    @Volatile
    private var instance: StorageIsolationManager? = null

    fun getInstance(context: Context): StorageIsolationManager {
      return instance ?: synchronized(this) {
        instance ?: StorageIsolationManager(context.applicationContext).also { instance = it }
      }
    }
  }

  /**
   * Generates an isolated container context ID.
   */
  fun getContainerContextId(tabId: String, containerType: ContainerType): String {
    return "${containerType.prefix}_${tabId}"
  }

  /**
   * Clears site data for a specific origin or all data.
   */
  suspend fun clearSiteData(
    runtime: GeckoRuntime,
    flags: Long = StorageController.ClearFlags.ALL
  ) = withContext(Dispatchers.Main) {
    try {
      runtime.storageController.clearData(flags).accept(
        { Log.i(TAG, "Site data cleared successfully (flags: $flags)") },
        { err: Throwable? -> Log.e(TAG, "Failed to clear site data", err) }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Exception during clearSiteData", e)
    }
  }

  /**
   * Clears cookies and storage for a specific host.
   */
  suspend fun clearSiteDataForHost(
    runtime: GeckoRuntime,
    host: String,
    flags: Long = StorageController.ClearFlags.ALL
  ) = withContext(Dispatchers.Main) {
    try {
      runtime.storageController.clearDataFromHost(host, flags).accept(
        { Log.i(TAG, "Site data cleared for host: $host") },
        { err: Throwable? -> Log.e(TAG, "Failed to clear site data for host: $host", err) }
      )
    } catch (e: Exception) {
      Log.e(TAG, "Exception during clearSiteDataForHost: $host", e)
    }
  }
}
