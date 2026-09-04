package com.remmi.browser.engine
import android.content.Context
import android.content.Intent
import android.net.Uri

object BrowserActions {
  fun shareUrl(context: Context, url: String, title: String) {
    val sendIntent: Intent = Intent().apply {
      action = Intent.ACTION_SEND
      putExtra(Intent.EXTRA_TEXT, url)
      putExtra(Intent.EXTRA_TITLE, title)
      type = "text/plain"
    }
    val shareIntent = Intent.createChooser(sendIntent, null)
    context.startActivity(shareIntent)
  }
}
