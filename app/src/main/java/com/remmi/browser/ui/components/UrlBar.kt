package com.remmi.browser.ui.components

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.remmi.browser.R
import com.remmi.browser.security.ClipboardManager
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.storage.BookmarkItem
import com.remmi.browser.storage.HistoryItem
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.SearchEngine
import com.remmi.browser.storage.SettingsRepository
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Modern, ultra-sleek, beautiful Address Bar for Remmi Browser.
 * Features:
 * - Floating Stadium/Pill capsule styling with glassmorphism surface gradient
 * - Security & Protocol badge with status dot (TLS, ONION, SANDBOX, HTTP)
 * - Intelligent domain highlighting (bold domain + subtle path)
 * - Integrated subtle bottom loading shimmer bar
 * - Modern embedded action buttons (Reader mode, Gold star bookmark, Reload)
 * - Instant live query suggestions (Search engine, Bookmarks, History)
 * - One-tap clipboard URL detection & Paste-and-Go card
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalUrlBar(
  url: String,
  isSecure: Boolean,
  profile: PrivacyProfile,
  isLoading: Boolean,
  isBookmarked: Boolean = false,
  isReaderActive: Boolean = false,
  onUrlSubmit: (String) -> Unit,
  onReload: () -> Unit,
  onToggleBookmark: () -> Unit = {},
  onToggleReader: () -> Unit = {},
  onOpenSecurityPanel: () -> Unit = {},
  onInspectRedirects: () -> Unit = {},
  onShareUrl: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val clipboard = remember { ClipboardManager(context) }
  val settingsRepo = remember { SettingsRepository.getInstance(context) }
  val settings by settingsRepo.settings.collectAsState()
  val searchEngine = remember(settings.searchEngineName) { SearchEngine.fromId(settings.searchEngineName) }

  var isEditing by remember { mutableStateOf(false) }
  var editText by remember(url) { mutableStateOf(url) }
  val focusRequester = remember { FocusRequester() }
  var showLongPressMenu by remember { mutableStateOf(false) }

  var copiedUrlPrompt by remember { mutableStateOf<String?>(null) }
  var dismissedCopiedUrl by remember { mutableStateOf<String?>(null) }

  var historySuggestions by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
  var bookmarkSuggestions by remember { mutableStateOf<List<BookmarkItem>>(emptyList()) }

  BackHandler(enabled = isEditing) {
    isEditing = false
    editText = url
  }

  val isInternalPage = url.isEmpty() || url == "about:blank" || url == "remmi://newtab" || url == "about:home"
  val isEffectiveSecure = isSecure || isInternalPage
  val accentColor = if (profile == PrivacyProfile.GHOST) ThemeCyber.colors.torPurple else ThemeCyber.colors.primary
  val borderColor = if (isEditing) {
    accentColor
  } else if (isEffectiveSecure) {
    if (isInternalPage) ThemeCyber.colors.surfaceBorder.copy(alpha = 0.8f) else accentColor.copy(alpha = 0.6f)
  } else {
    ThemeCyber.colors.dangerRed
  }

  // Clipboard Link Detection
  LaunchedEffect(isEditing) {
    if (isEditing) {
      focusRequester.requestFocus()
      val clip = clipboard.getCopiedUrl()
      if (!clip.isNullOrBlank() && clip != dismissedCopiedUrl && clip != url) {
        copiedUrlPrompt = clip
      } else {
        copiedUrlPrompt = null
      }
    } else {
      copiedUrlPrompt = null
    }
  }

  // Live Query Suggestions for History & Bookmarks
  LaunchedEffect(isEditing, editText) {
    if (isEditing) {
      val query = editText.trim()
      withContext(Dispatchers.IO) {
        val db = RemmiDatabase.getDatabaseAsync(context)
        if (query.isNotEmpty()) {
          val hist = db.historyDao().searchHistory(query)
          val bkmk = db.bookmarkDao().searchBookmarks(query)
          historySuggestions = hist
          bookmarkSuggestions = bkmk
        } else {
          val recent = db.historyDao().getRecentHistory()
          historySuggestions = recent.take(6)
          bookmarkSuggestions = emptyList()
        }
      }
    } else {
      historySuggestions = emptyList()
      bookmarkSuggestions = emptyList()
    }
  }

  // Parse URL domain and path for modern display highlighting
  val domainAndPath = remember(url) {
    if (isInternalPage) {
      Pair("Search or type web address...", "")
    } else {
      try {
        val uri = Uri.parse(url)
        val host = uri.host?.removePrefix("www.") ?: url
        val path = if (!uri.path.isNullOrEmpty() && uri.path != "/") {
          uri.path + (if (!uri.query.isNullOrEmpty()) "?${uri.query}" else "")
        } else ""
        Pair(host, path)
      } catch (_: Exception) {
        Pair(url, "")
      }
    }
  }

  Column(modifier = modifier.fillMaxWidth()) {
    // 1. Primary Address Bar Modern Row Container
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp, vertical = 2.dp)
        .height(46.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      // Home Button
      IconButton(
        onClick = { onUrlSubmit("about:home") },
        modifier = Modifier.size(36.dp)
      ) {
        Icon(
          imageVector = Icons.Outlined.Home,
          contentDescription = "Home",
          tint = ThemeCyber.colors.textPrimary,
          modifier = Modifier.size(22.dp)
        )
      }

      // Address Bar Pill Container
      Surface(
        shape = RoundedCornerShape(23.dp),
        color = ThemeCyber.colors.surface,
        border = BorderStroke(1.1.dp, borderColor),
        shadowElevation = if (isEditing) 6.dp else 2.dp,
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight()
      ) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.horizontalGradient(
                colors = listOf(
                  ThemeCyber.colors.surface,
                  ThemeCyber.colors.surfaceLight.copy(alpha = 0.65f),
                  ThemeCyber.colors.surface
                )
              )
            )
        ) {
          Row(
            modifier = Modifier
              .fillMaxSize()
              .padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Protocol / Security indicator capsule pill button
            val badgeText = when {
              isInternalPage -> "SANDBOX"
              url.contains(".onion") -> "ONION"
              isSecure -> "TLS"
              else -> "HTTP"
            }
            val badgeColor = if (isEffectiveSecure) accentColor else ThemeCyber.colors.dangerRed
            val badgeIcon = if (isEffectiveSecure) {
              when {
                isInternalPage -> Icons.Default.Shield
                url.contains(".onion") -> Icons.Default.VpnKey
                else -> Icons.Default.Lock
              }
            } else {
              Icons.Default.Warning
            }
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = badgeColor.copy(alpha = 0.12f),
              border = BorderStroke(0.7.dp, badgeColor.copy(alpha = 0.35f)),
              modifier = Modifier
                .clickable { onOpenSecurityPanel() }
                .testTag("security_badge_button")
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
              ) {
                Icon(
                  imageVector = badgeIcon,
                  contentDescription = if (isEffectiveSecure) "Secure Connection" else "Unencrypted Connection",
                  tint = badgeColor,
                  modifier = Modifier.size(11.dp),
                )
                Spacer(modifier = Modifier.width(3.5.dp))
                Text(
                  text = badgeText,
                  color = badgeColor,
                  fontFamily = CyberMonoFamily,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                )
              }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Center Text field / Display
            if (isEditing) {
              Box(
                modifier = Modifier
                  .weight(1f)
                  .padding(end = 4.dp),
                contentAlignment = Alignment.CenterStart
              ) {
                if (editText.isEmpty()) {
                  Text(
                    text = "Search or enter URL...",
                    color = ThemeCyber.colors.textMuted,
                    fontFamily = CyberMonoFamily,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                BasicTextField(
                  value = editText,
                  onValueChange = { editText = it },
                  textStyle = TextStyle(
                    color = ThemeCyber.colors.textPrimary,
                    fontFamily = CyberMonoFamily,
                    fontSize = 14.sp,
                  ),
                  keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false
                  ),
                  keyboardActions = KeyboardActions(
                    onGo = {
                      val target = if (editText.trim().contains(" ") && !editText.trim().startsWith("http")) {
                        searchEngine.buildSearchUrl(editText.trim())
                      } else {
                        editText.trim()
                      }
                      onUrlSubmit(target)
                      isEditing = false
                    }
                  ),
                  singleLine = true,
                  modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                )
              }
              IconButton(
                onClick = { 
                  if (editText.isNotEmpty()) {
                    editText = ""
                  } else {
                    isEditing = false
                    editText = url
                  }
                },
                modifier = Modifier.size(24.dp)
              ) {
                Icon(
                  Icons.Default.Clear,
                  contentDescription = "Clear or cancel",
                  tint = ThemeCyber.colors.textSecondary,
                  modifier = Modifier.size(16.dp)
                )
              }
            } else {
              Box(
                modifier = Modifier
                  .weight(1f)
                  .combinedClickable(
                    onClick = { isEditing = true },
                    onLongClick = { showLongPressMenu = true }
                  )
                  .padding(vertical = 8.dp),
                contentAlignment = Alignment.CenterStart
              ) {
                Text(
                  text = buildAnnotatedString {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = ThemeCyber.colors.textPrimary)) {
                      append(domainAndPath.first)
                    }
                    if (domainAndPath.second.isNotEmpty()) {
                      withStyle(style = SpanStyle(color = ThemeCyber.colors.textMuted)) {
                        append(domainAndPath.second)
                      }
                    }
                  },
                  fontFamily = CyberMonoFamily,
                  fontSize = 14.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )

                DropdownMenu(
                  expanded = showLongPressMenu,
                  onDismissRequest = { showLongPressMenu = false },
                  modifier = Modifier
                    .background(ThemeCyber.colors.surface)
                    .border(1.dp, ThemeCyber.colors.surfaceBorder, RoundedCornerShape(8.dp))
                ) {
                  DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp)) },
                    text = { Text("Copy Exact URL", color = ThemeCyber.colors.textPrimary, fontSize = 13.sp) },
                    onClick = {
                      showLongPressMenu = false
                      clipboard.copy(url, "URL")
                      android.widget.Toast.makeText(context, "Exact URL copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                  )
                  DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.CleaningServices, contentDescription = null, tint = ThemeCyber.colors.secondary, modifier = Modifier.size(16.dp)) },
                    text = { Text("Copy Clean URL (No Trackers)", color = ThemeCyber.colors.textPrimary, fontSize = 13.sp) },
                    onClick = {
                      showLongPressMenu = false
                      val clean = com.remmi.browser.security.RedirectInspector.stripTrackingParameters(url)
                      clipboard.copy(clean, "Clean URL")
                      android.widget.Toast.makeText(context, "Clean URL copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                  )
                  DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, tint = ThemeCyber.colors.primary, modifier = Modifier.size(16.dp)) },
                    text = { Text("Copy Domain Only", color = ThemeCyber.colors.textPrimary, fontSize = 13.sp) },
                    onClick = {
                      showLongPressMenu = false
                      val domain = com.remmi.browser.security.RedirectInspector.extractDomain(url)
                      clipboard.copy(domain, "Domain")
                      android.widget.Toast.makeText(context, "Domain copied: $domain", android.widget.Toast.LENGTH_SHORT).show()
                    }
                  )
                  DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null, tint = ThemeCyber.colors.secondary, modifier = Modifier.size(16.dp)) },
                    text = { Text("Inspect Link & Redirects", color = ThemeCyber.colors.textPrimary, fontSize = 13.sp) },
                    onClick = {
                      showLongPressMenu = false
                      onInspectRedirects()
                    }
                  )
                  DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Shield, contentDescription = null, tint = accentColor, modifier = Modifier.size(16.dp)) },
                    text = { Text("Security Shield Info", color = ThemeCyber.colors.textPrimary, fontSize = 13.sp) },
                    onClick = {
                      showLongPressMenu = false
                      onOpenSecurityPanel()
                    }
                  )
                  DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = ThemeCyber.colors.textSecondary, modifier = Modifier.size(16.dp)) },
                    text = { Text("Share Link", color = ThemeCyber.colors.textPrimary, fontSize = 13.sp) },
                    onClick = {
                      showLongPressMenu = false
                      onShareUrl()
                    }
                  )
                }
              }
            }

            // Bookmark Star Inside Pill
            if (!isInternalPage) {
              IconButton(
                onClick = onToggleBookmark,
                modifier = Modifier
                  .size(30.dp)
                  .testTag("url_bookmark_button")
              ) {
                Icon(
                  imageVector = if (isBookmarked) Icons.Default.Star else Icons.Outlined.StarBorder,
                  contentDescription = if (isBookmarked) "Bookmarked" else "Bookmark Page",
                  tint = if (isBookmarked) ThemeCyber.colors.warningYellow else ThemeCyber.colors.textMuted,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }
          
          // Integrated Micro Loading Indicator at bottom edge of address bar capsule
          if (isLoading) {
            val infiniteTransition = rememberInfiniteTransition(label = "url_shimmer")
            val shimmerAlpha by infiniteTransition.animateFloat(
              initialValue = 0.4f,
              targetValue = 1f,
              animationSpec = infiniteRepeatable(
                animation = tween(700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
              ),
              label = "shimmer_alpha"
            )
            Box(
              modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(bottomStart = 23.dp, bottomEnd = 23.dp))
                .background(
                  Brush.horizontalGradient(
                    colors = listOf(
                      accentColor.copy(alpha = 0.2f),
                      accentColor.copy(alpha = shimmerAlpha),
                      ThemeCyber.colors.secondary.copy(alpha = shimmerAlpha),
                      accentColor.copy(alpha = 0.2f)
                    )
                  )
                )
            )
          }
        }
      }

      // Outer action icons
      if (!isInternalPage) {
        IconButton(
          onClick = onToggleReader,
          modifier = Modifier.size(32.dp)
        ) {
          Icon(
            imageVector = Icons.Outlined.MenuBook,
            contentDescription = "Toggle Reader Mode",
            tint = if (isReaderActive) ThemeCyber.colors.primary else ThemeCyber.colors.textPrimary,
            modifier = Modifier.size(20.dp),
          )
        }
        IconButton(
          onClick = onShareUrl,
          modifier = Modifier.size(32.dp)
        ) {
          Icon(
            imageVector = Icons.Outlined.Share,
            contentDescription = "Share",
            tint = ThemeCyber.colors.textPrimary,
            modifier = Modifier.size(20.dp),
          )
        }
        IconButton(
          onClick = onOpenSecurityPanel,
          modifier = Modifier.size(32.dp)
        ) {
          Icon(
            imageVector = Icons.Outlined.VerifiedUser,
            contentDescription = "Security",
            tint = ThemeCyber.colors.textPrimary,
            modifier = Modifier.size(20.dp),
          )
        }
      }
      IconButton(
        onClick = onReload,
        modifier = Modifier.size(32.dp)
      ) {
        Icon(
          imageVector = Icons.Outlined.Refresh,
          contentDescription = "Reload page",
          tint = ThemeCyber.colors.textPrimary,
          modifier = Modifier.size(20.dp),
        )
      }
    }

    // 2. Copied Link Detection Card (When Focused / Editing)
    AnimatedVisibility(
      visible = isEditing && copiedUrlPrompt != null,
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      copiedUrlPrompt?.let { copied ->

        Surface(
          shape = RoundedCornerShape(12.dp),
          color = ThemeCyber.colors.surface,
          border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f)),
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clickable {
                onUrlSubmit(copied)
                isEditing = false
              }
              .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accentColor.copy(alpha = 0.15f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                Icons.Default.ContentPaste,
                contentDescription = "Copied Link",
                tint = accentColor,
                modifier = Modifier.size(18.dp)
              )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Link you copied",
                color = accentColor,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = CyberMonoFamily
              )
              Text(
                text = copied,
                color = ThemeCyber.colors.textPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Paste & Go Action Button
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = accentColor.copy(alpha = 0.2f),
              modifier = Modifier
                .clickable {
                  onUrlSubmit(copied)
                  isEditing = false
                }
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Go",
                  color = accentColor,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  fontFamily = CyberMonoFamily
                )
                Spacer(modifier = Modifier.width(3.dp))
                Icon(
                  Icons.AutoMirrored.Filled.ArrowForward,
                  contentDescription = null,
                  tint = accentColor,
                  modifier = Modifier.size(12.dp)
                )
              }
            }

            IconButton(
              onClick = {
                dismissedCopiedUrl = copied
                copiedUrlPrompt = null
              },
              modifier = Modifier.size(24.dp)
            ) {
              Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = ThemeCyber.colors.textMuted,
                modifier = Modifier.size(14.dp)
              )
            }
          }
        }
      }
    }

    // 3. Dynamic History, Bookmark & Search Suggestions Dropdown
    AnimatedVisibility(
      visible = isEditing && (editText.isNotBlank() || historySuggestions.isNotEmpty() || bookmarkSuggestions.isNotEmpty()),
      enter = fadeIn() + expandVertically(),
      exit = fadeOut() + shrinkVertically(),
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = ThemeCyber.colors.surface,
        border = BorderStroke(1.dp, ThemeCyber.colors.surfaceBorder.copy(alpha = 0.7f)),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 270.dp)
          .padding(top = 6.dp)
      ) {
        LazyColumn(
          modifier = Modifier.fillMaxWidth(),
          contentPadding = PaddingValues(vertical = 4.dp)
        ) {
          // Search Suggestion Row
          if (editText.isNotBlank()) {
            item {
              SuggestionRow(
                icon = Icons.Default.Search,
                iconTint = ThemeCyber.colors.textSecondary,
                title = "Search ${searchEngine.displayName}",
                subtitle = editText.trim(),
                isSearch = true,
                onClick = {
                  val query = editText.trim()
                  val target = if (query.startsWith("http://") || query.startsWith("https://") || (query.contains(".") && !query.contains(" "))) {
                    query
                  } else {
                    searchEngine.buildSearchUrl(query)
                  }
                  onUrlSubmit(target)
                  isEditing = false
                },
                onFill = {
                  editText = editText.trim()
                }
              )
              HorizontalDivider(color = ThemeCyber.colors.surfaceBorder.copy(alpha = 0.3f), modifier = Modifier.padding(horizontal = 10.dp))
            }
          }

          // Bookmark Matches
          if (bookmarkSuggestions.isNotEmpty()) {
            items(bookmarkSuggestions.take(3), key = { "bkmk_${it.id}" }) { bkmk ->
              SuggestionRow(
                icon = Icons.Default.Star,
                iconTint = ThemeCyber.colors.warningYellow,
                title = bkmk.title.ifEmpty { bkmk.url },
                subtitle = bkmk.url,
                onClick = {
                  onUrlSubmit(bkmk.url)
                  isEditing = false
                },
                onFill = {
                  editText = bkmk.url
                }
              )
            }
          }

          // History Matches
          if (historySuggestions.isNotEmpty()) {
            items(historySuggestions.take(8), key = { "hist_${it.id}" }) { hist ->
              SuggestionRow(
                icon = Icons.Default.History,
                iconTint = accentColor,
                title = hist.title.ifEmpty { hist.url },
                subtitle = hist.url,
                onClick = {
                  onUrlSubmit(hist.url)
                  isEditing = false
                },
                onFill = {
                  editText = hist.url
                }
              )
            }
          }
        }
      }
    }

  }
}

@Composable
private fun SuggestionRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  iconTint: Color,
  title: String,
  subtitle: String,
  isSearch: Boolean = false,
  onClick: () -> Unit,
  onFill: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 12.dp, vertical = 7.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = iconTint,
      modifier = Modifier.size(18.dp)
    )

    Spacer(modifier = Modifier.width(10.dp))

    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = title,
        color = ThemeCyber.colors.textPrimary,
        fontSize = 13.sp,
        fontWeight = if (isSearch) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (subtitle.isNotBlank() && subtitle != title) {
        Text(
          text = subtitle,
          color = ThemeCyber.colors.textMuted,
          fontSize = 11.sp,
          fontFamily = CyberMonoFamily,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }

    IconButton(
      onClick = onFill,
      modifier = Modifier.size(28.dp)
    ) {
      Icon(
        imageVector = Icons.Default.NorthWest,
        contentDescription = "Fill in address bar",
        tint = ThemeCyber.colors.textSecondary,
        modifier = Modifier.size(15.dp)
      )
    }
  }
}
