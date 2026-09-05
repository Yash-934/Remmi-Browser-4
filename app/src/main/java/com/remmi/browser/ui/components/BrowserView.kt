package com.remmi.browser.ui.components

import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.remmi.browser.downloads.DownloadHandler
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.engine.GeckoEngineManager
import com.remmi.browser.engine.GeckoTabCallbacks
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.reader.ReaderArticle
import com.remmi.browser.reader.ReaderExtractor
import com.remmi.browser.security.CurrentTorRoute
import com.remmi.browser.security.NetworkHardening
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebResponse

@Composable
fun BrowserView(
  tab: BrowserTab,
  onUrlChange: (String) -> Unit,
  onTitleChange: (String) -> Unit,
  onProgressChange: (Int) -> Unit,
  onLoadingChange: (Boolean) -> Unit,
  onSecurityChange: (Boolean) -> Unit,
  onNavStateChange: (canGoBack: Boolean, canGoForward: Boolean) -> Unit,
  onTrackerBlocked: (url: String, type: String) -> Unit,
  onReaderArticleExtracted: ((ReaderArticle?) -> Unit)? = null,
  onContextMenuRequested: ((WebContextMenuData) -> Unit)? = null,
  onScrollChange: ((isScrollingDown: Boolean) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val geckoEngine = remember { GeckoEngineManager.getInstance(context) }
  val downloadHandler = remember { DownloadHandler.getInstance(context) }

  var geckoViewRef by remember { mutableStateOf<GeckoView?>(null) }
  var isCurrentlyLoading by remember(tab.id) { mutableStateOf(tab.isLoading) }
  var progressFloat by remember(tab.id) { mutableFloatStateOf(0f) }
  val animatedProgress by animateFloatAsState(
    targetValue = if (isCurrentlyLoading || tab.isLoading) progressFloat else 0f,
    label = "cyber_progress",
  )

  var lastNavigatedUrl by remember(tab.id) { mutableStateOf(geckoEngine.getLastDispatchedUrl(tab.id) ?: "") }
  val isViewAttached by geckoEngine.getViewAttachmentState(tab.id).collectAsState()

  val currentOnUrlChange by rememberUpdatedState(onUrlChange)
  val currentOnTitleChange by rememberUpdatedState(onTitleChange)
  val currentOnProgressChange by rememberUpdatedState(onProgressChange)
  val currentOnLoadingChange by rememberUpdatedState(onLoadingChange)
  val currentOnSecurityChange by rememberUpdatedState(onSecurityChange)
  val currentOnNavStateChange by rememberUpdatedState(onNavStateChange)
  val currentOnTrackerBlocked by rememberUpdatedState(onTrackerBlocked)
  val currentOnScrollChange by rememberUpdatedState(onScrollChange)
  val currentOnReaderArticleExtracted by rememberUpdatedState(onReaderArticleExtracted)
  val currentOnContextMenuRequested by rememberUpdatedState(onContextMenuRequested)

  // Callbacks bundle decoupled from GeckoSession
  val tabCallbacks = remember(tab.id, tab.profile) {
    object : GeckoTabCallbacks {
      override fun onUrlChange(url: String) {
        lastNavigatedUrl = url
        currentOnUrlChange(url)
      }

      override fun onTitleChange(title: String) {
        currentOnTitleChange(title)
      }

      override fun onProgressChange(progress: Int) {
        if ((isCurrentlyLoading || tab.isLoading) && progress > 0) {
          progressFloat = (progress.toFloat() / 100f).coerceIn(0f, 1f)
          currentOnProgressChange(progress)
        } else {
          progressFloat = 0f
          currentOnProgressChange(0)
        }
      }

      override fun onLoadingChange(isLoading: Boolean) {
        isCurrentlyLoading = isLoading
        currentOnLoadingChange(isLoading)
        if (isLoading) {
          progressFloat = 0.1f
        } else {
          progressFloat = 0f
          android.util.Log.i("AppStartup", "STATE_LOG: FIRST_PAGE_STOP (time=${android.os.SystemClock.elapsedRealtime()})")
          // Page load completed, capture preview thumbnail safely
          geckoViewRef?.let { gv ->
            com.remmi.browser.engine.TabThumbnailManager.getInstance(context).captureGeckoView(tab.id, gv)
          }
        }
      }

      override fun onSecurityChange(isSecure: Boolean) {
        currentOnSecurityChange(isSecure)
      }

      override fun onNavStateChange(canGoBack: Boolean, canGoForward: Boolean) {
        currentOnNavStateChange(canGoBack, canGoForward)
      }

      override fun onTrackerBlocked(url: String, type: String) {
        currentOnTrackerBlocked(url, type)
      }

      override fun onScrollChanged(scrollX: Int, scrollY: Int, isScrollingDown: Boolean) {
        currentOnScrollChange?.invoke(isScrollingDown)
      }

      override fun onExternalResponse(response: WebResponse) {
        val uri = response.uri
        val contentDisposition = response.headers["Content-Disposition"]
        val filename = contentDisposition?.substringAfter("filename=")?.trim('"', '\'', ' ')
          ?: uri.substringAfterLast('/').substringBefore('?').ifEmpty { "download_${System.currentTimeMillis()}" }
        val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
        val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: 0L

        downloadHandler.enqueueDownload(
          url = uri,
          suggestedFilename = filename,
          mimeType = contentType,
          contentLength = contentLength,
          isGhost = tab.profile == PrivacyProfile.GHOST,
          webResponse = response
        )
      }

      override fun onContextMenu(data: WebContextMenuData) {
        currentOnContextMenuRequested?.invoke(data)
      }
    }
  }

  // Explicit lifecycle observer to re-activate tab session safely on app resume/pause
  DisposableEffect(tab.id) {
    val sessId = geckoEngine.getSession(tab.id)?.let { "0x" + Integer.toHexString(System.identityHashCode(it)) } ?: "none"
    val mountMsg = "[FORENSIC][BROWSER_VIEW_MOUNT] tabId=${tab.id} session=$sessId url=${tab.url} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
    android.util.Log.i("BrowserView", mountMsg)
    com.remmi.browser.util.DebugLogManager.log(mountMsg)
    onDispose {
      val unmountCaller = try { Thread.currentThread().stackTrace.take(6).joinToString(" -> ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } } catch (_: Exception) { "unknown" }
      val unmountMsg = "[FORENSIC][BROWSER_VIEW_UNMOUNT] tabId=${tab.id} session=$sessId url=${tab.url} reason=BrowserView_disposed caller=$unmountCaller elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      android.util.Log.i("BrowserView", unmountMsg)
      com.remmi.browser.util.DebugLogManager.log(unmountMsg)
    }
  }

  DisposableEffect(lifecycleOwner, tab.id) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> {
          scope.launch {
            geckoEngine.setTabActive(tab.id, true)
            geckoViewRef?.let { gv ->
              gv.visibility = View.VISIBLE
              geckoEngine.attachView(
                tabId = tab.id,
                geckoView = gv,
                profile = tab.profile,
                isDesktopMode = tab.isDesktopMode,
                securityLevel = tab.securityLevel,
                containerType = tab.containerType,
                callbacks = tabCallbacks,
              )
            }
          }
        }
        Lifecycle.Event.ON_PAUSE -> {
          scope.launch {
            geckoEngine.setTabActive(tab.id, false)
          }
        }
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // Synchronize tab settings with the underlying GeckoSession
  LaunchedEffect(tab.id, tab.profile, tab.isDesktopMode, tab.securityLevel, tab.containerType) {
    geckoEngine.updateTabSettings(tab.id, tab.isDesktopMode, tab.profile, tab.securityLevel)
  }

  // Handle URL navigation safely through GeckoEngineManager without feedback loops
  // Invariant: ONLY loadUrl when view attachment is complete
  LaunchedEffect(tab.url, isViewAttached) {
    if (!isViewAttached) return@LaunchedEffect
    if (tab.url.isNotBlank() && tab.url != lastNavigatedUrl) {
      lastNavigatedUrl = tab.url
      if (tab.url == "about:blank" || tab.url == "remmi://newtab" || tab.url == "about:home") {
        geckoEngine.resetToNewTab(tab.id)
      } else {
        geckoEngine.loadUrl(tab.id, tab.url)
      }
    }
  }

  // Handle Reader Mode trigger with real web content extraction
  LaunchedEffect(tab.isReaderMode, tab.url) {
    if (tab.isReaderMode && tab.readerArticle == null && tab.url.isNotBlank() && tab.url != "about:blank") {
      try {
        val blockExtension = geckoEngine.blockExtension
        val htmlDeferred = CompletableDeferred<String>()
        
        blockExtension.extractTabHtml(
          tabId = tab.id,
          callback = { extractedUrl, html ->
            if (extractedUrl == tab.url || html.isNotEmpty()) {
              htmlDeferred.complete(html)
            }
          }
        )

        val pageHtml = withTimeoutOrNull(2500L) { htmlDeferred.await() }

        val extracted = if (!pageHtml.isNullOrBlank()) {
          val domain = try {
            java.net.URI(tab.url).host ?: tab.url.substringAfter("://").substringBefore('/')
          } catch (e: Exception) {
            tab.url.substringAfter("://").substringBefore('/')
          }
          ReaderExtractor.parseHtmlDocument(pageHtml, tab.url, tab.title, domain)
        } else {
          val isGhost = tab.profile == PrivacyProfile.GHOST
          ReaderExtractor.extractFromUrl(context, tab.url, tab.title, isGhost)
        }

        onReaderArticleExtracted?.invoke(extracted)
      } catch (e: Exception) {
        Log.e("BrowserView", "Failed to extract clean reader article for ${tab.url}", e)
        onReaderArticleExtracted?.invoke(null)
      }
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
  ) {
    val isNewTab = tab.url.isBlank() || tab.url == "about:blank" || tab.url == "remmi://newtab"

    if (isNewTab) {
      NewTabPage(
        profile = tab.profile,
        blockedTrackersCount = tab.blockedTrackersCount,
        onNavigate = { target ->
          onUrlChange(target)
        },
        modifier = Modifier.fillMaxSize()
      )
    } else {
      AndroidView(
        factory = { ctx ->
          val gv = GeckoView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.VISIBLE
            isFocusable = true
            isFocusableInTouchMode = true
            isNestedScrollingEnabled = false
            tag = tab.id
            geckoViewRef = this
          }
          val gvId = "0x" + Integer.toHexString(System.identityHashCode(gv))
          val now = android.os.SystemClock.elapsedRealtime()
          val msg = "[FORENSIC][VIEW_FACTORY] tabId=${tab.id} view=$gvId tag=${gv.tag} url=${tab.url} elapsedRealtime=$now"
          android.util.Log.i("BrowserView", msg)
          com.remmi.browser.util.DebugLogManager.log(msg)

          scope.launch {
            geckoEngine.attachView(
              tabId = tab.id,
              geckoView = gv,
              profile = tab.profile,
              isDesktopMode = tab.isDesktopMode,
              securityLevel = tab.securityLevel,
              containerType = tab.containerType,
              callbacks = tabCallbacks,
            )
          }
          gv
        },
        update = { geckoView ->
          geckoViewRef = geckoView
          val gvId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
          val now = android.os.SystemClock.elapsedRealtime()
          val prevTag = geckoView.tag as? String
          val isTagMatch = prevTag == tab.id
          val updateMsg = "[FORENSIC][VIEW_UPDATE] tabId=${tab.id} view=$gvId tag=$prevTag isTagMatch=$isTagMatch url=${tab.url} elapsedRealtime=$now"
          android.util.Log.i("BrowserView", updateMsg)
          com.remmi.browser.util.DebugLogManager.log(updateMsg)

          if (!isTagMatch) {
            geckoEngine.logDestructiveOp("TAG_MISMATCH_DETACH", tab.id, null, geckoView, tab.url, "update isTagMatch=false prevTag=$prevTag")
            geckoEngine.checkPostNavFailure(tab.id, "TAG_MISMATCH_DETACH", tab.url)
            val oldTabId = prevTag
            geckoView.visibility = View.VISIBLE
            scope.launch {
              if (oldTabId != null) {
                geckoEngine.detachView(oldTabId, geckoView)
              }
              geckoView.tag = tab.id
              geckoEngine.attachView(
                tabId = tab.id,
                geckoView = geckoView,
                profile = tab.profile,
                isDesktopMode = tab.isDesktopMode,
                securityLevel = tab.securityLevel,
                containerType = tab.containerType,
                callbacks = tabCallbacks,
              )
            }
          }
        },
        onRelease = { geckoView ->
          val currentTag = geckoView.tag as? String ?: tab.id
          val gvId = "0x" + Integer.toHexString(System.identityHashCode(geckoView))
          val now = android.os.SystemClock.elapsedRealtime()
          val relMsg = "[FORENSIC][VIEW_ON_RELEASE] tabId=${tab.id} view=$gvId tag=$currentTag url=${tab.url} elapsedRealtime=$now"
          android.util.Log.i("BrowserView", relMsg)
          com.remmi.browser.util.DebugLogManager.log(relMsg)

          geckoEngine.logDestructiveOp("VIEW_ON_RELEASE", tab.id, null, geckoView, tab.url, "AndroidView.onRelease")
          geckoEngine.checkPostNavFailure(tab.id, "VIEW_ON_RELEASE", tab.url)

          com.remmi.browser.engine.TabThumbnailManager.getInstance(context).captureGeckoView(tab.id, geckoView)
          geckoViewRef = null
          // Synchronous detach cleanly removes ownership from attachedViews before clearing view tag
          geckoEngine.detachViewSync(currentTag, geckoView)
        },
        modifier = Modifier
          .fillMaxSize()
          .testTag("gecko_browser_view"),
      )
    }

    // Top Glowing Cyber Progress Bar
    if ((tab.isLoading || isCurrentlyLoading) && progressFloat > 0f && animatedProgress > 0f) {
      LinearProgressIndicator(
        progress = { animatedProgress },
        modifier = Modifier
          .fillMaxWidth()
          .height(3.dp)
          .align(Alignment.TopCenter)
          .testTag("cyber_loading_progress_bar"),
        color = if (tab.profile == PrivacyProfile.GHOST) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary,
        trackColor = ThemeCyber.colors.surfaceLight,
      )
    }
  }
}
