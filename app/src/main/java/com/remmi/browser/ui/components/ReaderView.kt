package com.remmi.browser.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.reader.ReaderArticle
import com.remmi.browser.reader.ReaderExporter
import com.remmi.browser.reader.ReaderHighlight
import com.remmi.browser.reader.ReaderParagraph
import com.remmi.browser.reader.ReaderSpeechManager
import com.remmi.browser.reader.ReaderTranslator
import com.remmi.browser.reader.TtsPlayState
import com.remmi.browser.storage.RemmiDatabase
import com.remmi.browser.storage.ReadingListItem
import com.remmi.browser.ui.theme.ThemeCyber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderThemePreset(
  val displayName: String,
  val background: Color,
  val textColor: Color,
  val accentColor: Color,
  val surfaceColor: Color,
) {
  CLEAN_LIGHT("LIGHT", Color(0xFFFBFBFC), Color(0xFF1E2022), Color(0xFF0066CC), Color(0xFFF0F2F5)),
  SEPIA("SEPIA", Color(0xFFFBF0D9), Color(0xFF332619), Color(0xFF9E651E), Color(0xFFF2E4C4)),
  CYBER_DARK("CYBER", Color(0xFF0A0A0F), Color(0xFFE0F7FA), Color(0xFF00E5FF), Color(0xFF14141E)),
  TERMINAL("MATRIX", Color(0xFF020E05), Color(0xFF00FF66), Color(0xFF00E676), Color(0xFF071F0C)),
  OLED_BLACK("OLED", Color(0xFF000000), Color(0xFFEDEDED), Color(0xFF00E5FF), Color(0xFF161616)),
}

enum class ReaderFontChoice(val displayName: String, val fontFamily: FontFamily) {
  SANS("SANS", FontFamily.SansSerif),
  SERIF("SERIF", FontFamily.Serif),
  MONO("MONO", FontFamily.Monospace),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderView(
  article: ReaderArticle?,
  initialFontSizeIndex: Int = 1,
  onFontSizeChanged: (Int) -> Unit = {},
  onClose: () -> Unit,
  isGhostRoute: Boolean = false,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val scrollState = rememberScrollState()

  // State
  var fontSizeIndex by remember { mutableIntStateOf(initialFontSizeIndex) } // 0=14sp, 1=17sp, 2=21sp, 3=25sp
  val isLight = ThemeCyber.colors.isLight
  val initialReaderTheme = if (isLight) ReaderThemePreset.CLEAN_LIGHT else ReaderThemePreset.CYBER_DARK
  var readerTheme by remember(isLight) { mutableStateOf(initialReaderTheme) }
  var fontChoice by remember { mutableStateOf(ReaderFontChoice.SANS) }

  // Feature Toggle Panels
  var showStylePanel by remember { mutableStateOf(false) }
  var showAudioPlayer by remember { mutableStateOf(false) }
  var showTranslateSheet by remember { mutableStateOf(false) }
  var showExportSheet by remember { mutableStateOf(false) }
  var showSaveSheet by remember { mutableStateOf(false) }
  var isHighlightMode by remember { mutableStateOf(false) }
  var activeHighlightColor by remember { mutableStateOf("#FFEB3B") } // Yellow
  var isUnderlineMode by remember { mutableStateOf(false) }

  // Translation State
  var currentArticle by remember(article) { mutableStateOf(article) }
  var isTranslating by remember { mutableStateOf(false) }
  var translationProgress by remember { mutableStateOf(0f) }
  var isShowingOriginal by remember { mutableStateOf(false) }
  var extractionTimedOut by remember(article) { mutableStateOf(false) }

  LaunchedEffect(article) {
    if (article == null) {
      kotlinx.coroutines.delay(6000L)
      extractionTimedOut = true
    } else {
      extractionTimedOut = false
    }
  }

  // Highlights Store (Paragraph index -> Highlight)
  val highlights = remember { mutableStateMapOf<Int, ReaderHighlight>() }

  // TTS Manager
  val speechManager = remember { ReaderSpeechManager(context) }
  val speechState by speechManager.state.collectAsState()

  // Dispose TTS on exit
  DisposableEffect(Unit) {
    onDispose {
      speechManager.shutdown()
    }
  }

  // Update speech manager whenever paragraphs change
  LaunchedEffect(currentArticle) {
    currentArticle?.let { art ->
      speechManager.setArticle(art.activeParagraphs)
    }
  }

  val fontSize = when (fontSizeIndex) {
    0 -> 14.sp
    1 -> 17.sp
    2 -> 21.sp
    else -> 25.sp
  }
  val lineSpacing = when (fontSizeIndex) {
    0 -> 23.sp
    1 -> 28.sp
    2 -> 34.sp
    else -> 40.sp
  }

  val displayArticle = if (isShowingOriginal) article else currentArticle

  // Saved State
  var isArticleSaved by remember { mutableStateOf(false) }

  LaunchedEffect(displayArticle?.sourceUrl) {
    displayArticle?.sourceUrl?.let { url ->
      if (url.isNotBlank()) {
        withContext(Dispatchers.IO) {
          val db = RemmiDatabase.getDatabaseAsync(context)
          val existing = db.readingListDao().getReadingByUrl(url)
          isArticleSaved = existing != null
        }
      }
    }
  }

  // Auto scroll to current paragraph being spoken
  LaunchedEffect(speechState.currentParagraphIndex) {
    if (speechState.playState == TtsPlayState.PLAYING) {
      val total = speechState.totalParagraphs.coerceAtLeast(1)
      val current = speechState.currentParagraphIndex.coerceAtLeast(0)
      val targetScroll = (scrollState.maxValue * (current.toFloat() / total)).toInt()
      scrollState.animateScrollTo(targetScroll)
    }
  }

  // Calculate reading progress (0.0f to 1.0f)
  val readingProgress = if (scrollState.maxValue > 0) {
    (scrollState.value.toFloat() / scrollState.maxValue.toFloat()).coerceIn(0f, 1f)
  } else {
    0f
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(readerTheme.background)
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // ==========================================
      // 1. TOP HEADER (Exit, Domain, Reading Time, Progress)
      // ==========================================
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = readerTheme.surfaceColor,
        shadowElevation = 3.dp,
        border = BorderStroke(0.5.dp, readerTheme.accentColor.copy(alpha = 0.2f))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            // Left: Back button & Title Badge
            IconButton(
              onClick = {
                speechManager.stop()
                onClose()
              },
              modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(readerTheme.background)
                .border(0.6.dp, readerTheme.accentColor.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .testTag("reader_exit_button")
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Exit Reader Mode",
                tint = readerTheme.accentColor,
                modifier = Modifier.size(18.dp),
              )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(
              modifier = Modifier.weight(1f)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.MenuBook,
                  contentDescription = null,
                  tint = readerTheme.accentColor,
                  modifier = Modifier.size(13.dp),
                )
                Text(
                  text = "READER MODE",
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = readerTheme.accentColor,
                  maxLines = 1,
                )
              }

              val siteName = displayArticle?.siteName?.ifBlank { null }
                ?: displayArticle?.sourceUrl?.let { url ->
                  try {
                    java.net.URI(url).host?.replace("www.", "")
                  } catch (e: Exception) {
                    null
                  }
                } ?: "Clean View"

              Text(
                text = "$siteName • ~${displayArticle?.readingTimeMinutes ?: 1} min read",
                fontFamily = fontChoice.fontFamily,
                fontSize = 10.sp,
                color = readerTheme.textColor.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Right Quick Actions (Save & Share)
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              // Save to Reading List / Offline Button
              IconButton(
                onClick = {
                  showSaveSheet = true
                },
                modifier = Modifier
                  .size(36.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(if (isArticleSaved) Color(0xFFF59E0B).copy(alpha = 0.2f) else readerTheme.background)
                  .border(
                    0.6.dp,
                    if (isArticleSaved) Color(0xFFF59E0B) else readerTheme.accentColor.copy(alpha = 0.3f),
                    RoundedCornerShape(8.dp)
                  )
                  .testTag("reader_save_reading_list_btn")
              ) {
                Icon(
                  imageVector = if (isArticleSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                  contentDescription = "Save to Reading List",
                  tint = if (isArticleSaved) Color(0xFFF59E0B) else readerTheme.accentColor,
                  modifier = Modifier.size(18.dp)
                )
              }

              IconButton(
                onClick = {
                  displayArticle?.let { art ->
                    ReaderExporter.shareArticle(context, art)
                  }
                },
                modifier = Modifier
                  .size(36.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(readerTheme.background)
                  .border(0.6.dp, readerTheme.accentColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                  .testTag("reader_share_btn")
              ) {
                Icon(
                  imageVector = Icons.Default.Share,
                  contentDescription = "Share Article",
                  tint = readerTheme.accentColor,
                  modifier = Modifier.size(17.dp)
                )
              }
            }
          }

          // Real-time Reading Progress Bar
          LinearProgressIndicator(
            progress = { readingProgress },
            modifier = Modifier
              .fillMaxWidth()
              .height(2.dp),
            color = readerTheme.accentColor,
            trackColor = Color.Transparent
          )
        }
      }

      // Translation Banner (when active)
      if (currentArticle?.translatedTitle != null) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .background(readerTheme.accentColor.copy(alpha = 0.12f))
            .border(0.5.dp, readerTheme.accentColor.copy(alpha = 0.3f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Translate,
              contentDescription = null,
              tint = readerTheme.accentColor,
              modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (isShowingOriginal) "SHOWING ORIGINAL" else "TRANSLATED TO ${currentArticle?.targetLanguage?.uppercase() ?: "SELECTED LANGUAGE"}",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = readerTheme.accentColor,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }

          Text(
            text = if (isShowingOriginal) "SHOW TRANSLATED" else "SHOW ORIGINAL",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = readerTheme.accentColor,
            modifier = Modifier
              .clip(RoundedCornerShape(4.dp))
              .background(readerTheme.accentColor.copy(alpha = 0.2f))
              .clickable { isShowingOriginal = !isShowingOriginal }
              .padding(horizontal = 8.dp, vertical = 3.dp)
          )
        }
      }

      // Translation Loading Progress
      if (isTranslating) {
        LinearProgressIndicator(
          progress = { translationProgress },
          modifier = Modifier
            .fillMaxWidth()
            .height(3.dp),
          color = readerTheme.accentColor,
          trackColor = readerTheme.surfaceColor
        )
      }

      // ==========================================
      // 2. MAIN SCROLLABLE ARTICLE CANVAS
      // ==========================================
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
      ) {
        if (displayArticle == null) {
          if (extractionTimedOut) {
            Column(
              modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(
                imageVector = Icons.Default.MenuBook,
                contentDescription = null,
                tint = readerTheme.accentColor.copy(alpha = 0.6f),
                modifier = Modifier.size(48.dp)
              )
              Spacer(modifier = Modifier.height(16.dp))
              Text(
                text = "READER MODE UNAVAILABLE",
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = readerTheme.textColor
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = "Could not extract article content from this page. Some pages do not contain standard article bodies.",
                fontFamily = fontChoice.fontFamily,
                fontSize = 13.sp,
                color = readerTheme.textColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 400.dp)
              )
              Spacer(modifier = Modifier.height(20.dp))
              Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = readerTheme.accentColor),
                shape = RoundedCornerShape(8.dp)
              ) {
                Text("Exit Reader Mode", color = Color.Black, fontWeight = FontWeight.Bold)
              }
            }
          } else {
            // Loading / Parsing state
            Column(
              modifier = Modifier.fillMaxSize(),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              CircularProgressIndicator(
                color = readerTheme.accentColor,
                modifier = Modifier.size(36.dp)
              )
              Spacer(modifier = Modifier.height(14.dp))
              Text(
                text = "EXTRACTING CLEAN ARTICLE CONTENT...",
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = readerTheme.accentColor
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Stripping ads, clutter, and tracking scripts",
                fontFamily = fontChoice.fontFamily,
                fontSize = 11.sp,
                color = readerTheme.textColor.copy(alpha = 0.6f)
              )
            }
          }
        } else if (displayArticle.activeParagraphs.isEmpty()) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Icon(
              imageVector = Icons.Default.MenuBook,
              contentDescription = null,
              tint = readerTheme.accentColor.copy(alpha = 0.6f),
              modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = "NO READABLE CONTENT FOUND",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
              color = readerTheme.textColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "This page does not have readable text content to display in Reader Mode.",
              fontFamily = fontChoice.fontFamily,
              fontSize = 13.sp,
              color = readerTheme.textColor.copy(alpha = 0.7f),
              textAlign = TextAlign.Center,
              modifier = Modifier.widthIn(max = 400.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
              onClick = onClose,
              colors = ButtonDefaults.buttonColors(containerColor = readerTheme.accentColor),
              shape = RoundedCornerShape(8.dp)
            ) {
              Text("Exit Reader Mode", color = Color.Black, fontWeight = FontWeight.Bold)
            }
          }
        } else {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .widthIn(max = 720.dp)
              .verticalScroll(scrollState)
              .padding(horizontal = 20.dp, vertical = 18.dp)
          ) {
            // Source & Reading Time Pill
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (displayArticle.siteName.isNotBlank()) {
                Text(
                  text = displayArticle.siteName.uppercase(),
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = readerTheme.accentColor,
                )
              }

              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Default.Schedule,
                  contentDescription = null,
                  tint = readerTheme.textColor.copy(alpha = 0.5f),
                  modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "~${displayArticle.readingTimeMinutes} MIN READ",
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 10.sp,
                  color = readerTheme.textColor.copy(alpha = 0.5f),
                )
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Article Title
            Text(
              text = displayArticle.activeTitle.ifEmpty { "Reader Mode Article" },
              fontSize = (fontSize.value + 6).sp,
              fontWeight = FontWeight.Bold,
              fontFamily = fontChoice.fontFamily,
              color = readerTheme.textColor,
              lineHeight = (fontSize.value + 12).sp,
            )

            // Author / Byline
            if (displayArticle.byline.isNotBlank()) {
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "BY ${displayArticle.byline.uppercase()}",
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = readerTheme.accentColor.copy(alpha = 0.85f),
              )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = readerTheme.accentColor.copy(alpha = 0.2f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(16.dp))

            // Article Paragraphs
            if (displayArticle.activeParagraphs.isNotEmpty()) {
              displayArticle.activeParagraphs.forEach { paragraph ->
                val isCurrentlySpoken = speechState.playState == TtsPlayState.PLAYING && speechState.currentParagraphIndex == paragraph.index
                val highlight = highlights[paragraph.index]

                val paragraphBg = when {
                  isCurrentlySpoken -> readerTheme.accentColor.copy(alpha = 0.18f)
                  highlight != null && !highlight.isUnderline -> parseColorOrFallback(highlight.colorHex).copy(alpha = 0.35f)
                  else -> Color.Transparent
                }

                val textDecoration = if (highlight?.isUnderline == true) TextDecoration.Underline else TextDecoration.None

                val paragraphModifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(6.dp))
                  .background(paragraphBg)
                  .then(
                    if (isHighlightMode) {
                      Modifier.clickable {
                        if (highlights.containsKey(paragraph.index)) {
                          highlights.remove(paragraph.index)
                        } else {
                          highlights[paragraph.index] = ReaderHighlight(
                            paragraphIndex = paragraph.index,
                            colorHex = activeHighlightColor,
                            isUnderline = isUnderlineMode
                          )
                        }
                      }
                    } else if (showAudioPlayer) {
                      Modifier.clickable {
                        speechManager.playFrom(paragraph.index)
                      }
                    } else {
                      Modifier
                    }
                  )
                  .padding(
                    horizontal = if (paragraphBg != Color.Transparent) 8.dp else 0.dp,
                    vertical = if (paragraphBg != Color.Transparent) 6.dp else 0.dp
                  )
                  .padding(bottom = 14.dp)

                if (paragraph.isHeading) {
                  Text(
                    text = paragraph.text,
                    fontSize = (fontSize.value + 3).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontChoice.fontFamily,
                    color = readerTheme.accentColor,
                    lineHeight = (fontSize.value + 8).sp,
                    textDecoration = textDecoration,
                    modifier = paragraphModifier
                  )
                } else {
                  Text(
                    text = paragraph.text,
                    fontSize = fontSize,
                    lineHeight = lineSpacing,
                    fontFamily = fontChoice.fontFamily,
                    color = readerTheme.textColor.copy(alpha = 0.95f),
                    textDecoration = textDecoration,
                    modifier = paragraphModifier
                  )
                }
              }
            } else {
              Text(
                text = "Extracting article typography and paragraphs...\n\nIf this page is protected or media-only, tap Back to view the original webpage.",
                fontFamily = fontChoice.fontFamily,
                fontSize = 13.sp,
                color = readerTheme.textColor.copy(alpha = 0.6f),
              )
            }

            Spacer(modifier = Modifier.height(100.dp))
          }
        }
      }

      // ==========================================
      // 3. EXPANDABLE FLOATING PANELS ABOVE DOCK
      // ==========================================

      // Panel A: Typography & Appearance Styles
      AnimatedVisibility(
        visible = showStylePanel,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
      ) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = readerTheme.surfaceColor,
          shadowElevation = 8.dp,
          border = BorderStroke(1.dp, readerTheme.accentColor.copy(alpha = 0.3f))
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            // Row 1: Font Size Controls (A- / A+ / Indicator)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "TEXT SIZE",
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = readerTheme.textColor.copy(alpha = 0.6f)
              )

              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                // A- button
                Box(
                  modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(readerTheme.background)
                    .border(0.6.dp, readerTheme.accentColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .clickable(enabled = fontSizeIndex > 0) {
                      fontSizeIndex--
                      onFontSizeChanged(fontSizeIndex)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("reader_font_smaller"),
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    text = "A -",
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (fontSizeIndex > 0) readerTheme.accentColor else readerTheme.textColor.copy(alpha = 0.3f)
                  )
                }

                // Size label
                val sizeLabels = listOf("Small", "Medium", "Large", "Extra")
                Text(
                  text = sizeLabels.getOrElse(fontSizeIndex) { "Medium" },
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold,
                  color = readerTheme.textColor,
                  modifier = Modifier.padding(horizontal = 8.dp)
                )

                // A+ button
                Box(
                  modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(readerTheme.background)
                    .border(0.6.dp, readerTheme.accentColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .clickable(enabled = fontSizeIndex < 3) {
                      fontSizeIndex++
                      onFontSizeChanged(fontSizeIndex)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("reader_font_larger"),
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    text = "A +",
                    fontFamily = ThemeCyber.fontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (fontSizeIndex < 3) readerTheme.accentColor else readerTheme.textColor.copy(alpha = 0.3f)
                  )
                }
              }
            }

            HorizontalDivider(color = readerTheme.textColor.copy(alpha = 0.1f), thickness = 0.5.dp)

            // Row 2: Font Family Chips (Sans, Serif, Mono)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "FONT TYPE",
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = readerTheme.textColor.copy(alpha = 0.6f)
              )

              Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                ReaderFontChoice.entries.forEach { choice ->
                  val isSelected = fontChoice == choice
                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(6.dp))
                      .background(if (isSelected) readerTheme.accentColor.copy(alpha = 0.2f) else readerTheme.background)
                      .border(
                        0.8.dp,
                        if (isSelected) readerTheme.accentColor else readerTheme.textColor.copy(alpha = 0.2f),
                        RoundedCornerShape(6.dp)
                      )
                      .clickable { fontChoice = choice }
                      .padding(horizontal = 10.dp, vertical = 6.dp)
                  ) {
                    Text(
                      text = choice.displayName,
                      fontFamily = choice.fontFamily,
                      fontSize = 11.sp,
                      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                      color = if (isSelected) readerTheme.accentColor else readerTheme.textColor.copy(alpha = 0.8f)
                    )
                  }
                }
              }
            }

            HorizontalDivider(color = readerTheme.textColor.copy(alpha = 0.1f), thickness = 0.5.dp)

            // Row 3: Theme Swatches
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "THEME",
                fontFamily = ThemeCyber.fontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = readerTheme.textColor.copy(alpha = 0.6f)
              )

              Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                ReaderThemePreset.entries.forEach { preset ->
                  val isSelected = readerTheme == preset
                  Box(
                    modifier = Modifier
                      .size(28.dp)
                      .clip(CircleShape)
                      .background(preset.background)
                      .border(
                        width = if (isSelected) 2.5.dp else 1.dp,
                        color = if (isSelected) preset.accentColor else readerTheme.textColor.copy(alpha = 0.3f),
                        shape = CircleShape
                      )
                      .clickable { readerTheme = preset },
                    contentAlignment = Alignment.Center
                  ) {
                    Box(
                      modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(preset.accentColor)
                    )
                  }
                }
              }
            }
          }
        }
      }

      // Panel B: Highlight Color Toolbar
      AnimatedVisibility(
        visible = isHighlightMode,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
      ) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = readerTheme.surfaceColor,
          border = BorderStroke(0.8.dp, Color(0xFFFFA000).copy(alpha = 0.5f))
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "TAP TEXT TO HIGHLIGHT",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold,
              color = Color(0xFFFFA000)
            )

            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Yellow
              HighlightColorButton(
                color = Color(0xFFFFEB3B),
                isSelected = activeHighlightColor == "#FFEB3B" && !isUnderlineMode,
                onClick = {
                  activeHighlightColor = "#FFEB3B"
                  isUnderlineMode = false
                }
              )
              // Green
              HighlightColorButton(
                color = Color(0xFF69F0AE),
                isSelected = activeHighlightColor == "#69F0AE" && !isUnderlineMode,
                onClick = {
                  activeHighlightColor = "#69F0AE"
                  isUnderlineMode = false
                }
              )
              // Cyan
              HighlightColorButton(
                color = Color(0xFF00E5FF),
                isSelected = activeHighlightColor == "#00E5FF" && !isUnderlineMode,
                onClick = {
                  activeHighlightColor = "#00E5FF"
                  isUnderlineMode = false
                }
              )
              // Coral Red
              HighlightColorButton(
                color = Color(0xFFFF5252),
                isSelected = activeHighlightColor == "#FF5252" && !isUnderlineMode,
                onClick = {
                  activeHighlightColor = "#FF5252"
                  isUnderlineMode = false
                }
              )
              // Underline mode toggle
              Box(
                modifier = Modifier
                  .size(26.dp)
                  .clip(RoundedCornerShape(4.dp))
                  .background(if (isUnderlineMode) Color(0xFFFFA000).copy(alpha = 0.25f) else Color.Transparent)
                  .border(1.dp, if (isUnderlineMode) Color(0xFFFFA000) else readerTheme.textColor.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                  .clickable { isUnderlineMode = !isUnderlineMode },
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.FormatUnderlined,
                  contentDescription = "Underline Mode",
                  tint = if (isUnderlineMode) Color(0xFFFFA000) else readerTheme.textColor,
                  modifier = Modifier.size(16.dp)
                )
              }

              // Clear All
              if (highlights.isNotEmpty()) {
                Text(
                  text = "CLEAR (${highlights.size})",
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 10.sp,
                  color = ThemeCyber.colors.dangerRed,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ThemeCyber.colors.dangerRed.copy(alpha = 0.15f))
                    .clickable { highlights.clear() }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                )
              }
            }
          }
        }
      }

      // Panel C: Audio Narration Dock (TTS Read Aloud Player Bar)
      AnimatedVisibility(
        visible = showAudioPlayer,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
      ) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = readerTheme.surfaceColor,
          shadowElevation = 8.dp,
          border = BorderStroke(1.dp, readerTheme.accentColor.copy(alpha = 0.4f))
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 8.dp)
          ) {
            // Snippet & Status
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = Icons.Default.GraphicEq,
                  contentDescription = null,
                  tint = readerTheme.accentColor,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = if (speechState.playState == TtsPlayState.PLAYING) "READING ALOUD..." else "AUDIO NARRATOR",
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = readerTheme.accentColor
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "[${speechState.currentParagraphIndex + 1}/${speechState.totalParagraphs.coerceAtLeast(1)}]",
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 10.sp,
                  color = readerTheme.textColor.copy(alpha = 0.6f)
                )
              }

              // Speed Toggle Chip (0.75x, 1x, 1.25x, 1.5x, 2x)
              val rates = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .background(readerTheme.background)
                  .border(0.6.dp, readerTheme.accentColor.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                  .clickable {
                    val nextIdx = (rates.indexOf(speechState.speechRate) + 1) % rates.size
                    speechManager.setSpeechRate(rates[nextIdx])
                  }
                  .padding(horizontal = 8.dp, vertical = 3.dp)
              ) {
                Text(
                  text = "${speechState.speechRate}x",
                  fontFamily = ThemeCyber.fontFamily,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  color = readerTheme.accentColor
                )
              }

              Spacer(modifier = Modifier.width(6.dp))

              IconButton(
                onClick = {
                  speechManager.stop()
                  showAudioPlayer = false
                },
                modifier = Modifier.size(28.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Close,
                  contentDescription = "Close Audio Player",
                  tint = readerTheme.textColor.copy(alpha = 0.7f),
                  modifier = Modifier.size(16.dp)
                )
              }
            }

            // Audio Player Controls (Prev, Play/Pause, Next, Stop)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically
            ) {
              IconButton(
                onClick = { speechManager.previous() },
                modifier = Modifier.size(38.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.SkipPrevious,
                  contentDescription = "Previous Paragraph",
                  tint = readerTheme.accentColor,
                  modifier = Modifier.size(22.dp)
                )
              }

              Spacer(modifier = Modifier.width(12.dp))

              // Play / Pause FAB
              Box(
                modifier = Modifier
                  .size(44.dp)
                  .clip(CircleShape)
                  .background(readerTheme.accentColor)
                  .clickable { speechManager.togglePlayPause() }
                  .testTag("reader_tts_play_pause"),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = if (speechState.playState == TtsPlayState.PLAYING) Icons.Default.Pause else Icons.Default.PlayArrow,
                  contentDescription = "Play/Pause",
                  tint = if (readerTheme == ReaderThemePreset.CLEAN_LIGHT) Color.White else Color.Black,
                  modifier = Modifier.size(26.dp)
                )
              }

              Spacer(modifier = Modifier.width(12.dp))

              IconButton(
                onClick = { speechManager.next() },
                modifier = Modifier.size(38.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.SkipNext,
                  contentDescription = "Next Paragraph",
                  tint = readerTheme.accentColor,
                  modifier = Modifier.size(22.dp)
                )
              }

              Spacer(modifier = Modifier.width(12.dp))

              IconButton(
                onClick = { speechManager.stop() },
                modifier = Modifier.size(38.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Stop,
                  contentDescription = "Stop",
                  tint = ThemeCyber.colors.dangerRed,
                  modifier = Modifier.size(20.dp)
                )
              }
            }
          }
        }
      }

      // ==========================================
      // 4. DEDICATED BOTTOM READER ACTION DOCK
      // (Style, Translate, Highlight, Listen, Export)
      // ==========================================
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .navigationBarsPadding(),
        color = readerTheme.surfaceColor,
        shadowElevation = 10.dp,
        border = BorderStroke(0.6.dp, readerTheme.accentColor.copy(alpha = 0.25f))
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 4.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // 1. Style / Typography Button
          ReaderBottomTabItem(
            icon = Icons.Default.TextFields,
            label = "Style",
            isActive = showStylePanel,
            accentColor = readerTheme.accentColor,
            textColor = readerTheme.textColor,
            modifier = Modifier.weight(1f),
            onClick = {
              showStylePanel = !showStylePanel
              if (showStylePanel) {
                isHighlightMode = false
              }
            }
          )

          // 2. Translate Button
          val isTranslated = currentArticle?.translatedTitle != null
          ReaderBottomTabItem(
            icon = Icons.Default.Translate,
            label = if (isTranslated) (currentArticle?.targetLanguage?.take(3)?.uppercase() ?: "Trans") else "Translate",
            isActive = isTranslated || showTranslateSheet,
            accentColor = readerTheme.accentColor,
            textColor = readerTheme.textColor,
            badge = if (isTranslated) "✓" else null,
            modifier = Modifier.weight(1f),
            onClick = {
              showTranslateSheet = true
            }
          )

          // 3. Highlight Mode Button
          ReaderBottomTabItem(
            icon = Icons.Default.Highlight,
            label = "Highlight",
            isActive = isHighlightMode,
            accentColor = Color(0xFFFFA000),
            textColor = readerTheme.textColor,
            badge = if (highlights.isNotEmpty()) "${highlights.size}" else null,
            modifier = Modifier.weight(1f),
            onClick = {
              isHighlightMode = !isHighlightMode
              if (isHighlightMode) {
                showStylePanel = false
              }
            }
          )

          // 4. Listen / Audio Narrator Button
          val isAudioActive = showAudioPlayer || speechState.playState == TtsPlayState.PLAYING
          ReaderBottomTabItem(
            icon = if (speechState.playState == TtsPlayState.PLAYING) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Headphones,
            label = "Listen",
            isActive = isAudioActive,
            accentColor = readerTheme.accentColor,
            textColor = readerTheme.textColor,
            badge = if (speechState.playState == TtsPlayState.PLAYING) "▶" else null,
            modifier = Modifier.weight(1f),
            onClick = {
              showAudioPlayer = !showAudioPlayer
              if (showAudioPlayer && speechState.playState == TtsPlayState.IDLE) {
                speechManager.togglePlayPause()
              }
            }
          )

          // 5. Export / Share Button
          ReaderBottomTabItem(
            icon = Icons.Default.Download,
            label = "Export",
            isActive = showExportSheet,
            accentColor = readerTheme.accentColor,
            textColor = readerTheme.textColor,
            modifier = Modifier.weight(1f),
            onClick = {
              showExportSheet = true
            }
          )
        }
      }
    }
  }

  // ==========================================
  // 5. TRANSLATE MODAL BOTTOM SHEET
  // ==========================================
  if (showTranslateSheet) {
    ModalBottomSheet(
      onDismissRequest = { showTranslateSheet = false },
      containerColor = readerTheme.surfaceColor,
      sheetState = rememberModalBottomSheetState()
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Translate,
              contentDescription = null,
              tint = readerTheme.accentColor,
              modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "TRANSLATE ARTICLE",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = readerTheme.textColor
            )
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = "Select target translation language:",
          fontSize = 12.sp,
          fontFamily = fontChoice.fontFamily,
          color = readerTheme.textColor.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Language Grid
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          ReaderTranslator.SUPPORTED_LANGUAGES.forEach { lang ->
            val isSelected = currentArticle?.targetLanguage == lang.displayName
            Surface(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable {
                  showTranslateSheet = false
                  displayArticle?.let { art ->
                    scope.launch {
                      isTranslating = true
                      translationProgress = 0.1f
                      try {
                        val translated = ReaderTranslator.translateArticle(context, 
                          article = art,
                          targetLanguageCode = lang.code,
                          isGhost = isGhostRoute,
                          onProgress = { current, total ->
                            translationProgress = (current.toFloat() / total).coerceIn(0.1f, 0.95f)
                          }
                        )
                        currentArticle = translated
                        isShowingOriginal = false
                      } finally {
                        isTranslating = false
                        translationProgress = 1f
                      }
                    }
                  }
                },
              color = if (isSelected) readerTheme.accentColor.copy(alpha = 0.15f) else readerTheme.background,
              border = BorderStroke(0.6.dp, if (isSelected) readerTheme.accentColor else readerTheme.textColor.copy(alpha = 0.15f))
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "${lang.displayName} (${lang.nativeName})",
                  fontFamily = fontChoice.fontFamily,
                  fontSize = 13.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                  color = if (isSelected) readerTheme.accentColor else readerTheme.textColor
                )

                if (isSelected) {
                  Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = readerTheme.accentColor,
                    modifier = Modifier.size(16.dp)
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Open in External Google Translate Button
        Button(
          onClick = {
            showTranslateSheet = false
            displayArticle?.let { art ->
              ReaderTranslator.launchExternalTranslator(context, art.sourceUrl, isGhostRoute)
            }
          },
          colors = ButtonDefaults.buttonColors(
            containerColor = readerTheme.background,
            contentColor = readerTheme.accentColor
          ),
          border = BorderStroke(0.8.dp, readerTheme.accentColor),
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
        ) {
          Icon(Icons.Default.Language, null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "OPEN FULL WEB TRANSLATION",
            fontFamily = ThemeCyber.fontFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
        }

        Spacer(modifier = Modifier.height(20.dp))
      }
    }
  }

  // ==========================================
  // 6. EXPORT & SHARE MODAL BOTTOM SHEET
  // ==========================================
  if (showExportSheet) {
    ModalBottomSheet(
      onDismissRequest = { showExportSheet = false },
      containerColor = readerTheme.surfaceColor,
      sheetState = rememberModalBottomSheetState()
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 10.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Download,
              contentDescription = null,
              tint = readerTheme.accentColor,
              modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "EXPORT & SHARE ARTICLE",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = readerTheme.textColor
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Export Options
        ExportOptionTile(
          icon = Icons.Default.Bookmark,
          title = "Save to Reading List (Offline Available)",
          subtitle = "Save folder-wise with tags & full offline cached article",
          accentColor = Color(0xFFF59E0B),
          textColor = readerTheme.textColor,
          surfaceColor = readerTheme.background,
          onClick = {
            showExportSheet = false
            showSaveSheet = true
          }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExportOptionTile(
          icon = Icons.Default.PictureAsPdf,
          title = "Print / Export as PDF",
          subtitle = "Clean paginated document with headers and sources",
          accentColor = readerTheme.accentColor,
          textColor = readerTheme.textColor,
          surfaceColor = readerTheme.background,
          onClick = {
            showExportSheet = false
            displayArticle?.let { art ->
              ReaderExporter.printOrExportPdf(context, art)
            }
          }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExportOptionTile(
          icon = Icons.Default.Description,
          title = "Export as Markdown (.md)",
          subtitle = "Save clean structured markdown to Downloads & Share",
          accentColor = readerTheme.accentColor,
          textColor = readerTheme.textColor,
          surfaceColor = readerTheme.background,
          onClick = {
            showExportSheet = false
            displayArticle?.let { art ->
              scope.launch {
                ReaderExporter.exportMarkdownFile(context, art)
              }
            }
          }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExportOptionTile(
          icon = Icons.Default.Description,
          title = "Export as DOC / Word Document (.doc)",
          subtitle = "Formatted HTML document compatible with Word & Docs",
          accentColor = readerTheme.accentColor,
          textColor = readerTheme.textColor,
          surfaceColor = readerTheme.background,
          onClick = {
            showExportSheet = false
            displayArticle?.let { art ->
              scope.launch {
                ReaderExporter.exportDocFile(context, art)
              }
            }
          }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExportOptionTile(
          icon = Icons.Default.ContentCopy,
          title = "Copy to Clipboard",
          subtitle = "Copy complete formatted article text to clipboard",
          accentColor = readerTheme.accentColor,
          textColor = readerTheme.textColor,
          surfaceColor = readerTheme.background,
          onClick = {
            showExportSheet = false
            displayArticle?.let { art ->
              ReaderExporter.copyToClipboard(context, art, asMarkdown = true)
            }
          }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExportOptionTile(
          icon = Icons.Default.Share,
          title = "Share Article Link & Snippet",
          subtitle = "Send to messaging apps, email, or notes",
          accentColor = readerTheme.accentColor,
          textColor = readerTheme.textColor,
          surfaceColor = readerTheme.background,
          onClick = {
            showExportSheet = false
            displayArticle?.let { art ->
              ReaderExporter.shareArticle(context, art)
            }
          }
        )

        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }

  // 4. Save to Reading List Folder-wise Bottom Sheet
  if (showSaveSheet) {
    val dbState by RemmiDatabase.databaseState.collectAsState()
    val db = (dbState as? RemmiDatabase.DatabaseState.Ready)?.database
    val existingFolders by remember(db) {
      db?.readingListDao()?.getAllFolders() ?: kotlinx.coroutines.flow.flowOf(emptyList<String>())
    }.collectAsState(initial = emptyList<String>())
    val defaultFolders = listOf("General", "Technology", "Research", "News", "AI & Science", "Crypto & Privacy", "Read Later")
    val combinedFolders = remember(existingFolders) {
      (defaultFolders + existingFolders).distinct().filter { it.isNotBlank() }
    }

    var selectedFolder by remember { mutableStateOf("General") }
    var isCustomFolderMode by remember { mutableStateOf(false) }
    var customFolderInput by remember { mutableStateOf("") }
    var topicInput by remember {
      val domainPart = displayArticle?.sourceUrl?.let { url ->
        try {
          android.net.Uri.parse(url).host?.removePrefix("www.") ?: ""
        } catch (e: Exception) { "" }
      } ?: ""
      mutableStateOf(if (domainPart.isNotBlank()) domainPart else "General")
    }
    var notesInput by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }

    ModalBottomSheet(
      onDismissRequest = { showSaveSheet = false },
      containerColor = readerTheme.surfaceColor,
      sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 20.dp, vertical = 10.dp)
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        // Header
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Bookmark,
              contentDescription = null,
              tint = Color(0xFFF59E0B),
              modifier = Modifier.size(22.dp)
            )
            Text(
              text = "SAVE TO READING LIST",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 15.sp,
              fontWeight = FontWeight.Bold,
              color = readerTheme.textColor
            )
          }

          Surface(
            shape = RoundedCornerShape(4.dp),
            color = Color(0xFF10B981).copy(alpha = 0.15f),
            border = BorderStroke(0.5.dp, Color(0xFF10B981).copy(alpha = 0.4f))
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
              Icon(
                imageVector = Icons.Default.OfflinePin,
                contentDescription = null,
                tint = Color(0xFF10B981),
                modifier = Modifier.size(12.dp)
              )
              Text(
                text = "100% Offline Ready",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF10B981)
              )
            }
          }
        }

        // Article Info Preview Card
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = readerTheme.background,
          border = BorderStroke(0.6.dp, readerTheme.accentColor.copy(alpha = 0.25f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = displayArticle?.activeTitle ?: "Untitled Article",
              fontFamily = fontChoice.fontFamily,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = readerTheme.textColor,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              val domain = try {
                android.net.Uri.parse(displayArticle?.sourceUrl ?: "").host?.removePrefix("www.") ?: "web"
              } catch (e: Exception) { "web" }
              
              Text(
                text = "🌐 $domain",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = readerTheme.accentColor
              )

              Text(
                text = "•",
                fontSize = 11.sp,
                color = readerTheme.textColor.copy(alpha = 0.5f)
              )

              Text(
                text = "⏱ ~${displayArticle?.readingTimeMinutes ?: 1} min read (${displayArticle?.wordCount ?: 0} words)",
                fontSize = 11.sp,
                color = readerTheme.textColor.copy(alpha = 0.7f)
              )
            }
          }
        }

        // Section 1: Choose Folder
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Select Reading Folder:",
              fontFamily = ThemeCyber.fontFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = readerTheme.textColor
            )

            TextButton(
              onClick = { isCustomFolderMode = !isCustomFolderMode }
            ) {
              Icon(
                imageVector = if (isCustomFolderMode) Icons.Default.Folder else Icons.Default.Add,
                contentDescription = null,
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = if (isCustomFolderMode) "Pick Existing" else "+ New Folder",
                fontSize = 11.sp,
                color = Color(0xFFF59E0B),
                fontWeight = FontWeight.Bold
              )
            }
          }

          if (isCustomFolderMode) {
            OutlinedTextField(
              value = customFolderInput,
              onValueChange = { customFolderInput = it },
              label = { Text("New Folder Name", fontSize = 12.sp) },
              placeholder = { Text("e.g. Science, Crypto, Deep Dives", fontSize = 12.sp) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFF59E0B),
                unfocusedBorderColor = readerTheme.accentColor.copy(alpha = 0.3f),
                focusedTextColor = readerTheme.textColor,
                unfocusedTextColor = readerTheme.textColor
              )
            )
          } else {
            // Folder Chips Flow / Wrap
            androidx.compose.foundation.lazy.LazyRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              items(combinedFolders.size) { index ->
                val folderName = combinedFolders[index]
                val isSelected = selectedFolder.equals(folderName, ignoreCase = true)
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = if (isSelected) Color(0xFFF59E0B).copy(alpha = 0.2f) else readerTheme.background,
                  border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 0.6.dp,
                    color = if (isSelected) Color(0xFFF59E0B) else readerTheme.accentColor.copy(alpha = 0.25f)
                  ),
                  modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { selectedFolder = folderName }
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                  ) {
                    Icon(
                      imageVector = Icons.Default.Folder,
                      contentDescription = null,
                      tint = if (isSelected) Color(0xFFF59E0B) else readerTheme.textColor.copy(alpha = 0.6f),
                      modifier = Modifier.size(14.dp)
                    )
                    Text(
                      text = folderName,
                      fontSize = 12.sp,
                      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                      color = if (isSelected) Color(0xFFF59E0B) else readerTheme.textColor
                    )
                  }
                }
              }
            }
          }
        }

        // Section 2: Topic / Tags Input
        OutlinedTextField(
          value = topicInput,
          onValueChange = { topicInput = it },
          label = { Text("Topic / Tag (Optional)", fontSize = 12.sp) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = readerTheme.accentColor,
            unfocusedBorderColor = readerTheme.accentColor.copy(alpha = 0.3f),
            focusedTextColor = readerTheme.textColor,
            unfocusedTextColor = readerTheme.textColor
          )
        )

        // Section 3: Personal Notes (Optional)
        OutlinedTextField(
          value = notesInput,
          onValueChange = { notesInput = it },
          label = { Text("Personal Notes (Optional)", fontSize = 12.sp) },
          maxLines = 2,
          modifier = Modifier.fillMaxWidth(),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = readerTheme.accentColor,
            unfocusedBorderColor = readerTheme.accentColor.copy(alpha = 0.3f),
            focusedTextColor = readerTheme.textColor,
            unfocusedTextColor = readerTheme.textColor
          )
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Save Action Button
        Button(
          onClick = {
            val art = displayArticle
            if (art != null) {
              isSaving = true
              val targetFolder = if (isCustomFolderMode && customFolderInput.isNotBlank()) {
                customFolderInput.trim()
              } else {
                selectedFolder.trim()
              }
              val hostDomain = try {
                android.net.Uri.parse(art.sourceUrl).host?.removePrefix("www.") ?: "web"
              } catch (e: Exception) { "web" }

              val readingItem = ReadingListItem(
                url = art.sourceUrl,
                title = art.activeTitle.ifBlank { "Untitled Article" },
                domain = hostDomain,
                siteName = art.siteName,
                byline = art.byline,
                excerpt = art.excerpt,
                contentJson = art.toJson(),
                folder = targetFolder.ifBlank { "General" },
                topic = topicInput.trim().ifBlank { "General" },
                readingTimeMinutes = art.readingTimeMinutes,
                wordCount = art.wordCount,
                leadImageUrl = art.leadImageUrl,
                notes = notesInput.trim(),
                savedAt = System.currentTimeMillis()
              )

              scope.launch(Dispatchers.IO) {
                val db = RemmiDatabase.getDatabaseAsync(context)
                db.readingListDao().insert(readingItem)
                withContext(Dispatchers.Main) {
                  isArticleSaved = true
                  isSaving = false
                  showSaveSheet = false
                  Toast.makeText(
                    context,
                    "Article saved to '${targetFolder}' for offline reading!",
                    Toast.LENGTH_SHORT
                  ).show()
                }
              }
            }
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B)),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("reader_confirm_save_btn"),
          enabled = !isSaving
        ) {
          if (isSaving) {
            CircularProgressIndicator(
              color = Color.Black,
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp
            )
          } else {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Bookmark,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(18.dp)
              )
              Text(
                text = "Save for Offline Reading",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }
}

@Composable
private fun ReaderBottomTabItem(
  icon: ImageVector,
  label: String,
  isActive: Boolean,
  accentColor: Color,
  textColor: Color,
  modifier: Modifier = Modifier,
  badge: String? = null,
  onClick: () -> Unit
) {
  Box(
    modifier = modifier
      .fillMaxHeight()
      .clip(RoundedCornerShape(8.dp))
      .background(if (isActive) accentColor.copy(alpha = 0.15f) else Color.Transparent)
      .clickable { onClick() }
      .padding(vertical = 4.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Box(contentAlignment = Alignment.TopEnd) {
        Icon(
          imageVector = icon,
          contentDescription = label,
          tint = if (isActive) accentColor else textColor.copy(alpha = 0.75f),
          modifier = Modifier.size(20.dp)
        )

        if (badge != null) {
          Box(
            modifier = Modifier
              .size(12.dp)
              .clip(CircleShape)
              .background(accentColor),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = badge,
              fontSize = 8.sp,
              fontWeight = FontWeight.Bold,
              color = Color.Black,
              textAlign = TextAlign.Center
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(3.dp))

      Text(
        text = label,
        fontSize = 10.sp,
        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
        fontFamily = ThemeCyber.fontFamily,
        color = if (isActive) accentColor else textColor.copy(alpha = 0.75f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
private fun HighlightColorButton(
  color: Color,
  isSelected: Boolean,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier
      .size(24.dp)
      .clip(CircleShape)
      .background(color)
      .border(
        width = if (isSelected) 2.5.dp else 0.8.dp,
        color = if (isSelected) Color.White else Color.Gray.copy(alpha = 0.5f),
        shape = CircleShape
      )
      .clickable { onClick() }
  )
}

@Composable
private fun ExportOptionTile(
  icon: ImageVector,
  title: String,
  subtitle: String,
  accentColor: Color,
  textColor: Color,
  surfaceColor: Color,
  onClick: () -> Unit
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .clickable { onClick() },
    color = surfaceColor,
    border = BorderStroke(0.6.dp, accentColor.copy(alpha = 0.25f))
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(
        modifier = Modifier
          .size(36.dp)
          .clip(RoundedCornerShape(6.dp))
          .background(accentColor.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.size(20.dp)
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = title,
          fontSize = 13.sp,
          fontWeight = FontWeight.Bold,
          color = textColor
        )
        Text(
          text = subtitle,
          fontSize = 10.sp,
          color = textColor.copy(alpha = 0.6f)
        )
      }
    }
  }
}

private fun parseColorOrFallback(hex: String): Color {
  return try {
    Color(android.graphics.Color.parseColor(hex))
  } catch (e: Exception) {
    Color(0xFFFFEB3B)
  }
}
