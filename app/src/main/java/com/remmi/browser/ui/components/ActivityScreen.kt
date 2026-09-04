package com.remmi.browser.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.remmi.browser.R
import com.remmi.browser.storage.BookmarkItem
import com.remmi.browser.storage.HistoryItem
import com.remmi.browser.ui.theme.ThemeCyber
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    viewModel: ActivityViewModel,
    initialTab: Int = 0,
    onSelectUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(initialTab) }

    val searchQuery by viewModel.searchQuery.collectAsState()
    val dateFilter by viewModel.dateFilter.collectAsState()
    val selectedFolder by viewModel.selectedFolder.collectAsState()
    val bookmarkSort by viewModel.bookmarkSort.collectAsState()
    val allFolders by viewModel.allFolders.collectAsState()
    val folderCounts by viewModel.folderCounts.collectAsState()

    val historyList by viewModel.historyList.collectAsState()
    val bookmarksList by viewModel.bookmarksList.collectAsState()
    val allBookmarks by viewModel.allBookmarks.collectAsState()

    val isSelectionMode by viewModel.isSelectionMode.collectAsState()
    val selectedHistoryIds by viewModel.selectedHistoryIds.collectAsState()
    val selectedBookmarkIds by viewModel.selectedBookmarkIds.collectAsState()

    val searchFocusRequester = remember { FocusRequester() }

    // Dialogs state
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkToEdit by remember { mutableStateOf<BookmarkItem?>(null) }
    var bookmarkToMove by remember { mutableStateOf<BookmarkItem?>(null) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showImportExportDialog by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showCustomDateDialog by remember { mutableStateOf(false) }

    // Brand color & theme helpers
    val isLight = ThemeCyber.colors.isLight
    val accentColor = if (isLight) Color(0xFF1A73E8) else Color(0xFF00E5FF)
    val accentContainerColor = if (isLight) Color(0xFFE8F0FE) else Color(0x2600E5FF)
    val surfaceCardColor = if (isLight) Color(0xFFFFFFFF) else Color(0xFF131722)
    val surfaceBorderColor = if (isLight) Color(0xFFE2E8F0) else Color(0xFF222938)
    val backgroundScreenColor = if (isLight) Color(0xFFF8FAFC) else Color(0xFF0A0D14)
    val textPrimaryColor = if (isLight) Color(0xFF0F172A) else Color(0xFFF1F5F9)
    val textSecondaryColor = if (isLight) Color(0xFF64748B) else Color(0xFF94A3B8)
    val textMutedColor = if (isLight) Color(0xFF94A3B8) else Color(0xFF64748B)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundScreenColor)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Modal Drag Handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(textMutedColor.copy(alpha = 0.4f))
            )
        }

        // Header
        ActivityHeader(
            isSelectionMode = isSelectionMode,
            selectedCount = if (selectedTab == 0) selectedHistoryIds.size else selectedBookmarkIds.size,
            onSelectAll = {
                if (selectedTab == 0) {
                    viewModel.selectAllHistory(historyList.map { it.id })
                } else {
                    viewModel.selectAllBookmarks(bookmarksList.map { it.id })
                }
            },
            onCancelSelection = { viewModel.clearSelection() },
            onDismiss = onDismiss,
            accentColor = accentColor,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            surfaceBorderColor = surfaceBorderColor
        )

        // Search Bar
        ActivitySearchBar(
            searchQuery = searchQuery,
            onQueryChange = { viewModel.updateSearchQuery(it) },
            onOpenFilter = { showFilterSheet = true },
            focusRequester = searchFocusRequester,
            surfaceColor = surfaceCardColor,
            borderColor = surfaceBorderColor,
            accentColor = accentColor,
            textPrimaryColor = textPrimaryColor,
            textMutedColor = textMutedColor
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Top Tabs
        ActivityTabs(
            selectedTab = selectedTab,
            onTabSelected = {
                selectedTab = it
                if (isSelectionMode) viewModel.clearSelection()
            },
            accentColor = accentColor,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            borderColor = surfaceBorderColor
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Main Tab Content
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    if (targetState > initialState) {
                        (fadeIn(animationSpec = tween(180)) + slideInHorizontally { width -> width / 4 })
                            .togetherWith(fadeOut(animationSpec = tween(180)) + slideOutHorizontally { width -> -width / 4 })
                    } else {
                        (fadeIn(animationSpec = tween(180)) + slideInHorizontally { width -> -width / 4 })
                            .togetherWith(fadeOut(animationSpec = tween(180)) + slideOutHorizontally { width -> width / 4 })
                    }
                },
                label = "ActivityTabContent"
            ) { tabIndex ->
                if (tabIndex == 0) {
                    HistoryTabContent(
                        historyList = historyList,
                        dateFilter = dateFilter,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedHistoryIds,
                        onToggleSelect = { viewModel.toggleHistorySelection(it) },
                        onFilterChanged = { filter ->
                            if (filter == DateFilter.CUSTOM) {
                                showCustomDateDialog = true
                            } else {
                                viewModel.updateDateFilter(filter)
                            }
                        },
                        onSelectUrl = {
                            onSelectUrl(it)
                            onDismiss()
                        },
                        onDelete = { viewModel.deleteHistory(it) },
                        onBookmark = { item ->
                            viewModel.addBookmark(item.title, item.url)
                            Toast.makeText(context, "Added to Bookmarks", Toast.LENGTH_SHORT).show()
                        },
                        onShare = { item -> shareUrl(context, item.title, item.url) },
                        onClearAll = { showClearHistoryDialog = true },
                        accentColor = accentColor,
                        accentContainerColor = accentContainerColor,
                        surfaceCardColor = surfaceCardColor,
                        surfaceBorderColor = surfaceBorderColor,
                        textPrimaryColor = textPrimaryColor,
                        textSecondaryColor = textSecondaryColor,
                        textMutedColor = textMutedColor
                    )
                } else {
                    BookmarksTabContent(
                        bookmarksList = bookmarksList,
                        allFolders = allFolders,
                        folderCounts = folderCounts,
                        selectedFolder = selectedFolder,
                        bookmarkSort = bookmarkSort,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedBookmarkIds,
                        onToggleSelect = { viewModel.toggleBookmarkSelection(it) },
                        onFolderChanged = { viewModel.updateFolder(it) },
                        onSortChanged = { viewModel.updateBookmarkSort(it) },
                        onNewFolderClick = { showNewFolderDialog = true },
                        onAddBookmarkClick = { showAddBookmarkDialog = true },
                        onSelectUrl = {
                            onSelectUrl(it)
                            onDismiss()
                        },
                        onDelete = { viewModel.deleteBookmark(it) },
                        onEdit = { bookmarkToEdit = it },
                        onMove = { bookmarkToMove = it },
                        onShare = { item -> shareUrl(context, item.title, item.url) },
                        accentColor = accentColor,
                        accentContainerColor = accentContainerColor,
                        surfaceCardColor = surfaceCardColor,
                        surfaceBorderColor = surfaceBorderColor,
                        textPrimaryColor = textPrimaryColor,
                        textSecondaryColor = textSecondaryColor,
                        textMutedColor = textMutedColor
                    )
                }
            }
        }

        // Bottom Action Bar (Context-aware)
        ActivityBottomBar(
            selectedTab = selectedTab,
            isSelectionMode = isSelectionMode,
            selectedCount = if (selectedTab == 0) selectedHistoryIds.size else selectedBookmarkIds.size,
            onSearchInPage = { searchFocusRequester.requestFocus() },
            onOpenFilter = { showFilterSheet = true },
            onExport = {
                val data = if (selectedTab == 0) viewModel.exportHistoryJson() else viewModel.exportBookmarksJson()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Remmi Activity Export", data))
                Toast.makeText(context, "${if (selectedTab == 0) "History" else "Bookmarks"} JSON copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onToggleSelectionMode = {
                viewModel.setSelectionMode(!isSelectionMode)
            },
            onDeleteSelected = {
                if (selectedTab == 0) viewModel.deleteSelectedHistory() else viewModel.deleteSelectedBookmarks()
            },
            onShareSelected = {
                val urls = if (selectedTab == 0) {
                    historyList.filter { it.id in selectedHistoryIds }.joinToString("\n") { "${it.title}: ${it.url}" }
                } else {
                    bookmarksList.filter { it.id in selectedBookmarkIds }.joinToString("\n") { "${it.title}: ${it.url}" }
                }
                if (urls.isNotBlank()) shareUrl(context, "Selected Links", urls)
            },
            onImportExportClick = { showImportExportDialog = true },
            onBackupClick = { showBackupDialog = true },
            onEditClick = { showNewFolderDialog = true },
            surfaceColor = surfaceCardColor,
            borderColor = surfaceBorderColor,
            accentColor = accentColor,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor
        )
    }

    // --- Dialogs ---

    // 1. Clear History Dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            containerColor = surfaceCardColor,
            title = {
                Text("Clear Browsing History", color = textPrimaryColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Text(
                    "Are you sure you want to delete all browsing history records? This action is permanent and cannot be undone.",
                    color = textSecondaryColor,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearHistory()
                        showClearHistoryDialog = false
                        Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.dangerRed)
                ) {
                    Text("Clear All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text("Cancel", color = textPrimaryColor)
                }
            }
        )
    }

    // 2. Add Bookmark Dialog
    if (showAddBookmarkDialog) {
        var title by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        var category by remember { mutableStateOf(if (selectedFolder != "All") selectedFolder else "General") }

        AlertDialog(
            onDismissRequest = { showAddBookmarkDialog = false },
            containerColor = surfaceCardColor,
            title = {
                Text("Add Bookmark", color = textPrimaryColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        placeholder = { Text("e.g. Wikipedia") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Folder / Category") },
                        placeholder = { Text("e.g. General, Work") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (url.isNotBlank()) {
                            viewModel.addBookmark(title.ifBlank { url }, url, category)
                            showAddBookmarkDialog = false
                            Toast.makeText(context, "Bookmark saved", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("Save", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddBookmarkDialog = false }) {
                    Text("Cancel", color = textPrimaryColor)
                }
            }
        )
    }

    // 3. Edit Bookmark Dialog
    bookmarkToEdit?.let { item ->
        var editTitle by remember(item) { mutableStateOf(item.title) }
        var editUrl by remember(item) { mutableStateOf(item.url) }
        var editCategory by remember(item) { mutableStateOf(item.category) }

        AlertDialog(
            onDismissRequest = { bookmarkToEdit = null },
            containerColor = surfaceCardColor,
            title = {
                Text("Edit Bookmark", color = textPrimaryColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editUrl,
                        onValueChange = { editUrl = it },
                        label = { Text("URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editCategory,
                        onValueChange = { editCategory = it },
                        label = { Text("Folder") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.updateBookmark(item.id, editTitle, editUrl, editCategory)
                        bookmarkToEdit = null
                        Toast.makeText(context, "Bookmark updated", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("Save Changes", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkToEdit = null }) {
                    Text("Cancel", color = textPrimaryColor)
                }
            }
        )
    }

    // 4. Move Bookmark Dialog
    bookmarkToMove?.let { item ->
        AlertDialog(
            onDismissRequest = { bookmarkToMove = null },
            containerColor = surfaceCardColor,
            title = {
                Text("Move to Folder", color = textPrimaryColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val selectableFolders = allFolders.filter { it != "All" }
                    selectableFolders.forEach { folder ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (item.category == folder) accentContainerColor else Color.Transparent,
                            border = BorderStroke(1.dp, if (item.category == folder) accentColor else surfaceBorderColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.moveBookmark(item, folder)
                                    bookmarkToMove = null
                                    Toast.makeText(context, "Moved to $folder", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Text(
                                text = folder,
                                color = if (item.category == folder) accentColor else textPrimaryColor,
                                fontWeight = if (item.category == folder) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { bookmarkToMove = null }) {
                    Text("Cancel", color = textPrimaryColor)
                }
            }
        )
    }

    // 5. New Folder Dialog
    if (showNewFolderDialog) {
        var newFolderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            containerColor = surfaceCardColor,
            title = {
                Text("Create New Folder", color = textPrimaryColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder Name") },
                    placeholder = { Text("e.g. Research, Tech, Entertainment") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newFolderName.isNotBlank()) {
                            viewModel.addCustomFolder(newFolderName)
                            showNewFolderDialog = false
                            Toast.makeText(context, "Folder created", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("Create", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("Cancel", color = textPrimaryColor)
                }
            }
        )
    }

    // 6. Import/Export Dialog
    if (showImportExportDialog) {
        var importText by remember { mutableStateOf("") }
        var isImportMode by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showImportExportDialog = false },
            containerColor = surfaceCardColor,
            title = {
                Text(
                    if (isImportMode) "Import Bookmarks" else "Import / Export Hub",
                    color = textPrimaryColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!isImportMode) {
                        Text(
                            "Export your bookmarks to JSON or import existing bookmarks from another device or backup.",
                            color = textSecondaryColor,
                            fontSize = 13.sp
                        )
                        Button(
                            onClick = {
                                val json = viewModel.exportBookmarksJson()
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Bookmarks JSON", json))
                                Toast.makeText(context, "Exported JSON copied to clipboard", Toast.LENGTH_LONG).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Bookmarks JSON")
                        }

                        OutlinedButton(
                            onClick = {
                                val json = viewModel.exportBookmarksJson()
                                shareUrl(context, "REMMI Bookmarks Export", json)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Share / Save Export File")
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = surfaceBorderColor)

                        OutlinedButton(
                            onClick = { isImportMode = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Paste JSON to Import")
                        }
                    } else {
                        OutlinedTextField(
                            value = importText,
                            onValueChange = { importText = it },
                            label = { Text("JSON Payload") },
                            placeholder = { Text("[{\"title\":\"...\",\"url\":\"...\"}]") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                    if (!clip.isNullOrBlank()) importText = clip
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Paste Clip")
                            }
                            Button(
                                onClick = {
                                    val imported = viewModel.importBookmarksJson(importText)
                                    if (imported > 0) {
                                        Toast.makeText(context, "Successfully imported $imported bookmarks", Toast.LENGTH_LONG).show()
                                        showImportExportDialog = false
                                    } else {
                                        Toast.makeText(context, "Invalid format or empty list", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                            ) {
                                Text("Import")
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportExportDialog = false }) {
                    Text("Close", color = textPrimaryColor)
                }
            }
        )
    }

    // 7. Backup Dialog
    if (showBackupDialog) {
        AlertDialog(
            onDismissRequest = { showBackupDialog = false },
            containerColor = surfaceCardColor,
            title = {
                Text("Activity Vault Backup", color = textPrimaryColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Create an on-device snapshot of your Bookmarks and History. All data stays local to your device and is never uploaded.",
                        color = textSecondaryColor,
                        fontSize = 13.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = accentContainerColor,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = accentColor, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Local & Privacy Protected", color = accentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("${allBookmarks.size} bookmarks • ${historyList.size} history logs", color = textSecondaryColor, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val bkmkJson = viewModel.exportBookmarksJson()
                        val histJson = viewModel.exportHistoryJson()
                        val combined = "{\n  \"bookmarks\": $bkmkJson,\n  \"history\": $histJson\n}"
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("REMMI Activity Backup", combined))
                        Toast.makeText(context, "Full Backup copied to clipboard!", Toast.LENGTH_LONG).show()
                        showBackupDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Create Backup", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackupDialog = false }) {
                    Text("Cancel", color = textPrimaryColor)
                }
            }
        )
    }

    // 8. Custom Date Range Dialog
    if (showCustomDateDialog) {
        var daysAgo by remember { mutableFloatStateOf(14f) }
        AlertDialog(
            onDismissRequest = { showCustomDateDialog = false },
            containerColor = surfaceCardColor,
            title = {
                Text("Custom Date Range", color = textPrimaryColor, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Filter history within the last ${daysAgo.toInt()} days:", color = textSecondaryColor, fontSize = 13.sp)
                    Slider(
                        value = daysAgo,
                        onValueChange = { daysAgo = it },
                        valueRange = 1f..60f,
                        steps = 59,
                        colors = SliderDefaults.colors(
                            thumbColor = accentColor,
                            activeTrackColor = accentColor
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val now = System.currentTimeMillis()
                        val minTime = now - (daysAgo.toLong() * 24L * 60L * 60L * 1000L)
                        viewModel.updateDateFilter(DateFilter.CUSTOM, Pair(minTime, now))
                        showCustomDateDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("Apply Range", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDateDialog = false }) {
                    Text("Cancel", color = textPrimaryColor)
                }
            }
        )
    }

    // 9. Filter BottomSheet
    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            containerColor = surfaceCardColor,
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Activity Filters", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textPrimaryColor)
                    IconButton(onClick = { showFilterSheet = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = textSecondaryColor)
                    }
                }

                Text("Date Filter", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = textPrimaryColor)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DateFilter.values()) { filter ->
                        FilterChip(
                            selected = dateFilter == filter,
                            onClick = {
                                if (filter == DateFilter.CUSTOM) {
                                    showFilterSheet = false
                                    showCustomDateDialog = true
                                } else {
                                    viewModel.updateDateFilter(filter)
                                }
                            },
                            label = { Text(filter.label, fontSize = 12.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = surfaceCardColor,
                                selectedContainerColor = accentContainerColor,
                                labelColor = textSecondaryColor,
                                selectedLabelColor = accentColor
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = surfaceBorderColor,
                                selectedBorderColor = accentColor,
                                enabled = true,
                                selected = dateFilter == filter
                            )
                        )
                    }
                }

                Text("Bookmark Sorting", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = textPrimaryColor)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BookmarkSort.values()) { sort ->
                        FilterChip(
                            selected = bookmarkSort == sort,
                            onClick = { viewModel.updateBookmarkSort(sort) },
                            label = { Text(sort.label, fontSize = 12.5.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = surfaceCardColor,
                                selectedContainerColor = accentContainerColor,
                                labelColor = textSecondaryColor,
                                selectedLabelColor = accentColor
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = surfaceBorderColor,
                                selectedBorderColor = accentColor,
                                enabled = true,
                                selected = bookmarkSort == sort
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { showFilterSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                ) {
                    Text("Apply & Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// --- Header ---
@Composable
fun ActivityHeader(
    isSelectionMode: Boolean,
    selectedCount: Int,
    onSelectAll: () -> Unit,
    onCancelSelection: () -> Unit,
    onDismiss: () -> Unit,
    accentColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    surfaceBorderColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isSelectionMode) {
            Column {
                Text(
                    text = "ACTIVITY",
                    color = textPrimaryColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "REMMI BROWSER",
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, surfaceBorderColor),
                modifier = Modifier
                    .size(38.dp)
                    .clickable(onClick = onDismiss)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = textPrimaryColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = accentColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$selectedCount selected",
                    color = textPrimaryColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(onClick = onSelectAll) {
                    Text("Select All", color = accentColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }

            TextButton(onClick = onCancelSelection) {
                Text("Cancel", color = textSecondaryColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }
}

// --- Search Bar ---
@Composable
fun ActivitySearchBar(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onOpenFilter: () -> Unit,
    focusRequester: FocusRequester,
    surfaceColor: Color,
    borderColor: Color,
    accentColor: Color,
    textPrimaryColor: Color,
    textMutedColor: Color
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = surfaceColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(46.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = accentColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (searchQuery.isEmpty()) {
                    Text(
                        "Search activity, bookmarks, history...",
                        color = textMutedColor,
                        fontSize = 13.sp
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = textPrimaryColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(accentColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = textMutedColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            IconButton(onClick = onOpenFilter, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = "Filter",
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// --- Tabs ---
@Composable
fun ActivityTabs(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    accentColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    borderColor: Color
) {
    TabRow(
        selectedTabIndex = selectedTab,
        containerColor = Color.Transparent,
        contentColor = accentColor,
        divider = { HorizontalDivider(color = borderColor.copy(alpha = 0.6f)) },
        indicator = { tabPositions ->
            SecondaryIndicator(
                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                color = accentColor,
                height = 2.5.dp
            )
        },
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Tab(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            text = {
                Text(
                    "History",
                    fontSize = 14.sp,
                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedTab == 0) accentColor else textSecondaryColor
                )
            }
        )
        Tab(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            text = {
                Text(
                    "Bookmarks",
                    fontSize = 14.sp,
                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                    color = if (selectedTab == 1) accentColor else textSecondaryColor
                )
            }
        )
    }
}

// --- History Tab Content ---
@Composable
fun HistoryTabContent(
    historyList: List<HistoryItem>,
    dateFilter: DateFilter,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit,
    onFilterChanged: (DateFilter) -> Unit,
    onSelectUrl: (String) -> Unit,
    onDelete: (HistoryItem) -> Unit,
    onBookmark: (HistoryItem) -> Unit,
    onShare: (HistoryItem) -> Unit,
    onClearAll: () -> Unit,
    accentColor: Color,
    accentContainerColor: Color,
    surfaceCardColor: Color,
    surfaceBorderColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    textMutedColor: Color
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Filter Chips Row
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(DateFilter.values()) { filter ->
                FilterChip(
                    selected = dateFilter == filter,
                    onClick = { onFilterChanged(filter) },
                    label = {
                        Text(
                            filter.label,
                            fontSize = 12.sp,
                            fontWeight = if (dateFilter == filter) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = surfaceCardColor,
                        selectedContainerColor = accentContainerColor,
                        labelColor = textSecondaryColor,
                        selectedLabelColor = accentColor
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        borderColor = surfaceBorderColor,
                        selectedBorderColor = accentColor,
                        enabled = true,
                        selected = dateFilter == filter
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }

        val groupedHistory = remember(historyList) {
            historyList
                .groupBy { getDateGroupKey(it.timestamp) }
                .mapKeys { (_, items) -> formatTimestampToRelativeDateString(items.first().timestamp) }
        }

        if (groupedHistory.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = accentContainerColor,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.History, contentDescription = null, tint = accentColor, modifier = Modifier.size(32.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("No browsing history yet", color = textPrimaryColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Your visited pages will appear here.", color = textSecondaryColor, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                groupedHistory.forEach { (dateHeader, items) ->
                    item {
                        Text(
                            text = dateHeader,
                            color = accentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
                        )
                    }
                    items(items, key = { it.id }) { item ->
                        HistoryTimelineItem(
                            item = item,
                            isSelectionMode = isSelectionMode,
                            isSelected = item.id in selectedIds,
                            onToggleSelect = { onToggleSelect(item.id) },
                            onItemClick = { onSelectUrl(item.url) },
                            onDelete = { onDelete(item) },
                            onBookmark = { onBookmark(item) },
                            onShare = { onShare(item) },
                            accentColor = accentColor,
                            surfaceCardColor = surfaceCardColor,
                            surfaceBorderColor = surfaceBorderColor,
                            textPrimaryColor = textPrimaryColor,
                            textSecondaryColor = textSecondaryColor,
                            textMutedColor = textMutedColor
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Bottom summary & clear browsing data
                item {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccessTime, contentDescription = null, tint = textMutedColor, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("${historyList.size} items in total", color = textMutedColor, fontSize = 12.sp)
                        }

                        TextButton(onClick = onClearAll) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = ThemeCyber.colors.dangerRed, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear Browsing Data", color = ThemeCyber.colors.dangerRed, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

// --- History Item with Timeline Node ---
@Composable
fun HistoryTimelineItem(
    item: HistoryItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onItemClick: () -> Unit,
    onDelete: () -> Unit,
    onBookmark: () -> Unit,
    onShare: () -> Unit,
    accentColor: Color,
    surfaceCardColor: Color,
    surfaceBorderColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    textMutedColor: Color
) {
    val isInternalPage = item.url.startsWith("about:") || item.url.startsWith("chrome:") || item.url.startsWith("remmi:")
    val domain = remember(item.url) {
        if (isInternalPage) item.url else {
            try {
                android.net.Uri.parse(item.url).host?.removePrefix("www.") ?: item.url
            } catch (_: Exception) {
                item.url
            }
        }
    }

    val displayTitle = when {
        item.url == "about:newtab" || item.url == "about:home" -> "New Tab"
        item.title.isNotBlank() -> item.title
        else -> item.url
    }

    val typeBadge = when {
        isInternalPage -> "Internal"
        domain.contains("wikipedia") || domain.contains("medium") -> "Article"
        else -> "Website"
    }

    val timeFormatter = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    val timeStr = remember(item.timestamp) { timeFormatter.format(Date(item.timestamp)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Timeline node on left
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(52.dp)
        ) {
            Text(
                text = timeStr,
                color = textMutedColor,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
        }

        Spacer(modifier = Modifier.width(6.dp))

        // History Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = surfaceCardColor,
            border = BorderStroke(1.dp, surfaceBorderColor),
            modifier = Modifier
                .weight(1f)
                .clickable {
                    if (isSelectionMode) onToggleSelect() else onItemClick()
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        colors = CheckboxDefaults.colors(checkedColor = accentColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }

                // Favicon / Brand Logo badge
                BrandFaviconBadge(
                    url = item.url,
                    domain = domain,
                    isInternal = isInternalPage
                )

                Spacer(modifier = Modifier.width(10.dp))

                // Info center
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayTitle,
                        color = textPrimaryColor,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = domain,
                        color = textSecondaryColor,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = textMutedColor.copy(alpha = 0.12f),
                        modifier = Modifier.wrapContentSize()
                    ) {
                        Text(
                            text = typeBadge,
                            color = textSecondaryColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (!isSelectionMode) {
                    // Right actions: Bookmark & Share & Delete
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = onBookmark, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Outlined.StarOutline, contentDescription = "Bookmark", tint = textSecondaryColor, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onShare, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Outlined.Share, contentDescription = "Share", tint = textSecondaryColor, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = textMutedColor, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

// --- Bookmarks Tab Content (Split Two-Column) ---
@Composable
fun BookmarksTabContent(
    bookmarksList: List<BookmarkItem>,
    allFolders: List<String>,
    folderCounts: Map<String, Int>,
    selectedFolder: String,
    bookmarkSort: BookmarkSort,
    isSelectionMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit,
    onFolderChanged: (String) -> Unit,
    onSortChanged: (BookmarkSort) -> Unit,
    onNewFolderClick: () -> Unit,
    onAddBookmarkClick: () -> Unit,
    onSelectUrl: (String) -> Unit,
    onDelete: (BookmarkItem) -> Unit,
    onEdit: (BookmarkItem) -> Unit,
    onMove: (BookmarkItem) -> Unit,
    onShare: (BookmarkItem) -> Unit,
    accentColor: Color,
    accentContainerColor: Color,
    surfaceCardColor: Color,
    surfaceBorderColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    textMutedColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // LEFT: Folder Sidebar (approx 34% width)
        Column(
            modifier = Modifier
                .weight(0.34f)
                .fillMaxHeight()
                .padding(end = 10.dp)
        ) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(allFolders) { folder ->
                    val isSelected = selectedFolder.equals(folder, ignoreCase = true)
                    val count = folderCounts[folder] ?: 0

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) accentContainerColor else Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFolderChanged(folder) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp, horizontal = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = folder,
                                color = if (isSelected) accentColor else textSecondaryColor,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = count.toString(),
                                color = if (isSelected) accentColor else textMutedColor,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.Transparent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNewFolderClick)
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("New Folder", color = accentColor, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Vertical divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(surfaceBorderColor.copy(alpha = 0.5f))
        )

        Spacer(modifier = Modifier.width(10.dp))

        // RIGHT: Bookmark List (approx 66% width)
        Column(
            modifier = Modifier
                .weight(0.66f)
                .fillMaxHeight()
        ) {
            // Header: count & sort
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${bookmarksList.size} bookmarks",
                    color = textMutedColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )

                var showSortMenu by remember { mutableStateOf(false) }
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = surfaceCardColor,
                        border = BorderStroke(0.8.dp, surfaceBorderColor),
                        modifier = Modifier.clickable { showSortMenu = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Sort", color = textSecondaryColor, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(16.dp))
                        }
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        containerColor = surfaceCardColor
                    ) {
                        BookmarkSort.values().forEach { sort ->
                            DropdownMenuItem(
                                text = { Text(sort.label, color = textPrimaryColor, fontSize = 13.sp) },
                                onClick = {
                                    onSortChanged(sort)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            if (bookmarksList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.BookmarkBorder, contentDescription = null, tint = textMutedColor, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("No bookmarks yet", color = textPrimaryColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Save pages to access them quickly.", color = textSecondaryColor, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(bookmarksList, key = { it.id }) { item ->
                        BookmarkCardItem(
                            item = item,
                            isSelectionMode = isSelectionMode,
                            isSelected = item.id in selectedIds,
                            onToggleSelect = { onToggleSelect(item.id) },
                            onItemClick = { onSelectUrl(item.url) },
                            onDelete = { onDelete(item) },
                            onEdit = { onEdit(item) },
                            onMove = { onMove(item) },
                            onShare = { onShare(item) },
                            accentColor = accentColor,
                            surfaceCardColor = surfaceCardColor,
                            surfaceBorderColor = surfaceBorderColor,
                            textPrimaryColor = textPrimaryColor,
                            textSecondaryColor = textSecondaryColor,
                            textMutedColor = textMutedColor
                        )
                    }
                }
            }

            // + Add Bookmark Button
            OutlinedButton(
                onClick = onAddBookmarkClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = accentColor)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add Bookmark", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

// --- Bookmark Card Item ---
@Composable
fun BookmarkCardItem(
    item: BookmarkItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onItemClick: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onShare: () -> Unit,
    accentColor: Color,
    surfaceCardColor: Color,
    surfaceBorderColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    textMutedColor: Color
) {
    val domain = remember(item.url) {
        try {
            android.net.Uri.parse(item.url).host?.removePrefix("www.") ?: item.url
        } catch (_: Exception) {
            item.url
        }
    }

    var showMenu by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = surfaceCardColor,
        border = BorderStroke(1.dp, surfaceBorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (isSelectionMode) onToggleSelect() else onItemClick()
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    colors = CheckboxDefaults.colors(checkedColor = accentColor)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Brand Favicon
            BrandFaviconBadge(url = item.url, domain = domain, isInternal = false)

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { item.url },
                    color = textPrimaryColor,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = domain,
                    color = textSecondaryColor,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = textSecondaryColor, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        containerColor = surfaceCardColor
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open in Tab", color = textPrimaryColor) },
                            leadingIcon = { Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = accentColor) },
                            onClick = {
                                showMenu = false
                                onItemClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Edit Bookmark", color = textPrimaryColor) },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = textSecondaryColor) },
                            onClick = {
                                showMenu = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Move to Folder", color = textPrimaryColor) },
                            leadingIcon = { Icon(Icons.Default.DriveFileMove, contentDescription = null, tint = textSecondaryColor) },
                            onClick = {
                                showMenu = false
                                onMove()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share", color = textPrimaryColor) },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = textSecondaryColor) },
                            onClick = {
                                showMenu = false
                                onShare()
                            }
                        )
                        HorizontalDivider(color = surfaceBorderColor)
                        DropdownMenuItem(
                            text = { Text("Delete", color = ThemeCyber.colors.dangerRed) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = ThemeCyber.colors.dangerRed) },
                            onClick = {
                                showMenu = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
    }
}

// --- Brand Favicon Badge (High-res vector brand logo with smart fallback) ---
@Composable
fun BrandFaviconBadge(
    url: String,
    domain: String,
    isInternal: Boolean
) {
    val brand = remember(url, domain, isInternal) {
        if (isInternal || url.startsWith("about:") || url.startsWith("remmi:")) {
            BrandInfo(
                iconRes = R.drawable.ic_remmi_panda,
                isVectorLogo = true,
                bgColor = Color(0xFF0284C7),
                textColor = Color.White,
                letterFallback = "🐼",
                brandName = "Remmi"
            )
        } else {
            BrandLogoRegistry.resolveBrand(url = url, domain = domain)
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = brand.bgColor.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, brand.bgColor.copy(alpha = 0.35f)),
        modifier = Modifier.size(38.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (brand.iconRes != null) {
                if (brand.iconTint != null) {
                    Icon(
                        painter = painterResource(brand.iconRes),
                        contentDescription = brand.brandName,
                        tint = brand.iconTint,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        painter = painterResource(brand.iconRes),
                        contentDescription = brand.brandName,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                Text(
                    text = brand.letterFallback,
                    color = brand.bgColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

// --- Bottom Action Bar ---
@Composable
fun ActivityBottomBar(
    selectedTab: Int,
    isSelectionMode: Boolean,
    selectedCount: Int,
    onSearchInPage: () -> Unit,
    onOpenFilter: () -> Unit,
    onExport: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onDeleteSelected: () -> Unit,
    onShareSelected: () -> Unit,
    onImportExportClick: () -> Unit,
    onBackupClick: () -> Unit,
    onEditClick: () -> Unit,
    surfaceColor: Color,
    borderColor: Color,
    accentColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = surfaceColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (!isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedTab == 0) {
                    // History actions
                    BottomActionButton(
                        icon = Icons.Default.Search,
                        label = "Search in Page",
                        onClick = onSearchInPage,
                        accentColor = accentColor,
                        textSecondaryColor = textSecondaryColor
                    )
                    BottomActionButton(
                        icon = Icons.Default.Tune,
                        label = "Filter",
                        onClick = onOpenFilter,
                        accentColor = accentColor,
                        textSecondaryColor = textSecondaryColor
                    )
                    BottomActionButton(
                        icon = Icons.Default.FileUpload,
                        label = "Export",
                        onClick = onExport,
                        accentColor = accentColor,
                        textSecondaryColor = textSecondaryColor
                    )
                    BottomActionButton(
                        icon = Icons.Default.Checklist,
                        label = "Select",
                        onClick = onToggleSelectionMode,
                        accentColor = accentColor,
                        textSecondaryColor = textSecondaryColor
                    )
                } else {
                    // Bookmarks actions
                    BottomActionButton(
                        icon = Icons.Default.SyncAlt,
                        label = "Import/Export",
                        onClick = onImportExportClick,
                        accentColor = accentColor,
                        textSecondaryColor = textSecondaryColor
                    )
                    BottomActionButton(
                        icon = Icons.Default.CloudUpload,
                        label = "Backup",
                        onClick = onBackupClick,
                        accentColor = accentColor,
                        textSecondaryColor = textSecondaryColor
                    )
                    BottomActionButton(
                        icon = Icons.Default.Edit,
                        label = "Edit",
                        onClick = onEditClick,
                        accentColor = accentColor,
                        textSecondaryColor = textSecondaryColor
                    )
                    BottomActionButton(
                        icon = Icons.Default.Checklist,
                        label = "Select",
                        onClick = onToggleSelectionMode,
                        accentColor = accentColor,
                        textSecondaryColor = textSecondaryColor
                    )
                }
            }
        } else {
            // Selection mode bottom actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onDeleteSelected,
                    enabled = selectedCount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ThemeCyber.colors.dangerRed,
                        disabledContainerColor = ThemeCyber.colors.dangerRed.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete ($selectedCount)", fontWeight = FontWeight.Bold)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onShareSelected,
                        enabled = selectedCount > 0,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }

                    TextButton(onClick = onToggleSelectionMode) {
                        Text("Done", color = accentColor, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun BottomActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    accentColor: Color,
    textSecondaryColor: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color.Transparent,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = label, tint = textSecondaryColor, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            color = textSecondaryColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

// --- Helper Functions ---
private fun formatTimestampToRelativeDateString(timestamp: Long): String {
    val cal = Calendar.getInstance()
    val today = cal.get(Calendar.DAY_OF_YEAR)
    val todayYear = cal.get(Calendar.YEAR)

    cal.timeInMillis = timestamp
    val targetDay = cal.get(Calendar.DAY_OF_YEAR)
    val targetYear = cal.get(Calendar.YEAR)

    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    val formattedDate = sdf.format(Date(timestamp))

    return when {
        todayYear == targetYear && today == targetDay -> "TODAY — ${formattedDate.uppercase(Locale.getDefault())}"
        todayYear == targetYear && today - targetDay == 1 -> "YESTERDAY — ${formattedDate.uppercase(Locale.getDefault())}"
        else -> formattedDate.uppercase(Locale.getDefault())
    }
}

private fun getDateGroupKey(timestamp: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun shareUrl(context: Context, title: String, url: String) {
    val sendIntent: Intent = Intent().apply {
        action = Intent.ACTION_SEND
        putExtra(Intent.EXTRA_TITLE, title)
        putExtra(Intent.EXTRA_TEXT, url)
        type = "text/plain"
    }
    val shareIntent = Intent.createChooser(sendIntent, null)
    context.startActivity(shareIntent)
}
