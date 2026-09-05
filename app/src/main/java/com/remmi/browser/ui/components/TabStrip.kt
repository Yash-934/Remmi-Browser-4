package com.remmi.browser.ui.components

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.remmi.browser.R
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.engine.TabGroup
import com.remmi.browser.engine.TabThumbnailManager
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.storage.BookmarkItem
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// -------------------------------------------------------------
// HORIZONTAL DESKTOP / TABLET TAB STRIP
// -------------------------------------------------------------

@Composable
fun TabStrip(
  tabs: List<BrowserTab>,
  tabGroups: List<TabGroup> = emptyList(),
  activeIndex: Int,
  onTabSelect: (Int) -> Unit,
  onTabClose: (String) -> Unit,
  onNewTab: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val cyberColors = ThemeCyber.colors
  val bgColor = cyberColors.background
  val surfaceColor = cyberColors.surface
  val activeSurfaceColor = cyberColors.surfaceLight
  val borderColor = cyberColors.surfaceBorder
  val activeBorderColor = cyberColors.primary
  val textPrimary = cyberColors.textPrimary
  val textSecondary = cyberColors.textSecondary

  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(bgColor)
      .padding(vertical = 4.dp, horizontal = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LazyRow(
      modifier = Modifier.weight(1f),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      contentPadding = PaddingValues(horizontal = 4.dp),
    ) {
      itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
        val isActive = index == activeIndex
        val group = tabGroups.find { it.id == tab.groupId }
        val groupColor = group?.let { Color(it.colorHex) }
        val activeColor = if (tab.profile == PrivacyProfile.GHOST) {
          cyberColors.torPurple
        } else {
          groupColor ?: activeBorderColor
        }

        Row(
          modifier = Modifier
            .width(150.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) activeSurfaceColor else surfaceColor)
            .border(
              width = if (isActive) 1.2.dp else 0.6.dp,
              color = if (isActive) activeColor else (groupColor?.copy(alpha = 0.5f) ?: borderColor),
              shape = RoundedCornerShape(8.dp)
            )
            .clickable { onTabSelect(index) }
            .padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          if (groupColor != null) {
            Box(
              modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(groupColor)
            )
            Spacer(modifier = Modifier.width(5.dp))
          }

          if (tab.profile == PrivacyProfile.INCOGNITO) {
            Icon(
              painter = painterResource(R.drawable.ic_incognito),
              contentDescription = "Incognito Tab",
              tint = if (isActive) textPrimary else textSecondary,
              modifier = Modifier.size(13.dp),
            )
          } else if (tab.profile == PrivacyProfile.GHOST) {
            Icon(
              painter = painterResource(R.drawable.ic_tor),
              contentDescription = "Tor Tab",
              tint = if (isActive) cyberColors.torPurple else textSecondary,
              modifier = Modifier.size(14.dp),
            )
          } else {
            Icon(
              imageVector = Icons.Default.Shield,
              contentDescription = null,
              tint = if (isActive) activeColor else textSecondary,
              modifier = Modifier.size(12.dp),
            )
          }

          Spacer(modifier = Modifier.width(5.dp))

          Text(
            text = tab.title.ifEmpty { "New Tab" },
            color = if (isActive) textPrimary else textSecondary,
            fontFamily = CyberMonoFamily,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )

          IconButton(
            onClick = { onTabClose(tab.id) },
            modifier = Modifier.size(18.dp),
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close Tab",
              tint = if (isActive) activeColor else textSecondary,
              modifier = Modifier.size(11.dp),
            )
          }
        }
      }
    }

    IconButton(
      onClick = onNewTab,
      modifier = Modifier
        .size(32.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(surfaceColor)
        .border(0.6.dp, borderColor, RoundedCornerShape(8.dp))
        .testTag("add_new_tab_button"),
    ) {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = "New Tab",
        tint = activeBorderColor,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

// -------------------------------------------------------------
// TAB FILTER ENUM
// -------------------------------------------------------------

enum class TabFilter {
  ALL,
  RECENT,
  ACTIVE,
  SLEEP
}

// -------------------------------------------------------------
// REDESIGNED TAB SWITCHER SCREEN / MODAL SHEET
// -------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabGridSheet(
  tabs: List<BrowserTab>,
  tabGroups: List<TabGroup>,
  activeIndex: Int,
  onTabSelect: (Int) -> Unit,
  onTabClose: (String) -> Unit,
  onNewTab: (PrivacyProfile, groupId: String?) -> Unit,
  onCreateGroup: (title: String, colorHex: Long, tabIds: List<String>) -> Unit,
  onAddTabToGroup: (tabId: String, groupId: String) -> Unit,
  onRemoveTabFromGroup: (tabId: String) -> Unit,
  onUpdateGroup: (groupId: String, title: String, colorHex: Long) -> Unit,
  onDeleteGroup: (groupId: String, closeTabs: Boolean) -> Unit,
  onToggleGroupCollapse: (groupId: String) -> Unit,
  onSetTabInactive: (tabId: String, isInactive: Boolean) -> Unit,
  onSetGroupInactive: (groupId: String, isInactive: Boolean) -> Unit,
  onCloseAllInactiveTabs: () -> Unit,
  onDuplicateTab: (tabId: String) -> Unit,
  onTogglePinTab: (tabId: String) -> Unit,
  onToggleLockTab: (tabId: String) -> Unit = {},
  onCloseMultipleTabs: (List<String>) -> Unit = {},
  onLockMultipleTabs: (List<String>, Boolean) -> Unit = { _, _ -> },
  onSetMultipleTabsInactive: (List<String>, Boolean) -> Unit = { _, _ -> },
  onMoveMultipleTabsToGroup: (List<String>, String?) -> Unit = { _, _ -> },
  onCloseAllTabs: () -> Unit,
  onOpenSettings: () -> Unit = {},
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val cyberColors = ThemeCyber.colors
  val isLight = cyberColors.isLight
  val isDark = !isLight
  val scope = rememberCoroutineScope()
  val thumbnailManager = remember { TabThumbnailManager.getInstance(context) }
  val thumbnailVersions by thumbnailManager.thumbnailVersions.collectAsState()

  // Theme Colors dynamically matching selected CyberTheme
  val backgroundColor = cyberColors.background
  val surfaceCardColor = cyberColors.surface
  val surfacePillColor = cyberColors.surfaceLight
  val borderColor = cyberColors.surfaceBorder
  val activeAccentColor = cyberColors.primary
  val textPrimary = cyberColors.textPrimary
  val textSecondary = cyberColors.textSecondary
  val textMuted = cyberColors.textMuted
  val dangerRed = cyberColors.dangerRed
  val torPurple = cyberColors.torPurple

  // Local State
  var selectedFilter by remember { mutableStateOf(TabFilter.ALL) }
  var searchQuery by remember { mutableStateOf("") }
  var selectedSpaceFilter by remember { mutableStateOf<String?>(null) } // null = all, "personal", "incognito", "tor", or groupId
  var showSpacesMenu by remember { mutableStateOf(false) }

  // Select Mode State
  var isSelectMode by remember { mutableStateOf(false) }
  val selectedTabIds = remember { mutableStateListOf<String>() }

  // Dialog States
  var showCreateGroupDialog by remember { mutableStateOf(false) }
  var showMoveToSpaceDialog by remember { mutableStateOf(false) }
  var editingGroup by remember { mutableStateOf<TabGroup?>(null) }
  var tabOptionsTarget by remember { mutableStateOf<BrowserTab?>(null) }

  // Dynamic Tab Sets & Counts
  val personalTabs = remember(tabs) { tabs.filter { it.profile == PrivacyProfile.SHIELD && it.groupId == null } }
  val incognitoTabs = remember(tabs) { tabs.filter { it.profile == PrivacyProfile.INCOGNITO } }
  val torTabs = remember(tabs) { tabs.filter { it.profile == PrivacyProfile.GHOST } }
  val inactiveTabs = remember(tabs) { tabs.filter { it.isInactive } }
  val activeOnlyTabs = remember(tabs) { tabs.filter { !it.isInactive } }

  // Filtered Tab List
  val filteredTabs = remember(tabs, selectedFilter, selectedSpaceFilter, searchQuery) {
    var list = when (selectedSpaceFilter) {
      "personal" -> personalTabs
      "incognito" -> incognitoTabs
      "tor" -> torTabs
      null -> tabs
      else -> tabs.filter { it.groupId == selectedSpaceFilter }
    }

    list = when (selectedFilter) {
      TabFilter.ALL -> list
      TabFilter.RECENT -> list.sortedByDescending { it.lastAccessedAt }
      TabFilter.ACTIVE -> list.filter { !it.isInactive }
      TabFilter.SLEEP -> list.filter { it.isInactive }
    }

    val query = searchQuery.trim().lowercase()
    if (query.isNotEmpty()) {
      list = list.filter {
        it.title.lowercase().contains(query) ||
          it.url.lowercase().contains(query) ||
          it.profile.name.lowercase().contains(query)
      }
    }
    list
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .background(backgroundColor)
      .statusBarsPadding()
      .navigationBarsPadding()
      .padding(horizontal = 14.dp, vertical = 6.dp)
  ) {
    // 1. DRAG HANDLE & TOP BRAND HEADER
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = 6.dp),
      contentAlignment = Alignment.Center
    ) {
      Box(
        modifier = Modifier
          .width(38.dp)
          .height(4.dp)
          .clip(CircleShape)
          .background(textMuted.copy(alpha = 0.35f))
      )
    }

    if (isSelectMode) {
      // SELECT MODE HEADER
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "Select Tabs",
            color = textPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = CyberMonoFamily
          )
          Spacer(modifier = Modifier.width(6.dp))
          Surface(
            shape = CircleShape,
            color = activeAccentColor.copy(alpha = 0.15f),
            border = BorderStroke(0.8.dp, activeAccentColor)
          ) {
            Text(
              text = "${selectedTabIds.size}",
              color = activeAccentColor,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = CyberMonoFamily,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          TextButton(
            onClick = {
              if (selectedTabIds.size == filteredTabs.size) {
                selectedTabIds.clear()
              } else {
                selectedTabIds.clear()
                selectedTabIds.addAll(filteredTabs.map { it.id })
              }
            }
          ) {
            Text(
              text = if (selectedTabIds.size == filteredTabs.size) "Deselect All" else "Select All",
              color = activeAccentColor,
              fontSize = 12.5.sp,
              fontWeight = FontWeight.SemiBold
            )
          }

          Button(
            onClick = {
              isSelectMode = false
              selectedTabIds.clear()
            },
            colors = ButtonDefaults.buttonColors(containerColor = activeAccentColor),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
          ) {
            Text("Done", color = if (isDark) Color.Black else Color.White, fontWeight = FontWeight.Bold)
          }
        }
      }
    } else {
      // COMPACT HEADER: [ R Remmi 1 ]  [ Search ]  [Incog] [+]
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Logo + Remmi Title + Total Count Pill
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = surfaceCardColor,
          border = BorderStroke(1.dp, activeAccentColor.copy(alpha = 0.2f))
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Surface(
              shape = RoundedCornerShape(6.dp),
              color = activeAccentColor.copy(alpha = 0.15f),
              modifier = Modifier.size(24.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Text(
                  text = "R",
                  color = activeAccentColor,
                  fontFamily = CyberMonoFamily,
                  fontWeight = FontWeight.Bold,
                  fontSize = 14.sp
                )
              }
            }
            Text(
              text = "Remmi",
              color = textPrimary,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = CyberMonoFamily
            )
            Surface(
              shape = CircleShape,
              color = surfaceCardColor,
              border = BorderStroke(0.5.dp, borderColor)
            ) {
              Text(
                text = "${tabs.size}",
                color = textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = CyberMonoFamily,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
              )
            }
          }
        }

        // Search Bar
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = surfaceCardColor,
          border = BorderStroke(1.dp, borderColor),
          shadowElevation = if (isDark) 0.dp else 1.dp,
          modifier = Modifier
            .weight(1f)
            .height(36.dp)
        ) {
          Row(
            modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Search,
              contentDescription = "Search Tabs and Spaces",
              tint = if (searchQuery.isNotEmpty()) activeAccentColor else textMuted,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f)) {
              if (searchQuery.isEmpty()) {
                Text(
                  text = "Search Tabs and Spaces",
                  color = textMuted,
                  fontSize = 12.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
              BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                textStyle = TextStyle(
                  color = textPrimary,
                  fontSize = 12.5.sp,
                  fontWeight = FontWeight.Medium
                ),
                cursorBrush = SolidColor(activeAccentColor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth()
              )
            }
            if (searchQuery.isNotEmpty()) {
              IconButton(
                onClick = { searchQuery = "" },
                modifier = Modifier.size(24.dp)
              ) {
                Icon(
                  Icons.Default.Close,
                  contentDescription = "Clear search",
                  tint = textMuted,
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          }
        }

        // [ Incognito ] Button
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = surfaceCardColor,
          border = BorderStroke(1.dp, borderColor),
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable {
              onNewTab(PrivacyProfile.INCOGNITO, null)
              onDismiss()
            }
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              painter = painterResource(R.drawable.ic_incognito),
              contentDescription = "New Incognito Tab",
              tint = textPrimary,
              modifier = Modifier.size(18.dp)
            )
          }
        }

        // [+] New Tab
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = surfaceCardColor,
          border = BorderStroke(1.dp, borderColor),
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable {
              val currentActiveTab = tabs.getOrNull(activeIndex)
              val targetProf = when {
                selectedSpaceFilter == "tor" -> PrivacyProfile.GHOST
                selectedSpaceFilter == "incognito" -> PrivacyProfile.INCOGNITO
                currentActiveTab?.profile == PrivacyProfile.GHOST -> PrivacyProfile.GHOST
                currentActiveTab?.profile == PrivacyProfile.INCOGNITO -> PrivacyProfile.INCOGNITO
                else -> PrivacyProfile.SHIELD
              }
              onNewTab(targetProf, selectedSpaceFilter?.takeIf { it != "personal" && it != "incognito" && it != "tor" })
              onDismiss()
            }
            .testTag("add_tab_button")
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              Icons.Default.Add,
              contentDescription = "New Tab",
              tint = activeAccentColor,
              modifier = Modifier.size(20.dp)
            )
          }
        }

        // [3 Dot / Close Menu]
        Box {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = surfaceCardColor,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
              .size(36.dp)
              .clip(RoundedCornerShape(10.dp))
              .clickable { showSpacesMenu = true }
              .testTag("close_tabs_sheet_button")
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = textPrimary,
                modifier = Modifier.size(18.dp)
              )
            }
          }
          
          DropdownMenu(
            expanded = showSpacesMenu,
            onDismissRequest = { showSpacesMenu = false },
            modifier = Modifier.background(surfaceCardColor)
          ) {
            DropdownMenuItem(
              text = { Text("All Spaces", color = if (selectedSpaceFilter == null) activeAccentColor else textPrimary) },
              leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null, tint = if (selectedSpaceFilter == null) activeAccentColor else textPrimary, modifier = Modifier.size(20.dp)) },
              onClick = { 
                selectedSpaceFilter = null
                showSpacesMenu = false
              }
            )
            DropdownMenuItem(
              text = { Text("Personal Space", color = if (selectedSpaceFilter == "personal") activeAccentColor else textPrimary) },
              leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = if (selectedSpaceFilter == "personal") activeAccentColor else textPrimary, modifier = Modifier.size(20.dp)) },
              onClick = { 
                selectedSpaceFilter = "personal"
                showSpacesMenu = false
              }
            )
            DropdownMenuItem(
              text = { Text("Incognito Space", color = if (selectedSpaceFilter == "incognito") activeAccentColor else textPrimary) },
              leadingIcon = { Icon(painterResource(R.drawable.ic_incognito), contentDescription = null, tint = if (selectedSpaceFilter == "incognito") activeAccentColor else textPrimary, modifier = Modifier.size(20.dp)) },
              onClick = { 
                selectedSpaceFilter = "incognito"
                showSpacesMenu = false
              }
            )
            DropdownMenuItem(
              text = { Text("Tor Space", color = if (selectedSpaceFilter == "tor") torPurple else textPrimary) },
              leadingIcon = { Icon(painterResource(R.drawable.ic_tor), contentDescription = null, tint = if (selectedSpaceFilter == "tor") torPurple else textPrimary, modifier = Modifier.size(20.dp)) },
              onClick = { 
                selectedSpaceFilter = "tor"
                showSpacesMenu = false
              }
            )
            tabGroups.forEach { group ->
               DropdownMenuItem(
                 text = { Text(group.title, color = if (selectedSpaceFilter == group.id) Color(group.colorHex) else textPrimary) },
                 leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, tint = Color(group.colorHex), modifier = Modifier.size(20.dp)) },
                 onClick = {
                   selectedSpaceFilter = group.id
                   showSpacesMenu = false
                 }
               )
            }
            HorizontalDivider(color = borderColor)
            DropdownMenuItem(
              text = { Text("Manage Spaces", color = textPrimary) },
              leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null, tint = textPrimary, modifier = Modifier.size(20.dp)) },
              onClick = { 
                showCreateGroupDialog = true
                showSpacesMenu = false
              }
            )
            HorizontalDivider(color = borderColor)
            DropdownMenuItem(
              text = { Text("Close Tabs Switcher", color = textPrimary) },
              leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, tint = textPrimary, modifier = Modifier.size(20.dp)) },
              onClick = { 
                onDismiss()
                showSpacesMenu = false
              }
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // 4. MAIN SCROLLABLE CONTENT (SPACES + TAB FILTERS + TAB GRID)
    LazyColumn(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // TAB FILTERS SECTION (Chips: All, Recent, Active, Sleep)
      item {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
          item {
            FilterChipItem(
              title = "All",
              count = tabs.size,
              icon = Icons.Default.GridView,
              isSelected = selectedFilter == TabFilter.ALL,
              accentColor = activeAccentColor,
              onClick = { selectedFilter = TabFilter.ALL }
            )
          }
          item {
            FilterChipItem(
              title = "Recent",
              icon = Icons.Default.Schedule,
              isSelected = selectedFilter == TabFilter.RECENT,
              accentColor = activeAccentColor,
              onClick = { selectedFilter = TabFilter.RECENT }
            )
          }
          item {
            FilterChipItem(
              title = "Active",
              count = activeOnlyTabs.size,
              icon = Icons.Default.CheckCircle,
              isSelected = selectedFilter == TabFilter.ACTIVE,
              accentColor = activeAccentColor,
              onClick = { selectedFilter = TabFilter.ACTIVE }
            )
          }
          item {
            FilterChipItem(
              title = "Sleep",
              count = inactiveTabs.size,
              icon = Icons.Default.NightlightRound,
              isSelected = selectedFilter == TabFilter.SLEEP,
              accentColor = activeAccentColor,
              onClick = { selectedFilter = TabFilter.SLEEP }
            )
          }
        }
      }

      // ALL TABS SECTION HEADER
      item {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = if (selectedSpaceFilter != null) "FILTERED TABS" else "ALL TABS",
              color = textPrimary,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold,
              letterSpacing = 0.8.sp
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = activeAccentColor.copy(alpha = 0.15f),
              border = BorderStroke(0.6.dp, activeAccentColor.copy(alpha = 0.3f))
            ) {
              Text(
                text = "${filteredTabs.size}",
                color = activeAccentColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = CyberMonoFamily,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
              )
            }
          }
          TextButton(
            onClick = {
              onCloseAllTabs()
              onDismiss()
            },
            colors = ButtonDefaults.textButtonColors(contentColor = dangerRed)
          ) {
            Text(
              text = "Close All",
              fontSize = 12.5.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }

      // TAB GRID ITEMS (2 COLUMNS ADAPTIVE)
      if (filteredTabs.isNotEmpty()) {
        val chunkedTabs = filteredTabs.chunked(2)
        items(chunkedTabs) { row ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            for (tab in row) {
              val originalIndex = tabs.indexOfFirst { it.id == tab.id }
              val isActive = originalIndex == activeIndex
              val isSelected = selectedTabIds.contains(tab.id)
              val group = tabGroups.find { it.id == tab.groupId }
              val groupColor = group?.let { Color(it.colorHex) }

              // Trigger reactive thumbnail state by observing thumbnailVersions
              val versionKey = thumbnailVersions[tab.id] ?: 0L
              val thumbnailBitmap = remember(tab.id, versionKey) {
                thumbnailManager.getThumbnail(tab.id)
              }

              Box(modifier = Modifier.weight(1f)) {
                ModernTabCard(
                  tab = tab,
                  isActive = isActive,
                  isSelected = isSelected,
                  isSelectMode = isSelectMode,
                  groupColor = groupColor,
                  thumbnail = thumbnailBitmap,
                  activeAccentColor = activeAccentColor,
                  onSelect = {
                    if (isSelectMode) {
                      if (isSelected) selectedTabIds.remove(tab.id) else selectedTabIds.add(tab.id)
                    } else {
                      if (originalIndex >= 0) {
                        onTabSelect(originalIndex)
                        onDismiss()
                      }
                    }
                  },
                  onClose = {
                    if (tab.isLocked) {
                      Toast.makeText(context, "Tab is locked. Unlock it before closing.", Toast.LENGTH_SHORT).show()
                    } else {
                      onTabClose(tab.id)
                    }
                  },
                  onOptions = { tabOptionsTarget = tab }
                )
              }
            }
            if (row.size == 1) {
              Spacer(modifier = Modifier.weight(1f))
            }
          }
        }
      } else {
        // Empty State
        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 40.dp),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Default.TabUnselected,
                contentDescription = null,
                tint = textMuted,
                modifier = Modifier.size(48.dp)
              )
              Spacer(modifier = Modifier.height(10.dp))
              Text(
                text = if (searchQuery.isNotEmpty()) "No matching tabs" else "No open tabs in this space",
                color = textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = if (searchQuery.isNotEmpty()) "Try another search query" else "Open a new tab to start browsing.",
                color = textMuted,
                fontSize = 12.sp
              )
              Spacer(modifier = Modifier.height(14.dp))
              Button(
                onClick = {
                  val currentActiveTab = tabs.getOrNull(activeIndex)
                  val targetProf = when {
                    selectedSpaceFilter == "tor" -> PrivacyProfile.GHOST
                    selectedSpaceFilter == "incognito" -> PrivacyProfile.INCOGNITO
                    currentActiveTab?.profile == PrivacyProfile.GHOST -> PrivacyProfile.GHOST
                    currentActiveTab?.profile == PrivacyProfile.INCOGNITO -> PrivacyProfile.INCOGNITO
                    else -> PrivacyProfile.SHIELD
                  }
                  onNewTab(targetProf, selectedSpaceFilter?.takeIf { it != "personal" && it != "incognito" && it != "tor" })
                  onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = activeAccentColor),
                shape = RoundedCornerShape(10.dp)
              ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ New Tab", fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }
    }

    // 5. BOTTOM ACTION BAR DOCKED AT THE BOTTOM
    Spacer(modifier = Modifier.height(8.dp))
    Surface(
      shape = RoundedCornerShape(18.dp),
      color = surfaceCardColor,
      border = BorderStroke(1.dp, borderColor),
      shadowElevation = if (isDark) 0.dp else 2.dp,
      modifier = Modifier
        .fillMaxWidth()
        .height(64.dp)
    ) {
      if (isSelectMode) {
        // SELECT MODE ACTIONS (Close, Move, Group, Lock, Sleep)
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically
        ) {
          val hasSelection = selectedTabIds.isNotEmpty()

          BottomNavIconButton(
            icon = Icons.Default.Close,
            label = "Close (${selectedTabIds.size})",
            tint = if (hasSelection) dangerRed else textMuted,
            enabled = hasSelection,
            onClick = {
              onCloseMultipleTabs(selectedTabIds.toList())
              selectedTabIds.clear()
              isSelectMode = false
            }
          )

          BottomNavIconButton(
            icon = Icons.Default.DriveFileMove,
            label = "Move",
            tint = if (hasSelection) activeAccentColor else textMuted,
            enabled = hasSelection,
            onClick = { showMoveToSpaceDialog = true }
          )

          BottomNavIconButton(
            icon = Icons.Default.Folder,
            label = "Group",
            tint = if (hasSelection) activeAccentColor else textMuted,
            enabled = hasSelection,
            onClick = { showCreateGroupDialog = true }
          )

          BottomNavIconButton(
            icon = Icons.Default.Lock,
            label = "Lock",
            tint = if (hasSelection) textPrimary else textMuted,
            enabled = hasSelection,
            onClick = {
              onLockMultipleTabs(selectedTabIds.toList(), true)
              Toast.makeText(context, "Locked ${selectedTabIds.size} tabs", Toast.LENGTH_SHORT).show()
              selectedTabIds.clear()
              isSelectMode = false
            }
          )

          BottomNavIconButton(
            icon = Icons.Default.NightlightRound,
            label = "Sleep",
            tint = if (hasSelection) textPrimary else textMuted,
            enabled = hasSelection,
            onClick = {
              onSetMultipleTabsInactive(selectedTabIds.toList(), true)
              Toast.makeText(context, "Suspended ${selectedTabIds.size} tabs to sleep", Toast.LENGTH_SHORT).show()
              selectedTabIds.clear()
              isSelectMode = false
            }
          )
        }
      } else {
        // NORMAL BOTTOM BAR ([ Select ] [ Group Tabs ] [ Lock Tabs ] [ Sleep Tabs ] [ Settings ])
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically
        ) {
          BottomNavIconButton(
            icon = Icons.Default.CheckCircleOutline,
            label = "Select",
            tint = textPrimary,
            onClick = { isSelectMode = true }
          )

          BottomNavIconButton(
            icon = Icons.Default.Folder,
            label = "Group Tabs",
            tint = textPrimary,
            onClick = { showCreateGroupDialog = true }
          )

          BottomNavIconButton(
            icon = Icons.Default.Lock,
            label = "Lock Tabs",
            tint = textPrimary,
            onClick = {
              val currentActive = tabs.getOrNull(activeIndex)
              if (currentActive != null) {
                onToggleLockTab(currentActive.id)
                val status = if (!currentActive.isLocked) "Locked" else "Unlocked"
                Toast.makeText(context, "$status '${currentActive.title}'", Toast.LENGTH_SHORT).show()
              }
            }
          )

          BottomNavIconButton(
            icon = Icons.Default.Bedtime,
            label = "Sleep Tabs",
            tint = textPrimary,
            onClick = {
              val nonActiveIds = tabs.mapIndexedNotNull { idx, t -> if (idx != activeIndex) t.id else null }
              if (nonActiveIds.isNotEmpty()) {
                onSetMultipleTabsInactive(nonActiveIds, true)
                Toast.makeText(context, "Put ${nonActiveIds.size} background tabs to sleep", Toast.LENGTH_SHORT).show()
              }
            }
          )

          BottomNavIconButton(
            icon = Icons.Default.Settings,
            label = "Settings",
            tint = textPrimary,
            onClick = {
              onOpenSettings()
              onDismiss()
            }
          )
        }
      }
    }
  }

  // --- DIALOGS & ACTION SHEETS ---

  // 1. Create Space / Tab Group Dialog
  if (showCreateGroupDialog) {
    CreateTabGroupDialog(
      availableTabs = tabs.filter { it.groupId == null },
      preselectedTabIds = selectedTabIds.toList(),
      onDismiss = { showCreateGroupDialog = false },
      onCreate = { title, colorHex, tabIds ->
        onCreateGroup(title, colorHex, tabIds)
        showCreateGroupDialog = false
        selectedTabIds.clear()
        isSelectMode = false
      }
    )
  }

  // 2. Edit Tab Group Dialog
  editingGroup?.let { group ->
    EditTabGroupDialog(
      group = group,
      onDismiss = { editingGroup = null },
      onSave = { newTitle, newColor ->
        onUpdateGroup(group.id, newTitle, newColor)
        editingGroup = null
      },
      onDelete = { closeTabs ->
        onDeleteGroup(group.id, closeTabs)
        editingGroup = null
      }
    )
  }

  // 3. Move Selected Tabs To Space Dialog
  if (showMoveToSpaceDialog) {
    AlertDialog(
      onDismissRequest = { showMoveToSpaceDialog = false },
      containerColor = surfaceCardColor,
      title = {
        Text("Move ${selectedTabIds.size} Tabs to Space", color = textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          // Move to Personal (Ungroup)
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = surfacePillColor,
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                onMoveMultipleTabsToGroup(selectedTabIds.toList(), null)
                showMoveToSpaceDialog = false
                selectedTabIds.clear()
                isSelectMode = false
              }
              .padding(10.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(Icons.Default.Person, contentDescription = null, tint = activeAccentColor, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(10.dp))
              Text("Personal Space", color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
          }

          tabGroups.forEach { grp ->
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = surfacePillColor,
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  onMoveMultipleTabsToGroup(selectedTabIds.toList(), grp.id)
                  showMoveToSpaceDialog = false
                  selectedTabIds.clear()
                  isSelectMode = false
                }
                .padding(10.dp)
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = Color(grp.colorHex), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(grp.title, color = textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { showMoveToSpaceDialog = false }) {
          Text("Cancel", color = textSecondary)
        }
      }
    )
  }

  // 4. Tab Contextual Action Sheet (⋮ Menu)
  tabOptionsTarget?.let { tab ->
    TabActionSheet(
      tab = tab,
      tabGroups = tabGroups,
      onDismiss = { tabOptionsTarget = null },
      onOpen = {
        val originalIndex = tabs.indexOfFirst { it.id == tab.id }
        if (originalIndex >= 0) {
          onTabSelect(originalIndex)
          onDismiss()
        }
        tabOptionsTarget = null
      },
      onDuplicate = {
        onDuplicateTab(tab.id)
        tabOptionsTarget = null
      },
      onAddToBookmarks = {
        scope.launch(Dispatchers.IO) {
          val db = RemmiDatabase.getDatabaseAsync(context)
          db.bookmarkDao().insert(
            BookmarkItem(
              title = tab.title.ifBlank { tab.url },
              url = tab.url,
              category = "General"
            )
          )
        }
        Toast.makeText(context, "Added to Bookmarks", Toast.LENGTH_SHORT).show()
        tabOptionsTarget = null
      },
      onTogglePin = {
        onTogglePinTab(tab.id)
        tabOptionsTarget = null
      },
      onToggleLock = {
        onToggleLockTab(tab.id)
        tabOptionsTarget = null
      },
      onToggleInactive = {
        onSetTabInactive(tab.id, !tab.isInactive)
        tabOptionsTarget = null
      },
      onShare = {
        if (tab.url.isNotBlank() && tab.url != "about:blank") {
          val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, tab.title)
            putExtra(Intent.EXTRA_TEXT, tab.url)
          }
          context.startActivity(Intent.createChooser(shareIntent, "Share URL"))
        }
        tabOptionsTarget = null
      },
      onAssignToGroup = { gid ->
        if (gid != null) {
          onAddTabToGroup(tab.id, gid)
        } else {
          onRemoveTabFromGroup(tab.id)
        }
        tabOptionsTarget = null
      },
      onCreateGroupWithTab = {
        showCreateGroupDialog = true
        tabOptionsTarget = null
      },
      onCloseTab = {
        if (tab.isLocked) {
          Toast.makeText(context, "Tab is locked. Unlock it before closing.", Toast.LENGTH_SHORT).show()
        } else {
          onTabClose(tab.id)
        }
        tabOptionsTarget = null
      }
    )
  }
}

// -------------------------------------------------------------
// SPACE CARD (Personal, Incognito, Tor, Custom Groups)
// -------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpaceCard(
  title: String,
  count: Int,
  isSelected: Boolean,
  icon: @Composable () -> Unit,
  accentColor: Color,
  onClick: () -> Unit,
  onLongClick: (() -> Unit)? = null,
) {
  val cyberColors = ThemeCyber.colors
  val isDark = !cyberColors.isLight
  val surfaceColor = cyberColors.surface
  val borderColor = cyberColors.surfaceBorder
  val textPrimary = cyberColors.textPrimary
  val textMuted = cyberColors.textMuted

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = if (isSelected) accentColor.copy(alpha = if (isDark) 0.18f else 0.10f) else surfaceColor,
    border = BorderStroke(
      width = if (isSelected) 1.8.dp else 1.dp,
      color = if (isSelected) accentColor else borderColor
    ),
    shadowElevation = if (isDark || isSelected) 0.dp else 1.dp,
    modifier = Modifier
      .width(140.dp)
      .height(48.dp)
      .clip(RoundedCornerShape(12.dp))
      .combinedClickable(
        onClick = onClick,
        onLongClick = onLongClick
      )
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier.size(24.dp),
          contentAlignment = Alignment.Center
        ) {
          icon()
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = title.replace(" Space", ""),
          color = if (isSelected) accentColor else textPrimary,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      Text(
        text = if (count == 1) "1 tab" else "$count tabs",
        color = if (isSelected) accentColor.copy(alpha = 0.9f) else textMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Normal
      )
    }
  }
}

// -------------------------------------------------------------
// NEW SPACE CARD (+ ✨ New Space)
// -------------------------------------------------------------

@Composable
private fun NewSpaceCard(
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val cyberColors = ThemeCyber.colors
  val isDark = !cyberColors.isLight
  val surfaceColor = cyberColors.surface
  val borderColor = cyberColors.surfaceBorder
  val textPrimary = cyberColors.textPrimary
  val textSecondary = cyberColors.textSecondary

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = surfaceColor,
    border = BorderStroke(1.dp, borderColor),
    shadowElevation = if (isDark) 0.dp else 1.dp,
    modifier = modifier
      .width(120.dp)
      .height(48.dp)
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center
    ) {
      Icon(
        imageVector = Icons.Default.Add,
        contentDescription = "New Space",
        tint = textPrimary,
        modifier = Modifier.size(18.dp)
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = "New Space",
        color = textPrimary,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

// -------------------------------------------------------------
// FILTER CHIP ITEM (All, Recent, Active, Sleep)
// -------------------------------------------------------------

@Composable
private fun FilterChipItem(
  title: String,
  count: Int? = null,
  icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
  accentColor: Color,
  isSelected: Boolean,
  onClick: () -> Unit,
) {
  val cyberColors = ThemeCyber.colors
  val isDark = !cyberColors.isLight
  val surfaceColor = cyberColors.surface
  val borderColor = cyberColors.surfaceBorder
  val textPrimary = cyberColors.textPrimary
  val textSecondary = cyberColors.textSecondary

  Surface(
    shape = RoundedCornerShape(18.dp),
    color = if (isSelected) accentColor else surfaceColor,
    border = BorderStroke(
      width = if (isSelected) 0.dp else 1.dp,
      color = if (isSelected) Color.Transparent else borderColor
    ),
    shadowElevation = if (isSelected || isDark) 0.dp else 1.dp,
    modifier = Modifier
      .height(34.dp)
      .clip(RoundedCornerShape(18.dp))
      .clickable(onClick = onClick)
  ) {
    Row(
      modifier = Modifier
        .fillMaxHeight()
        .padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = if (isSelected) (if (isDark) Color.Black else Color.White) else textSecondary,
          modifier = Modifier.size(15.dp)
        )
      }
      Text(
        text = if (count != null) "$title ($count)" else title,
        color = if (isSelected) (if (isDark) Color.Black else Color.White) else textPrimary,
        fontSize = 12.5.sp,
        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
      )
    }
  }
}

// -------------------------------------------------------------
// REDESIGNED 2-COLUMN MODERN TAB CARD WITH REAL THUMBNAIL
// -------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModernTabCard(
  tab: BrowserTab,
  isActive: Boolean,
  isSelected: Boolean,
  isSelectMode: Boolean,
  groupColor: Color?,
  thumbnail: Bitmap?,
  activeAccentColor: Color,
  onSelect: () -> Unit,
  onClose: () -> Unit,
  onOptions: () -> Unit,
) {
  val cyberColors = ThemeCyber.colors
  val isDark = !cyberColors.isLight
  val surfaceColor = cyberColors.surface
  val borderColor = cyberColors.surfaceBorder
  val textPrimary = cyberColors.textPrimary
  val textSecondary = cyberColors.textSecondary
  val textMuted = cyberColors.textMuted
  val torPurple = cyberColors.torPurple

  val profileColor = when (tab.profile) {
    PrivacyProfile.GHOST -> torPurple
    PrivacyProfile.INCOGNITO -> textSecondary
    else -> groupColor ?: activeAccentColor
  }

  val isBlankOrNewTab = tab.url.isEmpty() || tab.url == "about:blank" || tab.url == "remmi://newtab" || tab.title.equals("Remmi Home", ignoreCase = true)
  val cleanDomain = remember(tab.url) {
    try {
      val uri = android.net.Uri.parse(tab.url)
      uri.host?.removePrefix("www.") ?: if (isBlankOrNewTab) "remmi.browser" else tab.url
    } catch (_: Exception) {
      tab.url
    }
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = surfaceColor),
    shape = RoundedCornerShape(20.dp),
    border = BorderStroke(
      width = if (isActive || isSelected) 2.dp else 1.dp,
      color = if (isSelected) activeAccentColor else if (isActive) profileColor else (groupColor?.copy(alpha = 0.6f) ?: borderColor)
    ),
    elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 2.dp),
    modifier = Modifier
      .fillMaxWidth()
      .height(238.dp)
      .clip(RoundedCornerShape(20.dp))
      .combinedClickable(
        onClick = onSelect,
        onLongClick = onOptions
      )
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .background(
          if (isActive || isSelected) {
            Brush.verticalGradient(
              colors = listOf(
                profileColor.copy(alpha = if (isDark) 0.15f else 0.08f),
                surfaceColor
              )
            )
          } else {
            SolidColor(surfaceColor)
          }
        )
        .padding(8.dp)
    ) {
      // 1. CARD TOP: Active Dot + Title + Security Label + ⋮ Menu
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          // Active Blue Dot or Lock indicator
          if (tab.isLocked) {
            Icon(
              imageVector = Icons.Default.Lock,
              contentDescription = "Locked Tab",
              tint = activeAccentColor,
              modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
          } else {
            Box(
              modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (isActive) profileColor else textMuted)
            )
            Spacer(modifier = Modifier.width(5.dp))
          }

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = if (isBlankOrNewTab) "Remmi Home" else tab.title.ifEmpty { cleanDomain },
              color = textPrimary,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )

            // Security Badge: Shield / Incognito / Tor
            Row(verticalAlignment = Alignment.CenterVertically) {
              val profileLabel = when (tab.profile) {
                PrivacyProfile.GHOST -> "Tor"
                PrivacyProfile.INCOGNITO -> "Incognito"
                else -> "Shield"
              }
              Text(
                text = profileLabel,
                color = profileColor,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = CyberMonoFamily
              )
            }
          }
        }

        // Selection Checkbox or ⋮ Menu
        if (isSelectMode) {
          Checkbox(
            checked = isSelected,
            onCheckedChange = { onSelect() },
            colors = CheckboxDefaults.colors(checkedColor = activeAccentColor),
            modifier = Modifier.size(22.dp)
          )
        } else {
          IconButton(
            onClick = onOptions,
            modifier = Modifier.size(24.dp)
          ) {
            Icon(
              imageVector = Icons.Default.MoreVert,
              contentDescription = "Tab options",
              tint = textSecondary,
              modifier = Modifier.size(16.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      // 2. CARD MIDDLE: ACTUAL WEBPAGE THUMBNAIL / PREVIEW CANVAS
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
          .clip(RoundedCornerShape(12.dp))
          .background(cyberColors.surfaceLight)
          .border(0.6.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
      ) {
        if (isBlankOrNewTab) {
          // Remmi Home Authentic Visual Preview
          RemmiHomePreviewCanvas()
        } else if (thumbnail != null && !thumbnail.isRecycled) {
          // Actual Webpage Snapshot
          Image(
            bitmap = thumbnail.asImageBitmap(),
            contentDescription = "Webpage Preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
          )
        } else {
          // High-Polish Graceful Web Skeleton / Fallback Preview
          WebSkeletonPreview(
            title = tab.title.ifEmpty { cleanDomain },
            domain = cleanDomain,
            isGhost = tab.profile == PrivacyProfile.GHOST
          )
        }

        // Overlay Badges: Sleep or Locked
        if (tab.isInactive) {
          Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color.Black.copy(alpha = 0.75f),
            modifier = Modifier
              .align(Alignment.BottomEnd)
              .padding(6.dp)
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(Icons.Default.NightlightRound, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
              Spacer(modifier = Modifier.width(3.dp))
              Text(
                text = "SLEEP",
                fontSize = 8.5.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(6.dp))

      // 3. CARD BOTTOM: Domain / Tagline & Close Tab Button
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          val bottomLine1 = if (isBlankOrNewTab) "Remmi Browser" else cleanDomain
          val bottomLine2 = if (isBlankOrNewTab) "Private. Fast. Yours." else tab.title.ifEmpty { cleanDomain }
          Text(
            text = bottomLine1,
            color = textPrimary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Text(
            text = bottomLine2,
            color = textMuted,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        Text(
          text = "Close Tab",
          color = if (tab.isLocked) textMuted else textSecondary,
          fontSize = 10.5.sp,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier
            .clickable(onClick = onClose)
            .padding(start = 6.dp, top = 2.dp, bottom = 2.dp)
        )
      }
    }
  }
}

// -------------------------------------------------------------
// REMMI HOME VISUAL PREVIEW CANVAS (Panda + Hero + Search Bar)
// -------------------------------------------------------------

@Composable
private fun RemmiHomePreviewCanvas() {
  val cyberColors = ThemeCyber.colors
  val isDark = !cyberColors.isLight

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          colors = listOf(cyberColors.background, cyberColors.surface)
        )
      )
      .padding(6.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier.fillMaxWidth()
    ) {
      // 3D Panda Mascot Artwork (Scaled for thumbnail)
      PandaMascotArt(
        size = 54.dp,
        isDarkTheme = isDark,
        accentColor = cyberColors.primary
      )

      Spacer(modifier = Modifier.height(4.dp))

      Text(
        text = "Remmi Browser",
        color = cyberColors.textPrimary,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.ExtraBold,
        fontFamily = CyberMonoFamily
      )

      Text(
        text = "Private. Fast. Yours.",
        color = cyberColors.textSecondary,
        fontSize = 8.sp,
        fontWeight = FontWeight.Normal
      )

      Spacer(modifier = Modifier.height(6.dp))

      // Miniature Search Bar Surface
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = cyberColors.surfaceLight,
        border = BorderStroke(0.6.dp, cyberColors.surfaceBorder),
        modifier = Modifier
          .fillMaxWidth(0.92f)
          .height(20.dp)
      ) {
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = cyberColors.primary,
            modifier = Modifier.size(9.dp)
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "Search or enter web address",
            color = cyberColors.textMuted,
            fontSize = 7.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}

// -------------------------------------------------------------
// WEB SKELETON / HIGH-POLISH FALLBACK PREVIEW
// -------------------------------------------------------------

@Composable
private fun WebSkeletonPreview(
  title: String,
  domain: String,
  isGhost: Boolean = false
) {
  val cyberColors = ThemeCyber.colors
  val surfaceColor = cyberColors.surface
  val wireframeColor = cyberColors.surfaceLight
  val textPrimary = cyberColors.textPrimary
  val textMuted = cyberColors.textMuted
  val torPurple = cyberColors.torPurple

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(surfaceColor)
      .padding(8.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      // Header wireframe
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(16.dp)
              .clip(CircleShape)
              .background(if (isGhost) torPurple.copy(alpha = 0.3f) else wireframeColor),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = if (isGhost) Icons.Default.VpnKey else Icons.Default.Public,
              contentDescription = null,
              tint = if (isGhost) torPurple else textMuted,
              modifier = Modifier.size(10.dp)
            )
          }
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = domain,
            color = textMuted,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Icon(Icons.Default.Menu, contentDescription = null, tint = wireframeColor, modifier = Modifier.size(12.dp))
      }

      // Main Content Skeleton / Title
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Text(
          text = title,
          color = textPrimary,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
        // Simulated text lines
        Box(modifier = Modifier.fillMaxWidth(0.85f).height(5.dp).clip(RoundedCornerShape(2.dp)).background(wireframeColor))
        Box(modifier = Modifier.fillMaxWidth(0.65f).height(5.dp).clip(RoundedCornerShape(2.dp)).background(wireframeColor))
      }

      // Simulated button or footer
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Box(
          modifier = Modifier
            .weight(1f)
            .height(14.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isGhost) torPurple.copy(alpha = 0.2f) else cyberColors.surfaceBorder),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = if (isGhost) "Onion Circuit" else "Explore",
            color = if (isGhost) torPurple else textMuted,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    }
  }
}

// -------------------------------------------------------------
// BOTTOM NAV ICON BUTTON HELPER
// -------------------------------------------------------------

@Composable
private fun BottomNavIconButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  tint: Color,
  enabled: Boolean = true,
  onClick: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 6.dp, vertical = 4.dp)
  ) {
    Icon(
      imageVector = icon,
      contentDescription = label,
      tint = tint,
      modifier = Modifier.size(20.dp)
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
      text = label,
      color = tint,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.Medium,
      maxLines = 1
    )
  }
}

// -------------------------------------------------------------
// CREATE TAB GROUP / SPACE DIALOG
// -------------------------------------------------------------

@Composable
private fun CreateTabGroupDialog(
  availableTabs: List<BrowserTab>,
  preselectedTabIds: List<String> = emptyList(),
  onDismiss: () -> Unit,
  onCreate: (title: String, colorHex: Long, selectedTabIds: List<String>) -> Unit,
) {
  val cyberColors = ThemeCyber.colors
  val isDark = !cyberColors.isLight
  val surfaceColor = cyberColors.surface
  val borderColor = cyberColors.surfaceBorder
  val textPrimary = cyberColors.textPrimary
  val textSecondary = cyberColors.textSecondary

  var groupTitle by remember { mutableStateOf("") }
  var selectedColorHex by remember { mutableStateOf(TabGroup.PRESET_COLORS[0]) }
  val selectedTabIds = remember { mutableStateListOf<String>().apply { addAll(preselectedTabIds) } }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = surfaceColor,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.Folder,
          contentDescription = null,
          tint = Color(selectedColorHex),
          modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "New Space",
          color = textPrimary,
          fontFamily = CyberMonoFamily,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold
        )
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        OutlinedTextField(
          value = groupTitle,
          onValueChange = { groupTitle = it },
          label = { Text("Space Name (e.g. Work, Research, Dev)", fontSize = 12.sp) },
          singleLine = true,
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(selectedColorHex),
            unfocusedBorderColor = borderColor,
            focusedTextColor = textPrimary,
            unfocusedTextColor = textPrimary
          ),
          textStyle = TextStyle(fontFamily = CyberMonoFamily, fontSize = 13.sp),
          modifier = Modifier.fillMaxWidth()
        )

        Text(
          text = "Choose Space Color:",
          color = textSecondary,
          fontFamily = CyberMonoFamily,
          fontSize = 11.sp
        )

        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth()
        ) {
          TabGroup.PRESET_COLORS.forEach { colorVal ->
            val isSelected = selectedColorHex == colorVal
            Box(
              modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(colorVal))
                .border(
                  width = if (isSelected) 2.5.dp else 0.dp,
                  color = if (isSelected) (if (isDark) Color.White else Color.Black) else Color.Transparent,
                  shape = CircleShape
                )
                .clickable { selectedColorHex = colorVal },
              contentAlignment = Alignment.Center
            ) {
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = if (isDark) Color.Black else Color.White,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }

        if (availableTabs.isNotEmpty()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Include open tabs in this space:",
            color = textSecondary,
            fontFamily = CyberMonoFamily,
            fontSize = 11.sp
          )

          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 130.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            items(availableTabs, key = { it.id }) { tab ->
              val isChecked = selectedTabIds.contains(tab.id)
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(6.dp))
                  .background(if (isChecked) Color(selectedColorHex).copy(alpha = 0.15f) else cyberColors.surfaceLight)
                  .clickable {
                    if (isChecked) selectedTabIds.remove(tab.id) else selectedTabIds.add(tab.id)
                  }
                  .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Checkbox(
                  checked = isChecked,
                  onCheckedChange = { check ->
                    if (check) selectedTabIds.add(tab.id) else selectedTabIds.remove(tab.id)
                  },
                  colors = CheckboxDefaults.colors(checkedColor = Color(selectedColorHex)),
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = tab.title.ifEmpty { tab.url },
                  color = textPrimary,
                  fontFamily = CyberMonoFamily,
                  fontSize = 11.5.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onCreate(groupTitle.ifBlank { "Space" }, selectedColorHex, selectedTabIds.toList())
        },
        colors = ButtonDefaults.buttonColors(containerColor = Color(selectedColorHex))
      ) {
        Text("Create Space", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = CyberMonoFamily)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = textSecondary, fontFamily = CyberMonoFamily)
      }
    }
  )
}

// -------------------------------------------------------------
// EDIT TAB GROUP / SPACE DIALOG
// -------------------------------------------------------------

@Composable
private fun EditTabGroupDialog(
  group: TabGroup,
  onDismiss: () -> Unit,
  onSave: (title: String, colorHex: Long) -> Unit,
  onDelete: (closeTabs: Boolean) -> Unit,
) {
  val cyberColors = ThemeCyber.colors
  val isDark = !cyberColors.isLight
  val surfaceColor = cyberColors.surface
  val borderColor = cyberColors.surfaceBorder
  val textPrimary = cyberColors.textPrimary
  val textSecondary = cyberColors.textSecondary
  val dangerRed = cyberColors.dangerRed

  var groupTitle by remember { mutableStateOf(group.title) }
  var selectedColorHex by remember { mutableStateOf(group.colorHex) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = surfaceColor,
    title = {
      Text(
        text = "Edit Space",
        color = textPrimary,
        fontFamily = CyberMonoFamily,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        OutlinedTextField(
          value = groupTitle,
          onValueChange = { groupTitle = it },
          label = { Text("Space Name", fontSize = 12.sp) },
          singleLine = true,
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(selectedColorHex),
            unfocusedBorderColor = borderColor,
            focusedTextColor = textPrimary,
            unfocusedTextColor = textPrimary
          ),
          textStyle = TextStyle(fontFamily = CyberMonoFamily, fontSize = 13.sp),
          modifier = Modifier.fillMaxWidth()
        )

        Text(
          text = "Space Color:",
          color = textSecondary,
          fontFamily = CyberMonoFamily,
          fontSize = 11.sp
        )

        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth()
        ) {
          TabGroup.PRESET_COLORS.forEach { colorVal ->
            val isSelected = selectedColorHex == colorVal
            Box(
              modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(colorVal))
                .border(
                  width = if (isSelected) 2.5.dp else 0.dp,
                  color = if (isSelected) (if (isDark) Color.White else Color.Black) else Color.Transparent,
                  shape = CircleShape
                )
                .clickable { selectedColorHex = colorVal },
              contentAlignment = Alignment.Center
            ) {
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = if (isDark) Color.Black else Color.White,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          OutlinedButton(
            onClick = { onDelete(false) },
            border = BorderStroke(0.8.dp, borderColor),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
          ) {
            Text("Ungroup Tabs", fontSize = 11.sp, fontFamily = CyberMonoFamily, color = textPrimary)
          }

          Button(
            onClick = { onDelete(true) },
            colors = ButtonDefaults.buttonColors(containerColor = dangerRed.copy(alpha = 0.2f), contentColor = dangerRed),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
          ) {
            Text("Close Space & Tabs", fontSize = 11.sp, fontFamily = CyberMonoFamily)
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(groupTitle.ifBlank { "Space" }, selectedColorHex) },
        colors = ButtonDefaults.buttonColors(containerColor = Color(selectedColorHex))
      ) {
        Text("Save Changes", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = CyberMonoFamily)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel", color = textSecondary, fontFamily = CyberMonoFamily)
      }
    }
  )
}

// -------------------------------------------------------------
// TAB ACTION BOTTOM SHEET (⋮ Tab Menu)
// -------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabActionSheet(
  tab: BrowserTab,
  tabGroups: List<TabGroup>,
  onDismiss: () -> Unit,
  onOpen: () -> Unit,
  onDuplicate: () -> Unit,
  onAddToBookmarks: () -> Unit,
  onTogglePin: () -> Unit,
  onToggleLock: () -> Unit,
  onToggleInactive: () -> Unit,
  onShare: () -> Unit,
  onAssignToGroup: (groupId: String?) -> Unit,
  onCreateGroupWithTab: () -> Unit,
  onCloseTab: () -> Unit,
) {
  val cyberColors = ThemeCyber.colors
  val surfaceColor = cyberColors.surface
  val borderColor = cyberColors.surfaceBorder
  val textPrimary = cyberColors.textPrimary
  val textSecondary = cyberColors.textSecondary
  val dangerRed = cyberColors.dangerRed

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = surfaceColor,
    dragHandle = { BottomSheetDefaults.DragHandle(color = borderColor) }
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 8.dp)
        .navigationBarsPadding(),
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      // Header info
      Text(
        text = tab.title.ifEmpty { tab.url },
        color = textPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = CyberMonoFamily,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = tab.url,
        color = textSecondary,
        fontSize = 11.sp,
        fontFamily = CyberMonoFamily,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(8.dp))
      HorizontalDivider(color = borderColor.copy(alpha = 0.6f))
      Spacer(modifier = Modifier.height(4.dp))

      // 1. Open Tab
      TabActionRow(
        icon = Icons.Default.Launch,
        title = "Open Tab",
        onClick = onOpen
      )

      // 2. Duplicate Tab
      TabActionRow(
        icon = Icons.Default.ContentCopy,
        title = "Duplicate Tab",
        onClick = onDuplicate
      )

      // 3. Add to Bookmarks
      TabActionRow(
        icon = Icons.Default.BookmarkBorder,
        title = "Add to Bookmarks",
        onClick = onAddToBookmarks
      )

      // 4. Lock / Unlock Tab
      TabActionRow(
        icon = if (tab.isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
        title = if (tab.isLocked) "Unlock Tab" else "Lock Tab (Protect from closing)",
        onClick = onToggleLock
      )

      // 5. Pin / Unpin Tab
      TabActionRow(
        icon = Icons.Default.PushPin,
        title = if (tab.isPinned) "Unpin Tab" else "Pin Tab",
        onClick = onTogglePin
      )

      // 6. Sleep / Wake Tab
      TabActionRow(
        icon = Icons.Default.Bedtime,
        title = if (tab.isInactive) "Wake Tab" else "Put Tab to Sleep",
        onClick = onToggleInactive
      )

      // 7. Share
      TabActionRow(
        icon = Icons.Default.Share,
        title = "Share Link",
        onClick = onShare
      )

      // 8. Spaces Assignment
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "MOVE TO SPACE:",
        color = textSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = CyberMonoFamily
      )

      if (tab.groupId != null) {
        TabActionRow(
          icon = Icons.Default.FolderOff,
          title = "Remove from Group / Personal Space",
          iconTint = dangerRed,
          onClick = { onAssignToGroup(null) }
        )
      }

      tabGroups.forEach { group ->
        val isCurrent = tab.groupId == group.id
        TabActionRow(
          icon = Icons.Default.Folder,
          title = group.title,
          iconTint = Color(group.colorHex),
          trailingContent = if (isCurrent) "Current" else null,
          onClick = { onAssignToGroup(group.id) }
        )
      }

      TabActionRow(
        icon = Icons.Default.CreateNewFolder,
        title = "+ Create New Space with this tab",
        iconTint = cyberColors.primary,
        onClick = onCreateGroupWithTab
      )

      // 9. Close Tab
      Spacer(modifier = Modifier.height(4.dp))
      TabActionRow(
        icon = Icons.Default.Close,
        title = "Close Tab",
        iconTint = dangerRed,
        onClick = onCloseTab
      )
    }
  }
}

@Composable
private fun TabActionRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  iconTint: Color = LocalContentColor.current,
  trailingContent: String? = null,
  onClick: () -> Unit,
) {
  val cyberColors = ThemeCyber.colors
  val textPrimary = cyberColors.textPrimary
  val textMuted = cyberColors.textMuted

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = iconTint,
        modifier = Modifier.size(18.dp)
      )
      Spacer(modifier = Modifier.width(12.dp))
      Text(
        text = title,
        color = textPrimary,
        fontFamily = CyberMonoFamily,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium
      )
    }

    if (trailingContent != null) {
      Text(
        text = trailingContent,
        color = textMuted,
        fontFamily = CyberMonoFamily,
        fontSize = 10.sp
      )
    }
  }
}

