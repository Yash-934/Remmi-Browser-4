package com.remmi.browser.ui.screens

import androidx.activity.compose.BackHandler
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.res.painterResource
import com.remmi.browser.R
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.engine.TabManager
import com.remmi.browser.reader.ReaderArticle
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.security.NetworkHardening
import com.remmi.browser.security.PrivacyNetworkController
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.RedirectInspector
import com.remmi.browser.security.TorManager
import com.remmi.browser.security.TorStatusChecker
import com.remmi.browser.storage.BookmarkItem
import com.remmi.browser.storage.HistoryItem
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.SearchEngine
import com.remmi.browser.storage.SessionTabEntity
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.storage.SpeedDialItem
import com.remmi.browser.ui.components.BrowserView
import com.remmi.browser.ui.components.CyberpunkBackground
import com.remmi.browser.model.WebContextMenuData
import com.remmi.browser.downloads.DownloadHandler
import com.remmi.browser.ui.components.CircuitVisualizerSheet
import com.remmi.browser.ui.components.DownloadsDrawer
import com.remmi.browser.ui.components.FindInPageBar
import com.remmi.browser.ui.components.GlitchText
import com.remmi.browser.ui.components.ActivityScreen
import com.remmi.browser.ui.components.ActivityViewModel
import com.remmi.browser.ui.components.ActivityViewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remmi.browser.ui.components.HudOverlay
import com.remmi.browser.ui.components.ImagePreviewDialog
import com.remmi.browser.ui.components.NewTabPage
import com.remmi.browser.ui.components.PagePreviewSheet
import com.remmi.browser.ui.components.ReaderView
import com.remmi.browser.ui.components.SecurityShieldSheet
import com.remmi.browser.ui.components.TabGridSheet
import com.remmi.browser.ui.components.TabGroupSelectDialog
import com.remmi.browser.ui.components.SplitScreenView
import com.remmi.browser.ui.components.TabStrip
import com.remmi.browser.ui.components.TerminalUrlBar
import com.remmi.browser.ui.components.WebContextMenuSheet
import com.remmi.browser.ui.components.PanicWipeDialog
import com.remmi.browser.ui.components.RedirectInspectorSheet
import com.remmi.browser.ui.components.ClickCandidatesSheet
import com.remmi.browser.ui.components.UrlSecuritySheet
import com.remmi.browser.ui.screens.SecurityCenterScreen
import com.remmi.browser.security.ClickTargetAnalyzer
import com.remmi.browser.security.ClickTargetCandidate
import com.remmi.browser.engine.BrowserActions
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
  onOpenSettings: () -> Unit = {},
  onOpenWelcome: () -> Unit = {},
  onOpenPasswords: () -> Unit = {},
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

  val tabManager = remember { TabManager.getInstance() }
  val tabs by tabManager.tabs.collectAsState()
  val tabGroups by tabManager.tabGroups.collectAsState()
  val activeTabIndex by tabManager.activeTabIndex.collectAsState()

  var composeCount by remember { mutableIntStateOf(0) }
  SideEffect {
    composeCount++
    val curTabId = tabManager.activeTab?.id ?: "none"
    com.remmi.browser.util.DebugLogManager.log("[FORENSIC] BROWSER_SCREEN_COMPOSE count=$composeCount activeTabId=$curTabId (time=${android.os.SystemClock.elapsedRealtime()})")
  }

  LaunchedEffect(Unit) {
    com.remmi.browser.util.CrashHandlerHelper.updateStartupPhase(context, com.remmi.browser.util.StartupPhase.BROWSER_SCREEN_COMPOSE)
    com.remmi.browser.util.DebugLogManager.log("[FORENSIC] BROWSER_SCREEN_COMPOSE LaunchedEffect entered")
  }

  DisposableEffect(Unit) {
    val curTabId = tabManager.activeTab?.id ?: "none"
    val enterMsg = "[FORENSIC][COMPOSE_ENTER] screen=BrowserScreen tabId=$curTabId tabCount=${tabManager.tabs.value.size} activeIndex=${tabManager.activeTabIndex.value} elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
    android.util.Log.i("BrowserScreen", enterMsg)
    com.remmi.browser.util.DebugLogManager.log(enterMsg)
    onDispose {
      val exitCaller = try { Thread.currentThread().stackTrace.take(6).joinToString(" -> ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } } catch (_: Exception) { "unknown" }
      val exitMsg = "[FORENSIC][COMPOSE_EXIT] screen=BrowserScreen tabId=$curTabId tabCount=${tabManager.tabs.value.size} activeIndex=${tabManager.activeTabIndex.value} reason=BrowserScreen_left_composition caller=$exitCaller elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
      android.util.Log.i("BrowserScreen", exitMsg)
      com.remmi.browser.util.DebugLogManager.log(exitMsg)
    }
  }

  val torManager = remember { TorManager.getInstance(context) }
  val geckoEngine = remember { com.remmi.browser.engine.GeckoEngineManager.getInstance(context) }
  val dbState by RemmiDatabase.databaseState.collectAsState()
  val database = (dbState as? RemmiDatabase.DatabaseState.Ready)?.database
  val clipboardMgr = remember { ClipboardManager(context) }
  val settingsRepo = remember { SettingsRepository.getInstance(context) }
  val passwordRepo = remember { com.remmi.browser.security.PasswordManagerRepository.getInstance(context) }
  val autofillHelper = remember { com.remmi.browser.security.autofill.PasswordAutofillCoordinator(context, scope, passwordRepo) }
  val sitePolicyManager = remember { com.remmi.browser.security.SiteSecurityPolicyManager.getInstance(context) }
  val torState by torManager.bootstrapState.collectAsState()
  val circuit by torManager.currentCircuit.collectAsState()
  val settings by settingsRepo.settings.collectAsState()
  LaunchedEffect(settings.defaultProfile) {
    tabManager.updateInitialTabProfile(settings.defaultProfile)
  }
  LaunchedEffect(Unit) {
    tabManager.checkAndMarkInactiveTabs(thresholdHours = 24)
  }
  val historyList by remember(database) {
    database?.historyDao()?.getAllHistory() ?: kotlinx.coroutines.flow.flowOf(emptyList<com.remmi.browser.storage.HistoryItem>())
  }.collectAsState(initial = emptyList<com.remmi.browser.storage.HistoryItem>())
  val bookmarksList by remember(database) {
    database?.bookmarkDao()?.getAllBookmarks() ?: kotlinx.coroutines.flow.flowOf(emptyList<com.remmi.browser.storage.BookmarkItem>())
  }.collectAsState(initial = emptyList<com.remmi.browser.storage.BookmarkItem>())
  val downloadsList by remember(database) {
    database?.downloadDao()?.getAllDownloads() ?: kotlinx.coroutines.flow.flowOf(emptyList<com.remmi.browser.storage.DownloadItem>())
  }.collectAsState(initial = emptyList<com.remmi.browser.storage.DownloadItem>())

  val speedDials by settingsRepo.speedDials.collectAsState()
  val savePrompt by autofillHelper.savePrompt.collectAsState()
  val fillPrompt by autofillHelper.fillPrompt.collectAsState()

  val activeTab = tabs.getOrNull(activeTabIndex) ?: tabs.firstOrNull() ?: BrowserTab()
  val isNewTab = activeTab.url.isBlank() || activeTab.url == "about:blank" || activeTab.url == "remmi://newtab" || activeTab.url == "about:home"
  SideEffect {
    tabManager.recordRecomposition(activeTab.id)
  }

  LaunchedEffect(
    settings.dnsProvider,
    settings.encryptedClientHelloEnabled,
    settings.globalPrivacyControlEnabled,
    settings.doNotTrackEnabled,
    settings.strictReferrerPolicy,
    settings.httpsOnlyMode,
  ) {
    geckoEngine.updateGlobalPreferences(settings)
  }

  var showTabGridSheet by remember { mutableStateOf(false) }
  var showSecuritySheet by remember { mutableStateOf(false) }
  var showSecurityCenter by remember { mutableStateOf(false) }
  var showSplitScreen by remember { mutableStateOf(false) }
  var secondaryTabId by remember { mutableStateOf<String?>(null) }
  var showTabGroupSelectDialog by remember { mutableStateOf(false) }
  var pendingTabGroupUrl by remember { mutableStateOf<String?>(null) }
  
  // Track secondary tab
  val secondaryTab = tabs.find { it.id == secondaryTabId }

  var showUrlSecuritySheet by remember { mutableStateOf(false) }
  var inspectingRedirectUrl by remember { mutableStateOf<String?>(null) }
  var detectedClickCandidates by remember { mutableStateOf<List<ClickTargetCandidate>>(emptyList()) }
  var showCircuitSheet by remember { mutableStateOf(false) }
  var showHistoryBookmarksSheet by remember { mutableStateOf(false) }
  var historyBookmarksInitialTab by remember { mutableIntStateOf(0) }
  var showDownloadsSheet by remember { mutableStateOf(false) }
  var showReadingListScreen by remember { mutableStateOf(false) }
  var showMenuDropdown by remember { mutableStateOf(false) }
  var showDevMenuDialog by remember { mutableStateOf(false) }
  var showPanicWipeDialog by remember { mutableStateOf(false) }
  var showPrintPdfProgress by remember { mutableStateOf(false) }

  // Register Click Transparency Inspector callback from WebExtension
  LaunchedEffect(Unit) {
    com.remmi.adblock.BlockExtension.getInstance().onClickInspected = { candidatesJson, hasOverlay, intercepted, _pageUrl ->
      val inspection = ClickTargetAnalyzer.fromExtensionJson(candidatesJson, hasOverlay)
      if (intercepted || inspection.hasOverlay || inspection.candidates.size > 1) {
        scope.launch(Dispatchers.Main) {
          detectedClickCandidates = inspection.candidates
        }
      }
    }
  }

  // Check autofill on active tab URL change
  LaunchedEffect(activeTab.url) {
    if (activeTab.url.startsWith("https://", ignoreCase = true) && activeTab.url != "about:blank") {
      autofillHelper.checkForAutofill(activeTab.id, activeTab.url) { _, _ -> }
    }
  }

  // Long-press Context Menu & Preview Overlays
  var activeContextMenuData by remember { mutableStateOf<WebContextMenuData?>(null) }
  var pagePreviewData by remember { mutableStateOf<Pair<String, String>?>(null) }
  var imagePreviewData by remember { mutableStateOf<Pair<String, String>?>(null) }

  // Find in page state
  var isFindInPageActive by remember { mutableStateOf(false) }
  var findQuery by remember { mutableStateOf("") }
  var findCurrentMatch by remember { mutableIntStateOf(0) }
  var findTotalMatches by remember { mutableIntStateOf(0) }

  var isFullScreenMode by remember { mutableStateOf(false) }
  var isSessionRestored by remember { mutableStateOf(false) }
  var isWaitingForTor by remember { mutableStateOf(false) }

  val isGhost = activeTab.profile == PrivacyProfile.GHOST
  val profileColor = if (isGhost) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary

  val isBookmarked = remember(bookmarksList, activeTab.url) {
    bookmarksList.any { it.url == activeTab.url }
  }

  var lastBackPressTime by remember { mutableLongStateOf(0L) }

  // Restore previous session tabs on startup if enabled (Always erase Incognito / Ghost tabs from disk on launch)
  LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
      val db = RemmiDatabase.getDatabaseAsync(context)
      db.sessionTabDao().clearPrivateTabs()

      if (tabManager.tabs.value.isNotEmpty() && (tabManager.tabs.value.size > 1 || tabManager.tabs.value[0].url != "about:blank")) {
        // Memory state already contains active tabs (e.g. returning from Settings or other screens)
        isSessionRestored = true
      } else if (settings.clearDataOnExit) {
        db.sessionTabDao().clearAllTabs()
        db.historyDao().clearHistory()
        com.remmi.browser.engine.GeckoEngineManager.getInstance(context).clearCookiesAndCacheSafely()
        isSessionRestored = true
      } else if (settings.restoreLastSession) {
        val savedTabs = db.sessionTabDao().getAllTabsList()
        val nonPrivateSavedTabs = savedTabs.filter { it.profile != PrivacyProfile.GHOST.name && it.profile != PrivacyProfile.INCOGNITO.name }
        if (nonPrivateSavedTabs.isNotEmpty()) {
          withContext(Dispatchers.Main) {
            val currentTabs = tabManager.tabs.value
            val isStillUntouched = currentTabs.size == 1 && (currentTabs[0].url == "about:blank" || currentTabs[0].url.isBlank())
            if (isStillUntouched) {
              tabManager.restoreSavedTabs(nonPrivateSavedTabs)
            } else {
              val skipMsg = "[FORENSIC][RESTORE_GUARD] User navigation in-progress, skipping disk session restore"
              android.util.Log.i("BrowserScreen", skipMsg)
              com.remmi.browser.util.DebugLogManager.log(skipMsg)
            }
            isSessionRestored = true
          }
        } else {
          isSessionRestored = true
        }
      } else {
        isSessionRestored = true
      }
    }
  }

  // Auto-save tabs to encrypted database whenever tab list changes (strictly exclude Incognito / Ghost tabs, debounced)
  val persistKey = remember(tabs) {
    tabs.filter { it.profile != PrivacyProfile.GHOST && it.profile != PrivacyProfile.INCOGNITO }
      .joinToString("|") { "${it.id}:${it.url}:${it.title}" }
  }
  LaunchedEffect(persistKey, isSessionRestored) {
    if (isSessionRestored && !settings.clearDataOnExit) {
      kotlinx.coroutines.delay(800) // Debounce tab persistence write storm
      withContext(Dispatchers.IO) {
        val db = RemmiDatabase.getDatabaseAsync(context)
        val nonPrivateTabs = tabs.filter { it.profile != PrivacyProfile.GHOST && it.profile != PrivacyProfile.INCOGNITO }
        val entities = nonPrivateTabs.mapIndexed { index, tab ->
          SessionTabEntity(
            id = tab.id,
            url = tab.url,
            title = tab.title,
            position = index,
            timestamp = tab.createdAt,
            profile = tab.profile.name,
            isDesktopMode = tab.isDesktopMode,
            isReaderMode = tab.isReaderMode,
          )
        }
        db.sessionTabDao().clearAllTabs()
        if (entities.isNotEmpty()) {
          db.sessionTabDao().insertAll(entities)
        }
      }
    }
  }

  // Ensure private tabs are never persisted to disk across restarts
  DisposableEffect(lifecycleOwner) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
        scope.launch(Dispatchers.IO) {
          val db = RemmiDatabase.getDatabaseAsync(context)
          db.sessionTabDao().clearPrivateTabs()
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // Clear data on app exit lifecycle observer (only on genuine finish, never on minimize/background)
  DisposableEffect(lifecycleOwner, settings.clearDataOnExit) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
        val activity = context as? android.app.Activity
        if (activity?.isFinishing == true && settings.clearDataOnExit) {
          scope.launch(Dispatchers.IO) {
            val db = RemmiDatabase.getDatabaseAsync(context)
            db.sessionTabDao().clearAllTabs()
            db.historyDao().clearHistory()
            com.remmi.browser.engine.GeckoEngineManager.getInstance(context).clearCookiesAndCacheSafely()
          }
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // Hardened Ghost Mode / Shield Mode toggle logic using PrivacyNetworkController
  val privacyController = remember { PrivacyNetworkController.getInstance(context) }

  val handleToggleGhostMode: () -> Unit = {
    val currentProfile = activeTab.profile
    if (currentProfile == PrivacyProfile.SHIELD) {
      // Switching TO Ghost mode: Close session first to prevent clearnet leak
      scope.launch {
        isWaitingForTor = true
        privacyController.enterGhostMode(activeTab.id).onSuccess { port ->
          isWaitingForTor = false
          withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
              context,
              "Ghost Mode Active • Encrypted Tor Routing (127.0.0.1:$port)",
              android.widget.Toast.LENGTH_SHORT
            ).show()
          }
          if (activeTab.url.isBlank() || activeTab.url == "about:blank" || activeTab.url == "remmi://newtab" || activeTab.url == "about:home") {
            geckoEngine.loadUrl(activeTab.id, "https://check.torproject.org")
          }
        }.onFailure { err ->
          isWaitingForTor = false
          withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(
              context,
              "Tor Verification Failed: ${err.message}",
              android.widget.Toast.LENGTH_LONG
            ).show()
          }
        }
      }
    } else {
      // Switching BACK to Shield mode
      scope.launch {
        privacyController.enterShieldMode(activeTab.id)
        withContext(Dispatchers.Main) {
          android.widget.Toast.makeText(
            context,
            "Shield Mode Active • Direct Clearnet Restored",
            android.widget.Toast.LENGTH_SHORT
          ).show()
        }
      }
    }
  }

  val handleOpenGhostTab: (String?) -> Unit = { url ->
    val targetDestination = if (!url.isNullOrBlank()) url else "about:blank"
    tabManager.openTab(url = targetDestination, profile = PrivacyProfile.INCOGNITO)
    android.widget.Toast.makeText(
      context,
      "Opened in Incognito Tab",
      android.widget.Toast.LENGTH_SHORT
    ).show()
  }

  // Handle system back navigation (including edge swipe gestures)
  BackHandler(enabled = true) {
    if (activeContextMenuData != null) {
      activeContextMenuData = null
    } else if (pagePreviewData != null) {
      pagePreviewData = null
    } else if (imagePreviewData != null) {
      imagePreviewData = null
    } else if (isFullScreenMode) {
      isFullScreenMode = false
    } else if (showMenuDropdown) {
      showMenuDropdown = false
    } else if (showTabGridSheet) {
      showTabGridSheet = false
    } else if (showSecuritySheet) {
      showSecuritySheet = false
    } else if (showCircuitSheet) {
      showCircuitSheet = false
    } else if (showHistoryBookmarksSheet) {
      showHistoryBookmarksSheet = false
    } else if (showDownloadsSheet) {
      showDownloadsSheet = false
    } else if (isFindInPageActive) {
      isFindInPageActive = false
    } else if (activeTab.isReaderMode) {
      tabManager.toggleReaderMode(activeTab.id)
    } else if (activeTab.canGoBack || geckoEngine.canGoBack(activeTab.id)) {
      Log.i("BrowserScreen", "[FORENSIC] NAV_BACK_REQUEST tabId=${activeTab.id} action=GO_BACK url=${activeTab.url}")
      geckoEngine.goBack(activeTab.id)
    } else if (!isNewTab) {
      Log.i("BrowserScreen", "[FORENSIC] NAV_BACK_NO_HISTORY tabId=${activeTab.id} action=RESET_TO_NEW_TAB url=${activeTab.url}")
      // If on a loaded website with no back history in session, go back to New Tab page
      tabManager.updateTab(activeTab.id) {
        it.copy(url = "about:blank", title = "New Tab", canGoBack = false, canGoForward = false, isReaderMode = false, isSecure = true, readerArticle = null)
      }
      geckoEngine.resetToNewTab(activeTab.id)
    } else if (tabs.size > 1) {
      // If on New Tab page and multiple tabs exist, close active tab
      tabManager.closeTab(activeTab.id)
      scope.launch { com.remmi.browser.engine.GeckoEngineManager.getInstance(context).closeSessionSafely(activeTab.id) }
    } else {
      // On Home screen with 1 tab: Double-back to exit to prevent accidental app closing
      val currentTime = System.currentTimeMillis()
      if (currentTime - lastBackPressTime < 2000) {
        (context as? android.app.Activity)?.finish()
      } else {
        lastBackPressTime = currentTime
        android.widget.Toast.makeText(context, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
      }
    }
  }

  val isFullBgActive = isNewTab && settings.fullscreenWallpaperEnabled && (settings.customWallpaperUri != null || settings.backgroundAnimation.isNotEmpty())

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ThemeCyber.colors.background)
  ) {
    if (isFullBgActive) {
      CyberpunkBackground(
        backgroundType = settings.backgroundAnimation,
        customWallpaperUri = settings.customWallpaperUri,
        wallpaperDimLevel = settings.wallpaperDimLevel,
        wallpaperScaleMode = settings.wallpaperScaleMode,
        modifier = Modifier.fillMaxSize(),
      )
    }

    Scaffold(
      modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .navigationBarsPadding(),
      containerColor = if (isFullBgActive) Color.Transparent else ThemeCyber.colors.background,
    ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
    ) {
      // 1. Sleek Terminal URL Bar & Quick Nav (hidden on New Tab home screen, reader mode, and full view mode)
      AnimatedVisibility(
        visible = !isFullScreenMode && !activeTab.isReaderMode && !isNewTab,
        enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
      ) {
        TerminalUrlBar(
          url = activeTab.url,
          isSecure = activeTab.isSecure,
          profile = activeTab.profile,
          isLoading = activeTab.isLoading,
          isBookmarked = isBookmarked,
          isReaderActive = activeTab.isReaderMode,
          onUrlSubmit = { target ->
            val sanitized = NetworkHardening.sanitizeUrl(target)
            val currentTab = activeTab
            if (currentTab.url == sanitized) {
              geckoEngine.reload(currentTab.id)
            } else {
              tabManager.updateTab(currentTab.id) { it.copy(url = sanitized, isReaderMode = false, readerArticle = null) }
              geckoEngine.loadUrl(currentTab.id, sanitized)
            }
          },
          onReload = { geckoEngine.reload(activeTab.id) },
          onToggleBookmark = {
            scope.launch(Dispatchers.IO) {
              val db = RemmiDatabase.getDatabaseAsync(context)
              if (isBookmarked) {
                db.bookmarkDao().deleteByUrl(activeTab.url)
              } else {
                db.bookmarkDao().insert(
                  BookmarkItem(
                    url = activeTab.url,
                    title = activeTab.title.ifEmpty { activeTab.url }
                  )
                )
              }
            }
          },
          onToggleReader = {
            tabManager.toggleReaderMode(activeTab.id)
          },
          onOpenSecurityPanel = { showUrlSecuritySheet = true },
          onInspectRedirects = { inspectingRedirectUrl = activeTab.url },
          onShareUrl = { BrowserActions.shareUrl(context, activeTab.url, activeTab.title) },
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
      }

      // 4. Main Canvas View (New Tab Page or Browser View or Reader View)
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
      ) {
        val primaryContent: @Composable () -> Unit = {
          Box(modifier = Modifier.fillMaxSize()) {
            val isGhostAndNotReady = activeTab.profile == PrivacyProfile.GHOST && torState !is TorManager.TorState.READY
    
            if (isWaitingForTor || (activeTab.profile == PrivacyProfile.GHOST && (torState is TorManager.TorState.OFF || torState.isConnecting))) {
          val progress = torState.progress
          val statusMsg = torState.statusText

          Column(
            modifier = Modifier
              .fillMaxSize()
              .background(ThemeCyber.colors.background)
              .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            androidx.compose.material3.CircularProgressIndicator(
              color = ThemeCyber.colors.torPurple,
              modifier = Modifier.size(48.dp)
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(20.dp))
            GlitchText(
              text = "ESTABLISHING SECURE CIRCUIT...",
              fontSize = 16.sp,
              color = ThemeCyber.colors.torPurple
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = if (progress > 0) "$progress% • $statusMsg" else statusMsg,
              color = ThemeCyber.colors.textSecondary,
              fontFamily = CyberMonoFamily,
              fontSize = 12.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.LinearProgressIndicator(
              progress = { progress.coerceAtLeast(10) / 100f },
              color = ThemeCyber.colors.torPurple,
              trackColor = ThemeCyber.colors.surfaceLight,
              modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
            Text(
              text = "FAIL-CLOSED ACTIVE: Direct clearnet traffic is strictly blocked to prevent IP/location leak.",
              color = ThemeCyber.colors.textMuted,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
          }
        } else if (activeTab.profile == PrivacyProfile.GHOST && torState is TorManager.TorState.FAILED) {
          val failedState = torState as TorManager.TorState.FAILED
          val errorMsg = "[${failedState.category}] ${failedState.message}"
          Column(
            modifier = Modifier
              .fillMaxSize()
              .background(ThemeCyber.colors.background)
              .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Icon(
              imageVector = Icons.Default.Shield,
              contentDescription = "Tor Error",
              tint = ThemeCyber.colors.warningYellow,
              modifier = Modifier.size(54.dp)
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
            GlitchText(
              text = "TOR CIRCUIT CONNECTION FAILED",
              fontSize = 16.sp,
              color = ThemeCyber.colors.warningYellow
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = errorMsg,
              color = ThemeCyber.colors.textSecondary,
              fontFamily = CyberMonoFamily,
              fontSize = 12.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "FAIL-CLOSED DEFENSE: Clearnet fallback is blocked to protect your real IP address.",
              color = ThemeCyber.colors.torPurple,
              fontFamily = CyberMonoFamily,
              fontSize = 11.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
              Button(
                onClick = {
                  scope.launch {
                    privacyController.resetTorFailures()
                    privacyController.enterGhostMode(activeTab.id)
                  }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.torPurple),
                shape = RoundedCornerShape(6.dp),
              ) {
                Text("RETRY", fontFamily = CyberMonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold)
              }
              if (torManager.isOrbotInstalled()) {
                Button(
                  onClick = {
                    torManager.getOrbotStartIntent()?.let { intent ->
                      context.startActivity(intent)
                    }
                  },
                  colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),

                  shape = RoundedCornerShape(6.dp),
                ) {
                  Text("OPEN ORBOT", color = ThemeCyber.colors.backgroundDarker, fontFamily = CyberMonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
              }
              Button(
                onClick = handleToggleGhostMode,
                colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.surfaceLight),
                shape = RoundedCornerShape(6.dp),
              ) {
                Text("SHIELD MODE", color = ThemeCyber.colors.textPrimary, fontFamily = CyberMonoFamily, fontSize = 12.sp)
              }
            }

          }
        } else {
          val isNewTab = activeTab.url.isBlank() || activeTab.url == "about:blank" || activeTab.url == "remmi://newtab" || activeTab.url == "about:home"

          if (isNewTab) {
          NewTabPage(
            profile = activeTab.profile,
            blockedTrackersCount = activeTab.blockedTrackersCount,
            torState = torState,
            circuit = circuit,
            isDesktopMode = activeTab.isDesktopMode,
            isReaderMode = activeTab.isReaderMode,
            searchEngine = SearchEngine.fromId(settings.searchEngineName),
            speedDials = speedDials,
            backgroundAnimation = settings.backgroundAnimation,
            customWallpaperUri = settings.customWallpaperUri,
            wallpaperDimLevel = settings.wallpaperDimLevel,
            fullscreenWallpaperEnabled = settings.fullscreenWallpaperEnabled,
            wallpaperScaleMode = settings.wallpaperScaleMode,
            onSearch = { query, engine ->
              val encoded = try {
                URLEncoder.encode(query, "UTF-8")
              } catch (e: Exception) {
                query
              }
              val targetUrl = String.format(engine.searchUrlFormat, encoded)
              tabManager.updateTab(activeTab.id) { it.copy(url = targetUrl, isReaderMode = false, readerArticle = null) }
            },
            onNavigate = { target ->
              val sanitized = NetworkHardening.sanitizeUrl(target)
              tabManager.updateTab(activeTab.id) { it.copy(url = sanitized, isReaderMode = false, readerArticle = null) }
            },
            onSelectSearchEngine = { engine ->
              settingsRepo.updateSearchEngine(engine.displayName)
            },
            onSelectTheme = { theme ->
              settingsRepo.updateCyberTheme(theme)
            },
            onAddSpeedDial = { item -> settingsRepo.addSpeedDial(item) },
            onEditSpeedDial = { item -> settingsRepo.editSpeedDial(item) },
            onDeleteSpeedDial = { id -> settingsRepo.removeSpeedDial(id) },
            onResetSpeedDials = { settingsRepo.resetSpeedDials() },
            onUpdateWallpaper = { uri -> settingsRepo.updateCustomWallpaper(uri) },
            onUpdateBackgroundAnimation = { type -> settingsRepo.updateBackgroundAnimation(type) },
            onUpdateWallpaperDimLevel = { settingsRepo.updateWallpaperDimLevel(it) },
            onUpdateFullscreenWallpaper = { settingsRepo.updateFullscreenWallpaper(it) },
            onUpdateWallpaperScaleMode = { settingsRepo.updateWallpaperScaleMode(it) },
            onNewTab = {
              val targetProfile = if (activeTab.profile == PrivacyProfile.GHOST || settings.defaultProfile == PrivacyProfile.GHOST) PrivacyProfile.GHOST else PrivacyProfile.SHIELD
              if (targetProfile == PrivacyProfile.GHOST) {
                handleOpenGhostTab("about:blank")
              } else {
                tabManager.openTab(
                  profile = targetProfile,
                  isDesktop = settings.defaultDesktopMode,
                )
              }
            },
            onOpenBookmarks = {
              historyBookmarksInitialTab = 1
              showHistoryBookmarksSheet = true
            },
            onOpenHistory = {
              historyBookmarksInitialTab = 0
              showHistoryBookmarksSheet = true
            },
            onOpenDownloads = { showDownloadsSheet = true },
            onOpenReadingList = { showReadingListScreen = true },
            onOpenSettings = onOpenSettings,
            onToggleDesktop = { tabManager.toggleDesktopMode(activeTab.id) },
            onToggleGhost = handleToggleGhostMode,
            onToggleReader = { tabManager.toggleReaderMode(activeTab.id) },
            onInspectCircuit = { showCircuitSheet = true },
            onSecurityShieldClick = { showSecuritySheet = true },
            modifier = Modifier.fillMaxSize()
          )
        } else {
          androidx.compose.runtime.key(activeTab.id) {
            BrowserView(
              tab = activeTab,
              onUrlChange = { newUrl ->
                tabManager.updateTab(activeTab.id) { 
                  if (it.url != newUrl) {
                    it.copy(url = newUrl, isReaderMode = false, readerArticle = null)
                  } else {
                    it.copy(url = newUrl)
                  }
                }
                if (activeTab.profile != PrivacyProfile.GHOST && activeTab.profile != PrivacyProfile.INCOGNITO) {
                  scope.launch(Dispatchers.IO) {
                    val db = RemmiDatabase.getDatabaseAsync(context)
                    db.historyDao().insertIfNotDuplicate(
                      HistoryItem(
                        url = newUrl,
                        title = activeTab.title,
                        profile = activeTab.profile.name
                      )
                    )
                  }
                }
              },
              onTitleChange = { newTitle ->
                tabManager.updateTab(activeTab.id) { it.copy(title = newTitle) }
              },
              onProgressChange = { /* handled locally in BrowserView to avoid recomposition churn */ },
              onLoadingChange = { loading ->
                if (activeTab.isLoading != loading) {
                  tabManager.updateTab(activeTab.id) { it.copy(isLoading = loading) }
                }
              },
              onSecurityChange = { secure ->
                if (activeTab.isSecure != secure) {
                  tabManager.updateTab(activeTab.id) { it.copy(isSecure = secure) }
                }
              },
              onNavStateChange = { canBack, canForward ->
                tabManager.updateTab(activeTab.id) { tab ->
                  if (tab.canGoBack != canBack || tab.canGoForward != canForward) {
                    tab.copy(canGoBack = canBack, canGoForward = canForward)
                  } else {
                    tab
                  }
                }
              },
              onTrackerBlocked = { url, _ ->
                tabManager.incrementTrackerCount(activeTab.id, url)
              },
              onReaderArticleExtracted = { article ->
                tabManager.setReaderArticle(activeTab.id, article)
              },
              onContextMenuRequested = { data ->
                activeContextMenuData = data
              },
              modifier = Modifier.fillMaxSize()
            )
          }
        }

        // Reader Mode Fullscreen View
        if (activeTab.isReaderMode) {
          ReaderView(
            article = activeTab.readerArticle,
            initialFontSizeIndex = settings.readerFontSize,
            onFontSizeChanged = { settingsRepo.updateReaderFontSize(it) },
            onClose = { tabManager.toggleReaderMode(activeTab.id) },
            isGhostRoute = activeTab.profile == PrivacyProfile.GHOST,
            modifier = Modifier.fillMaxSize()
          )
        }

        // Floating Exit Button for Full View Mode
        if (isFullScreenMode) {
          Surface(
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(16.dp)
              .clip(RoundedCornerShape(24.dp))
              .clickable { isFullScreenMode = false },
            shape = RoundedCornerShape(24.dp),
            color = ThemeCyber.colors.surface.copy(alpha = 0.9f),
            border = BorderStroke(1.dp, ThemeCyber.colors.primary.copy(alpha = 0.7f)),
            shadowElevation = 8.dp,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(
                imageVector = Icons.Default.FullscreenExit,
                contentDescription = "Exit Full View",
                tint = ThemeCyber.colors.primary,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = "EXIT FULL VIEW",
                color = ThemeCyber.colors.textPrimary,
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
        } // Close else
        } // Close Box(modifier=fillMaxSize())
        } // Close primaryContent lambda
        
        if (showSplitScreen && secondaryTab != null) {
          SplitScreenView(
            primaryTab = activeTab,
            secondaryTab = secondaryTab,
            primaryContent = primaryContent,
            onSwapTabs = {
              val temp = activeTab.id
              tabManager.switchToTab(secondaryTab.id)
              secondaryTabId = temp
            },
            onCloseSplit = {
              showSplitScreen = false
              secondaryTabId = null
            },
            onSecondaryUrlChange = { newUrl ->
              tabManager.updateTab(secondaryTab.id) { 
                if (it.url != newUrl) it.copy(url = newUrl, isReaderMode = false, readerArticle = null) else it.copy(url = newUrl)
              }
            },
            onSecondaryTitleChange = { newTitle -> tabManager.updateTab(secondaryTab.id) { it.copy(title = newTitle) } },
            onSecondaryLoadingChange = { loading -> tabManager.updateTab(secondaryTab.id) { it.copy(isLoading = loading) } },
            onSecondarySecurityChange = { secure -> tabManager.updateTab(secondaryTab.id) { it.copy(isSecure = secure) } },
            onSecondaryNavStateChange = { canBack, canForward -> tabManager.updateTab(secondaryTab.id) { it.copy(canGoBack = canBack, canGoForward = canForward) } },
            onSecondaryTrackerBlocked = { url, _ -> tabManager.incrementTrackerCount(secondaryTab.id, url) },
            onSecondaryContextMenuRequested = { activeContextMenuData = it },
            onSecondaryBack = { geckoEngine.goBack(secondaryTab.id) },
            onSecondaryForward = { geckoEngine.goForward(secondaryTab.id) },
            onSecondaryReload = { geckoEngine.reload(secondaryTab.id) }
          )
        } else {
          primaryContent()
        }
      } // Close Box(modifier=weight(1f))

      // 5. Find in Page Bar (Conditional Overlay)
      androidx.compose.animation.AnimatedVisibility(
        visible = isFindInPageActive && !isFullScreenMode,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
      ) {
        FindInPageBar(
          query = findQuery,
          currentMatch = findCurrentMatch,
          totalMatches = findTotalMatches,
          onQueryChange = { q ->
            findQuery = q
            geckoEngine.findInPage(activeTab.id, q)
          },
          onFindNext = {
            geckoEngine.findInPage(activeTab.id, findQuery, backwards = false)
          },
          onFindPrevious = {
            geckoEngine.findInPage(activeTab.id, findQuery, backwards = true)
          },
          onClose = {
            geckoEngine.clearFindInPage(activeTab.id)
            isFindInPageActive = false
            findQuery = ""
          },
        )
      }

      // 5.5 Print / PDF Export Progress Bar
      AnimatedVisibility(
        visible = showPrintPdfProgress,
        enter = fadeIn(),
        exit = fadeOut(),
      ) {
        Surface(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          shape = RoundedCornerShape(8.dp),
          color = ThemeCyber.colors.surface,
          border = BorderStroke(1.dp, ThemeCyber.colors.secondary)
        ) {
          Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            androidx.compose.material3.CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              color = ThemeCyber.colors.secondary,
              strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              "Preparing Document (PDF / Print)...",
              color = ThemeCyber.colors.textPrimary,
              fontFamily = CyberMonoFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }

      // Password Save & Autofill Prompt Overlay
      if (savePrompt != null && !isFullScreenMode) {
        Surface(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
          shape = RoundedCornerShape(8.dp),
          color = ThemeCyber.colors.surface,
          border = BorderStroke(1.dp, ThemeCyber.colors.primary)
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Icon(Icons.Default.VpnKey, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(20.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Column {
                Text(
                  "SAVE PASSWORD IN CYBER VAULT?",
                  color = ThemeCyber.colors.primary,
                  fontSize = 11.sp,
                  fontFamily = CyberMonoFamily,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  "Account: ${savePrompt?.username?.ifEmpty { "Saved Login" }}",
                  color = ThemeCyber.colors.textSecondary,
                  fontSize = 10.sp
                )
              }
            }
            Row {
              IconButton(onClick = { autofillHelper.dismissSavePrompt() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(16.dp))
              }
              Button(
                onClick = { savePrompt?.onSave?.invoke() },
                colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
                modifier = Modifier.height(32.dp)
              ) {
                Text("SAVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }

      // 6. Bottom Navigation Control Bar (Clean, Unified Browser Toolbar)
      AnimatedVisibility(
        visible = !isFullScreenMode && !activeTab.isReaderMode,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
      ) {
        val hasCustomWallpaper = isNewTab && settings.fullscreenWallpaperEnabled && settings.customWallpaperUri != null
        val isLight = ThemeCyber.colors.isLight

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .background(if (hasCustomWallpaper) Color.Transparent else ThemeCyber.colors.surface)
            .then(
              if (hasCustomWallpaper) Modifier
              else Modifier.border(0.5.dp, ThemeCyber.colors.surfaceBorder.copy(alpha = 0.6f))
            )
            .padding(horizontal = 4.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // 1. Back Button
          val canNavigateBack = activeTab.canGoBack || geckoEngine.canGoBack(activeTab.id) || !isNewTab
          IconButton(
            onClick = {
              if (activeTab.canGoBack || geckoEngine.canGoBack(activeTab.id)) {
                Log.i("BrowserScreen", "[FORENSIC] NAV_BACK_TOOLBAR tabId=${activeTab.id} action=GO_BACK")
                geckoEngine.goBack(activeTab.id)
              } else if (!isNewTab) {
                Log.i("BrowserScreen", "[FORENSIC] NAV_BACK_TOOLBAR tabId=${activeTab.id} action=RESET_TO_NEW_TAB")
                tabManager.updateTab(activeTab.id) {
                  it.copy(url = "about:blank", title = "New Tab", canGoBack = false, canGoForward = false, isReaderMode = false, isSecure = true, readerArticle = null)
                }
                geckoEngine.resetToNewTab(activeTab.id)
              }
            },
            enabled = canNavigateBack,
            modifier = Modifier
              .size(44.dp)
              .testTag("nav_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = if (canNavigateBack) {
                if (hasCustomWallpaper) Color.White else ThemeCyber.colors.primary
              } else {
                if (hasCustomWallpaper) Color.White.copy(alpha = 0.35f) else ThemeCyber.colors.textMuted.copy(alpha = 0.4f)
              },
              modifier = Modifier.size(20.dp),
            )
          }

          // 2. Forward Button
          val canNavigateForward = activeTab.canGoForward || geckoEngine.canGoForward(activeTab.id)
          IconButton(
            onClick = { geckoEngine.goForward(activeTab.id) },
            enabled = canNavigateForward,
            modifier = Modifier
              .size(44.dp)
              .testTag("nav_forward_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowForward,
              contentDescription = "Forward",
              tint = if (canNavigateForward) {
                if (hasCustomWallpaper) Color.White else ThemeCyber.colors.primary
              } else {
                if (hasCustomWallpaper) Color.White.copy(alpha = 0.35f) else ThemeCyber.colors.textMuted.copy(alpha = 0.4f)
              },
              modifier = Modifier.size(20.dp),
            )
          }

          // 3. Ghost / Privacy Profile Toggle Button
          val isTorActive = isGhost || com.remmi.browser.security.CurrentTorRoute.isGhostActive
          IconButton(
            onClick = handleToggleGhostMode,
            modifier = Modifier
              .size(44.dp)
              .testTag("ghost_mode_toggle_button"),
          ) {
            if (isTorActive) {
              Icon(
                painter = painterResource(R.drawable.ic_tor),
                contentDescription = "Toggle Privacy / Ghost Mode",
                tint = ThemeCyber.colors.torPurple,
                modifier = Modifier.size(22.dp),
              )
            } else {
              Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Toggle Privacy / Ghost Mode",
                tint = if (hasCustomWallpaper) Color.White else profileColor,
                modifier = Modifier.size(20.dp),
              )
            }
          }

          // Home Button (Styled Central Navigation Action)
          val isHomeScreen = activeTab.url.isBlank() || activeTab.url == "about:blank" || activeTab.url == "remmi://newtab" || activeTab.url == "about:home"
          IconButton(
            onClick = {
              tabManager.updateTab(activeTab.id) {
                it.copy(
                  url = "about:blank",
                  title = "New Tab",
                  canGoBack = false,
                  canGoForward = false,
                  isReaderMode = false,
                  isSecure = true,
                  readerArticle = null
                )
              }
              geckoEngine.loadUrl(activeTab.id, "about:blank")
            },
            modifier = Modifier
              .size(44.dp)
              .testTag("nav_home_button"),
          ) {
            Box(
              modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                  if (hasCustomWallpaper) Color.Black.copy(alpha = 0.35f)
                  else if (isHomeScreen) ThemeCyber.colors.primary.copy(alpha = 0.16f)
                  else ThemeCyber.colors.surfaceLight.copy(alpha = 0.5f)
                )
                .border(
                  width = if (isHomeScreen) 1.2.dp else 0.8.dp,
                  color = if (hasCustomWallpaper) Color.White.copy(alpha = 0.35f)
                  else if (isHomeScreen) ThemeCyber.colors.primary.copy(alpha = 0.6f)
                  else ThemeCyber.colors.surfaceBorder.copy(alpha = 0.8f),
                  shape = RoundedCornerShape(9.dp)
                ),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Home Screen",
                tint = if (hasCustomWallpaper) Color.White else if (isHomeScreen) ThemeCyber.colors.primary else ThemeCyber.colors.textSecondary,
                modifier = Modifier.size(19.dp),
              )
            }
          }

          // 4. Desktop Mode Toggle Button
          IconButton(
            onClick = {
              tabManager.toggleDesktopMode(activeTab.id)
            },
            modifier = Modifier
              .size(44.dp)
              .testTag("desktop_mode_toggle_button"),
          ) {
            Icon(
              imageVector = if (activeTab.isDesktopMode) Icons.Default.DesktopWindows else Icons.Default.Smartphone,
              contentDescription = "Toggle Desktop Site Mode",
              tint = if (activeTab.isDesktopMode) ThemeCyber.colors.primary else if (hasCustomWallpaper) Color.White else ThemeCyber.colors.textSecondary,
              modifier = Modifier.size(20.dp),
            )
          }

          // 5. Tab Switcher Button (Standard Browser Tab Counter Badge)
          IconButton(
            onClick = {
              geckoEngine.captureTabThumbnail(activeTab.id)
              showTabGridSheet = true
            },
            modifier = Modifier
              .size(44.dp)
              .testTag("tab_switcher_button"),
          ) {
            Box(
              modifier = Modifier
                .size(20.dp)
                .border(
                  1.5.dp,
                  if (hasCustomWallpaper) Color.White else ThemeCyber.colors.primary,
                  RoundedCornerShape(5.dp)
                ),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = "${tabs.size}",
                color = if (hasCustomWallpaper) Color.White else ThemeCyber.colors.primary,
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
              )
            }
          }

          // 6. More Menu Button
          Box(
            contentAlignment = Alignment.Center,
          ) {
            IconButton(
              onClick = { showMenuDropdown = true },
              modifier = Modifier
                .size(44.dp)
                .testTag("more_menu_button"),
            ) {
              Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Menu",
                tint = if (hasCustomWallpaper) Color.White else ThemeCyber.colors.textPrimary,
                modifier = Modifier.size(20.dp),
              )
            }

            val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
            val maxDropdownMenuHeight = screenHeight * 0.70f

            DropdownMenu(
              expanded = showMenuDropdown,
              onDismissRequest = { showMenuDropdown = false },
              shape = RoundedCornerShape(16.dp),
              containerColor = ThemeCyber.colors.surface,
              border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder.copy(alpha = 0.7f)),
              scrollState = rememberScrollState(),
              modifier = Modifier
                .widthIn(min = 240.dp, max = 290.dp)
                .heightIn(max = maxDropdownMenuHeight)
            ) {
              // Section 1: Display & Navigation
              // Full View (Fullscreen) Mode
              DropdownMenuItem(
                text = {
                  Text(
                    if (isFullScreenMode) "Exit Full View" else "Full View (Fullscreen)",
                    color = ThemeCyber.colors.primary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                  )
                },
                leadingIcon = {
                  Icon(
                    if (isFullScreenMode) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = null,
                    tint = ThemeCyber.colors.primary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  isFullScreenMode = !isFullScreenMode
                }
              )

              // New Ghost Tab
              DropdownMenuItem(
                text = {
                  Text(
                    "New Ghost Tab",
                    color = ThemeCyber.colors.torPurple,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                  )
                },
                leadingIcon = {
                  Icon(
                    painter = painterResource(R.drawable.ic_tor),
                    contentDescription = null,
                    tint = ThemeCyber.colors.torPurple,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  handleOpenGhostTab(null)
                }
              )

              // Reader Mode
              DropdownMenuItem(
                text = {
                  Text(
                    if (activeTab.isReaderMode) "Exit Reader Mode" else "Reader Mode",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.MenuBook,
                    contentDescription = null,
                    tint = ThemeCyber.colors.primary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  tabManager.toggleReaderMode(activeTab.id)
                }
              )

              // Reading List / Offline Articles
              DropdownMenuItem(
                text = {
                  Text(
                    "Reading List",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  showReadingListScreen = true
                }
              )

              HorizontalDivider(
                color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp)
              )

              // Section 2: Page Tools & Actions
              // Share Link
              DropdownMenuItem(
                text = {
                  Text(
                    "Share Link",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Share,
                    contentDescription = null,
                    tint = ThemeCyber.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  com.remmi.browser.engine.BrowserActions.shareUrl(context, activeTab.url, activeTab.title)
                }
              )

              // Find in Page
              DropdownMenuItem(
                text = {
                  Text(
                    "Find in Page",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.FindInPage,
                    contentDescription = null,
                    tint = ThemeCyber.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  isFindInPageActive = true
                }
              )

              // Copy Markdown (if in reader mode)
              if (activeTab.isReaderMode && activeTab.readerArticle != null) {
                DropdownMenuItem(
                  text = {
                    Text(
                      "Copy as Markdown",
                      color = ThemeCyber.colors.textPrimary,
                      fontFamily = ThemeCyber.fontFamily,
                      fontSize = 13.5.sp,
                    )
                  },
                  leadingIcon = {
                    Icon(
                      Icons.Default.Code,
                      contentDescription = null,
                      tint = ThemeCyber.colors.primary,
                      modifier = Modifier.size(18.dp)
                    )
                  },
                  contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                  onClick = {
                    showMenuDropdown = false
                    val md = com.remmi.browser.reader.ReaderExporter.generateMarkdown(activeTab.readerArticle!!)
                    clipboardMgr.copyWithAutoClear(md, "Markdown")
                  }
                )
              }

              // Print Page
              DropdownMenuItem(
                text = {
                  Text(
                    "Print Page",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Print,
                    contentDescription = null,
                    tint = ThemeCyber.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  if (activeTab.url.isBlank() || activeTab.url == "about:blank") {
                    android.widget.Toast.makeText(context, "Open a web page first to print", android.widget.Toast.LENGTH_SHORT).show()
                  } else {
                    val pageTitle = activeTab.title.ifBlank { activeTab.url }
                    showPrintPdfProgress = true
                    geckoEngine.printPage(context, activeTab.id, pageTitle) {
                      showPrintPdfProgress = false
                    }
                  }
                }
              )

              // Export as PDF
              DropdownMenuItem(
                text = {
                  Text(
                    "Export as PDF",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = ThemeCyber.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  if (activeTab.url.isBlank() || activeTab.url == "about:blank") {
                    android.widget.Toast.makeText(context, "Open a web page first to export as PDF", android.widget.Toast.LENGTH_SHORT).show()
                  } else {
                    val pageTitle = activeTab.title.ifBlank { activeTab.url }
                    showPrintPdfProgress = true
                    geckoEngine.exportPageAsPdf(activeTab.id, pageTitle) {
                      showPrintPdfProgress = false
                    }
                  }
                }
              )

              // Inspect Element (DevTools)
              DropdownMenuItem(
                text = {
                  Text(
                    "Inspect Element (DevTools)",
                    color = ThemeCyber.colors.secondary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Code,
                    contentDescription = null,
                    tint = ThemeCyber.colors.secondary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  if (activeTab.url.isNotBlank() && activeTab.url != "about:blank") {
                    showDevMenuDialog = true
                  } else {
                    android.widget.Toast.makeText(context, "Please open a website to inspect element", android.widget.Toast.LENGTH_SHORT).show()
                  }
                }
              )

              HorizontalDivider(
                color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp)
              )

              // Section 3: Data, Vault & Settings
              // Bookmarks
              DropdownMenuItem(
                text = {
                  Text(
                    "Bookmarks",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Bookmark,
                    contentDescription = null,
                    tint = ThemeCyber.colors.warningYellow,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  historyBookmarksInitialTab = 1
                  showHistoryBookmarksSheet = true
                }
              )

              // History
              DropdownMenuItem(
                text = {
                  Text(
                    "History",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = ThemeCyber.colors.primary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  historyBookmarksInitialTab = 0
                  showHistoryBookmarksSheet = true
                }
              )

              // Downloads Drawer
              DropdownMenuItem(
                text = {
                  Text(
                    if (downloadsList.isEmpty()) "Downloads" else "Downloads (${downloadsList.size})",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = ThemeCyber.colors.primary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  showDownloadsSheet = true
                }
              )

              // Tor Circuit
              DropdownMenuItem(
                text = {
                  Text(
                    "Tor Circuit Info",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Public,
                    contentDescription = null,
                    tint = ThemeCyber.colors.successGreen,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  showCircuitSheet = true
                }
              )

              HorizontalDivider(
                color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp)
              )

              // Security Center / Shield Hub
              DropdownMenuItem(
                text = {
                  Text(
                    "Security Center",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Shield,
                    contentDescription = null,
                    tint = if (ThemeCyber.colors.isLight) ThemeCyber.colors.primary else ThemeCyber.colors.neonCyan,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  showSecurityCenter = true
                }
              )

              // Remmi Vault / Passwords
              DropdownMenuItem(
                text = {
                  Text(
                    "Password Vault (Autofill)",
                    color = ThemeCyber.colors.primary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = ThemeCyber.colors.primary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  onOpenPasswords()
                }
              )

              // Settings
              DropdownMenuItem(
                text = {
                  Text(
                    "Settings",
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = ThemeCyber.colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  onOpenSettings()
                }
              )

              // Panic Mode (Data Wipe)
              DropdownMenuItem(
                text = {
                  Text(
                    "Panic Wipe",
                    color = ThemeCyber.colors.dangerRed,
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                  )
                },
                leadingIcon = {
                  Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = ThemeCyber.colors.dangerRed,
                    modifier = Modifier.size(18.dp)
                  )
                },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                onClick = {
                  showMenuDropdown = false
                  showPanicWipeDialog = true
                }
              )
            }
        }
      }
    }
  }
}

  // --- Modals & Sheets ---

  // 1. Tab Grid Sheet
  if (showTabGridSheet) {
    ModalBottomSheet(
      onDismissRequest = { showTabGridSheet = false },
      containerColor = ThemeCyber.colors.background,
      dragHandle = null,
    ) {
      TabGridSheet(
        tabs = tabs,
        tabGroups = tabGroups,
        activeIndex = activeTabIndex,
        onTabSelect = { index ->
          tabManager.switchTab(index)
          showTabGridSheet = false
        },
        onTabClose = { id ->
          tabManager.closeTab(id)
          scope.launch { com.remmi.browser.engine.GeckoEngineManager.getInstance(context).closeSessionSafely(id) }
        },
        onNewTab = { prof, groupId ->
          if (prof == PrivacyProfile.GHOST) {
            handleOpenGhostTab("about:blank")
          } else {
            tabManager.openTab(
              profile = prof,
              isDesktop = settings.defaultDesktopMode,
              groupId = groupId
            )
          }
          showTabGridSheet = false
        },
        onCreateGroup = { title, colorHex, tabIds ->
          tabManager.createGroup(title, colorHex, tabIds)
        },
        onAddTabToGroup = { tabId, groupId ->
          tabManager.addTabToGroup(tabId, groupId)
        },
        onRemoveTabFromGroup = { tabId ->
          tabManager.removeTabFromGroup(tabId)
        },
        onUpdateGroup = { groupId, title, colorHex ->
          tabManager.updateGroup(groupId, title, colorHex)
        },
        onDeleteGroup = { groupId, closeTabs ->
          tabManager.deleteGroup(groupId, closeTabs)
        },
        onToggleGroupCollapse = { groupId ->
          tabManager.toggleGroupCollapse(groupId)
        },
        onSetTabInactive = { tabId, isInactive ->
          tabManager.setTabInactive(tabId, isInactive)
        },
        onSetGroupInactive = { groupId, isInactive ->
          tabManager.setGroupInactive(groupId, isInactive)
        },
        onCloseAllInactiveTabs = {
          tabManager.closeAllInactiveTabs()
        },
        onDuplicateTab = { tabId ->
          tabManager.duplicateTab(tabId)
        },
        onTogglePinTab = { tabId ->
          tabManager.togglePinTab(tabId)
        },
        onToggleLockTab = { tabId ->
          tabManager.toggleLockTab(tabId)
        },
        onCloseMultipleTabs = { tabIds ->
          tabManager.closeMultipleTabs(tabIds)
        },
        onLockMultipleTabs = { tabIds, lock ->
          tabManager.lockTabs(tabIds, lock)
        },
        onSetMultipleTabsInactive = { tabIds, inactive ->
          tabManager.setTabsInactive(tabIds, inactive)
        },
        onMoveMultipleTabsToGroup = { tabIds, groupId ->
          tabManager.moveTabsToGroup(tabIds, groupId)
        },
        onCloseAllTabs = {
          tabManager.closeAllTabs(settings.defaultProfile)
          showTabGridSheet = false
        },
        onOpenSettings = {
          onOpenSettings()
        },
        onDismiss = { showTabGridSheet = false },
      )
    }
  }

  // 2. Security Shield Sheet
  if (showSecuritySheet) {
    ModalBottomSheet(
      onDismissRequest = { showSecuritySheet = false },
      containerColor = ThemeCyber.colors.background,
      dragHandle = null,
    ) {
      SecurityShieldSheet(
        profile = activeTab.profile,
        blockedCount = activeTab.blockedTrackersCount,
        blockedLog = activeTab.blockedLog,
        onToggleProfile = handleToggleGhostMode,
        onDismiss = { showSecuritySheet = false },
      )
    }
  }

  // Security Center Full Modal
  if (showSecurityCenter) {
    androidx.compose.ui.window.Dialog(
      onDismissRequest = { showSecurityCenter = false },
      properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
      SecurityCenterScreen(
        activeTab = activeTab,
        sitePolicyManager = sitePolicyManager,
        settingsRepo = settingsRepo,
        torManager = torManager,
        onSecurityLevelChange = { newLevel ->
          tabManager.setTabSecurityLevel(activeTab.id, newLevel)
          scope.launch {
            geckoEngine.updateTabSettings(activeTab.id, activeTab.isDesktopMode, activeTab.profile, newLevel)
          }
        },
        onBack = { showSecurityCenter = false }
      )
    }
  }

  // 3. Tor Circuit Sheet
  if (showCircuitSheet) {
    ModalBottomSheet(
      onDismissRequest = { showCircuitSheet = false },
      containerColor = ThemeCyber.colors.background,
      dragHandle = null,
    ) {
      CircuitVisualizerSheet(
        torState = torState,
        circuit = circuit,
        onRotateCircuit = {
          scope.launch { privacyController.rotateTorCircuit() }
        },
        onStartTor = {
          scope.launch {
            privacyController.resetTorFailures()
            privacyController.enterGhostMode(activeTab.id)
          }
        },
        onLaunchOrbot = {
          torManager.getOrbotStartIntent()?.let { intent ->
            context.startActivity(intent)
          }
        },
        isOrbotInstalled = torManager.isOrbotInstalled(),
        onCheckTorProject = {
          showCircuitSheet = false
          handleOpenGhostTab("https://check.torproject.org")
        },
        onDismiss = { showCircuitSheet = false },
      )

    }
  }

  // 4. History & Bookmarks Sheet
  if (showHistoryBookmarksSheet) {
    val db = database
    if (db != null) {
      val activityViewModel: ActivityViewModel = viewModel(
        key = "activity_vm_${db.hashCode()}",
        factory = ActivityViewModelFactory(db.historyDao(), db.bookmarkDao())
      )
      ModalBottomSheet(
        onDismissRequest = { showHistoryBookmarksSheet = false },
        containerColor = ThemeCyber.colors.background,
        dragHandle = null,
      ) {
        ActivityScreen(
          viewModel = activityViewModel,
          initialTab = historyBookmarksInitialTab,
          onSelectUrl = { url ->
            tabManager.updateTab(activeTab.id) { it.copy(url = url, isReaderMode = false, readerArticle = null) }
          },
          onDismiss = { showHistoryBookmarksSheet = false }
        )
      }
    }
  }

  // 5. Downloads Sheet
  if (showDownloadsSheet) {
    ModalBottomSheet(
      onDismissRequest = { showDownloadsSheet = false },
      containerColor = ThemeCyber.colors.background,
      dragHandle = null,
    ) {
      DownloadsDrawer(
        downloadsList = downloadsList,
        onDeleteDownload = { item ->
          scope.launch(Dispatchers.IO) {
            val db = RemmiDatabase.getDatabaseAsync(context)
            db.downloadDao().delete(item)
          }
        },
        onClearAll = {
          scope.launch(Dispatchers.IO) {
            val db = RemmiDatabase.getDatabaseAsync(context)
            db.downloadDao().clearAll()
          }
        },
        onDismiss = { showDownloadsSheet = false },
      )
    }
  }

  // 5.1. Reading List / Offline Saved Articles Screen
  if (showReadingListScreen) {
    ReadingListScreen(
      onOpenUrl = { url ->
        tabManager.updateTab(activeTab.id) { it.copy(url = url, isReaderMode = false, readerArticle = null) }
        showReadingListScreen = false
      },
      onDismiss = { showReadingListScreen = false }
    )
  }

  // 6. Long Press Context Menu Sheet (Links & Images matching user design)
  activeContextMenuData?.let { data ->
    WebContextMenuSheet(
      data = data,
      onDismiss = { activeContextMenuData = null },
      onOpenInNewTab = { url ->
        tabManager.openTab(url = url, profile = activeTab.profile, isDesktop = settings.defaultDesktopMode)
      },
      onOpenInNewTabInBackground = { url ->
        tabManager.openTabInBackground(url = url, profile = activeTab.profile, isDesktop = settings.defaultDesktopMode)
        android.widget.Toast.makeText(context, "Opened in background tab", android.widget.Toast.LENGTH_SHORT).show()
      },
      onOpenInNewTabInGroup = { url ->
        pendingTabGroupUrl = url
        showTabGroupSelectDialog = true
      },
      onOpenInInPrivateTab = { url ->
        handleOpenGhostTab(url)
      },
      onOpenInNewWindow = { url ->
        tabManager.openTabInBackground(url = url, profile = activeTab.profile, isDesktop = settings.defaultDesktopMode)
        secondaryTabId = tabManager.tabs.value.last().id
        showSplitScreen = true
        android.widget.Toast.makeText(context, "Opened in Split View", android.widget.Toast.LENGTH_SHORT).show()
      },
      onPreviewPage = { url, title ->
        pagePreviewData = Pair(url, title)
      },
      onPreviewImage = { imgUrl, title ->
        imagePreviewData = Pair(imgUrl, title)
      },
      onAskAiAboutImage = { imgUrl, title ->
      },
      onCopyLinkAddress = { url ->
        clipboardMgr.copyWithAutoClear(url)
        android.widget.Toast.makeText(context, "Link address copied", android.widget.Toast.LENGTH_SHORT).show()
      },
      onCopyLinkText = { text ->
        clipboardMgr.copyWithAutoClear(text)
        android.widget.Toast.makeText(context, "Link text copied", android.widget.Toast.LENGTH_SHORT).show()
      },
      onCopyImage = { imgUrl ->
        scope.launch {
          val success = clipboardMgr.copyImageDirect(imgUrl)
          if (success) {
            android.widget.Toast.makeText(context, "Image copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
          } else {
            android.widget.Toast.makeText(context, "Failed to copy image", android.widget.Toast.LENGTH_SHORT).show()
          }
        }
      },
      onDownloadLink = { url ->
        val filename = url.substringAfterLast('/').substringBefore('?').ifEmpty { "download_${System.currentTimeMillis()}" }
        DownloadHandler.getInstance(context).enqueueDownload(
          url = url,
          suggestedFilename = filename,
          mimeType = "application/octet-stream",
          contentLength = 0L,
          isGhost = activeTab.profile == PrivacyProfile.GHOST
        )
      },
      onDownloadImage = { imgUrl ->
        val ext = imgUrl.substringAfterLast('.', "jpg").substringBefore('?')
        val filename = "image_${System.currentTimeMillis()}.$ext"
        DownloadHandler.getInstance(context).enqueueDownload(
          url = imgUrl,
          suggestedFilename = filename,
          mimeType = "image/*",
          contentLength = 0L,
          isGhost = activeTab.profile == PrivacyProfile.GHOST
        )
      },
      onSearchWebForImage = { imgUrl ->
        val searchUrl = "https://www.bing.com/images/search?q=imgurl:${URLEncoder.encode(imgUrl, "UTF-8")}&view=detailv2"
        tabManager.openTab(url = searchUrl, profile = activeTab.profile, isDesktop = settings.defaultDesktopMode)
      },
      onShareLink = { url, title ->
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(android.content.Intent.EXTRA_SUBJECT, title)
          putExtra(android.content.Intent.EXTRA_TEXT, url)
        }
        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share link"))
      },
      onShareImage = { imgUrl, title ->
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(android.content.Intent.EXTRA_SUBJECT, title)
          putExtra(android.content.Intent.EXTRA_TEXT, imgUrl)
        }
        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share image"))
      },
      onInspectRedirects = { urlToInspect ->
        inspectingRedirectUrl = urlToInspect
      }
    )
  }

  // 6.2 Tab Group Select Dialog
  if (showTabGroupSelectDialog) {
    pendingTabGroupUrl?.let { urlToOpen ->
      TabGroupSelectDialog(
        targetUrl = urlToOpen,
        groups = tabGroups,
        tabs = tabs,
        onDismiss = {
          showTabGroupSelectDialog = false
          pendingTabGroupUrl = null
        },
        onSelectExistingGroup = { groupId ->
          tabManager.openTabInBackground(
            url = urlToOpen,
            profile = activeTab.profile,
            isDesktop = settings.defaultDesktopMode,
            groupId = groupId
          )
          showTabGroupSelectDialog = false
          pendingTabGroupUrl = null
          android.widget.Toast.makeText(context, "Opened in tab group", android.widget.Toast.LENGTH_SHORT).show()
        },
        onCreateNewGroup = { title, colorHex ->
          val newGroup = tabManager.createGroup(title, colorHex)
          tabManager.openTabInBackground(
            url = urlToOpen,
            profile = activeTab.profile,
            isDesktop = settings.defaultDesktopMode,
            groupId = newGroup.id
          )
          showTabGroupSelectDialog = false
          pendingTabGroupUrl = null
          android.widget.Toast.makeText(context, "Group created and tab opened", android.widget.Toast.LENGTH_SHORT).show()
        }
      )
    }
  }

  // 6.5. Link Transparency Redirect Inspector Sheet
  inspectingRedirectUrl?.let { targetUrl ->
    RedirectInspectorSheet(
      initialUrl = targetUrl,
      isGhost = activeTab.profile == PrivacyProfile.GHOST,
      candidates = detectedClickCandidates,
      actualBrowserLandedUrl = activeTab.url,
      onDismiss = {
        inspectingRedirectUrl = null
        detectedClickCandidates = emptyList()
      },
      onOpenUrl = { finalUrl ->
        inspectingRedirectUrl = null
        detectedClickCandidates = emptyList()
        if (RedirectInspector.isSchemeSafeForNavigation(finalUrl)) {
          tabManager.updateTab(activeTab.id) { it.copy(url = finalUrl, isReaderMode = false, readerArticle = null) }
          geckoEngine.loadUrl(activeTab.id, finalUrl)
        } else {
          android.widget.Toast.makeText(context, "Blocked navigation to unsafe URL scheme", android.widget.Toast.LENGTH_SHORT).show()
        }
      }
    )
  }

  // 6.55. Click Transparency Candidates Sheet (when overlays or multiple targets detected)
  if (detectedClickCandidates.isNotEmpty() && inspectingRedirectUrl == null) {
    ClickCandidatesSheet(
      candidates = detectedClickCandidates,
      onSelectCandidate = { candidate ->
        detectedClickCandidates = emptyList()
        val targetUrl = candidate.cleanUrl
        if (RedirectInspector.isSchemeSafeForNavigation(targetUrl)) {
          tabManager.updateTab(activeTab.id) { it.copy(url = targetUrl, isReaderMode = false, readerArticle = null) }
          geckoEngine.loadUrl(activeTab.id, targetUrl)
        } else {
          android.widget.Toast.makeText(context, "Blocked navigation to unsafe URL scheme", android.widget.Toast.LENGTH_SHORT).show()
        }
      },
      onInspectCandidate = { candidate ->
        inspectingRedirectUrl = candidate.url
      },
      onDismiss = {
        detectedClickCandidates = emptyList()
      }
    )
  }

  // 6.6. URL Security Telemetry Sheet
  if (showUrlSecuritySheet) {
    UrlSecuritySheet(
      url = activeTab.url,
      isSecure = activeTab.isSecure,
      profile = activeTab.profile,
      trackersBlocked = activeTab.blockedTrackersCount,
      securityLevel = activeTab.securityLevel,
      containerType = activeTab.containerType,
      onSecurityLevelChange = { newLevel ->
        tabManager.updateTab(activeTab.id) { it.copy(securityLevel = newLevel) }
        scope.launch {
          geckoEngine.updateTabSettings(activeTab.id, activeTab.isDesktopMode, activeTab.profile, newLevel)
        }
      },
      onDismiss = { showUrlSecuritySheet = false },
      onInspectRedirects = { redirectUrl ->
        showUrlSecuritySheet = false
        inspectingRedirectUrl = redirectUrl
      }
    )
  }

  // 7. Page Preview Sheet (Peek page)
  pagePreviewData?.let { (previewUrl, previewTitle) ->
    PagePreviewSheet(
      url = previewUrl,
      title = previewTitle,
      onDismiss = { pagePreviewData = null },
      onOpenFullTab = { url ->
        tabManager.openTab(url = url, profile = activeTab.profile, isDesktop = settings.defaultDesktopMode)
      }
    )
  }

  // 8. Image Full Preview Dialog
  imagePreviewData?.let { (imgUrl, title) ->
    ImagePreviewDialog(
      imageUrl = imgUrl,
      title = title,
      onDismiss = { imagePreviewData = null },
      onDownload = { url ->
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?')
        val filename = "image_${System.currentTimeMillis()}.$ext"
        DownloadHandler.getInstance(context).enqueueDownload(
          url = url,
          suggestedFilename = filename,
          mimeType = "image/*",
          contentLength = 0L,
          isGhost = activeTab.profile == PrivacyProfile.GHOST
        )
      },
      onShare = { url, t ->
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
          type = "text/plain"
          putExtra(android.content.Intent.EXTRA_SUBJECT, t)
          putExtra(android.content.Intent.EXTRA_TEXT, url)
        }
        context.startActivity(android.content.Intent.createChooser(shareIntent, "Share image"))
      },
      onOpenInTab = { url ->
        imagePreviewData = null
        tabManager.openTab(url = url, profile = activeTab.profile, isDesktop = settings.defaultDesktopMode)
      }
    )
  }

  // 9.5 DevTools Option Dialog
  if (showDevMenuDialog) {
    androidx.compose.material3.AlertDialog(
      onDismissRequest = { showDevMenuDialog = false },
      containerColor = ThemeCyber.colors.surface,
      title = {
        Text(
          text = "Developer Tools",
          color = ThemeCyber.colors.primary,
          fontFamily = ThemeCyber.fontFamily,
          fontWeight = FontWeight.ExtraBold,
          fontSize = 18.sp
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Button(
            onClick = {
              showDevMenuDialog = false
              android.widget.Toast.makeText(context, "Opening DOM Inspector...", android.widget.Toast.LENGTH_SHORT).show()
              val inspectScript = "javascript:(function(){" +
                "var existing=document.getElementById('__remmi_dom_inspector__');" +
                "if(existing){existing.remove();return;}" +
                "var d=document.createElement('div');" +
                "d.id='__remmi_dom_inspector__';" +
                "d.style='position:fixed;bottom:0;left:0;right:0;height:50%;background:#0a0e17;color:#00ffcc;font-family:monospace;font-size:12px;z-index:2147483647;border-top:2px solid #00ffcc;overflow:hidden;display:flex;flex-direction:column;box-shadow:0 -4px 20px rgba(0,0,0,0.85);';" +
                "var header='<div style=\"display:flex;justify-content:space-between;align-items:center;background:#131b26;padding:8px 12px;border-bottom:1px solid #1e293b;\"><b style=\"color:#00ffcc;font-size:12px;\">&lt;/&gt; REMMI DEVELOPER CONSOLE &amp; DOM</b><span onclick=\"document.getElementById(\\'__remmi_dom_inspector__\\').remove()\" style=\"cursor:pointer;color:#ff5555;font-weight:bold;padding:2px 8px;border:1px solid #ff5555;border-radius:4px;\">CLOSE [X]</span></div>';" +
                "var body='<div style=\"flex:1;overflow:auto;padding:10px;background:#06090e;\"><div style=\"color:#64748b;margin-bottom:6px;\">PAGE: ' + (document.title || 'Untitled') + ' | URL: ' + location.href + '</div><pre style=\"white-space:pre-wrap;color:#38bdf8;margin:0;font-size:11px;user-select:text;\">' + document.documentElement.outerHTML.replace(/[&<>\"]/g,function(t){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[t]||t;}) + '</pre></div>';" +
                "d.innerHTML=header+body;" +
                "document.body.appendChild(d);" +
                "})();"
              geckoEngine.executeScript(activeTab.id, inspectScript)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.secondary),
            shape = RoundedCornerShape(8.dp)
          ) {
            Icon(Icons.Default.Code, null, modifier = Modifier.size(18.dp), tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text("DOM Inspector & Console", color = Color.Black, fontFamily = ThemeCyber.fontFamily, fontWeight = FontWeight.Bold)
          }

          androidx.compose.material3.OutlinedButton(
            onClick = {
              showDevMenuDialog = false
              tabManager.openTab("view-source:${activeTab.url}", activeTab.profile, settings.defaultDesktopMode)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ThemeCyber.colors.textSecondary)
          ) {
            Icon(Icons.Default.FindInPage, null, modifier = Modifier.size(18.dp), tint = ThemeCyber.colors.textPrimary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("View Raw Page Source", color = ThemeCyber.colors.textPrimary, fontFamily = ThemeCyber.fontFamily)
          }
        }
      },
      confirmButton = {
        androidx.compose.material3.TextButton(onClick = { showDevMenuDialog = false }) {
          Text("Cancel", color = ThemeCyber.colors.dangerRed, fontFamily = ThemeCyber.fontFamily)
        }
      }
    )
  }

  // 10. Panic Wipe Dialog
  if (showPanicWipeDialog) {
    PanicWipeDialog(
      onDismiss = { showPanicWipeDialog = false },
      onWipeExecuted = {
        showPanicWipeDialog = false
      }
    )
  }

  // 11. Password Save Prompt Floating Card
  AnimatedVisibility(
    visible = savePrompt != null,
    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
  ) {
    savePrompt?.let { req ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp)
          .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
      ) {
        Card(
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.dp, ThemeCyber.colors.primary),
          elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth()
            ) {
              Icon(Icons.Default.Key, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(24.dp))
              Spacer(modifier = Modifier.width(10.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  "Save Password to Vault?",
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp,
                  color = ThemeCyber.colors.textPrimary,
                  fontFamily = ThemeCyber.fontFamily
                )
                Text(
                  req.origin.substringAfter("://").substringBefore('/'),
                  fontSize = 12.sp,
                  color = ThemeCyber.colors.textSecondary,
                  fontFamily = ThemeCyber.fontFamily
                )
              }
              IconButton(onClick = { autofillHelper.dismissSavePrompt() }) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = ThemeCyber.colors.textSecondary)
              }
            }
            if (req.username.isNotBlank()) {
              Spacer(modifier = Modifier.height(8.dp))
              Text("Account: ${req.username}", fontSize = 13.sp, color = ThemeCyber.colors.textPrimary, fontFamily = ThemeCyber.fontFamily)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.End
            ) {
              androidx.compose.material3.TextButton(onClick = { autofillHelper.dismissSavePrompt() }) {
                Text("Never", color = ThemeCyber.colors.textSecondary, fontFamily = ThemeCyber.fontFamily)
              }
              Spacer(modifier = Modifier.width(8.dp))
              Button(
                onClick = {
                  req.onSave()
                  android.widget.Toast.makeText(context, "Password saved to Vault", android.widget.Toast.LENGTH_SHORT).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
                shape = RoundedCornerShape(10.dp)
              ) {
                Text("Save", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, fontFamily = ThemeCyber.fontFamily)
              }
            }
          }
        }
      }
    }
  }

  // 12. Password Autofill Prompt Floating Card
  AnimatedVisibility(
    visible = fillPrompt != null,
    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
  ) {
    fillPrompt?.let { req ->
      Box(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp)
          .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter
      ) {
        Card(
          colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.dp, ThemeCyber.colors.primary),
          elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VpnKey, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  "Autofill Credentials",
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp,
                  color = ThemeCyber.colors.textPrimary,
                  fontFamily = ThemeCyber.fontFamily
                )
              }
              IconButton(onClick = { autofillHelper.dismissFillPrompt() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = ThemeCyber.colors.textSecondary)
              }
            }
            Spacer(modifier = Modifier.height(10.dp))
            req.credentials.forEach { cred ->
              androidx.compose.material3.Surface(
                shape = RoundedCornerShape(10.dp),
                color = ThemeCyber.colors.surfaceLight,
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 4.dp)
                  .clickable {
                    req.onSelect(cred)
                    val currentOrigin = try {
                      val uri = java.net.URI(activeTab.url)
                      "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}"
                    } catch(e: Exception) { activeTab.url }
                    
                    if (currentOrigin == req.origin && activeTab.id == req.tabId) {
                      val jsAutofill = com.remmi.browser.security.CredentialAutofillScript.generateSafeAutofillScript(cred.username, cred.password)
                      geckoEngine.executeScript(activeTab.id, jsAutofill)
                      android.widget.Toast.makeText(context, "Autofilled credentials", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                      android.widget.Toast.makeText(context, "Autofill aborted (Origin/Tab mismatch)", android.widget.Toast.LENGTH_SHORT).show()
                    }
                  }
              ) {
                Row(
                  modifier = Modifier.padding(12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Column {
                    Text(
                      cred.username.ifEmpty { "Account" },
                      fontWeight = FontWeight.SemiBold,
                      fontSize = 14.sp,
                      color = ThemeCyber.colors.textPrimary,
                      fontFamily = ThemeCyber.fontFamily
                    )
                    Text("••••••••••••", fontSize = 12.sp, color = ThemeCyber.colors.textSecondary)
                  }
                  Icon(Icons.Default.Key, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(18.dp))
                }
              }
            }
          }
        }
      }
    }
  }
}
}


