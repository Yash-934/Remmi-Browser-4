package com.remmi.browser.ui.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.remmi.browser.R
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.security.NetworkHardening
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.TorCircuit
import com.remmi.browser.security.TorManager
import com.remmi.browser.storage.BookmarkItem
import com.remmi.browser.storage.HistoryItem
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.SearchEngine
import com.remmi.browser.storage.SpeedDialItem
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.CyberTheme
import com.remmi.browser.ui.theme.ThemeCyber
import com.remmi.browser.util.DefaultBrowserHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

fun getFaviconUrl(url: String): String {
  // Remote favicon queries (e.g. Google S2) leak visited domains.
  // We return empty to rely exclusively on local site-provided icons and letter badges.
  return ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NewTabPage(
  profile: PrivacyProfile = PrivacyProfile.SHIELD,
  blockedTrackersCount: Int = 0,
  torState: TorManager.TorState = TorManager.TorState.OFF,
  circuit: TorCircuit? = null,
  isDesktopMode: Boolean = false,
  isReaderMode: Boolean = false,
  searchEngine: SearchEngine = SearchEngine.DUCK_DUCK_GO,
  speedDials: List<SpeedDialItem> = emptyList(),
  backgroundAnimation: String = BackgroundTypes.LIGHT_AURA_MESH,
  customWallpaperUri: String? = null,
  wallpaperDimLevel: Float = 0.0f,
  fullscreenWallpaperEnabled: Boolean = true,
  wallpaperScaleMode: String = "CROP",
  onSearch: (query: String, engine: SearchEngine) -> Unit = { _, _ -> },
  onNavigate: (String) -> Unit = {},
  onSelectSearchEngine: (SearchEngine) -> Unit = {},
  onSelectTheme: (CyberTheme) -> Unit = {},
  onAddSpeedDial: (SpeedDialItem) -> Unit = {},
  onEditSpeedDial: (SpeedDialItem) -> Unit = {},
  onDeleteSpeedDial: (String) -> Unit = {},
  onResetSpeedDials: () -> Unit = {},
  onUpdateWallpaper: (String?) -> Unit = {},
  onUpdateBackgroundAnimation: (String) -> Unit = {},
  onUpdateWallpaperDimLevel: (Float) -> Unit = {},
  onUpdateFullscreenWallpaper: (Boolean) -> Unit = {},
  onUpdateWallpaperScaleMode: (String) -> Unit = {},
  onNewTab: () -> Unit = {},
  onOpenBookmarks: () -> Unit = {},
  onOpenHistory: () -> Unit = {},
  onOpenDownloads: () -> Unit = {},
  onOpenSettings: () -> Unit = {},
  onOpenReadingList: () -> Unit = {},
  onToggleDesktop: () -> Unit = {},
  onToggleGhost: () -> Unit = {},
  onToggleReader: () -> Unit = {},
  onInspectCircuit: () -> Unit = {},
  onSecurityShieldClick: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val focusManager = LocalFocusManager.current
  val clipboard = remember { ClipboardManager(context) }
  val scrollState = rememberScrollState()

  var searchQuery by remember { mutableStateOf("") }
  var isSearchFocused by remember { mutableStateOf(false) }
  var showAddDialog by remember { mutableStateOf(false) }
  var showThemeDialog by remember { mutableStateOf(false) }
  var showWallpaperDialog by remember { mutableStateOf(false) }
  var showSearchEngineMenu by remember { mutableStateOf(false) }
  var isFavoritesEditMode by remember { mutableStateOf(false) }

  var editingItem by remember { mutableStateOf<SpeedDialItem?>(null) }
  var itemToDelete by remember { mutableStateOf<SpeedDialItem?>(null) }

  var copiedUrlPrompt by remember { mutableStateOf<String?>(null) }
  var dismissedCopiedUrl by remember { mutableStateOf<String?>(null) }
  var historySuggestions by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
  var bookmarkSuggestions by remember { mutableStateOf<List<BookmarkItem>>(emptyList()) }

  var isDefaultBrowser by remember {
    mutableStateOf(DefaultBrowserHelper.isDefaultBrowser(context))
  }
  var isDefaultPromptDismissed by rememberSaveable { mutableStateOf(false) }

  val defaultBrowserLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) {
    isDefaultBrowser = DefaultBrowserHelper.isDefaultBrowser(context)
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        isDefaultBrowser = DefaultBrowserHelper.isDefaultBrowser(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // Check clipboard on resume
  LaunchedEffect(Unit) {
    val clip = clipboard.getCopiedUrl()
    if (!clip.isNullOrBlank() && clip != dismissedCopiedUrl) {
      copiedUrlPrompt = clip
    }
  }

  // Live Query Suggestions for History & Bookmarks
  LaunchedEffect(searchQuery) {
    val query = searchQuery.trim()
    if (query.isNotEmpty()) {
      withContext(Dispatchers.IO) {
        val db = RemmiDatabase.getDatabaseAsync(context)
        val hist = db.historyDao().searchHistory(query)
        val bkmk = db.bookmarkDao().searchBookmarks(query)
        historySuggestions = hist.take(4)
        bookmarkSuggestions = bkmk.take(4)
      }
    } else {
      historySuggestions = emptyList()
      bookmarkSuggestions = emptyList()
    }
  }

  // Voice Search launcher
  val voiceSearchLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
      val query = matches?.firstOrNull()
      if (!query.isNullOrBlank()) {
        searchQuery = query
        onSearch(query, searchEngine)
      }
    }
  }

  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) {
      onUpdateWallpaper(uri.toString())
      onUpdateBackgroundAnimation(BackgroundTypes.CUSTOM_IMAGE)
    }
  }

  val isLight = ThemeCyber.colors.isLight
  val hasCustomWallpaper = customWallpaperUri != null

  // Greeting dynamic calculation
  val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
  val (greetingText, greetingIcon) = remember(currentHour) {
    when (currentHour) {
      in 5..11 -> "Good Morning" to "☼"
      in 12..16 -> "Good Afternoon" to "☼"
      in 17..21 -> "Good Evening" to "☾"
      else -> "Good Night" to "★"
    }
  }

  Box(modifier = modifier.fillMaxSize()) {
    // 1. Dynamic Background Animation / Custom Image if not rendered globally
    if (!fullscreenWallpaperEnabled) {
      CyberpunkBackground(
        backgroundType = backgroundAnimation,
        customWallpaperUri = customWallpaperUri,
        wallpaperDimLevel = wallpaperDimLevel,
        wallpaperScaleMode = wallpaperScaleMode,
      )
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(scrollState)
        .padding(horizontal = 18.dp)
        .padding(top = 8.dp, bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // ==========================================
      // 1. TOP HEADER (Secure Pill, Theme, Settings)
      // ==========================================
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // SECURE / TOR STATUS PILL (LEFT)
        val isTorConnected = torState is TorManager.TorState.READY || profile == PrivacyProfile.GHOST || profile == PrivacyProfile.INCOGNITO
        val pillBg = when {
          isTorConnected -> ThemeCyber.colors.torPurple.copy(alpha = if (isLight) 0.12f else 0.25f)
          isLight -> Color(0xFFE8F5E9)
          else -> Color(0xFF10281E)
        }
        val pillBorder = when {
          isTorConnected -> ThemeCyber.colors.torPurple.copy(alpha = 0.6f)
          isLight -> Color(0xFFA5D6A7)
          else -> Color(0xFF2E7D32)
        }
        val pillContentColor = when {
          isTorConnected -> ThemeCyber.colors.torPurple
          isLight -> Color(0xFF2E7D32)
          else -> Color(0xFF4CAF50)
        }
        val pillLabel = when {
          isTorConnected -> "TOR ONION"
          else -> "SECURE"
        }

        Surface(
          shape = RoundedCornerShape(20.dp),
          color = pillBg,
          border = BorderStroke(1.dp, pillBorder),
          shadowElevation = if (isLight) 1.dp else 0.dp,
          modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onSecurityShieldClick() }
            .testTag("home_security_pill")
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            if (isTorConnected) {
              Icon(
                painter = painterResource(R.drawable.ic_tor),
                contentDescription = "Tor Status",
                tint = pillContentColor,
                modifier = Modifier.size(15.dp)
              )
            } else {
              Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "Security Status",
                tint = pillContentColor,
                modifier = Modifier.size(15.dp)
              )
            }
            Text(
              text = pillLabel,
              color = pillContentColor,
              fontWeight = FontWeight.Bold,
              fontSize = 11.5.sp,
              letterSpacing = 0.8.sp,
              fontFamily = CyberMonoFamily
            )
          }
        }

        // RIGHT ACTION BUTTONS (Theme & Settings)
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          // Theme Switcher Button
          IconButton(
            onClick = { showThemeDialog = true },
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(
                if (isLight) Color.White.copy(alpha = 0.85f)
                else Color(0xFF1E293B).copy(alpha = 0.8f)
              )
              .border(
                1.dp,
                if (isLight) Color(0xFFE2E8F0) else Color(0xFF334155),
                CircleShape
              )
              .testTag("home_theme_toggle_button")
          ) {
            Icon(
              imageVector = Icons.Default.Palette,
              contentDescription = "Switch Theme",
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(18.dp)
            )
          }

          // Settings Button
          IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(
                if (isLight) Color.White.copy(alpha = 0.85f)
                else Color(0xFF1E293B).copy(alpha = 0.8f)
              )
              .border(
                1.dp,
                if (isLight) Color(0xFFE2E8F0) else Color(0xFF334155),
                CircleShape
              )
              .testTag("home_settings_button")
          ) {
            Icon(
              imageVector = Icons.Default.Settings,
              contentDescription = "Settings",
              tint = if (isLight) Color(0xFF475569) else Color(0xFF94A3B8),
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }

      // ==========================================
      // 2. GREETING & PANDA MASCOT SECTION
      // ==========================================
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 4.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Greeting Typography
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Text(
              text = "$greetingText $greetingIcon",
              color = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
              fontSize = 13.5.sp,
              fontWeight = FontWeight.Medium
            )
          }

          Text(
            text = "Explore the web",
            color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp
          )

          val annotatedYourWay = buildAnnotatedString {
            withStyle(
              SpanStyle(
                color = ThemeCyber.colors.primary,
                fontWeight = FontWeight.ExtraBold
              )
            ) {
              append("your ")
            }
            withStyle(
              SpanStyle(
                color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                fontWeight = FontWeight.Bold
              )
            ) {
              append("way")
            }
          }

          Text(
            text = annotatedYourWay,
            fontSize = 24.sp,
            letterSpacing = (-0.3).sp
          )
        }

        // Stylized Cute 3D Panda Mascot
        PandaMascotArt(
          size = 88.dp,
          isDarkTheme = !isLight,
          accentColor = ThemeCyber.colors.primary,
          modifier = Modifier.padding(start = 8.dp)
        )
      }

      // ==========================================
      // DEFAULT BROWSER PROMPT BANNER (Home Screen)
      // ==========================================
      if (!isDefaultBrowser && !isDefaultPromptDismissed) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (isLight) Color(0xFFEFF6FF) else Color(0xFF0F1E36),
          border = BorderStroke(
            1.dp,
            if (isLight) Color(0xFFBFDBFE) else Color(0xFF38BDF8).copy(alpha = 0.4f)
          ),
          shadowElevation = if (isLight) 1.dp else 0.dp,
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, bottom = 4.dp)
            .testTag("home_set_default_browser_card")
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(26.dp)
                  .clip(RoundedCornerShape(7.dp))
                  .background(if (isLight) Color(0xFFDBEAFE) else Color(0xFF1E3A8A).copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Language,
                  contentDescription = null,
                  tint = if (isLight) Color(0xFF1D4ED8) else Color(0xFF38BDF8),
                  modifier = Modifier.size(15.dp)
                )
              }

              Text(
                text = "Set Remmi as default browser",
                color = if (isLight) Color(0xFF1E3A8A) else Color(0xFFF1F5F9),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Button(
                onClick = {
                  val activity = context as? Activity
                  if (activity != null) {
                    DefaultBrowserHelper.requestSetDefaultBrowser(activity, defaultBrowserLauncher)
                  }
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = if (isLight) Color(0xFF2563EB) else Color(0xFF00E5FF),
                  contentColor = if (isLight) Color.White else Color(0xFF070B13)
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                  .height(28.dp)
                  .testTag("home_set_default_browser_btn")
              ) {
                Text(
                  text = "SET DEFAULT",
                  fontSize = 10.5.sp,
                  fontWeight = FontWeight.ExtraBold,
                  fontFamily = ThemeCyber.fontFamily,
                  letterSpacing = 0.3.sp
                )
              }

              IconButton(
                onClick = { isDefaultPromptDismissed = true },
                modifier = Modifier.size(24.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Dismiss",
                  tint = if (isLight) Color(0xFF94A3B8) else Color(0xFF64748B),
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          }
        }
      }

      // ==========================================
      // 3. REMMI BROWSER BRANDING
      // ==========================================
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        // Remmi Logo Container
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = if (isLight) Color.White else Color(0xFF131B26),
          border = BorderStroke(
            1.2.dp,
            if (isLight) Color(0xFFE2E8F0) else ThemeCyber.colors.primary.copy(alpha = 0.4f)
          ),
          shadowElevation = if (isLight) 3.dp else 0.dp,
          modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(16.dp))
        ) {
          Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
          ) {
            Image(
              painter = painterResource(id = R.drawable.ic_remmi_panda),
              contentDescription = "REMMI Browser Logo",
              modifier = Modifier
                .size(42.dp),
              contentScale = ContentScale.Fit
            )
          }
        }

        // REMMI BROWSER Title
        Text(
          text = "REMMI BROWSER",
          color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC),
          fontWeight = FontWeight.ExtraBold,
          fontSize = 13.5.sp,
          letterSpacing = 2.sp,
          fontFamily = CyberMonoFamily
        )
      }

      // ==========================================
      // 4. MAIN SEARCH / ADDRESS BAR
      // ==========================================
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 4.dp, bottom = 14.dp)
      ) {
        Surface(
          shape = RoundedCornerShape(28.dp),
          color = if (isLight) Color.White else Color(0xFF131B26),
          border = BorderStroke(
            1.2.dp,
            if (isSearchFocused) ThemeCyber.colors.primary
            else if (isLight) Color(0xFFE2E8F0)
            else Color(0xFF1E293B)
          ),
          shadowElevation = if (isLight) 3.dp else 0.dp,
          modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .testTag("home_search_bar_surface")
        ) {
          Row(
            modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            // Search Icon / Search Engine Icon
            IconButton(
              onClick = { showSearchEngineMenu = true },
              modifier = Modifier.size(32.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = ThemeCyber.colors.primary,
                modifier = Modifier.size(22.dp)
              )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Search Text Field
            Box(
              modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
              contentAlignment = Alignment.CenterStart
            ) {
              if (searchQuery.isEmpty()) {
                Text(
                  text = "Search or enter web address",
                  color = if (isLight) Color(0xFF94A3B8) else Color(0xFF64748B),
                  fontSize = 14.5.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }

              BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                textStyle = TextStyle(
                  color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                  fontSize = 14.5.sp,
                  fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(ThemeCyber.colors.primary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                  onSearch = {
                    val query = searchQuery.trim()
                    if (query.isNotEmpty()) {
                      focusManager.clearFocus()
                      if (query.startsWith("http://") || query.startsWith("https://") || (!query.contains(" ") && query.contains("."))) {
                        onNavigate(if (!query.startsWith("http://") && !query.startsWith("https://")) "https://$query" else query)
                      } else {
                        onSearch(query, searchEngine)
                      }
                    }
                  }
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("home_search_input")
              )
            }

            if (searchQuery.isNotEmpty()) {
              IconButton(
                onClick = { searchQuery = "" },
                modifier = Modifier.size(30.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Clear search",
                  tint = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                  modifier = Modifier.size(18.dp)
                )
              }
            } else {
              // Paste from Clipboard Action
              IconButton(
                onClick = {
                  val clip = clipboard.getCopiedUrl()
                  if (!clip.isNullOrBlank()) {
                    searchQuery = clip
                    Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                  } else {
                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                  }
                },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.ContentPaste,
                  contentDescription = "Paste URL from Clipboard",
                  tint = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                  modifier = Modifier.size(20.dp)
                )
              }

              // Voice Search Action
              IconButton(
                onClick = {
                  try {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                      putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to search...")
                    }
                    voiceSearchLauncher.launch(intent)
                  } catch (e: Exception) {
                    Toast.makeText(context, "Voice search unavailable", Toast.LENGTH_SHORT).show()
                  }
                },
                modifier = Modifier.size(32.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Mic,
                  contentDescription = "Voice Search",
                  tint = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                  modifier = Modifier.size(20.dp)
                )
              }
            }
          }
        }
      }

      // ==========================================
      // LIVE SUGGESTIONS DROPDOWN (If searching)
      // ==========================================
      if (searchQuery.trim().isNotEmpty() && (historySuggestions.isNotEmpty() || bookmarkSuggestions.isNotEmpty())) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = if (isLight) Color.White else Color(0xFF131B26),
          border = BorderStroke(1.dp, if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)),
          shadowElevation = 4.dp,
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
        ) {
          Column(modifier = Modifier.padding(8.dp)) {
            // Bookmarks matches
            bookmarkSuggestions.forEach { item ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .clickable {
                    focusManager.clearFocus()
                    onNavigate(item.url)
                  }
                  .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.Star,
                  contentDescription = null,
                  tint = Color(0xFFFFB300),
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = item.title,
                    color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = item.url,
                    color = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }
            }

            // History matches
            historySuggestions.forEach { item ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(8.dp))
                  .clickable {
                    focusManager.clearFocus()
                    onNavigate(item.url)
                  }
                  .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.History,
                  contentDescription = null,
                  tint = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = item.title,
                    color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = item.url,
                    color = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }
            }
          }
        }
      }

      // ==========================================
      // COPIED URL BANNER (If clipboard has link)
      // ==========================================
      copiedUrlPrompt?.let { url ->
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = if (isLight) Color(0xFFF0FDF4) else Color(0xFF132A1C),
          border = BorderStroke(1.dp, if (isLight) Color(0xFFBBF7D0) else Color(0xFF1B4D2E)),
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Default.ContentPaste,
                contentDescription = null,
                tint = Color(0xFF16A34A),
                modifier = Modifier.size(18.dp)
              )
              Column {
                Text(
                  text = "Link in clipboard",
                  color = if (isLight) Color(0xFF166534) else Color(0xFF4ADE80),
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = url,
                  color = if (isLight) Color(0xFF334155) else Color(0xFFCBD5E1),
                  fontSize = 12.5.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              TextButton(
                onClick = {
                  copiedUrlPrompt = null
                  dismissedCopiedUrl = url
                  onNavigate(url)
                }
              ) {
                Text("Open", color = Color(0xFF16A34A), fontWeight = FontWeight.Bold)
              }
              IconButton(
                onClick = {
                  copiedUrlPrompt = null
                  dismissedCopiedUrl = url
                },
                modifier = Modifier.size(28.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Dismiss",
                  tint = Color(0xFF64748B),
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }
      }

      // ==========================================
      // 5. QUICK ACTIONS CARD (Floating Glass Card)
      // ==========================================
      Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (isLight) Color.White else Color(0xFF131B26),
        border = BorderStroke(1.dp, if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)),
        shadowElevation = if (isLight) 2.5.dp else 0.dp,
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 16.dp)
          .testTag("home_quick_actions_card")
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp, horizontal = 8.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
          ) {
            // 1. Bookmarks
            QuickActionItem(
              icon = Icons.Default.Bookmark,
              label = "Bookmarks",
              isLight = isLight,
              accentColor = Color(0xFF3B82F6),
              onClick = onOpenBookmarks
            )

            // 2. History
            QuickActionItem(
              icon = Icons.Default.History,
              label = "History",
              isLight = isLight,
              accentColor = Color(0xFF8B5CF6),
              onClick = onOpenHistory
            )

            // 3. Downloads
            QuickActionItem(
              icon = Icons.Default.Download,
              label = "Downloads",
              isLight = isLight,
              accentColor = Color(0xFF10B981),
              onClick = onOpenDownloads
            )

            // 4. Reading List / Offline Articles
            QuickActionItem(
              icon = Icons.Default.MenuBook,
              label = "Reading List",
              isLight = isLight,
              accentColor = Color(0xFFF59E0B),
              onClick = onOpenReadingList
            )

            // 5. Tor Privacy Network / Onion Mode
            QuickActionItem(
              painter = painterResource(R.drawable.ic_tor),
              label = "Tor",
              isLight = isLight,
              accentColor = Color(0xFFA855F7),
              onClick = onToggleGhost
            )
          }

          Spacer(modifier = Modifier.height(8.dp))

          // Subtle Slider Indicator Dot
          Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .width(16.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ThemeCyber.colors.primary)
            )
            Box(
              modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(if (isLight) Color(0xFFCBD5E1) else Color(0xFF334155))
            )
          }
        }
      }

      // ==========================================
      // 6. FAVORITES / SHORTCUTS SECTION
      // ==========================================
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 16.dp)
      ) {
        // Section Header Row
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = "Quick Links",
              color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC),
              fontSize = 16.sp,
              fontWeight = FontWeight.Bold
            )
            IconButton(
              onClick = { isFavoritesEditMode = !isFavoritesEditMode },
              modifier = Modifier.size(24.dp)
            ) {
              Icon(
                imageVector = if (isFavoritesEditMode) Icons.Default.Check else Icons.Default.Edit,
                contentDescription = "Edit Quick Links",
                tint = if (isFavoritesEditMode) ThemeCyber.colors.primary else if (isLight) Color(0xFF94A3B8) else Color(0xFF64748B),
                modifier = Modifier.size(16.dp)
              )
            }
          }

          Text(
            text = "Show all",
            color = ThemeCyber.colors.primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .clickable { onOpenBookmarks() }
              .padding(horizontal = 4.dp, vertical = 2.dp)
          )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Favorites Grid Layout (5 columns)
        val defaultSpeedDials = listOf(
          SpeedDialItem(id = "ddg", title = "DuckDuckGo", url = "https://duckduckgo.com"),
          SpeedDialItem(id = "github", title = "GitHub", url = "https://github.com"),
          SpeedDialItem(id = "wikipedia", title = "Wikipedia", url = "https://wikipedia.org"),
          SpeedDialItem(id = "reddit", title = "Reddit", url = "https://reddit.com"),
          SpeedDialItem(id = "hackernews", title = "Hacker News", url = "https://news.ycombinator.com"),
          SpeedDialItem(id = "youtube", title = "YouTube", url = "https://youtube.com"),
          SpeedDialItem(id = "twitter", title = "X / Twitter", url = "https://x.com"),
          SpeedDialItem(id = "tor", title = "Tor Project", url = "https://torproject.org"),
          SpeedDialItem(id = "proton", title = "Proton", url = "https://proton.me"),
        )
        val effectiveSpeedDials = if (speedDials.isNotEmpty()) speedDials else defaultSpeedDials

        // Responsive grid with 5 columns
        val chunkedFavorites = effectiveSpeedDials.chunked(5)
        chunkedFavorites.forEach { rowItems ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            rowItems.forEach { item ->
              FavoriteTile(
                item = item,
                isLight = isLight,
                isEditMode = isFavoritesEditMode,
                onClick = {
                  if (isFavoritesEditMode) {
                    editingItem = item
                  } else {
                    onNavigate(item.url)
                  }
                },
                onLongClick = {
                  editingItem = item
                },
                onDelete = {
                  itemToDelete = item
                },
                modifier = Modifier.weight(1f)
              )
            }
            // Fill empty slots if row has fewer than 5 items
            val emptySlots = 5 - rowItems.size
            if (emptySlots > 0 && rowItems == chunkedFavorites.last()) {
              // Place Add button in next slot
              FavoriteAddTile(
                isLight = isLight,
                onClick = { showAddDialog = true },
                modifier = Modifier.weight(1f)
              )
              for (i in 1 until emptySlots) {
                Spacer(modifier = Modifier.weight(1f))
              }
            } else {
              for (i in 0 until emptySlots) {
                Spacer(modifier = Modifier.weight(1f))
              }
            }
          }
        }

        // If all rows were full, show Add Button on its own row
        if (effectiveSpeedDials.size % 5 == 0) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Start
          ) {
            FavoriteAddTile(
              isLight = isLight,
              onClick = { showAddDialog = true },
              modifier = Modifier.width(68.dp)
            )
          }
        }
      }
    }
  }

  // ==========================================
  // DIALOGS & OVERLAYS
  // ==========================================

  // 1. THEME & LIVE ANIMATION SELECTOR DIALOG
  if (showThemeDialog) {
    ThemeSelectorDialog(
      currentAnimation = backgroundAnimation,
      customWallpaperUri = customWallpaperUri,
      wallpaperDimLevel = wallpaperDimLevel,
      wallpaperScaleMode = wallpaperScaleMode,
      onDismiss = { showThemeDialog = false },
      onSelectTheme = { theme ->
        onSelectTheme(theme)
      },
      onSelectAnimation = { anim ->
        if (customWallpaperUri != null) {
          onUpdateWallpaper(null)
        }
        onUpdateBackgroundAnimation(anim)
      },
      onPickPhoto = {
        photoPickerLauncher.launch("image/*")
      },
      onClearWallpaper = {
        onUpdateWallpaper(null)
        onUpdateBackgroundAnimation(BackgroundTypes.LIGHT_AURA_MESH)
      },
      onUpdateDimLevel = onUpdateWallpaperDimLevel,
      onUpdateScaleMode = onUpdateWallpaperScaleMode
    )
  }

  // 2. SEARCH ENGINE SELECTOR DIALOG
  if (showSearchEngineMenu) {
    SearchEngineSelectorDialog(
      currentEngine = searchEngine,
      onDismiss = { showSearchEngineMenu = false },
      onSelect = { engine ->
        onSelectSearchEngine(engine)
        showSearchEngineMenu = false
      }
    )
  }

  // 3. ADD FAVORITE DIALOG
  if (showAddDialog) {
    AddFavoriteDialog(
      onDismiss = { showAddDialog = false },
      onAdd = { title, url ->
        val fixedUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) "https://$url" else url
        onAddSpeedDial(SpeedDialItem(title = title, url = fixedUrl))
        showAddDialog = false
      }
    )
  }

  // 4. EDIT FAVORITE DIALOG
  editingItem?.let { item ->
    EditFavoriteDialog(
      item = item,
      onDismiss = { editingItem = null },
      onSave = { updated ->
        onEditSpeedDial(updated)
        editingItem = null
      },
      onDelete = {
        onDeleteSpeedDial(item.id)
        editingItem = null
      }
    )
  }

  // 5. DELETE FAVORITE CONFIRMATION
  itemToDelete?.let { item ->
    AlertDialog(
      onDismissRequest = { itemToDelete = null },
      title = { Text("Delete Favorite?", fontWeight = FontWeight.Bold) },
      text = { Text("Are you sure you want to remove \"${item.title}\" from favorites?") },
      confirmButton = {
        TextButton(
          onClick = {
            onDeleteSpeedDial(item.id)
            itemToDelete = null
          }
        ) {
          Text("Delete", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { itemToDelete = null }) {
          Text("Cancel")
        }
      },
      shape = RoundedCornerShape(16.dp),
      containerColor = if (isLight) Color.White else Color(0xFF1E293B)
    )
  }

  // 6. WALLPAPER & VISIBILITY DIALOG
  if (showWallpaperDialog) {
    WallpaperDialog(
      customWallpaperUri = customWallpaperUri,
      wallpaperDimLevel = wallpaperDimLevel,
      wallpaperScaleMode = wallpaperScaleMode,
      onDismiss = { showWallpaperDialog = false },
      onPickPhoto = { photoPickerLauncher.launch("image/*") },
      onClearWallpaper = {
        onUpdateWallpaper(null)
        onUpdateBackgroundAnimation(BackgroundTypes.LIGHT_AURA_MESH)
      },
      onUpdateDimLevel = onUpdateWallpaperDimLevel,
      onUpdateScaleMode = onUpdateWallpaperScaleMode
    )
  }
}

/**
 * Quick Action Item Composable inside Quick Actions Card
 */
@Composable
private fun QuickActionItem(
  icon: ImageVector? = null,
  painter: androidx.compose.ui.graphics.painter.Painter? = null,
  label: String,
  isLight: Boolean,
  accentColor: Color,
  onClick: () -> Unit
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 4.dp)
  ) {
    // Icon Container
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(
          if (isLight) accentColor.copy(alpha = 0.12f)
          else accentColor.copy(alpha = 0.20f)
        )
        .border(
          1.dp,
          if (isLight) accentColor.copy(alpha = 0.25f)
          else accentColor.copy(alpha = 0.40f),
          CircleShape
        ),
      contentAlignment = Alignment.Center
    ) {
      if (painter != null) {
        Icon(
          painter = painter,
          contentDescription = label,
          tint = accentColor,
          modifier = Modifier.size(20.dp)
        )
      } else if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = label,
          tint = accentColor,
          modifier = Modifier.size(20.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    Text(
      text = label,
      color = if (isLight) Color(0xFF334155) else Color(0xFFCBD5E1),
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * Favorite Shortcut Tile
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteTile(
  item: SpeedDialItem,
  isLight: Boolean,
  isEditMode: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  onDelete: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val faviconUrl = remember(item.url) { getFaviconUrl(item.url) }
  val initialLetter = remember(item.title) {
    item.title.trim().firstOrNull()?.uppercase() ?: "W"
  }

  // Check if it's Tor Project
  val isTorSite = remember(item.url, item.id) {
    item.url.contains("torproject.org") || item.id.equals("tor", ignoreCase = true)
  }

  // Consistent background color for domain letter placeholder
  val tileAccentColor = remember(item.title) {
    val colors = listOf(
      Color(0xFF3B82F6), Color(0xFF10B981), Color(0xFFF59E0B),
      Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF06B6D4)
    )
    val hash = Math.abs(item.title.hashCode())
    colors[hash % colors.size]
  }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .padding(horizontal = 3.dp)
      .combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick
      )
  ) {
    Box(
      modifier = Modifier.size(54.dp),
      contentAlignment = Alignment.Center
    ) {
      // Tile Surface Card
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isLight) Color.White else Color(0xFF131B26),
        border = BorderStroke(
          1.dp,
          if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)
        ),
        shadowElevation = if (isLight) 2.dp else 0.dp,
        modifier = Modifier
          .fillMaxSize()
          .clip(RoundedCornerShape(16.dp))
      ) {
        Box(
          modifier = Modifier.fillMaxSize(),
          contentAlignment = Alignment.Center
        ) {
          BrandFaviconIcon(
            url = item.url,
            iconKey = item.iconKey,
            title = item.title,
            size = 30.dp
          )
        }
      }

      // Delete Badge in Edit Mode
      if (isEditMode) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = 4.dp, y = (-4).dp)
            .size(20.dp)
            .clip(CircleShape)
            .background(Color(0xFFEF4444))
            .clickable(onClick = onDelete),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Delete",
            tint = Color.White,
            modifier = Modifier.size(12.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(4.dp))

    Text(
      text = item.title,
      color = if (isLight) Color(0xFF334155) else Color(0xFFCBD5E1),
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      textAlign = TextAlign.Center,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.fillMaxWidth()
    )
  }
}

/**
 * Add Favorite Shortcut Tile
 */
@Composable
private fun FavoriteAddTile(
  isLight: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .padding(horizontal = 3.dp)
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onClick)
  ) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = if (isLight) Color(0xFFF1F5F9) else Color(0xFF1E293B).copy(alpha = 0.5f),
      border = BorderStroke(
        1.dp,
        if (isLight) Color(0xFFCBD5E1) else Color(0xFF334155)
      ),
      modifier = Modifier
        .size(54.dp)
        .clip(RoundedCornerShape(16.dp))
    ) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = "Add Favorite",
          tint = ThemeCyber.colors.primary,
          modifier = Modifier.size(24.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(4.dp))

    Text(
      text = "Add",
      color = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      textAlign = TextAlign.Center
    )
  }
}

/**
 * Theme & Live Background Animation Selector Dialog
 */
@Composable
private fun ThemeSelectorDialog(
  currentAnimation: String,
  customWallpaperUri: String?,
  wallpaperDimLevel: Float,
  wallpaperScaleMode: String,
  onDismiss: () -> Unit,
  onSelectTheme: (CyberTheme) -> Unit,
  onSelectAnimation: (String) -> Unit,
  onPickPhoto: () -> Unit,
  onClearWallpaper: () -> Unit,
  onUpdateDimLevel: (Float) -> Unit,
  onUpdateScaleMode: (String) -> Unit
) {
  val isLight = ThemeCyber.colors.isLight
  val accentColor = ThemeCyber.colors.primary
  var selectedTab by remember { mutableIntStateOf(0) } // 0: Live Animations, 1: Themes, 2: Wallpaper

  val themes = listOf(
    CyberTheme.NORMAL_DEFAULT,
    CyberTheme.MINIMAL_DARK,
    CyberTheme.JARVIS,
    CyberTheme.CYBER_MATRIX,
    CyberTheme.STARK_IND,
    CyberTheme.VERONICA
  )

  val animOptions = listOf(
    Triple(BackgroundTypes.LIGHT_AURA_MESH, "Soft Aura Waves", "Ambient fluid mesh gradient"),
    Triple(BackgroundTypes.LIGHT_FLOATING_ORBS, "Floating Pastel Orbs", "Soft floating glowing spheres"),
    Triple(BackgroundTypes.LIGHT_GEOMETRIC_DOTS, "Pulsing Dot Grid", "Clean geometric pulsing grid"),
    Triple(BackgroundTypes.LIGHT_CONSTELLATION, "Starry Constellation", "Interactive connected nodes"),
    Triple(BackgroundTypes.CYBERPUNK_GRID, "3D Cyberpunk Grid", "Retro neon horizon wireframe"),
    Triple(BackgroundTypes.MATRIX_RAIN, "Matrix Digital Rain", "Cyberpunk falling glyph streams"),
    Triple(BackgroundTypes.NEON_PARTICLES, "Quantum Particles", "High-energy glowing dust"),
    Triple(BackgroundTypes.DIGITAL_AURORA, "Digital Neon Aurora", "Luminous flowing ribbons"),
    Triple(BackgroundTypes.MINIMAL_GRADIENT, "Minimal Stealth Gradient", "Clean distraction-free canvas")
  )

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(22.dp),
      color = if (isLight) Color.White else Color(0xFF0F172A),
      border = BorderStroke(1.dp, if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 16.dp)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
              imageVector = Icons.Default.Palette,
              contentDescription = null,
              tint = accentColor,
              modifier = Modifier.size(22.dp)
            )
            Text(
              text = "Theme & Appearance",
              fontWeight = FontWeight.Bold,
              fontSize = 17.sp,
              color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
            )
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close",
              tint = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8)
            )
          }
        }

        // Segmented Tabs: Live Animations | Themes | Wallpaper
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = if (isLight) Color(0xFFF1F5F9) else Color(0xFF1E293B),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(3.dp),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            val tabs = listOf("✨ Live Motion", "🎨 Colors", "🖼️ Wallpaper")
            tabs.forEachIndexed { index, title ->
              val isSelected = selectedTab == index
              Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (isSelected) {
                  if (isLight) Color.White else Color(0xFF334155)
                } else Color.Transparent,
                shadowElevation = if (isSelected && isLight) 1.dp else 0.dp,
                modifier = Modifier
                  .weight(1f)
                  .clip(RoundedCornerShape(9.dp))
                  .clickable { selectedTab = index }
              ) {
                Box(
                  modifier = Modifier.padding(vertical = 8.dp),
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    text = title,
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) {
                      accentColor
                    } else {
                      if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8)
                    }
                  )
                }
              }
            }
          }
        }

        // Tab Content
        when (selectedTab) {
          // 0: Live Animations
          0 -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = "Choose Live Background Animation:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isLight) Color(0xFF475569) else Color(0xFF94A3B8)
              )

              LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 280.dp)
              ) {
                items(animOptions) { (type, name, desc) ->
                  val isSelected = customWallpaperUri == null && currentAnimation == type
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) accentColor.copy(alpha = if (isLight) 0.12f else 0.20f)
                            else if (isLight) Color(0xFFF8FAFC) else Color(0xFF1E293B),
                    border = BorderStroke(
                      width = if (isSelected) 1.8.dp else 1.dp,
                      color = if (isSelected) accentColor else if (isLight) Color(0xFFE2E8F0) else Color(0xFF334155)
                    ),
                    modifier = Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(12.dp))
                      .clickable { onSelectAnimation(type) }
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                      Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                      ) {
                        Box(
                          modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(
                              if (isSelected) accentColor.copy(alpha = 0.25f)
                              else if (isLight) Color(0xFFE2E8F0) else Color(0xFF334155)
                            ),
                          contentAlignment = Alignment.Center
                        ) {
                          Icon(
                            imageVector = when (type) {
                              BackgroundTypes.MATRIX_RAIN -> Icons.Default.Terminal
                              BackgroundTypes.CYBERPUNK_GRID -> Icons.Default.GridOn
                              BackgroundTypes.NEON_PARTICLES -> Icons.Default.BlurOn
                              BackgroundTypes.DIGITAL_AURORA -> Icons.Default.Waves
                              BackgroundTypes.LIGHT_FLOATING_ORBS -> Icons.Default.BubbleChart
                              BackgroundTypes.LIGHT_CONSTELLATION -> Icons.Default.Hub
                              BackgroundTypes.LIGHT_GEOMETRIC_DOTS -> Icons.Default.Grain
                              BackgroundTypes.MINIMAL_GRADIENT -> Icons.Default.Contrast
                              else -> Icons.Default.AutoAwesome
                            },
                            contentDescription = null,
                            tint = if (isSelected) accentColor else if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                            modifier = Modifier.size(18.dp)
                          )
                        }

                        Column {
                          Text(
                            text = name,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
                          )
                          Text(
                            text = desc,
                            fontSize = 10.5.sp,
                            color = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                          )
                        }
                      }

                      if (isSelected) {
                        Box(
                          modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(accentColor),
                          contentAlignment = Alignment.Center
                        ) {
                          Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          // 1: Themes
          1 -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = "Preset Color Schemes:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isLight) Color(0xFF475569) else Color(0xFF94A3B8)
              )

              LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 280.dp)
              ) {
                items(themes) { theme ->
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (theme.isLight) Color(0xFFF8FAFC) else Color(0xFF1E293B),
                    border = BorderStroke(1.5.dp, theme.primaryAccent),
                    modifier = Modifier
                      .fillMaxWidth()
                      .height(60.dp)
                      .clip(RoundedCornerShape(12.dp))
                      .clickable { onSelectTheme(theme) }
                  ) {
                    Row(
                      modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      Box(
                        modifier = Modifier
                          .size(20.dp)
                          .clip(CircleShape)
                          .background(theme.primaryAccent)
                      )
                      Column {
                        Text(
                          text = theme.displayName,
                          fontSize = 11.5.sp,
                          fontWeight = FontWeight.Bold,
                          color = if (theme.isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC),
                          maxLines = 1
                        )
                        Text(
                          text = if (theme.isLight) "Light Mode" else "Dark Mode",
                          fontSize = 10.sp,
                          color = if (theme.isLight) Color(0xFF64748B) else Color(0xFF94A3B8)
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          // 2: Custom Wallpaper
          2 -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Text(
                text = "Custom Gallery Photo:",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isLight) Color(0xFF475569) else Color(0xFF94A3B8)
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Button(
                  onClick = onPickPhoto,
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Choose Photo")
                }

                if (customWallpaperUri != null) {
                  OutlinedButton(
                    onClick = onClearWallpaper,
                    shape = RoundedCornerShape(10.dp)
                  ) {
                    Text("Reset")
                  }
                }
              }

              // Visibility Slider
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val visibilityPercent = ((1f - wallpaperDimLevel) * 100).toInt()
                Text(
                  text = "Wallpaper Clarity: $visibilityPercent%",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium,
                  color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
                )
                Slider(
                  value = 1f - wallpaperDimLevel,
                  onValueChange = { onUpdateDimLevel(1f - it) },
                  colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor
                  )
                )
              }

              // Scaling Mode Chips
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                  text = "Scaling Mode",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Medium,
                  color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                  listOf("CROP" to "Fill Crop", "FIT" to "Fit", "FILL" to "Stretch").forEach { (mode, label) ->
                    val isSelected = wallpaperScaleMode.equals(mode, ignoreCase = true)
                    FilterChip(
                      selected = isSelected,
                      onClick = { onUpdateScaleMode(mode) },
                      label = { Text(label, fontSize = 11.5.sp) }
                    )
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

/**
 * Search Engine Selector Dialog
 */
@Composable
private fun SearchEngineSelectorDialog(
  currentEngine: SearchEngine,
  onDismiss: () -> Unit,
  onSelect: (SearchEngine) -> Unit
) {
  val isLight = ThemeCyber.colors.isLight
  val engines = SearchEngine.entries

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = if (isLight) Color.White else Color(0xFF0F172A),
      border = BorderStroke(1.dp, if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Select Search Engine",
          fontWeight = FontWeight.Bold,
          fontSize = 17.sp,
          color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
        )

        engines.forEach { engine ->
          val isSelected = engine == currentEngine
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isSelected) ThemeCyber.colors.primary.copy(alpha = 0.15f)
            else if (isLight) Color(0xFFF8FAFC) else Color(0xFF1E293B),
            border = BorderStroke(
              1.dp,
              if (isSelected) ThemeCyber.colors.primary else Color.Transparent
            ),
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .clickable { onSelect(engine) }
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column {
                Text(
                  text = engine.displayName,
                  fontWeight = FontWeight.Bold,
                  fontSize = 14.sp,
                  color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
                )
                Text(
                  text = engine.subtitle,
                  fontSize = 11.sp,
                  color = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8)
                )
              }
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.CheckCircle,
                  contentDescription = "Selected",
                  tint = ThemeCyber.colors.primary,
                  modifier = Modifier.size(20.dp)
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Add Favorite Dialog
 */
@Composable
private fun AddFavoriteDialog(
  onDismiss: () -> Unit,
  onAdd: (title: String, url: String) -> Unit
) {
  val isLight = ThemeCyber.colors.isLight
  var title by remember { mutableStateOf("") }
  var url by remember { mutableStateOf("") }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = if (isLight) Color.White else Color(0xFF0F172A),
      border = BorderStroke(1.dp, if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        Text(
          text = "Add to Favorites",
          fontWeight = FontWeight.Bold,
          fontSize = 18.sp,
          color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
        )

        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Title") },
          placeholder = { Text("e.g. GitHub") },
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text("URL / Web Address") },
          placeholder = { Text("e.g. github.com") },
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically
        ) {
          TextButton(onClick = onDismiss) {
            Text("Cancel")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              if (url.isNotBlank()) {
                val resolvedTitle = if (title.isBlank()) url.replace("https://", "").replace("http://", "").substringBefore("/") else title
                onAdd(resolvedTitle, url)
              }
            },
            enabled = url.isNotBlank(),
            shape = RoundedCornerShape(10.dp)
          ) {
            Text("Add Favorite")
          }
        }
      }
    }
  }
}

/**
 * Edit Favorite Dialog
 */
@Composable
private fun EditFavoriteDialog(
  item: SpeedDialItem,
  onDismiss: () -> Unit,
  onSave: (SpeedDialItem) -> Unit,
  onDelete: () -> Unit
) {
  val isLight = ThemeCyber.colors.isLight
  var title by remember { mutableStateOf(item.title) }
  var url by remember { mutableStateOf(item.url) }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = if (isLight) Color.White else Color(0xFF0F172A),
      border = BorderStroke(1.dp, if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Edit Favorite",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
          )
          IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(
              imageVector = Icons.Default.Delete,
              contentDescription = "Delete",
              tint = Color(0xFFEF4444)
            )
          }
        }

        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Title") },
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text("URL / Web Address") },
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth()
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically
        ) {
          TextButton(onClick = onDismiss) {
            Text("Cancel")
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              if (url.isNotBlank()) {
                onSave(item.copy(title = title, url = url))
              }
            },
            enabled = url.isNotBlank(),
            shape = RoundedCornerShape(10.dp)
          ) {
            Text("Save")
          }
        }
      }
    }
  }
}

/**
 * Wallpaper & Clarity Dialog
 */
@Composable
private fun WallpaperDialog(
  customWallpaperUri: String?,
  wallpaperDimLevel: Float,
  wallpaperScaleMode: String,
  onDismiss: () -> Unit,
  onPickPhoto: () -> Unit,
  onClearWallpaper: () -> Unit,
  onUpdateDimLevel: (Float) -> Unit,
  onUpdateScaleMode: (String) -> Unit
) {
  val isLight = ThemeCyber.colors.isLight

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(20.dp),
      color = if (isLight) Color.White else Color(0xFF0F172A),
      border = BorderStroke(1.dp, if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)),
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Column(
        modifier = Modifier.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "Wallpaper Settings",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
          )
          IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close",
              tint = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8)
            )
          }
        }

        // Pick Photo
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Button(
            onClick = onPickPhoto,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
          ) {
            Icon(Icons.Default.PhotoLibrary, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Choose Image")
          }

          if (customWallpaperUri != null) {
            OutlinedButton(
              onClick = onClearWallpaper,
              shape = RoundedCornerShape(10.dp)
            ) {
              Text("Reset")
            }
          }
        }

        // Visibility Slider
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          val visibilityPercent = ((1f - wallpaperDimLevel) * 100).toInt()
          Text(
            text = "Clarity: $visibilityPercent%",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
          )
          Slider(
            value = 1f - wallpaperDimLevel,
            onValueChange = { onUpdateDimLevel(1f - it) },
            colors = SliderDefaults.colors(
              thumbColor = ThemeCyber.colors.primary,
              activeTrackColor = ThemeCyber.colors.primary
            )
          )
        }

        // Scale Mode Chips
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            text = "Scaling Mode",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("CROP" to "Fill Crop", "FIT" to "Fit", "FILL" to "Stretch").forEach { (mode, label) ->
              val isSelected = wallpaperScaleMode.equals(mode, ignoreCase = true)
              FilterChip(
                selected = isSelected,
                onClick = { onUpdateScaleMode(mode) },
                label = { Text(label, fontSize = 11.5.sp) }
              )
            }
          }
        }
      }
    }
  }
}
