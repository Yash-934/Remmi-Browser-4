package com.remmi.browser.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.adblock.BlockExtension
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

enum class DevToolsTab(val label: String, val iconName: String) {
  CONSOLE("Console", "Terminal"),
  DOM("DOM Tree", "Code"),
  SOURCE("Raw Source", "FindInPage"),
  DIAGNOSTICS("Diagnostics", "Security")
}

data class ConsoleLogEntry(
  val id: String = UUID.randomUUID().toString(),
  val timestamp: String,
  val command: String,
  val output: String,
  val isError: Boolean = false,
  val isSystem: Boolean = false
)

data class DomNodeItem(
  val tag: String,
  val id: String = "",
  val className: String = "",
  val attributes: Map<String, String> = emptyMap(),
  val innerText: String = "",
  val outerHtml: String = "",
  val depth: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevToolsSheet(
  tab: BrowserTab,
  initialTab: DevToolsTab = DevToolsTab.CONSOLE,
  onDismiss: () -> Unit,
  onInjectInPageInspector: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current
  val coroutineScope = rememberCoroutineScope()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var currentTab by remember { mutableStateOf(initialTab) }
  var rawHtml by remember { mutableStateOf("") }
  var isLoadingHtml by remember { mutableStateOf(true) }

  // Console State
  var consoleInput by remember { mutableStateOf("") }
  val consoleLogs = remember {
    mutableStateListOf(
      ConsoleLogEntry(
        timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
        command = "// Remmi DevTools Console Initialized",
        output = "Ready for JavaScript evaluation on tab: ${tab.url}",
        isSystem = true
      )
    )
  }
  var isEvaluatingJs by remember { mutableStateOf(false) }

  // Source Viewer State
  var sourceSearchQuery by remember { mutableStateOf("") }
  var wordWrapEnabled by remember { mutableStateOf(true) }

  // DOM Search
  var domSearchQuery by remember { mutableStateOf("") }

  // Fetch HTML Source via WebExtension and OkHttp fallback
  LaunchedEffect(tab.id, tab.url) {
    isLoadingHtml = true
    val tabUrl = tab.url

    // 1. Try WebExtension native extraction
    BlockExtension.getInstance().extractTabHtml(tabId = tab.id) { url, html ->
      if (html.isNotBlank()) {
        rawHtml = html
        isLoadingHtml = false
      }
    }

    // 2. Network fallback if not about:blank and still empty
    coroutineScope.launch(Dispatchers.IO) {
      kotlinx.coroutines.delay(800)
      if (rawHtml.isBlank() && tabUrl.startsWith("http", ignoreCase = true)) {
        try {
          val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
          val req = Request.Builder()
            .url(tabUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) Remmi/1.0")
            .build()
          client.newCall(req).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (body.isNotBlank()) {
              withContext(Dispatchers.Main) {
                if (rawHtml.isBlank()) {
                  rawHtml = body
                  isLoadingHtml = false
                }
              }
            }
          }
        } catch (_: Exception) {}
      }
      withContext(Dispatchers.Main) {
        isLoadingHtml = false
      }
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = ThemeCyber.colors.backgroundDarker,
    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    dragHandle = {
      Box(
        modifier = Modifier
          .padding(top = 10.dp, bottom = 6.dp)
          .size(width = 44.dp, height = 4.dp)
          .clip(RoundedCornerShape(2.dp))
          .background(ThemeCyber.colors.surfaceBorder)
      )
    },
    modifier = modifier.fillMaxHeight(0.92f).testTag("dev_tools_sheet")
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp)
        .padding(bottom = 16.dp)
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .background(ThemeCyber.colors.secondary.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
              .border(1.dp, ThemeCyber.colors.secondary, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Default.Terminal, contentDescription = null, tint = ThemeCyber.colors.secondary, modifier = Modifier.size(20.dp))
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = "DEVELOPER TOOLS",
              fontFamily = CyberMonoFamily,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 15.sp,
              color = ThemeCyber.colors.secondary
            )
            Text(
              text = tab.url.ifBlank { "about:blank" },
              fontFamily = CyberMonoFamily,
              fontSize = 11.sp,
              color = ThemeCyber.colors.textSecondary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.widthIn(max = 220.dp)
            )
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          // Floating In-Page Overlay Toggle Button
          IconButton(
            onClick = {
              onInjectInPageInspector()
              Toast.makeText(context, "In-Page DevTools Overlay Injected", Toast.LENGTH_SHORT).show()
              onDismiss()
            },
            modifier = Modifier
              .size(34.dp)
              .background(ThemeCyber.colors.surface, RoundedCornerShape(8.dp))
              .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
          ) {
            Icon(
              Icons.Default.OpenInBrowser,
              contentDescription = "Inject In-Page Inspector",
              tint = ThemeCyber.colors.neonCyan,
              modifier = Modifier.size(18.dp)
            )
          }

          // Close button
          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .size(34.dp)
              .background(ThemeCyber.colors.surface, RoundedCornerShape(8.dp))
              .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
          ) {
            Icon(
              Icons.Default.Close,
              contentDescription = "Close",
              tint = ThemeCyber.colors.textPrimary,
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }

      // Tab Bar Navigation
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(ThemeCyber.colors.surface, RoundedCornerShape(10.dp))
          .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        DevToolsTab.values().forEach { t ->
          val isSelected = currentTab == t
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(if (isSelected) ThemeCyber.colors.secondary else Color.Transparent)
              .clickable { currentTab = t }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = t.label,
              fontFamily = CyberMonoFamily,
              fontSize = 11.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              color = if (isSelected) Color.Black else ThemeCyber.colors.textSecondary
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Tab Content Views
      Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when (currentTab) {
          DevToolsTab.CONSOLE -> {
            ConsoleTabView(
              tabId = tab.id,
              logs = consoleLogs,
              input = consoleInput,
              isEvaluating = isEvaluatingJs,
              onInputChange = { consoleInput = it },
              onClearLogs = { consoleLogs.clear() },
              onExecute = { script ->
                if (script.isNotBlank()) {
                  val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                  val logEntry = ConsoleLogEntry(
                    timestamp = ts,
                    command = script,
                    output = "Evaluating...",
                    isError = false
                  )
                  consoleLogs.add(logEntry)
                  val entryIndex = consoleLogs.lastIndex
                  isEvaluatingJs = true

                  BlockExtension.getInstance().evalScript(script) { result, isError ->
                    coroutineScope.launch(Dispatchers.Main) {
                      isEvaluatingJs = false
                      if (entryIndex in consoleLogs.indices) {
                        consoleLogs[entryIndex] = consoleLogs[entryIndex].copy(
                          output = result.ifBlank { "undefined" },
                          isError = isError
                        )
                      }
                    }
                  }
                  consoleInput = ""
                }
              }
            )
          }

          DevToolsTab.DOM -> {
            DomInspectorTabView(
              html = rawHtml,
              isLoading = isLoadingHtml,
              searchQuery = domSearchQuery,
              onSearchQueryChange = { domSearchQuery = it },
              onCopyOuterHtml = { nodeHtml ->
                clipboardManager.setText(AnnotatedString(nodeHtml))
                Toast.makeText(context, "Node HTML copied to clipboard", Toast.LENGTH_SHORT).show()
              },
              onRefresh = {
                isLoadingHtml = true
                BlockExtension.getInstance().extractTabHtml(tabId = tab.id) { _, h ->
                  rawHtml = h
                  isLoadingHtml = false
                }
              }
            )
          }

          DevToolsTab.SOURCE -> {
            RawSourceTabView(
              rawHtml = rawHtml,
              isLoading = isLoadingHtml,
              searchQuery = sourceSearchQuery,
              wordWrap = wordWrapEnabled,
              onSearchQueryChange = { sourceSearchQuery = it },
              onToggleWordWrap = { wordWrapEnabled = !wordWrapEnabled },
              onCopyAll = {
                clipboardManager.setText(AnnotatedString(rawHtml))
                Toast.makeText(context, "Full HTML Source copied (${rawHtml.length} chars)", Toast.LENGTH_SHORT).show()
              },
              onShareSource = {
                val sendIntent = Intent().apply {
                  action = Intent.ACTION_SEND
                  putExtra(Intent.EXTRA_TEXT, rawHtml)
                  putExtra(Intent.EXTRA_TITLE, "Page Source - ${tab.title}")
                  type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, "Share Page Source")
                context.startActivity(shareIntent)
              },
              onRefresh = {
                isLoadingHtml = true
                BlockExtension.getInstance().extractTabHtml(tabId = tab.id) { _, h ->
                  rawHtml = h
                  isLoadingHtml = false
                }
              }
            )
          }

          DevToolsTab.DIAGNOSTICS -> {
            DiagnosticsTabView(
              tab = tab,
              rawHtmlLength = rawHtml.length
            )
          }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 1. CONSOLE TAB VIEW
// ---------------------------------------------------------------------------
@Composable
fun ConsoleTabView(
  tabId: String,
  logs: List<ConsoleLogEntry>,
  input: String,
  isEvaluating: Boolean,
  onInputChange: (String) -> Unit,
  onClearLogs: () -> Unit,
  onExecute: (String) -> Unit
) {
  val listState = rememberLazyListState()
  val clipboardManager = LocalClipboardManager.current
  val context = LocalContext.current

  LaunchedEffect(logs.size) {
    if (logs.isNotEmpty()) {
      listState.animateScrollToItem(logs.size - 1)
    }
  }

  val quickSnippets = listOf(
    "document.title" to "document.title",
    "location.href" to "location.href",
    "document.cookie" to "document.cookie",
    "localStorage" to "JSON.stringify(localStorage)",
    "Links Count" to "document.querySelectorAll('a').length",
    "Images Count" to "document.querySelectorAll('img').length",
    "User Agent" to "navigator.userAgent",
    "Clear Alert" to "alert('DevTools Active')"
  )

  Column(modifier = Modifier.fillMaxSize()) {
    // Quick Snippets Bar
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      // Clear logs button
      AssistChip(
        onClick = onClearLogs,
        label = { Text("Clear Logs", fontFamily = CyberMonoFamily, fontSize = 11.sp, color = ThemeCyber.colors.dangerRed) },
        leadingIcon = { Icon(Icons.Default.DeleteSweep, null, tint = ThemeCyber.colors.dangerRed, modifier = Modifier.size(14.dp)) },
        colors = AssistChipDefaults.assistChipColors(containerColor = ThemeCyber.colors.surface),
        border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder)
      )

      quickSnippets.forEach { (label, code) ->
        AssistChip(
          onClick = { onExecute(code) },
          label = { Text(label, fontFamily = CyberMonoFamily, fontSize = 11.sp, color = ThemeCyber.colors.textPrimary) },
          colors = AssistChipDefaults.assistChipColors(containerColor = ThemeCyber.colors.surface),
          border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder)
        )
      }
    }

    // Console Logs Terminal Output
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF070B11))
        .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(10.dp))
        .padding(10.dp)
    ) {
      if (logs.isEmpty()) {
        Text(
          text = "Console is empty. Enter JavaScript below or choose a snippet.",
          fontFamily = CyberMonoFamily,
          fontSize = 12.sp,
          color = ThemeCyber.colors.textMuted
        )
      } else {
        SelectionContainer {
          LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
          ) {
            items(logs, key = { it.id }) { item ->
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(
                    if (item.isError) ThemeCyber.colors.dangerRed.copy(alpha = 0.08f)
                    else if (item.isSystem) ThemeCyber.colors.surfaceLight.copy(alpha = 0.3f)
                    else Color(0xFF0D1420),
                    RoundedCornerShape(6.dp)
                  )
                  .padding(8.dp)
              ) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                      text = if (item.isError) "✕ ERR" else if (item.isSystem) "ℹ SYS" else "❯ JS",
                      fontFamily = CyberMonoFamily,
                      fontSize = 10.sp,
                      fontWeight = FontWeight.Bold,
                      color = if (item.isError) ThemeCyber.colors.dangerRed else if (item.isSystem) ThemeCyber.colors.secondary else ThemeCyber.colors.neonCyan
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = item.timestamp,
                      fontFamily = CyberMonoFamily,
                      fontSize = 10.sp,
                      color = ThemeCyber.colors.textMuted
                    )
                  }

                  IconButton(
                    onClick = {
                      clipboardManager.setText(AnnotatedString(item.output))
                      Toast.makeText(context, "Output copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(20.dp)
                  ) {
                    Icon(Icons.Default.ContentCopy, null, tint = ThemeCyber.colors.textMuted, modifier = Modifier.size(12.dp))
                  }
                }

                if (!item.isSystem) {
                  Text(
                    text = item.command,
                    fontFamily = CyberMonoFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF93C5FD),
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                  )
                }

                Text(
                  text = item.output,
                  fontFamily = CyberMonoFamily,
                  fontSize = 11.sp,
                  color = if (item.isError) ThemeCyber.colors.dangerRed else if (item.isSystem) ThemeCyber.colors.textSecondary else Color(0xFF34D399)
                )
              }
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Interactive JS Input Bar
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = input,
        onValueChange = onInputChange,
        placeholder = {
          Text("Type JavaScript (e.g. document.title)", fontFamily = CyberMonoFamily, fontSize = 12.sp, color = ThemeCyber.colors.textMuted)
        },
        leadingIcon = {
          Text("❯", fontFamily = CyberMonoFamily, fontWeight = FontWeight.Bold, color = ThemeCyber.colors.secondary, fontSize = 14.sp)
        },
        trailingIcon = {
          if (input.isNotBlank()) {
            IconButton(onClick = { onInputChange("") }, modifier = Modifier.size(24.dp)) {
              Icon(Icons.Default.Clear, null, tint = ThemeCyber.colors.textMuted, modifier = Modifier.size(16.dp))
            }
          }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onExecute(input) }),
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = ThemeCyber.colors.surface,
          unfocusedContainerColor = ThemeCyber.colors.surface,
          focusedBorderColor = ThemeCyber.colors.secondary,
          unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
          focusedTextColor = ThemeCyber.colors.textPrimary,
          unfocusedTextColor = ThemeCyber.colors.textPrimary
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = CyberMonoFamily, fontSize = 12.sp),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.weight(1f)
      )

      Button(
        onClick = { onExecute(input) },
        enabled = input.isNotBlank() && !isEvaluating,
        colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.secondary),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
      ) {
        if (isEvaluating) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
        } else {
          Icon(Icons.Default.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("RUN", fontFamily = CyberMonoFamily, fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 12.sp)
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 2. DOM INSPECTOR TAB VIEW
// ---------------------------------------------------------------------------
@Composable
fun DomInspectorTabView(
  html: String,
  isLoading: Boolean,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onCopyOuterHtml: (String) -> Unit,
  onRefresh: () -> Unit
) {
  val parsedNodes = remember(html) {
    parseHtmlToNodes(html)
  }

  val filteredNodes = remember(parsedNodes, searchQuery) {
    if (searchQuery.isBlank()) parsedNodes
    else {
      val query = searchQuery.trim().lowercase()
      parsedNodes.filter { node ->
        node.tag.lowercase().contains(query) ||
          node.id.lowercase().contains(query) ||
          node.className.lowercase().contains(query) ||
          node.innerText.lowercase().contains(query)
      }
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // Search and Action Bar
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        placeholder = {
          Text("Search tags, classes, id (e.g. div, nav, btn)", fontFamily = CyberMonoFamily, fontSize = 11.sp, color = ThemeCyber.colors.textMuted)
        },
        leadingIcon = {
          Icon(Icons.Default.Search, null, tint = ThemeCyber.colors.secondary, modifier = Modifier.size(16.dp))
        },
        trailingIcon = {
          if (searchQuery.isNotBlank()) {
            IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(24.dp)) {
              Icon(Icons.Default.Clear, null, tint = ThemeCyber.colors.textMuted, modifier = Modifier.size(14.dp))
            }
          }
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = ThemeCyber.colors.surface,
          unfocusedContainerColor = ThemeCyber.colors.surface,
          focusedBorderColor = ThemeCyber.colors.secondary,
          unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
          focusedTextColor = ThemeCyber.colors.textPrimary,
          unfocusedTextColor = ThemeCyber.colors.textPrimary
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = CyberMonoFamily, fontSize = 11.sp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.weight(1f)
      )

      IconButton(
        onClick = onRefresh,
        modifier = Modifier
          .size(40.dp)
          .background(ThemeCyber.colors.surface, RoundedCornerShape(8.dp))
          .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
      ) {
        Icon(Icons.Default.Refresh, "Refresh DOM", tint = ThemeCyber.colors.secondary, modifier = Modifier.size(18.dp))
      }
    }

    // DOM Tree List
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF070B11))
        .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(10.dp))
        .padding(8.dp)
    ) {
      if (isLoading) {
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          CircularProgressIndicator(color = ThemeCyber.colors.secondary, modifier = Modifier.size(28.dp))
          Spacer(modifier = Modifier.height(10.dp))
          Text("Inspecting page DOM...", fontFamily = CyberMonoFamily, fontSize = 12.sp, color = ThemeCyber.colors.textSecondary)
        }
      } else if (filteredNodes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Text(
            text = if (html.isBlank()) "No DOM extracted yet. Tap Refresh above." else "No elements match '$searchQuery'",
            fontFamily = CyberMonoFamily,
            fontSize = 12.sp,
            color = ThemeCyber.colors.textMuted
          )
        }
      } else {
        SelectionContainer {
          LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
          ) {
            item {
              Text(
                text = "Extracted ${parsedNodes.size} element nodes (Filtered: ${filteredNodes.size})",
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp,
                color = ThemeCyber.colors.textMuted,
                modifier = Modifier.padding(bottom = 4.dp)
              )
            }
            items(filteredNodes) { node ->
              DomNodeCard(node = node, onCopy = { onCopyOuterHtml(node.outerHtml) })
            }
          }
        }
      }
    }
  }
}

@Composable
fun DomNodeCard(node: DomNodeItem, onCopy: () -> Unit) {
  var isExpanded by remember { mutableStateOf(false) }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(Color(0xFF0E1522), RoundedCornerShape(6.dp))
      .border(0.5.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
      .padding(8.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f).clickable { isExpanded = !isExpanded }
      ) {
        Text(
          text = "<${node.tag}>",
          fontFamily = CyberMonoFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 12.sp,
          color = ThemeCyber.colors.secondary
        )

        if (node.id.isNotBlank()) {
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "#${node.id}",
            fontFamily = CyberMonoFamily,
            fontSize = 11.sp,
            color = ThemeCyber.colors.neonCyan
          )
        }

        if (node.className.isNotBlank()) {
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = ".${node.className.split(" ").firstOrNull() ?: ""}",
            fontFamily = CyberMonoFamily,
            fontSize = 11.sp,
            color = Color(0xFFA78BFA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Node HTML", tint = ThemeCyber.colors.textMuted, modifier = Modifier.size(13.dp))
      }
    }

    if (node.innerText.isNotBlank()) {
      Text(
        text = node.innerText.take(80),
        fontFamily = CyberMonoFamily,
        fontSize = 11.sp,
        color = ThemeCyber.colors.textSecondary,
        maxLines = if (isExpanded) 10 else 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp)
      )
    }

    if (isExpanded && node.attributes.isNotEmpty()) {
      Spacer(modifier = Modifier.height(6.dp))
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF080C14), RoundedCornerShape(4.dp))
          .padding(6.dp)
      ) {
        Text("Attributes:", fontFamily = CyberMonoFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ThemeCyber.colors.textMuted)
        node.attributes.forEach { (k, v) ->
          Text(
            text = "$k=\"$v\"",
            fontFamily = CyberMonoFamily,
            fontSize = 10.sp,
            color = Color(0xFF38BDF8)
          )
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 3. RAW SOURCE TAB VIEW
// ---------------------------------------------------------------------------
@Composable
fun RawSourceTabView(
  rawHtml: String,
  isLoading: Boolean,
  searchQuery: String,
  wordWrap: Boolean,
  onSearchQueryChange: (String) -> Unit,
  onToggleWordWrap: () -> Unit,
  onCopyAll: () -> Unit,
  onShareSource: () -> Unit,
  onRefresh: () -> Unit
) {
  val lines = remember(rawHtml) {
    if (rawHtml.isBlank()) emptyList() else rawHtml.lines()
  }

  val matchCount = remember(rawHtml, searchQuery) {
    if (searchQuery.isBlank() || rawHtml.isBlank()) 0
    else {
      var count = 0
      var idx = 0
      val q = searchQuery.lowercase()
      val h = rawHtml.lowercase()
      while (idx < h.length) {
        val found = h.indexOf(q, idx)
        if (found != -1) {
          count++
          idx = found + q.length
        } else break
      }
      count
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    // Toolbar: Search, Word Wrap, Copy, Share
    Row(
      modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        placeholder = {
          Text("Find in HTML source...", fontFamily = CyberMonoFamily, fontSize = 11.sp, color = ThemeCyber.colors.textMuted)
        },
        leadingIcon = {
          Icon(Icons.Default.Search, null, tint = ThemeCyber.colors.secondary, modifier = Modifier.size(16.dp))
        },
        trailingIcon = {
          if (searchQuery.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "$matchCount found",
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp,
                color = if (matchCount > 0) ThemeCyber.colors.secondary else ThemeCyber.colors.dangerRed,
                modifier = Modifier.padding(end = 4.dp)
              )
              IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Clear, null, tint = ThemeCyber.colors.textMuted, modifier = Modifier.size(14.dp))
              }
            }
          }
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
          focusedContainerColor = ThemeCyber.colors.surface,
          unfocusedContainerColor = ThemeCyber.colors.surface,
          focusedBorderColor = ThemeCyber.colors.secondary,
          unfocusedBorderColor = ThemeCyber.colors.surfaceBorder,
          focusedTextColor = ThemeCyber.colors.textPrimary,
          unfocusedTextColor = ThemeCyber.colors.textPrimary
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = CyberMonoFamily, fontSize = 11.sp),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.weight(1f)
      )

      // Word wrap toggle
      IconButton(
        onClick = onToggleWordWrap,
        modifier = Modifier
          .size(38.dp)
          .background(if (wordWrap) ThemeCyber.colors.secondary.copy(alpha = 0.2f) else ThemeCyber.colors.surface, RoundedCornerShape(8.dp))
          .border(1.dp, if (wordWrap) ThemeCyber.colors.secondary else ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
      ) {
        Icon(Icons.Default.WrapText, "Toggle Word Wrap", tint = if (wordWrap) ThemeCyber.colors.secondary else ThemeCyber.colors.textSecondary, modifier = Modifier.size(18.dp))
      }

      // Copy all button
      IconButton(
        onClick = onCopyAll,
        modifier = Modifier
          .size(38.dp)
          .background(ThemeCyber.colors.surface, RoundedCornerShape(8.dp))
          .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
      ) {
        Icon(Icons.Default.ContentCopy, "Copy Full Source", tint = ThemeCyber.colors.textPrimary, modifier = Modifier.size(18.dp))
      }

      // Share button
      IconButton(
        onClick = onShareSource,
        modifier = Modifier
          .size(38.dp)
          .background(ThemeCyber.colors.surface, RoundedCornerShape(8.dp))
          .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
      ) {
        Icon(Icons.Default.Share, "Share Source", tint = ThemeCyber.colors.textPrimary, modifier = Modifier.size(18.dp))
      }
    }

    // Source Info Status
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "Total Lines: ${lines.size} | Characters: ${rawHtml.length}",
        fontFamily = CyberMonoFamily,
        fontSize = 10.sp,
        color = ThemeCyber.colors.textMuted
      )
      Text(
        text = if (wordWrap) "WRAP: ON" else "WRAP: OFF (SCROLL)",
        fontFamily = CyberMonoFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = if (wordWrap) ThemeCyber.colors.secondary else ThemeCyber.colors.textMuted
      )
    }

    Spacer(modifier = Modifier.height(4.dp))

    // Source Code Area
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF070B11))
        .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(10.dp))
    ) {
      if (isLoading) {
        Column(
          modifier = Modifier.fillMaxSize(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center
        ) {
          CircularProgressIndicator(color = ThemeCyber.colors.secondary, modifier = Modifier.size(28.dp))
          Spacer(modifier = Modifier.height(10.dp))
          Text("Extracting raw HTML source...", fontFamily = CyberMonoFamily, fontSize = 12.sp, color = ThemeCyber.colors.textSecondary)
        }
      } else if (rawHtml.isBlank()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("HTML source not available for this tab.", fontFamily = CyberMonoFamily, fontSize = 12.sp, color = ThemeCyber.colors.textMuted)
            Spacer(modifier = Modifier.height(8.dp))
            Button(
              onClick = onRefresh,
              colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.secondary),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("Retry Extracting Source", color = Color.Black, fontFamily = CyberMonoFamily, fontSize = 11.sp)
            }
          }
        }
      } else {
        SelectionContainer {
          val horizontalScroll = rememberScrollState()
          val scrollModifier = if (!wordWrap) Modifier.horizontalScroll(horizontalScroll) else Modifier

          LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)
          ) {
            itemsIndexed(lines) { index, line ->
              val lineNum = index + 1
              val isMatch = searchQuery.isNotBlank() && line.contains(searchQuery, ignoreCase = true)

              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(if (isMatch) ThemeCyber.colors.secondary.copy(alpha = 0.25f) else Color.Transparent)
                  .padding(vertical = 1.dp)
                  .then(scrollModifier)
              ) {
                // Line Number
                Text(
                  text = lineNum.toString().padStart(4, ' '),
                  fontFamily = CyberMonoFamily,
                  fontSize = 11.sp,
                  color = if (isMatch) ThemeCyber.colors.secondary else Color(0xFF475569),
                  modifier = Modifier.width(36.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // Formatted Code Line
                Text(
                  text = formatHtmlLine(line, searchQuery),
                  fontFamily = CyberMonoFamily,
                  fontSize = 11.sp,
                  color = Color(0xFF38BDF8)
                )
              }
            }
          }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 4. DIAGNOSTICS TAB VIEW
// ---------------------------------------------------------------------------
@Composable
fun DiagnosticsTabView(
  tab: BrowserTab,
  rawHtmlLength: Int
) {
  Column(
    modifier = Modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Card(
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
      shape = RoundedCornerShape(10.dp)
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Active Tab Security & Routing", fontFamily = CyberMonoFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ThemeCyber.colors.secondary)
        DiagnosticRow("URL", tab.url.ifBlank { "about:blank" })
        DiagnosticRow("Title", tab.title.ifBlank { "Untitled" })
        DiagnosticRow("Privacy Profile", tab.profile.name)
        DiagnosticRow("Container", tab.containerType.name)
        DiagnosticRow("Security Level", tab.securityLevel.name)
        DiagnosticRow("Desktop Mode", if (tab.isDesktopMode) "Enabled" else "Mobile")
      }
    }

    Card(
      colors = CardDefaults.cardColors(containerColor = ThemeCyber.colors.surface),
      border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder),
      shape = RoundedCornerShape(10.dp)
    ) {
      Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DOM & Document Metrics", fontFamily = CyberMonoFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ThemeCyber.colors.secondary)
        DiagnosticRow("Raw Source Size", "$rawHtmlLength bytes")
        DiagnosticRow("Native Bridge Port", "WebExtension Port Active")
        DiagnosticRow("Engine", "Mozilla GeckoView Quantum")
      }
    }
  }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(label, fontFamily = CyberMonoFamily, fontSize = 11.sp, color = ThemeCyber.colors.textMuted)
    SelectionContainer {
      Text(
        value,
        fontFamily = CyberMonoFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = ThemeCyber.colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 200.dp)
      )
    }
  }
}

// ---------------------------------------------------------------------------
// HTML PARSING & SYNTAX HELPERS
// ---------------------------------------------------------------------------
private fun parseHtmlToNodes(html: String): List<DomNodeItem> {
  if (html.isBlank()) return emptyList()
  val nodes = mutableListOf<DomNodeItem>()
  val tagRegex = Regex("""<([a-zA-Z0-9\-]+)([^>]*)>(.*?)(?:</\1>|(?=<[a-zA-Z0-9\-]+|$))""", RegexOption.DOT_MATCHES_ALL)

  try {
    val matches = tagRegex.findAll(html).take(150)
    for (m in matches) {
      val tagName = m.groupValues[1].lowercase()
      if (tagName in setOf("script", "style", "meta", "link")) continue

      val rawAttrs = m.groupValues[2]
      val inner = m.groupValues[3].replace(Regex("<[^>]*>"), "").trim()

      val idMatch = Regex("""id=["']([^"']+)["']""").find(rawAttrs)?.groupValues?.get(1) ?: ""
      val classMatch = Regex("""class=["']([^"']+)["']""").find(rawAttrs)?.groupValues?.get(1) ?: ""

      val attrMap = mutableMapOf<String, String>()
      Regex("""([a-zA-Z\-]+)=["']([^"']*)["']""").findAll(rawAttrs).forEach { a ->
        attrMap[a.groupValues[1]] = a.groupValues[2]
      }

      nodes.add(
        DomNodeItem(
          tag = tagName,
          id = idMatch,
          className = classMatch,
          attributes = attrMap,
          innerText = inner.replace("\n", " ").trim(),
          outerHtml = m.value.take(1000)
        )
      )
    }
  } catch (_: Exception) {}

  return nodes
}

private fun formatHtmlLine(line: String, searchQuery: String): AnnotatedString {
  return buildAnnotatedString {
    append(line)
    if (searchQuery.isNotBlank()) {
      var start = 0
      val q = searchQuery.lowercase()
      val lower = line.lowercase()
      while (start < lower.length) {
        val idx = lower.indexOf(q, start)
        if (idx != -1) {
          addStyle(
            SpanStyle(
              color = Color.Black,
              background = Color(0xFF00FFCC),
              fontWeight = FontWeight.Bold
            ),
            idx,
            idx + q.length
          )
          start = idx + q.length
        } else break
      }
    }
  }
}
