package com.remmi.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun FindInPageBar(
  query: String,
  currentMatch: Int,
  totalMatches: Int,
  onQueryChange: (String) -> Unit,
  onFindNext: () -> Unit,
  onFindPrevious: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val focusRequester = remember { FocusRequester() }
  val accentColor = ThemeCyber.colors.primary

  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(52.dp)
      .background(ThemeCyber.colors.surface)
      .border(1.dp, accentColor.copy(alpha = 0.8f))
      .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Icon(
      imageVector = Icons.Default.Search,
      contentDescription = "Search In Page",
      tint = accentColor,
      modifier = Modifier.size(18.dp),
    )

    Spacer(modifier = Modifier.width(8.dp))

    // Search Input Field
    BasicTextField(
      value = query,
      onValueChange = onQueryChange,
      textStyle = TextStyle(
        color = ThemeCyber.colors.textPrimary,
        fontFamily = CyberMonoFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
      ),
      cursorBrush = SolidColor(accentColor),
      singleLine = true,
      keyboardOptions = KeyboardOptions(
        imeAction = ImeAction.Search,
        autoCorrectEnabled = false,
      ),
      keyboardActions = KeyboardActions(
        onSearch = { onFindNext() }
      ),
      modifier = Modifier
        .weight(1f)
        .focusRequester(focusRequester)
        .testTag("find_in_page_input"),
      decorationBox = { innerTextField ->
        if (query.isEmpty()) {
          Text(
            text = "Find in DOM...",
            color = ThemeCyber.colors.textMuted,
            fontFamily = CyberMonoFamily,
            fontSize = 13.sp,
          )
        }
        innerTextField()
      }
    )

    // Match Counter Badge
    if (query.isNotBlank()) {
      Text(
        text = if (totalMatches > 0) "$currentMatch / $totalMatches" else "0 / 0",
        color = if (totalMatches > 0) accentColor else ThemeCyber.colors.dangerRed,
        fontFamily = CyberMonoFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
          .clip(RoundedCornerShape(4.dp))
          .background(ThemeCyber.colors.surfaceLight)
          .padding(horizontal = 6.dp, vertical = 3.dp)
          .testTag("find_match_count"),
      )
      Spacer(modifier = Modifier.width(6.dp))
    }

    // Previous Match
    IconButton(
      onClick = onFindPrevious,
      enabled = query.isNotBlank() && totalMatches > 0,
      modifier = Modifier
        .size(32.dp)
        .testTag("find_previous_button")
    ) {
      Icon(
        imageVector = Icons.Default.KeyboardArrowUp,
        contentDescription = "Previous Match",
        tint = if (query.isNotBlank() && totalMatches > 0) accentColor else ThemeCyber.colors.textMuted,
        modifier = Modifier.size(20.dp),
      )
    }

    // Next Match
    IconButton(
      onClick = onFindNext,
      enabled = query.isNotBlank() && totalMatches > 0,
      modifier = Modifier
        .size(32.dp)
        .testTag("find_next_button")
    ) {
      Icon(
        imageVector = Icons.Default.KeyboardArrowDown,
        contentDescription = "Next Match",
        tint = if (query.isNotBlank() && totalMatches > 0) accentColor else ThemeCyber.colors.textMuted,
        modifier = Modifier.size(20.dp),
      )
    }

    // Close Button
    IconButton(
      onClick = onClose,
      modifier = Modifier
        .size(32.dp)
        .testTag("find_close_button")
    ) {
      Icon(
        imageVector = Icons.Default.Close,
        contentDescription = "Close Find",
        tint = ThemeCyber.colors.textSecondary,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}
