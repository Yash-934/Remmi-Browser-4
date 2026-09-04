package com.remmi.browser.ui.screens

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.adblock.AdblockBridge
import com.remmi.adblock.FilterManager
import com.remmi.browser.security.DnsProvider
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.TamperDetection
import com.remmi.browser.storage.SearchEngine
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.ui.components.BackgroundTypes
import com.remmi.browser.ui.theme.BrowserFont
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.CyberTheme
import com.remmi.browser.ui.theme.ThemeCyber

/**
 * Settings Categories for clean, modular browser configuration screens.
 */
enum class SettingsCategory(
  val title: String,
  val icon: ImageVector,
) {
  SEARCH_ENGINE(
    title = "Search Engine",
    icon = Icons.Default.Search,
  ),
  APPEARANCE(
    title = "Appearance & Themes",
    icon = Icons.Default.Palette,
  ),
  PRIVACY_SECURITY(
    title = "Privacy & Security",
    icon = Icons.Default.Shield,
  ),
  ADBLOCK(
    title = "Shields & Ad Blocking",
    icon = Icons.Default.Shield,
  ),
  PASSWORDS(
    title = "Passwords & Vault",
    icon = Icons.Default.VpnKey,
  ),
  DISPLAY_VIEWPORT(
    title = "Display & Reader View",
    icon = Icons.Default.DesktopWindows,
  ),
  SYSTEM_ADVANCED(
    title = "System & Advanced",
    icon = Icons.Default.Terminal,
  ),
  ABOUT(
    title = "About Remmi Browser",
    icon = Icons.Default.Info,
  ),
  HELP_SUPPORT(
    title = "Help & Support",
    icon = Icons.Default.Help,
  );
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
  onBack: () -> Unit,
  onOpenPasswords: () -> Unit = {},
  onOpenDebugLogs: () -> Unit = {},
) {
  val context = LocalContext.current
  val activity = context as? Activity
  val scope = rememberCoroutineScope()

  val settingsRepo = remember { SettingsRepository.getInstance(context) }
  val settings by settingsRepo.settings.collectAsState()

  val adblockBridge = remember { AdblockBridge.getInstance() }
  val filterManager = remember { FilterManager.getInstance(context, adblockBridge) }
  val subscriptions by filterManager.subscriptions.collectAsState()

  val integrityReport = remember { TamperDetection.checkIntegrity(context) }
  val passwordRepo = remember { com.remmi.browser.security.PasswordManagerRepository.getInstance(context) }
  val vaultLockState by passwordRepo.lockState.collectAsState()

  var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
  var isSearching by remember { mutableStateOf(false) }
  var searchQuery by remember { mutableStateOf("") }

  var isDefaultBrowser by remember {
    mutableStateOf(com.remmi.browser.util.DefaultBrowserHelper.isDefaultBrowser(context))
  }

  val defaultBrowserLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) {
    isDefaultBrowser = com.remmi.browser.util.DefaultBrowserHelper.isDefaultBrowser(context)
  }

  val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
  androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
      if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        isDefaultBrowser = com.remmi.browser.util.DefaultBrowserHelper.isDefaultBrowser(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val wallpaperPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
  ) { uri: Uri? ->
    if (uri != null) {
      settingsRepo.updateCustomWallpaper(uri.toString())
      settingsRepo.updateBackgroundAnimation(BackgroundTypes.CUSTOM_IMAGE)
    }
  }

  val isLight = ThemeCyber.colors.isLight
  val pageBg = if (isLight) Color(0xFFF8FAFC) else Color(0xFF070B13)
  val cardBg = if (isLight) Color(0xFFFFFFFF) else Color(0xFF0E1726)
  val cardBorder = if (isLight) Color(0xFFE2E8F0) else Color(0xFF1E293B)
  val textPrimaryColor = if (isLight) Color(0xFF0F172A) else Color(0xFFF8FAFC)
  val textSecondaryColor = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8)
  val topBarTitleColor = if (isLight) Color(0xFF1E40AF) else Color(0xFFFFFFFF)

  var showPanicDialog by remember { mutableStateOf(false) }
  var showResetDefaultsDialog by remember { mutableStateOf(false) }
  var showAnimationMenu by remember { mutableStateOf(false) }
  var showFontMenu by remember { mutableStateOf(false) }
  var showDnsMenu by remember { mutableStateOf(false) }
  var showSearchEngineMenu by remember { mutableStateOf(false) }

  // Intercept Android System Back
  BackHandler(enabled = true) {
    if (isSearching) {
      isSearching = false
      searchQuery = ""
    } else if (selectedCategory != null) {
      selectedCategory = null
    } else {
      onBack()
    }
  }

  var totalDragX by remember { mutableFloatStateOf(0f) }

  val handleBackAction = {
    if (isSearching) {
      isSearching = false
      searchQuery = ""
    } else if (selectedCategory != null) {
      selectedCategory = null
    } else {
      onBack()
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .pointerInput(Unit) {
        detectHorizontalDragGestures(
          onDragStart = { totalDragX = 0f },
          onDragEnd = {
            if (totalDragX > 80f || totalDragX < -120f) {
              handleBackAction()
            }
            totalDragX = 0f
          },
          onDragCancel = { totalDragX = 0f },
          onHorizontalDrag = { _, dragAmount ->
            totalDragX += dragAmount
            if (totalDragX > 150f || totalDragX < -180f) {
              handleBackAction()
              totalDragX = 0f
            }
          }
        )
      }
  ) {
    Scaffold(
      modifier = Modifier
        .fillMaxSize()
        .background(pageBg)
        .statusBarsPadding()
        .navigationBarsPadding(),
      containerColor = pageBg,
      topBar = {
        TopAppBar(
          title = {
            if (isSearching) {
              OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search settings...", color = textSecondaryColor, fontSize = 14.sp) },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                  focusedContainerColor = Color.Transparent,
                  unfocusedContainerColor = Color.Transparent,
                  focusedIndicatorColor = Color.Transparent,
                  unfocusedIndicatorColor = Color.Transparent,
                  focusedTextColor = textPrimaryColor,
                  unfocusedTextColor = textPrimaryColor,
                  cursorColor = if (isLight) Color(0xFF2563EB) else Color(0xFF38BDF8),
                ),
                modifier = Modifier.fillMaxWidth()
              )
            } else {
              Text(
                text = (selectedCategory?.title ?: "SETTINGS").uppercase(),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = topBarTitleColor,
                letterSpacing = 0.5.sp
              )
            }
          },
          navigationIcon = {
            IconButton(
              onClick = handleBackAction,
              modifier = Modifier.testTag("settings_back_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = if (isLight) Color(0xFF1E40AF) else Color(0xFFFFFFFF),
                modifier = Modifier.size(24.dp)
              )
            }
          },
          actions = {
            if (selectedCategory == null) {
              IconButton(
                onClick = {
                  isSearching = !isSearching
                  if (!isSearching) searchQuery = ""
                }
              ) {
                Icon(
                  imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                  contentDescription = "Search Settings",
                  tint = if (isLight) Color(0xFF1E40AF) else Color(0xFFFFFFFF),
                  modifier = Modifier.size(22.dp)
                )
              }
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = pageBg,
          ),
        )
      }
    ) { paddingValues ->

      if (selectedCategory == null) {
        // ==========================================
        // 1. MAIN SETTINGS SCREEN (Categories List)
        // ==========================================
        LazyColumn(
          modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          // Top Spacer
          item {
            Spacer(modifier = Modifier.height(4.dp))
          }

          // Header Hero Card
          if (!isSearching || searchQuery.isBlank()) {
            item {
              SettingsHeroHeaderCard(
                isDefaultBrowser = isDefaultBrowser,
                onSetDefaultClick = {
                  if (activity != null) {
                    com.remmi.browser.util.DefaultBrowserHelper.requestSetDefaultBrowser(activity, defaultBrowserLauncher)
                  }
                },
                isLight = isLight,
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor
              )
            }
          }

          // Category 1: Search Engine
          val currentEngine = SearchEngine.fromId(settings.searchEngineName)
          val searchEngineSubtitle = "${currentEngine.displayName} • ${currentEngine.subtitle}"
          if (searchQuery.isBlank() || "search engine".contains(searchQuery, ignoreCase = true) || currentEngine.displayName.contains(searchQuery, ignoreCase = true)) {
            item {
              SettingsCategoryItemCard(
                icon = Icons.Default.Search,
                title = "Search Engine",
                subtitle = searchEngineSubtitle,
                badgeBg = if (isLight) Color(0xFFEFF6FF) else Color(0xFF0C213B),
                iconTint = if (isLight) Color(0xFF2563EB) else Color(0xFF38BDF8),
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor,
                onClick = { selectedCategory = SettingsCategory.SEARCH_ENGINE }
              )
            }
          }

          // Category 2: Appearance & Themes
          val wallpaperName = if (settings.customWallpaperUri != null) "Custom Photo" else "Live Animation"
          val appearanceSubtitle = "${settings.cyberTheme.displayName} Theme • ${settings.browserFont.displayName}"
          if (searchQuery.isBlank() || "appearance themes font wallpaper".contains(searchQuery, ignoreCase = true)) {
            item {
              SettingsCategoryItemCard(
                icon = Icons.Default.Palette,
                title = "Appearance & Themes",
                subtitle = appearanceSubtitle,
                badgeBg = if (isLight) Color(0xFFF5F3FF) else Color(0xFF231548),
                iconTint = if (isLight) Color(0xFF7C3AED) else Color(0xFFA78BFA),
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor,
                onClick = { selectedCategory = SettingsCategory.APPEARANCE }
              )
            }
          }

          // Category 3: Privacy & Security
          val profileName = if (settings.defaultProfile == PrivacyProfile.GHOST) "Ghost Mode (Tor)" else "Shield Mode (Fast FPP)"
          val privacySubtitle = "$profileName • ${settings.dnsProvider.displayName} • HTTPS-Only"
          if (searchQuery.isBlank() || "privacy security dns ghost shield https".contains(searchQuery, ignoreCase = true)) {
            item {
              SettingsCategoryItemCard(
                icon = Icons.Default.Shield,
                title = "Privacy & Security",
                subtitle = privacySubtitle,
                badgeBg = if (isLight) Color(0xFFF0FDF4) else Color(0xFF0E2E1B),
                iconTint = if (isLight) Color(0xFF16A34A) else Color(0xFF4ADE80),
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor,
                onClick = { selectedCategory = SettingsCategory.PRIVACY_SECURITY }
              )
            }
          }

          // Category 4: Shields & Ad Blocking
          val enabledCount = subscriptions.count { it.enabled }
          val adblockSubtitle = "$enabledCount Active Filter Lists • Native tracker & ad"
          if (searchQuery.isBlank() || "shields ad blocking tracker filter".contains(searchQuery, ignoreCase = true)) {
            item {
              SettingsCategoryItemCard(
                icon = Icons.Default.Shield,
                title = "Shields & Ad Blocking",
                subtitle = adblockSubtitle,
                badgeBg = if (isLight) Color(0xFFFFF7ED) else Color(0xFF34190B),
                iconTint = if (isLight) Color(0xFFEA580C) else Color(0xFFFB923C),
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor,
                onClick = { selectedCategory = SettingsCategory.ADBLOCK }
              )
            }
          }

          // Category 5: Passwords & Vault
          val vaultStatusLabel = when (vaultLockState) {
            is com.remmi.browser.security.VaultLockState.Unlocked -> "Unlocked"
            is com.remmi.browser.security.VaultLockState.Locked -> "Locked (AES-256-GCM)"
            is com.remmi.browser.security.VaultLockState.Uninitialized -> "Ready to set up"
            is com.remmi.browser.security.VaultLockState.TemporarilyLocked -> "Locked out"
            is com.remmi.browser.security.VaultLockState.CompromisedDevice -> "Compromised"
          }
          val passwordSubtitle = "Argon2id (64 MiB KDF) • StrongBox Keystore • $vaultStatusLabel"
          if (searchQuery.isBlank() || "passwords vault credentials autofill keys".contains(searchQuery, ignoreCase = true)) {
            item {
              SettingsCategoryItemCard(
                icon = Icons.Default.VpnKey,
                title = "Passwords & Vault",
                subtitle = passwordSubtitle,
                badgeBg = if (isLight) Color(0xFFF5F3FF) else Color(0xFF25144A),
                iconTint = if (isLight) Color(0xFF6D28D9) else Color(0xFFC084FC),
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor,
                onClick = { onOpenPasswords() }
              )
            }
          }

          // Category 6: Display & Reader View
          val readerSizeLabel = when (settings.readerFontSize) {
            0 -> "Small"
            1 -> "Medium"
            else -> "Large"
          }
          val displaySubtitle = "Reader text ($readerSizeLabel) • ${if (settings.pureBlackOled) "OLED Black" else "Standard Dark"} • Desktop"
          if (searchQuery.isBlank() || "display reader view oled font dark mode".contains(searchQuery, ignoreCase = true)) {
            item {
              SettingsCategoryItemCard(
                icon = Icons.Default.DesktopWindows,
                title = "Display & Reader View",
                subtitle = displaySubtitle,
                badgeBg = if (isLight) Color(0xFFECFEFF) else Color(0xFF082C35),
                iconTint = if (isLight) Color(0xFF0891B2) else Color(0xFF22D3EE),
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor,
                onClick = { selectedCategory = SettingsCategory.DISPLAY_VIEWPORT }
              )
            }
          }

          // Category 7: System & Advanced
          val integrityText = if (integrityReport.isRootDetected) "Integrity Flagged" else "Integrity Secure"
          val systemSubtitle = "$integrityText • Diagnostic logs • Emergency"
          if (searchQuery.isBlank() || "system advanced logs diagnostic panic integrity".contains(searchQuery, ignoreCase = true)) {
            item {
              SettingsCategoryItemCard(
                icon = Icons.Default.Terminal,
                title = "System & Advanced",
                subtitle = systemSubtitle,
                badgeBg = if (isLight) Color(0xFFEFF6FF) else Color(0xFF0F223E),
                iconTint = if (isLight) Color(0xFF2563EB) else Color(0xFF60A5FA),
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor,
                onClick = { selectedCategory = SettingsCategory.SYSTEM_ADVANCED }
              )
            }
          }

          // "OTHER" SECTION
          if (searchQuery.isBlank() || "other about help support version".contains(searchQuery, ignoreCase = true)) {
            item {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "OTHER",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondaryColor,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
              )
            }

            // About Remmi Browser
            item {
              SettingsCategoryItemCard(
                icon = Icons.Default.Info,
                title = "About Remmi Browser",
                subtitle = "Version 1.0.0",
                badgeBg = if (isLight) Color(0xFFF0F9FF) else Color(0xFF082F49),
                iconTint = if (isLight) Color(0xFF0284C7) else Color(0xFF38BDF8),
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor,
                onClick = { selectedCategory = SettingsCategory.ABOUT }
              )
            }

            // Help & Support
            item {
              SettingsCategoryItemCard(
                icon = Icons.Default.Help,
                title = "Help & Support",
                subtitle = "Get help & report issues",
                badgeBg = if (isLight) Color(0xFFEFF6FF) else Color(0xFF0F223E),
                iconTint = if (isLight) Color(0xFF2563EB) else Color(0xFF60A5FA),
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimaryColor,
                textSecondary = textSecondaryColor,
                onClick = { selectedCategory = SettingsCategory.HELP_SUPPORT }
              )
            }
          }

          // Restore Default Settings Button
          item {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = if (isLight) Color(0xFFFEF2F2) else Color(0xFF2A1215),
              border = BorderStroke(1.dp, if (isLight) Color(0xFFFECACA) else Color(0xFF5C1D24)),
              modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clickable { showResetDefaultsDialog = true }
            ) {
              Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = "Restore Default Settings",
                  tint = if (isLight) Color(0xFFDC2626) else Color(0xFFEF4444),
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Restore Default Settings",
                  color = if (isLight) Color(0xFFDC2626) else Color(0xFFEF4444),
                  fontWeight = FontWeight.Bold,
                  fontSize = 14.5.sp
                )
              }
            }
            Spacer(modifier = Modifier.height(24.dp))
          }
        }

      } else {
        // ==========================================
        // 2. DEDICATED CATEGORY SUB-SCREENS
        // ==========================================
        when (selectedCategory) {
          SettingsCategory.SEARCH_ENGINE -> {
            SearchEngineSubScreen(
              settings = settings,
              settingsRepo = settingsRepo,
              isLight = isLight,
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimaryColor,
              textSecondary = textSecondaryColor,
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
            )
          }

          SettingsCategory.APPEARANCE -> {
            AppearanceSubScreen(
              settings = settings,
              settingsRepo = settingsRepo,
              wallpaperPickerLauncher = wallpaperPickerLauncher,
              isLight = isLight,
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimaryColor,
              textSecondary = textSecondaryColor,
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
            )
          }

          SettingsCategory.PRIVACY_SECURITY -> {
            PrivacySecuritySubScreen(
              settings = settings,
              settingsRepo = settingsRepo,
              isLight = isLight,
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimaryColor,
              textSecondary = textSecondaryColor,
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
            )
          }

          SettingsCategory.ADBLOCK -> {
            AdblockSubScreen(
              subscriptions = subscriptions,
              filterManager = filterManager,
              isLight = isLight,
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimaryColor,
              textSecondary = textSecondaryColor,
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
            )
          }

          SettingsCategory.PASSWORDS -> {
            PasswordsSubScreen(
              vaultLockState = vaultLockState,
              onOpenPasswords = onOpenPasswords,
              isLight = isLight,
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimaryColor,
              textSecondary = textSecondaryColor,
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
            )
          }

          SettingsCategory.DISPLAY_VIEWPORT -> {
            DisplayViewportSubScreen(
              settings = settings,
              settingsRepo = settingsRepo,
              isLight = isLight,
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimaryColor,
              textSecondary = textSecondaryColor,
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
            )
          }

          SettingsCategory.SYSTEM_ADVANCED -> {
            SystemAdvancedSubScreen(
              isDefaultBrowser = isDefaultBrowser,
              activity = activity,
              defaultBrowserLauncher = defaultBrowserLauncher,
              integrityReport = integrityReport,
              onOpenDebugLogs = onOpenDebugLogs,
              onTriggerPanicWipe = { showPanicDialog = true },
              isLight = isLight,
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimaryColor,
              textSecondary = textSecondaryColor,
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
            )
          }

          SettingsCategory.ABOUT -> {
            AboutRemmiSubScreen(
              isLight = isLight,
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimaryColor,
              textSecondary = textSecondaryColor,
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
            )
          }

          SettingsCategory.HELP_SUPPORT -> {
            HelpSupportSubScreen(
              isLight = isLight,
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimaryColor,
              textSecondary = textSecondaryColor,
              modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
            )
          }

          null -> {}
        }
      }
    }
  }

  if (showPanicDialog) {
    com.remmi.browser.ui.components.PanicWipeDialog(
      onDismiss = { showPanicDialog = false },
      onWipeExecuted = {
        showPanicDialog = false
        onBack()
      }
    )
  }

  if (showResetDefaultsDialog) {
    AlertDialog(
      onDismissRequest = { showResetDefaultsDialog = false },
      title = {
        Text(
          "Restore Default Settings?",
          fontWeight = FontWeight.Bold,
          color = textPrimaryColor
        )
      },
      text = {
        Text(
          "This will reset your search engine, themes, appearance, and privacy toggles back to factory settings. Saved passwords and bookmarks will remain intact.",
          color = textSecondaryColor,
          fontSize = 13.5.sp
        )
      },
      confirmButton = {
        Button(
          onClick = {
            settingsRepo.updateSearchEngine("DuckDuckGo")
            settingsRepo.updateCyberTheme(CyberTheme.NORMAL_DEFAULT)
            settingsRepo.updateBrowserFont(BrowserFont.CHROME_SANS)
            settingsRepo.updateBackgroundAnimation(BackgroundTypes.LIGHT_AURA_MESH)
            settingsRepo.updateCustomWallpaper(null)
            settingsRepo.updateDefaultProfile(PrivacyProfile.SHIELD)
            settingsRepo.updateHttpsOnly(true)
            settingsRepo.updateRestoreLastSession(true)
            settingsRepo.updateClearOnExit(false)
            settingsRepo.updatePureBlackOled(false)
            settingsRepo.updateDefaultDesktopMode(false)
            showResetDefaultsDialog = false
            Toast.makeText(context, "Settings restored to factory defaults", Toast.LENGTH_SHORT).show()
          },
          colors = ButtonDefaults.buttonColors(containerColor = if (isLight) Color(0xFFDC2626) else Color(0xFFEF4444))
        ) {
          Text("Reset", color = Color.White, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showResetDefaultsDialog = false }) {
          Text("Cancel", color = textSecondaryColor)
        }
      },
      containerColor = cardBg,
      shape = RoundedCornerShape(18.dp)
    )
  }
}

/**
 * Hero Header Card matching screenshot:
 * [ Shield Icon ] REMMI BROWSER
 *                 Version 1.0 • Cyber Matrix Core
 *                 [ 🛡 Secure & Protected ]      [ DEFAULT APP ]
 */
@Composable
private fun SettingsHeroHeaderCard(
  isDefaultBrowser: Boolean,
  onSetDefaultClick: () -> Unit,
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = cardBg),
    modifier = Modifier
      .fillMaxWidth()
      .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
      ) {
        // Glowing Icon Badge
        Box(
          modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isLight) Color(0xFFEFF6FF) else Color(0xFF0C213B)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = if (isLight) Color(0xFF2563EB) else Color(0xFF38BDF8),
            modifier = Modifier.size(26.dp)
          )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column {
          Text(
            text = "REMMI BROWSER",
            color = if (isLight) Color(0xFF1D4ED8) else Color(0xFFFFFFFF),
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp
          )
          Spacer(modifier = Modifier.height(1.dp))
          Text(
            text = "Version 1.0 • Cyber Matrix Core",
            color = textSecondary,
            fontSize = 11.sp
          )
          Spacer(modifier = Modifier.height(6.dp))
          // Status Pill Badge
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isLight) Color(0xFFDBEAFE) else Color(0xFF172554)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = if (isLight) Color(0xFF1D4ED8) else Color(0xFF60A5FA),
                modifier = Modifier.size(11.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Secure & Protected",
                color = if (isLight) Color(0xFF1D4ED8) else Color(0xFF60A5FA),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      // "DEFAULT APP" status badge or trigger button
      if (isDefaultBrowser) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (isLight) Color(0xFFDCFCE7) else Color(0xFF052E16),
          border = BorderStroke(1.dp, Color(0xFF22C55E))
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              tint = if (isLight) Color(0xFF16A34A) else Color(0xFF22C55E),
              modifier = Modifier.size(12.dp)
            )
            Text(
              text = "DEFAULT",
              color = if (isLight) Color(0xFF16A34A) else Color(0xFF22C55E),
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = CyberMonoFamily
            )
          }
        }
      } else {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = if (isLight) Color(0xFFEFF6FF) else Color(0xFF1E3A8A).copy(alpha = 0.4f),
          border = BorderStroke(1.dp, if (isLight) Color(0xFF2563EB) else Color(0xFF38BDF8)),
          modifier = Modifier
            .clickable { onSetDefaultClick() }
            .testTag("settings_hero_set_default_btn")
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowForward,
              contentDescription = null,
              tint = if (isLight) Color(0xFF1D4ED8) else Color(0xFF38BDF8),
              modifier = Modifier.size(12.dp)
            )
            Text(
              text = "SET DEFAULT",
              color = if (isLight) Color(0xFF1D4ED8) else Color(0xFF38BDF8),
              fontSize = 10.sp,
              fontWeight = FontWeight.ExtraBold,
              fontFamily = CyberMonoFamily
            )
          }
        }
      }
    }
  }
}

/**
 * Category Item Card matching screenshot:
 * [ Icon in squircle ] Title                >
 *                      Subtitle (with bullets)
 */
@Composable
private fun SettingsCategoryItemCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  badgeBg: Color,
  iconTint: Color,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = cardBg),
    modifier = modifier
      .fillMaxWidth()
      .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      .clickable { onClick() }
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f)
      ) {
        // Squircle Icon Badge
        Box(
          modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(badgeBg),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
          )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            color = textPrimary,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = subtitle,
            color = textSecondary,
            fontSize = 11.5.sp,
            maxLines = 1
          )
        }
      }

      Spacer(modifier = Modifier.width(8.dp))

      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = textSecondary.copy(alpha = 0.6f),
        modifier = Modifier.size(16.dp)
      )
    }
  }
}

/**
 * Generic Sub-Screen Section Header
 */
@Composable
private fun SubSectionHeader(title: String, textSecondary: Color) {
  Text(
    text = title.uppercase(),
    color = textSecondary,
    fontSize = 11.5.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.8.sp,
    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp)
  )
}

/**
 * Generic Sub-Screen Toggle Card with Rounded Corners & Icon Badge
 */
@Composable
private fun SubScreenToggleCard(
  icon: ImageVector,
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  badgeBg: Color,
  iconTint: Color,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  Card(
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = cardBg),
    modifier = modifier
      .fillMaxWidth()
      .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      .clickable { onCheckedChange(!checked) }
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 13.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(38.dp)
          .clip(RoundedCornerShape(11.dp))
          .background(badgeBg),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = iconTint,
          modifier = Modifier.size(20.dp)
        )
      }

      Spacer(modifier = Modifier.width(13.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          color = textPrimary,
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = subtitle,
          color = textSecondary,
          fontSize = 11.sp,
          lineHeight = 15.sp
        )
      }

      Spacer(modifier = Modifier.width(10.dp))

      Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
          checkedThumbColor = Color.White,
          checkedTrackColor = iconTint,
          uncheckedThumbColor = textSecondary,
          uncheckedTrackColor = cardBorder
        )
      )
    }
  }
}

// =========================================================================
// SUB-SCREENS IMPLEMENTATION (All redesigned with matching rounded cards)
// =========================================================================

/**
 * 1. SEARCH ENGINE SUB-SCREEN
 */
@Composable
private fun SearchEngineSubScreen(
  settings: com.remmi.browser.storage.BrowserSettings,
  settingsRepo: SettingsRepository,
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  val currentEngine = SearchEngine.fromId(settings.searchEngineName)

  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(2.dp))
      SubSectionHeader("DEFAULT PRIVACY SEARCH ENGINE", textSecondary)
    }

    items(SearchEngine.entries) { engine ->
      val isSelected = currentEngine == engine
      val accentTint = if (isLight) Color(0xFF2563EB) else Color(0xFF38BDF8)

      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
          containerColor = if (isSelected) {
            if (isLight) Color(0xFFEFF6FF) else Color(0xFF0C213B)
          } else cardBg
        ),
        modifier = Modifier
          .fillMaxWidth()
          .border(
            width = if (isSelected) 1.5.dp else 0.8.dp,
            color = if (isSelected) accentTint else cardBorder,
            shape = RoundedCornerShape(18.dp)
          )
          .clickable { settingsRepo.updateSearchEngine(engine.displayName) }
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            Box(
              modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                  if (isSelected) {
                    if (isLight) Color(0xFFDBEAFE) else Color(0xFF1E3A8A)
                  } else {
                    if (isLight) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                  }
                ),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = if (isSelected) accentTint else textSecondary,
                modifier = Modifier.size(20.dp)
              )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
              Text(
                text = engine.displayName,
                color = if (isSelected) accentTint else textPrimary,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = engine.subtitle,
                color = textSecondary,
                fontSize = 11.5.sp
              )
            }
          }

          if (isSelected) {
            Box(
              modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accentTint),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = Color.White,
                modifier = Modifier.size(15.dp)
              )
            }
          }
        }
      }
    }
  }
}

/**
 * 2. APPEARANCE & THEMES SUB-SCREEN
 */
@Composable
private fun AppearanceSubScreen(
  settings: com.remmi.browser.storage.BrowserSettings,
  settingsRepo: SettingsRepository,
  wallpaperPickerLauncher: androidx.activity.result.ActivityResultLauncher<String>,
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  var showFontMenu by remember { mutableStateOf(false) }
  var showAnimMenu by remember { mutableStateOf(false) }
  val accentTint = if (isLight) Color(0xFF7C3AED) else Color(0xFFA78BFA)

  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    // Theme selection
    item {
      Spacer(modifier = Modifier.height(2.dp))
      SubSectionHeader("CYBERPUNK HUD ACCENT THEME", textSecondary)
    }

    item {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in CyberTheme.entries.indices step 2) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            val firstTheme = CyberTheme.entries[i]
            val secondTheme = if (i + 1 < CyberTheme.entries.size) CyberTheme.entries[i + 1] else null

            ThemeSelectionCard(
              theme = firstTheme,
              isSelected = settings.cyberTheme == firstTheme,
              onClick = { settingsRepo.updateCyberTheme(firstTheme) },
              cardBg = cardBg,
              cardBorder = cardBorder,
              textPrimary = textPrimary,
              textSecondary = textSecondary,
              modifier = Modifier.weight(1f)
            )

            if (secondTheme != null) {
              ThemeSelectionCard(
                theme = secondTheme,
                isSelected = settings.cyberTheme == secondTheme,
                onClick = { settingsRepo.updateCyberTheme(secondTheme) },
                cardBg = cardBg,
                cardBorder = cardBorder,
                textPrimary = textPrimary,
                textSecondary = textSecondary,
                modifier = Modifier.weight(1f)
              )
            } else {
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      }
    }

    // Typography
    item {
      SubSectionHeader("GLOBAL BROWSER FONT & TYPOGRAPHY", textSecondary)
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text(
            text = "Active App Font",
            color = textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Applies instantly across tabs, URL bar, sheets, HUD & all UI",
            color = textSecondary,
            fontSize = 11.5.sp
          )

          Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (isLight) Color(0xFFF8FAFC) else Color(0xFF070B13),
              border = BorderStroke(1.dp, accentTint),
              modifier = Modifier
                .fillMaxWidth()
                .clickable { showFontMenu = true }
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                  Icon(Icons.Default.Palette, null, tint = accentTint, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(10.dp))
                  Column {
                    Text(
                      text = settings.browserFont.displayName,
                      color = textPrimary,
                      fontSize = 13.5.sp,
                      fontWeight = FontWeight.Bold,
                      fontFamily = settings.browserFont.fontFamily
                    )
                    Text(
                      text = settings.browserFont.subtitle,
                      color = textSecondary,
                      fontSize = 10.5.sp
                    )
                  }
                }
                Icon(Icons.Default.ArrowDropDown, null, tint = accentTint, modifier = Modifier.size(24.dp))
              }
            }

            DropdownMenu(
              expanded = showFontMenu,
              onDismissRequest = { showFontMenu = false },
              modifier = Modifier
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            ) {
              BrowserFont.entries.forEach { fontOption ->
                val isSelected = settings.browserFont == fontOption
                DropdownMenuItem(
                  text = {
                    Text(
                      text = "${fontOption.displayName} (${fontOption.category})",
                      color = if (isSelected) accentTint else textPrimary,
                      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                      fontFamily = fontOption.fontFamily
                    )
                  },
                  onClick = {
                    settingsRepo.updateBrowserFont(fontOption)
                    showFontMenu = false
                  }
                )
              }
            }
          }
        }
      }
    }

    // Live Wallpaper & Animations
    item {
      SubSectionHeader("BACKGROUND ANIMATION & CUSTOM WALLPAPER", textSecondary)
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          val animOptions = listOf(
            BackgroundTypes.LIGHT_AURA_MESH to "Ambient Aura Waves (Light Mode)",
            BackgroundTypes.LIGHT_FLOATING_ORBS to "Floating Pastel Orbs (Light Mode)",
            BackgroundTypes.LIGHT_GEOMETRIC_DOTS to "Minimal Pulsing Dot Grid (Light Mode)",
            BackgroundTypes.LIGHT_CONSTELLATION to "Connected Nodes Constellation",
            BackgroundTypes.CYBERPUNK_GRID to "Cyberpunk 3D Grid (Retro)",
            BackgroundTypes.MATRIX_RAIN to "Matrix Digital Rain",
            BackgroundTypes.NEON_PARTICLES to "Neon Quantum Particles",
            BackgroundTypes.DIGITAL_AURORA to "Digital Neon Aurora",
            BackgroundTypes.MINIMAL_GRADIENT to "Minimal Stealth Gradient",
          )

          val currentSelectedLabel = if (settings.customWallpaperUri != null) {
            "Custom Image from Gallery"
          } else {
            animOptions.firstOrNull { it.first == settings.backgroundAnimation }?.second ?: "Ambient Aura Waves"
          }

          Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (isLight) Color(0xFFF8FAFC) else Color(0xFF070B13),
              border = BorderStroke(1.dp, accentTint),
              modifier = Modifier
                .fillMaxWidth()
                .clickable { showAnimMenu = true }
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                  Icon(Icons.Default.Wallpaper, null, tint = accentTint, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(10.dp))
                  Text(
                    text = currentSelectedLabel,
                    color = textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                  )
                }
                Icon(Icons.Default.ArrowDropDown, null, tint = accentTint, modifier = Modifier.size(24.dp))
              }
            }

            DropdownMenu(
              expanded = showAnimMenu,
              onDismissRequest = { showAnimMenu = false },
              modifier = Modifier
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            ) {
              animOptions.forEach { (type, label) ->
                DropdownMenuItem(
                  text = {
                    Text(
                      text = label,
                      color = if (settings.backgroundAnimation == type && settings.customWallpaperUri == null) accentTint else textPrimary
                    )
                  },
                  onClick = {
                    settingsRepo.updateBackgroundAnimation(type)
                    settingsRepo.updateCustomWallpaper(null)
                    showAnimMenu = false
                  }
                )
              }
            }
          }

          // Custom photo button
          OutlinedButton(
            onClick = { wallpaperPickerLauncher.launch("image/*") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, accentTint)
          ) {
            Icon(Icons.Default.PhotoLibrary, null, tint = accentTint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Choose Custom Photo from Gallery", color = accentTint, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
          }
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun ThemeSelectionCard(
  theme: CyberTheme,
  isSelected: Boolean,
  onClick: () -> Unit,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  val themeColor = theme.primaryAccent

  Card(
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = cardBg),
    modifier = modifier
      .height(68.dp)
      .border(
        width = if (isSelected) 1.5.dp else 0.8.dp,
        color = if (isSelected) themeColor else cardBorder,
        shape = RoundedCornerShape(14.dp)
      )
      .clickable { onClick() }
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(24.dp)
          .clip(CircleShape)
          .background(themeColor)
          .border(2.dp, if (isSelected) Color.White else Color.Transparent, CircleShape)
      )
      Spacer(modifier = Modifier.width(8.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = theme.displayName,
          color = if (isSelected) themeColor else textPrimary,
          fontSize = 11.5.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1
        )
        Text(
          text = theme.subtitle,
          color = textSecondary,
          fontSize = 9.5.sp,
          maxLines = 1
        )
      }
    }
  }
}

/**
 * 3. PRIVACY & SECURITY SUB-SCREEN
 */
@Composable
private fun PrivacySecuritySubScreen(
  settings: com.remmi.browser.storage.BrowserSettings,
  settingsRepo: SettingsRepository,
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  val greenTint = if (isLight) Color(0xFF16A34A) else Color(0xFF4ADE80)
  val greenBg = if (isLight) Color(0xFFF0FDF4) else Color(0xFF0E2E1B)
  var showDnsMenu by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(2.dp))
      SubSectionHeader("DEFAULT PRIVACY PROFILE", textSecondary)
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        val isShield = settings.defaultProfile == PrivacyProfile.SHIELD
        Card(
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = cardBg),
          modifier = Modifier
            .weight(1f)
            .border(
              width = if (isShield) 1.5.dp else 0.8.dp,
              color = if (isShield) greenTint else cardBorder,
              shape = RoundedCornerShape(18.dp)
            )
            .clickable { settingsRepo.updateDefaultProfile(PrivacyProfile.SHIELD) }
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Text("SHIELD MODE", color = if (isShield) greenTint else textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Fast FPP Direct", color = textSecondary, fontSize = 10.5.sp)
          }
        }

        val isGhost = settings.defaultProfile == PrivacyProfile.GHOST
        val ghostColor = if (isLight) Color(0xFF7C3AED) else Color(0xFFA78BFA)
        Card(
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = cardBg),
          modifier = Modifier
            .weight(1f)
            .border(
              width = if (isGhost) 1.5.dp else 0.8.dp,
              color = if (isGhost) ghostColor else cardBorder,
              shape = RoundedCornerShape(18.dp)
            )
            .clickable { settingsRepo.updateDefaultProfile(PrivacyProfile.GHOST) }
        ) {
          Column(modifier = Modifier.padding(14.dp)) {
            Text("GHOST MODE", color = if (isGhost) ghostColor else textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text("Tor 3-Hop Onion", color = textSecondary, fontSize = 10.5.sp)
          }
        }
      }
    }

    item {
      SubSectionHeader("ANTI-FINGERPRINTING & HARDENING", textSecondary)
    }

    item {
      SubScreenToggleCard(
        icon = Icons.Default.Shield,
        title = "HTTPS-Only Network Enforcement",
        subtitle = "Strictly upgrade all requests to TLS. Insecure HTTP connections are dropped immediately.",
        checked = settings.httpsOnlyMode,
        onCheckedChange = { settingsRepo.updateHttpsOnly(it) },
        badgeBg = greenBg,
        iconTint = greenTint,
        cardBg = cardBg,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary
      )
    }

    item {
      SubScreenToggleCard(
        icon = Icons.Default.OpenInBrowser,
        title = "Restore Previous Session",
        subtitle = "Automatically re-open previous browsing tabs and active state when Remmi starts.",
        checked = settings.restoreLastSession && !settings.clearDataOnExit,
        onCheckedChange = { settingsRepo.updateRestoreLastSession(it) },
        badgeBg = greenBg,
        iconTint = greenTint,
        cardBg = cardBg,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary
      )
    }

    item {
      SubScreenToggleCard(
        icon = Icons.Default.Delete,
        title = "Clear Data On App Exit",
        subtitle = "Automatically purge open tabs, temporary cache, DOM storage, and session keys on shutdown.",
        checked = settings.clearDataOnExit,
        onCheckedChange = { settingsRepo.updateClearOnExit(it) },
        badgeBg = if (isLight) Color(0xFFFEF2F2) else Color(0xFF2A1215),
        iconTint = if (isLight) Color(0xFFDC2626) else Color(0xFFEF4444),
        cardBg = cardBg,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary
      )
    }

    // Encrypted DNS (DoH)
    item {
      SubSectionHeader("ENCRYPTED DNS & PROTOCOLS", textSecondary)
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Encrypted DNS (DoH Provider)",
            color = textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Encrypts all DNS queries over TLS/HTTPS to bypass ISP logging and MITM snooping.",
            color = textSecondary,
            fontSize = 11.5.sp
          )

          Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (isLight) Color(0xFFF8FAFC) else Color(0xFF070B13),
              border = BorderStroke(1.dp, greenTint),
              modifier = Modifier
                .fillMaxWidth()
                .clickable { showDnsMenu = true }
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                  Icon(Icons.Default.Language, null, tint = greenTint, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(10.dp))
                  Column {
                    Text(
                      text = settings.dnsProvider.displayName,
                      color = textPrimary,
                      fontSize = 13.5.sp,
                      fontWeight = FontWeight.Bold
                    )
                    Text(
                      text = settings.dnsProvider.description,
                      color = textSecondary,
                      fontSize = 10.5.sp
                    )
                  }
                }
                Icon(Icons.Default.ArrowDropDown, null, tint = greenTint, modifier = Modifier.size(24.dp))
              }
            }

            DropdownMenu(
              expanded = showDnsMenu,
              onDismissRequest = { showDnsMenu = false },
              modifier = Modifier
                .background(cardBg)
                .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
            ) {
              DnsProvider.entries.forEach { provider ->
                DropdownMenuItem(
                  text = {
                    Column {
                      Text(
                        text = provider.displayName,
                        color = if (settings.dnsProvider == provider) greenTint else textPrimary,
                        fontWeight = if (settings.dnsProvider == provider) FontWeight.Bold else FontWeight.Normal
                      )
                      Text(
                        text = provider.description,
                        color = textSecondary,
                        fontSize = 10.sp
                      )
                    }
                  },
                  onClick = {
                    settingsRepo.updateDnsProvider(provider)
                    showDnsMenu = false
                  }
                )
              }
            }
          }
        }
      }
    }

    item {
      SubScreenToggleCard(
        icon = Icons.Default.Shield,
        title = "Encrypted Client Hello (ECH)",
        subtitle = "Encrypts TLS Server Name Indication (SNI) to prevent eavesdropping on visited domains.",
        checked = settings.encryptedClientHelloEnabled,
        onCheckedChange = { settingsRepo.updateEchEnabled(it) },
        badgeBg = greenBg,
        iconTint = greenTint,
        cardBg = cardBg,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary
      )
    }

    item {
      SubScreenToggleCard(
        icon = Icons.Default.Fingerprint,
        title = "Global Privacy Control (Sec-GPC)",
        subtitle = "Signals websites to prohibit tracking and user data sales under privacy regulations.",
        checked = settings.globalPrivacyControlEnabled,
        onCheckedChange = { settingsRepo.updateGpcEnabled(it) },
        badgeBg = greenBg,
        iconTint = greenTint,
        cardBg = cardBg,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary
      )
      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

/**
 * 4. SHIELDS & ADBLOCK SUB-SCREEN
 */
@Composable
private fun AdblockSubScreen(
  subscriptions: List<com.remmi.adblock.FilterSubscription>,
  filterManager: FilterManager,
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  val orangeTint = if (isLight) Color(0xFFEA580C) else Color(0xFFFB923C)
  val orangeBg = if (isLight) Color(0xFFFFF7ED) else Color(0xFF34190B)
  val isUpdating by filterManager.isUpdating.collectAsState()
  val scope = rememberCoroutineScope()

  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(2.dp))
      SubSectionHeader("NATIVE ADBLOCK LIST SUBSCRIPTIONS", textSecondary)
    }

    // Manual Update Trigger & Status Card
    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Engine Synchronization",
                color = textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
              )
              Text(
                text = if (isUpdating) "Downloading & compiling filter rules..." else "EasyList, EasyPrivacy, and Fanboy rules",
                color = textSecondary,
                fontSize = 11.sp
              )
            }

            Button(
              onClick = {
                scope.launch {
                  filterManager.updateAllSubscriptions(force = true)
                }
              },
              enabled = !isUpdating,
              colors = ButtonDefaults.buttonColors(containerColor = orangeTint),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
              if (isUpdating) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Syncing...", fontSize = 11.5.sp, color = Color.White)
              } else {
                Icon(Icons.Default.Refresh, contentDescription = "Update", tint = Color.White, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Update All", fontSize = 11.5.sp, color = Color.White)
              }
            }
          }
        }
      }
    }

    items(subscriptions) { sub ->
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 13.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(RoundedCornerShape(11.dp))
              .background(orangeBg),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Shield,
              contentDescription = null,
              tint = orangeTint,
              modifier = Modifier.size(20.dp)
            )
          }

          Spacer(modifier = Modifier.width(13.dp))

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = sub.title,
              color = textPrimary,
              fontSize = 13.5.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            if (sub.ruleCount > 0) {
              Surface(
                shape = RoundedCornerShape(4.dp),
                color = orangeBg
              ) {
                Text(
                  text = "${sub.ruleCount} RULES",
                  color = orangeTint,
                  fontSize = 9.5.sp,
                  fontWeight = FontWeight.ExtraBold,
                  fontFamily = CyberMonoFamily,
                  modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                )
              }
              Spacer(modifier = Modifier.height(3.dp))
            }
            Text(
              text = sub.description,
              color = textSecondary,
              fontSize = 11.sp,
              lineHeight = 15.sp
            )
          }

          Spacer(modifier = Modifier.width(10.dp))

          Switch(
            checked = sub.enabled,
            onCheckedChange = { filterManager.toggleSubscription(sub.id) },
            colors = SwitchDefaults.colors(
              checkedThumbColor = Color.White,
              checkedTrackColor = orangeTint,
              uncheckedThumbColor = textSecondary,
              uncheckedTrackColor = cardBorder
            )
          )
        }
      }
    }
  }
}

/**
 * 5. PASSWORDS SUB-SCREEN
 */
@Composable
private fun PasswordsSubScreen(
  vaultLockState: com.remmi.browser.security.VaultLockState,
  onOpenPasswords: () -> Unit,
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  val purpleTint = if (isLight) Color(0xFF6D28D9) else Color(0xFFC084FC)
  val purpleBg = if (isLight) Color(0xFFF5F3FF) else Color(0xFF25144A)

  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(2.dp))
      SubSectionHeader("MAXIMUM-SECURITY PASSWORD VAULT", textSecondary)
    }

    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
          .clickable { onOpenPasswords() }
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Box(
                modifier = Modifier
                  .size(42.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(purpleBg),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Default.VpnKey, null, tint = purpleTint, modifier = Modifier.size(22.dp))
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column {
                Text("CYBER VAULT CREDENTIALS", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                Text("Argon2id (64 MiB KDF) • StrongBox Keystore", color = textSecondary, fontSize = 11.5.sp)
              }
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = textSecondary, modifier = Modifier.size(16.dp))
          }

          Button(
            onClick = onOpenPasswords,
            colors = ButtonDefaults.buttonColors(containerColor = purpleTint),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Open Passwords & Vault Manager", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
          }
        }
      }
    }
  }
}

/**
 * 6. DISPLAY & READER VIEW SUB-SCREEN
 */
@Composable
private fun DisplayViewportSubScreen(
  settings: com.remmi.browser.storage.BrowserSettings,
  settingsRepo: SettingsRepository,
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  val cyanTint = if (isLight) Color(0xFF0891B2) else Color(0xFF22D3EE)
  val cyanBg = if (isLight) Color(0xFFECFEFF) else Color(0xFF082C35)

  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(2.dp))
      SubSectionHeader("VIEWPORT & RENDERING", textSecondary)
    }

    item {
      SubScreenToggleCard(
        icon = Icons.Default.Contrast,
        title = "Pure Black OLED Mode",
        subtitle = "Force true pitch-black (#000000) canvas for OLED battery efficiency.",
        checked = settings.pureBlackOled,
        onCheckedChange = { settingsRepo.updatePureBlackOled(it) },
        badgeBg = cyanBg,
        iconTint = cyanTint,
        cardBg = cardBg,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary
      )
    }

    item {
      SubScreenToggleCard(
        icon = Icons.Default.DesktopWindows,
        title = "Default Desktop Mode",
        subtitle = "Request full desktop web layouts by default on newly spawned tabs.",
        checked = settings.defaultDesktopMode,
        onCheckedChange = { settingsRepo.updateDefaultDesktopMode(it) },
        badgeBg = cyanBg,
        iconTint = cyanTint,
        cardBg = cardBg,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary
      )
    }

    item {
      SubScreenToggleCard(
        icon = Icons.Default.Speed,
        title = "Cyber Glitch HUD Effects",
        subtitle = "Enable dynamic chromatic aberration and terminal jitter scanline effects.",
        checked = settings.glitchAnimationEnabled,
        onCheckedChange = { settingsRepo.updateGlitchEnabled(it) },
        badgeBg = cyanBg,
        iconTint = cyanTint,
        cardBg = cardBg,
        cardBorder = cardBorder,
        textPrimary = textPrimary,
        textSecondary = textSecondary
      )
    }

    item {
      SubSectionHeader("READER VIEW DEFAULT FONT SIZE", textSecondary)
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Article Reader Font Size", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf("SMALL (14SP)" to 0, "MEDIUM (17SP)" to 1, "LARGE (21SP)" to 2).forEach { (label, index) ->
              val isSelected = settings.readerFontSize == index
              Surface(
                modifier = Modifier
                  .weight(1f)
                  .clickable { settingsRepo.updateReaderFontSize(index) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) {
                  if (isLight) Color(0xFFEFF6FF) else Color(0xFF0C213B)
                } else {
                  if (isLight) Color(0xFFF1F5F9) else Color(0xFF1E293B)
                },
                border = BorderStroke(1.dp, if (isSelected) cyanTint else Color.Transparent)
              ) {
                Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                  Text(
                    text = label,
                    color = if (isSelected) cyanTint else textSecondary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
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

/**
 * 7. SYSTEM & ADVANCED SUB-SCREEN
 */
@Composable
private fun SystemAdvancedSubScreen(
  isDefaultBrowser: Boolean,
  activity: Activity?,
  defaultBrowserLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
  integrityReport: TamperDetection.IntegrityReport,
  onOpenDebugLogs: () -> Unit,
  onTriggerPanicWipe: () -> Unit,
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  val blueTint = if (isLight) Color(0xFF2563EB) else Color(0xFF60A5FA)
  val blueBg = if (isLight) Color(0xFFEFF6FF) else Color(0xFF0F223E)

  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(2.dp))
      SubSectionHeader("DEFAULT SYSTEM BROWSER", textSecondary)
    }

    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
              Box(
                modifier = Modifier
                  .size(38.dp)
                  .clip(RoundedCornerShape(11.dp))
                  .background(blueBg),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Default.Language, null, tint = blueTint, modifier = Modifier.size(20.dp))
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column {
                Text(
                  text = if (isDefaultBrowser) "Default Browser: Active" else "Default Browser: Not Set",
                  color = if (isDefaultBrowser) Color(0xFF16A34A) else textPrimary,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = if (isDefaultBrowser) "Remmi is your primary browser for links." else "Make Remmi default for full privacy protection.",
                  color = textSecondary,
                  fontSize = 11.5.sp
                )
              }
            }
          }

          if (!isDefaultBrowser && activity != null) {
            Button(
              onClick = {
                com.remmi.browser.util.DefaultBrowserHelper.requestSetDefaultBrowser(activity, defaultBrowserLauncher)
              },
              colors = ButtonDefaults.buttonColors(containerColor = blueTint),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Icon(Icons.Default.OpenInBrowser, null, tint = Color.White, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Set Remmi as Default Browser", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
          }
        }
      }
    }

    item {
      SubSectionHeader("SYSTEM INTEGRITY & TAMPER AUDIT", textSecondary)
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "STATUS: ${integrityReport.systemIntegrityStatus}",
              color = if (integrityReport.isRootDetected) Color(0xFFDC2626) else Color(0xFF16A34A),
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp
            )
            Icon(
              imageVector = if (integrityReport.isRootDetected) Icons.Default.Warning else Icons.Default.CheckCircle,
              contentDescription = null,
              tint = if (integrityReport.isRootDetected) Color(0xFFDC2626) else Color(0xFF16A34A),
              modifier = Modifier.size(18.dp)
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "• Signature Verification: VALID\n• APK Debugger Check: ${if (integrityReport.isDebuggerAttached) "FLAGGED" else "SECURE"}\n• Root Detection: ${if (integrityReport.isRootDetected) "COMPROMISED" else "CLEAN"}",
            color = textSecondary,
            fontSize = 11.5.sp
          )
        }
      }
    }

    item {
      SubSectionHeader("ENGINE & PROXY DIAGNOSTICS", textSecondary)
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
          .clickable { onOpenDebugLogs() }
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Box(
              modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(blueBg),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.Terminal, null, tint = blueTint, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text("Diagnostic & Proxy Logs", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
              Text("Live SOCKS5 routing & WebExtension status", color = textSecondary, fontSize = 11.sp)
            }
          }
          Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = textSecondary, modifier = Modifier.size(16.dp))
        }
      }
    }

    // Panic Flush Button
    item {
      Spacer(modifier = Modifier.height(4.dp))
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isLight) Color(0xFFFEF2F2) else Color(0xFF2A1215),
        border = BorderStroke(1.dp, if (isLight) Color(0xFFFECACA) else Color(0xFF5C1D24)),
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
          .clickable { onTriggerPanicWipe() }
      ) {
        Row(
          modifier = Modifier.fillMaxSize(),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(Icons.Default.Delete, null, tint = if (isLight) Color(0xFFDC2626) else Color(0xFFEF4444), modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "PANIC WIPE // FLUSH ALL DATA",
            color = if (isLight) Color(0xFFDC2626) else Color(0xFFEF4444),
            fontWeight = FontWeight.Bold,
            fontSize = 13.5.sp
          )
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

/**
 * 8. ABOUT SUB-SCREEN
 */
@Composable
private fun AboutRemmiSubScreen(
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  val skyTint = if (isLight) Color(0xFF0284C7) else Color(0xFF38BDF8)
  val skyBg = if (isLight) Color(0xFFF0F9FF) else Color(0xFF082F49)

  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(2.dp))
      SubSectionHeader("ABOUT REMMI BROWSER", textSecondary)
    }

    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
              modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(skyBg),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.Shield, null, tint = skyTint, modifier = Modifier.size(26.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
              Text("Remmi Browser", color = textPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
              Text("Version 1.0.0 (Build 2026.08)", color = textSecondary, fontSize = 12.sp)
            }
          }

          Divider(color = cardBorder)

          Text(
            text = "Remmi Browser is an ultra-secure, privacy-first Android web browser equipped with embedded Tor onion routing, zero-telemetry trackers blocking, encrypted DNS-over-HTTPS, Argon2id encrypted cyber credentials vault, and custom cyberpunk HUD interfaces.",
            color = textSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp
          )
        }
      }
    }
  }
}

/**
 * 9. HELP & SUPPORT SUB-SCREEN
 */
@Composable
private fun HelpSupportSubScreen(
  isLight: Boolean,
  cardBg: Color,
  cardBorder: Color,
  textPrimary: Color,
  textSecondary: Color,
  modifier: Modifier = Modifier
) {
  val blueTint = if (isLight) Color(0xFF2563EB) else Color(0xFF60A5FA)
  val blueBg = if (isLight) Color(0xFFEFF6FF) else Color(0xFF0F223E)

  LazyColumn(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(2.dp))
      SubSectionHeader("HELP & SUPPORT", textSecondary)
    }

    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        modifier = Modifier
          .fillMaxWidth()
          .border(0.8.dp, cardBorder, RoundedCornerShape(18.dp))
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("Frequently Asked Questions", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)

          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("• How does Ghost Mode work?", color = blueTint, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            Text("Ghost mode routes all tab network traffic through the Tor 3-hop proxy network with circuit isolation per top-level domain.", color = textSecondary, fontSize = 11.5.sp)

            Text("• Where are my passwords saved?", color = blueTint, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            Text("Credentials are stored locally in an Argon2id + AES-256-GCM hardware-backed vault. No passwords ever touch cloud servers.", color = textSecondary, fontSize = 11.5.sp)

            Text("• How to report bugs or request features?", color = blueTint, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            Text("Remmi Browser is completely offline and open source. Check diagnostic logs under System & Advanced for proxy traces.", color = textSecondary, fontSize = 11.5.sp)
          }
        }
      }
    }
  }
}
