package com.remmi.browser.util

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

object DefaultBrowserHelper {

  /**
   * Checks whether Remmi is currently configured as the default browser on the device.
   */
  fun isDefaultBrowser(context: Context): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
          return roleManager.isRoleHeld(RoleManager.ROLE_BROWSER)
        }
      }

      val testIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com"))
      val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.resolveActivity(
          testIntent,
          PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        )
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.resolveActivity(testIntent, PackageManager.MATCH_DEFAULT_ONLY)
      }

      val defaultPkg = resolveInfo?.activityInfo?.packageName
      defaultPkg != null && defaultPkg == context.packageName
    } catch (e: Exception) {
      false
    }
  }

  /**
   * Prompts the user with system dialogs to set Remmi as their default browser.
   */
  fun requestSetDefaultBrowser(
    activity: Activity,
    roleLauncher: ActivityResultLauncher<Intent>? = null
  ) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
          val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
          if (roleLauncher != null) {
            roleLauncher.launch(intent)
          } else {
            activity.startActivity(intent)
          }
          return
        }
      }

      // Fallback for Android 9 or below / devices where RoleManager is unavailable
      val defaultAppsIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      if (defaultAppsIntent.resolveActivity(activity.packageManager) != null) {
        activity.startActivity(defaultAppsIntent)
      } else {
        val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.parse("package:${activity.packageName}")
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(appDetailsIntent)
      }
    } catch (e: Exception) {
      Toast.makeText(
        activity,
        "Open Android Settings > Default Apps to select Remmi",
        Toast.LENGTH_LONG
      ).show()
    }
  }
}
