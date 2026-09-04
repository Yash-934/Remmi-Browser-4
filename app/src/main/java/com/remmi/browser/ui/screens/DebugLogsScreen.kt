package com.remmi.browser.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.security.TorManager
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import com.remmi.browser.util.DebugLogManager
import kotlinx.coroutines.launch

private enum class LogFilter(val label: String) {
  ALL("ALL"),
  ROUTE("ROUTE"),
  TOR("TOR"),
  GECKO("GECKO"),
  ERRORS("ERRORS")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogsScreen(
  onBack: () -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val logs by DebugLogManager.logs.collectAsState()
  val torManager = TorManager.getInstance(context)
  val torState by torManager.bootstrapState.collectAsState()
  val circuit by torManager.currentCircuit.collectAsState()

  var searchQuery by remember { mutableStateOf("") }
  var isSearchVisible by remember { mutableStateOf(false) }
  var activeFilter by remember { mutableStateOf(LogFilter.ALL) }
  var autoScrollToBottom by remember { mutableStateOf(true) }

  val listState = rememberLazyListState()

  BackHandler(enabled = true) {
    onBack()
  }

  // Filter logs based on category and search query
  val filteredLogs = remember(logs, activeFilter, searchQuery) {
    logs.filter { logLine ->
      val matchesFilter = when (activeFilter) {
        LogFilter.ALL -> true
        LogFilter.ROUTE -> logLine.contains("[ROUTE]", ignoreCase = true) || logLine.contains("SOCKS", ignoreCase = true)
        LogFilter.TOR -> logLine.contains("Tor", ignoreCase = true) || logLine.contains("BOOTSTRAP", ignoreCase = true)
        LogFilter.GECKO -> logLine.contains("GECKO", ignoreCase = true) || logLine.contains("pref", ignoreCase = true)
        LogFilter.ERRORS -> logLine.contains("ERROR", ignoreCase = true) ||
          logLine.contains("FAILED", ignoreCase = true) ||
          logLine.contains("CRITICAL", ignoreCase = true)
      }
      val matchesQuery = searchQuery.isBlank() || logLine.contains(searchQuery, ignoreCase = true)
      matchesFilter && matchesQuery
    }
  }

  // Auto-scroll when new logs arrive if enabled
  LaunchedEffect(filteredLogs.size, autoScrollToBottom) {
    if (autoScrollToBottom && filteredLogs.isNotEmpty()) {
      listState.animateScrollToItem(filteredLogs.size - 1)
    }
  }

  Scaffold(
    containerColor = Color(0xFF070B10),
    topBar = {
      TopAppBar(
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Box(
              modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF00E5FF).copy(alpha = 0.15f))
                .border(0.8.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                tint = Color(0xFF00E5FF),
                modifier = Modifier.size(16.dp)
              )
            }
            Column {
              Text(
                text = "DIAGNOSTIC LOGS",
                color = Color(0xFFE2E8F0),
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
              )
              Text(
                text = "${filteredLogs.size} OF ${logs.size} EVENTS",
                color = Color(0xFF00E5FF),
                fontFamily = CyberMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        },
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("debug_logs_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = Color(0xFFE2E8F0)
            )
          }
        },
        actions = {
          // Toggle Search Bar
          IconButton(
            onClick = {
              isSearchVisible = !isSearchVisible
              if (!isSearchVisible) searchQuery = ""
            }
          ) {
            Icon(
              imageVector = if (isSearchVisible) Icons.Default.Close else Icons.Default.Search,
              contentDescription = "Search",
              tint = if (isSearchVisible) Color(0xFF00E5FF) else Color(0xFF94A3B8)
            )
          }

          // Share / Export Logs
          IconButton(
            onClick = {
              if (logs.isEmpty()) {
                Toast.makeText(context, "No logs to export", Toast.LENGTH_SHORT).show()
              } else {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_SUBJECT, "Remmi Browser Diagnostic Logs")
                  putExtra(Intent.EXTRA_TEXT, logs.joinToString("\n"))
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Diagnostic Logs"))
              }
            }
          ) {
            Icon(
              imageVector = Icons.Default.Share,
              contentDescription = "Share",
              tint = Color(0xFF94A3B8)
            )
          }

          // Refresh Logs
          IconButton(
            onClick = {
              DebugLogManager.log("Diagnostic trigger: Manual log refresh")
              Toast.makeText(context, "Refreshed", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.testTag("debug_logs_refresh_button")
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "Refresh",
              tint = Color(0xFF94A3B8)
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = Color(0xFF0B1017)
        )
      )
    }
  ) { paddingValues ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
        .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {

      // Search Bar Row (Animated)
      AnimatedVisibility(
        visible = isSearchVisible,
        enter = fadeIn(),
        exit = fadeOut()
      ) {
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
          color = Color(0xFF0F172A),
          shape = RoundedCornerShape(8.dp),
          border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f))
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Search,
              contentDescription = null,
              tint = Color(0xFF00E5FF),
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              modifier = Modifier.weight(1f),
              textStyle = TextStyle(
                color = Color(0xFFF1F5F9),
                fontFamily = CyberMonoFamily,
                fontSize = 12.sp
              ),
              cursorBrush = SolidColor(Color(0xFF00E5FF)),
              singleLine = true,
              decorationBox = { innerTextField ->
                if (searchQuery.isEmpty()) {
                  Text(
                    text = "Filter logs by keyword, IP, port...",
                    color = Color(0xFF64748B),
                    fontFamily = CyberMonoFamily,
                    fontSize = 12.sp
                  )
                }
                innerTextField()
              }
            )
            if (searchQuery.isNotEmpty()) {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Clear search",
                tint = Color(0xFF94A3B8),
                modifier = Modifier
                  .size(16.dp)
                  .clickable { searchQuery = "" }
              )
            }
          }
        }
      }

      // Diagnostic Status HUD Card
      Surface(
        color = Color(0xFF0C131D),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 8.dp)
      ) {
        Column(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            val isTorReady = torState is TorManager.TorState.READY
            val isTorStarting = torState !is TorManager.TorState.OFF && !isTorReady
            val statusColor = when {
              isTorReady -> Color(0xFF00E676)
              isTorStarting -> Color(0xFFFFD600)
              else -> Color(0xFFFF5252)
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.weight(1f, fill = false)
            ) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(statusColor)
              )
              Text(
                text = "TOR: ${if (isTorReady) "CONNECTED & ROUTED" else torState.statusText.uppercase()}",
                color = statusColor,
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Surface(
              shape = RoundedCornerShape(4.dp),
              color = Color(0xFFB388FF).copy(alpha = 0.15f),
              border = BorderStroke(0.6.dp, Color(0xFFB388FF).copy(alpha = 0.4f))
            ) {
              Text(
                text = "FAIL-CLOSED",
                color = Color(0xFFD8B4FE),
                fontFamily = CyberMonoFamily,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }

          if (circuit?.verifiedExitIp != null) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "VERIFIED EXIT IP",
                color = Color(0xFF94A3B8),
                fontFamily = CyberMonoFamily,
                fontSize = 10.sp
              )
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable {
                  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                  clipboard?.setPrimaryClip(ClipData.newPlainText("Exit IP", circuit?.verifiedExitIp ?: ""))
                  Toast.makeText(context, "Copied IP", Toast.LENGTH_SHORT).show()
                }
              ) {
                Text(
                  text = circuit?.verifiedExitIp ?: "",
                  color = Color(0xFF00E5FF),
                  fontFamily = CyberMonoFamily,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold
                )
                Icon(
                  imageVector = Icons.Default.ContentCopy,
                  contentDescription = null,
                  tint = Color(0xFF00E5FF).copy(alpha = 0.7f),
                  modifier = Modifier.size(11.dp)
                )
              }
            }
          }

          Text(
            text = "WEBRTC: BLOCKED • REMOTE DNS: ENFORCED • ISOLATION: STRICT",
            color = Color(0xFF64748B),
            fontFamily = CyberMonoFamily,
            fontSize = 9.sp,
            maxLines = 1
          )
        }
      }

      // Filter Chips Row
      LazyRow(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        items(LogFilter.values()) { filter ->
          val isSelected = activeFilter == filter
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF0F172A))
              .border(
                0.6.dp,
                if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B),
                RoundedCornerShape(6.dp)
              )
              .clickable { activeFilter = filter }
              .padding(horizontal = 10.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = filter.label,
              fontFamily = CyberMonoFamily,
              fontSize = 10.sp,
              fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
              color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF94A3B8)
            )
          }
        }
      }

      // Action Bar: Copy All & Clear (Redesigned Cyberpunk HUD Buttons)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Copy All Logs Button (Neon Cyan Cyber Aesthetic)
        Surface(
          onClick = {
            if (logs.isEmpty()) {
              Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
            } else {
              val fullText = filteredLogs.joinToString("\n")
              val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
              val clip = ClipData.newPlainText("Remmi Debug Logs", fullText)
              clipboard?.setPrimaryClip(clip)
              Toast.makeText(context, "Copied ${filteredLogs.size} logs to clipboard!", Toast.LENGTH_SHORT).show()
            }
          },
          shape = RoundedCornerShape(8.dp),
          color = Color(0xFF00E5FF).copy(alpha = 0.12f),
          border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
          modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .testTag("copy_debug_logs_button")
        ) {
          Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.ContentCopy,
              contentDescription = null,
              tint = Color(0xFF00E5FF),
              modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "COPY ALL",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFF00E5FF),
              letterSpacing = 0.5.sp
            )
          }
        }

        // Clear Logs Button (Neon Red Cyber Aesthetic)
        Surface(
          onClick = {
            DebugLogManager.clear()
            Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
          },
          shape = RoundedCornerShape(8.dp),
          color = Color(0xFFFF5252).copy(alpha = 0.12f),
          border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.45f)),
          modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .testTag("clear_debug_logs_button")
        ) {
          Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.DeleteSweep,
              contentDescription = null,
              tint = Color(0xFFFF5252),
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "CLEAR",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFFFF5252),
              letterSpacing = 0.5.sp
            )
          }
        }
      }

      // Log items list with weight(1f) to prevent screen overflow
      if (filteredLogs.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0E14))
            .border(0.6.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
            .padding(24.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Terminal,
              contentDescription = null,
              tint = Color(0xFF475569),
              modifier = Modifier.size(40.dp)
            )
            Text(
              text = if (searchQuery.isNotEmpty()) "NO MATCHING LOGS" else "NO DIAGNOSTIC LOGS YET",
              color = Color(0xFFE2E8F0),
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = if (searchQuery.isNotEmpty()) "Try adjusting your search query or filter chip." else "Toggle Ghost Mode ON or browse web pages to observe live SOCKS5 proxy routing and events.",
              color = Color(0xFF94A3B8),
              fontFamily = CyberMonoFamily,
              fontSize = 11.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
          }
        }
      } else {
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF080C12))
            .border(0.8.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp)),
          contentPadding = PaddingValues(8.dp),
          verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
          items(filteredLogs) { logLine ->
            val color = when {
              logLine.contains("GHOST_ROUTE_READY", ignoreCase = true) ||
                logLine.contains("TOR_EXIT_VERIFIED", ignoreCase = true) ||
                logLine.contains("PROXIED", ignoreCase = true) ||
                logLine.contains("SUCCESS", ignoreCase = true) ||
                logLine.contains("READY", ignoreCase = true) ||
                logLine.contains("CONFIRMED", ignoreCase = true) ||
                logLine.contains("REGISTERED", ignoreCase = true) ||
                logLine.contains("connected on port", ignoreCase = true) -> Color(0xFF00FFCC)

              logLine.contains("ERROR", ignoreCase = true) ||
                logLine.contains("FAILED", ignoreCase = true) ||
                logLine.contains("CRITICAL", ignoreCase = true) ||
                logLine.contains("REJECTED", ignoreCase = true) ||
                logLine.contains("disconnected", ignoreCase = true) -> Color(0xFFFF5577)

              logLine.contains("queuing", ignoreCase = true) ||
                logLine.contains("STARTING", ignoreCase = true) ||
                logLine.contains("BOOTSTRAP", ignoreCase = true) ||
                logLine.contains("NOTICE", ignoreCase = true) ||
                logLine.contains("DIRECT", ignoreCase = true) -> Color(0xFFFFCC00)

              logLine.contains("Sent SOCKS5", ignoreCase = true) ||
                logLine.contains("GHOST", ignoreCase = true) ||
                logLine.contains("TOR", ignoreCase = true) ||
                logLine.contains("ONION", ignoreCase = true) -> Color(0xFFB388FF)

              logLine.contains("GECKO", ignoreCase = true) ||
                logLine.contains("PHASE_A", ignoreCase = true) ||
                logLine.contains("pref", ignoreCase = true) -> Color(0xFF38BDF8)

              else -> Color(0xFFCBD5E1)
            }

            Box(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF0E141E))
                .border(0.5.dp, color.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                .clickable {
                  val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                  clipboard?.setPrimaryClip(ClipData.newPlainText("Log entry", logLine))
                  Toast.makeText(context, "Copied entry", Toast.LENGTH_SHORT).show()
                }
                .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
              Text(
                text = logLine,
                fontFamily = CyberMonoFamily,
                fontSize = 11.sp,
                color = color,
                lineHeight = 15.sp
              )
            }
          }
        }
      }
    }
  }
}

