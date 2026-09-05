package com.remmi.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.engine.TabGroup
import com.remmi.browser.ui.theme.ThemeCyber

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectTabGroupSheet(
  url: String,
  tabGroups: List<TabGroup>,
  tabs: List<BrowserTab>,
  onDismiss: () -> Unit,
  onSelectGroup: (groupId: String) -> Unit,
  onCreateGroupAndOpen: (title: String, colorHex: Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val cyberColors = ThemeCyber.colors
  val isLightMode = cyberColors.isLight
  val backgroundColor = if (isLightMode) Color.White else Color(0xFF1E1F24)
  val surfaceColor = if (isLightMode) Color(0xFFF3F4F6) else Color(0xFF2A2B32)
  val textColor = if (isLightMode) Color(0xFF1F2024) else Color(0xFFF1F1F3)
  val textSubColor = if (isLightMode) Color(0xFF6B7280) else Color(0xFF9CA3AF)
  val dividerColor = if (isLightMode) Color(0xFFE5E7EB) else Color(0xFF374151)
  val primaryColor = cyberColors.primary

  var isCreatingNewGroup by remember { mutableStateOf(tabGroups.isEmpty()) }
  var newGroupTitle by remember { mutableStateOf("") }
  var selectedColorHex by remember { mutableStateOf(TabGroup.PRESET_COLORS[0]) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = backgroundColor,
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    dragHandle = null,
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = 20.dp)
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(36.dp)
              .clip(CircleShape)
              .background(primaryColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.FolderOpen,
              contentDescription = null,
              tint = primaryColor,
              modifier = Modifier.size(20.dp)
            )
          }
          Spacer(modifier = Modifier.width(12.dp))
          Column {
            Text(
              text = if (isCreatingNewGroup) "Create Tab Group" else "Open in Tab Group",
              fontSize = 17.sp,
              fontWeight = FontWeight.Bold,
              color = textColor
            )
            Text(
              text = url.removePrefix("https://").removePrefix("http://"),
              fontSize = 12.sp,
              color = textSubColor,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
          Icon(Icons.Default.Close, contentDescription = "Close", tint = textSubColor)
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
      HorizontalDivider(color = dividerColor, thickness = 0.8.dp)
      Spacer(modifier = Modifier.height(16.dp))

      if (isCreatingNewGroup) {
        // --- Create New Group Form ---
        Text(
          text = "GROUP NAME",
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = textSubColor
        )
        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
          value = newGroupTitle,
          onValueChange = { newGroupTitle = it },
          placeholder = { Text("e.g. Work, Research, Shopping, Media", fontSize = 13.sp) },
          singleLine = true,
          shape = RoundedCornerShape(10.dp),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color(selectedColorHex),
            unfocusedBorderColor = dividerColor,
            focusedTextColor = textColor,
            unfocusedTextColor = textColor
          ),
          textStyle = TextStyle(fontSize = 14.sp),
          modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = "GROUP COLOR",
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = textSubColor
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.fillMaxWidth()
        ) {
          TabGroup.PRESET_COLORS.forEach { colorVal ->
            val isSelected = selectedColorHex == colorVal
            Box(
              modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(colorVal))
                .border(
                  width = if (isSelected) 2.5.dp else 0.dp,
                  color = if (isSelected) (if (isLightMode) Color.Black else Color.White) else Color.Transparent,
                  shape = CircleShape
                )
                .clickable { selectedColorHex = colorVal },
              contentAlignment = Alignment.Center
            ) {
              if (isSelected) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = if (isLightMode) Color.Black else Color.White,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (tabGroups.isNotEmpty()) {
            TextButton(onClick = { isCreatingNewGroup = false }) {
              Text("Choose Existing Group", color = primaryColor, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
          }

          Button(
            onClick = {
              val name = newGroupTitle.trim().ifEmpty { "Group ${tabGroups.size + 1}" }
              onCreateGroupAndOpen(name, selectedColorHex)
            },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(selectedColorHex),
              contentColor = Color.Black
            )
          ) {
            Text("Create & Open", fontWeight = FontWeight.Bold)
          }
        }
      } else {
        // --- List of Existing Groups ---
        Text(
          text = "SELECT AN EXISTING GROUP",
          fontFamily = ThemeCyber.fontFamily,
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
          color = textSubColor
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(tabGroups, key = { it.id }) { group ->
            val tabCount = tabs.count { it.groupId == group.id }
            Surface(
              onClick = { onSelectGroup(group.id) },
              shape = RoundedCornerShape(12.dp),
              color = surfaceColor,
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Box(
                  modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(group.colorHex))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = group.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                  )
                  Text(
                    text = "$tabCount tabs",
                    fontSize = 12.sp,
                    color = textSubColor
                  )
                }
                Icon(
                  imageVector = Icons.Default.Folder,
                  contentDescription = null,
                  tint = Color(group.colorHex),
                  modifier = Modifier.size(20.dp)
                )
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Create New Group Option Button
        Surface(
          onClick = { isCreatingNewGroup = true },
          shape = RoundedCornerShape(12.dp),
          color = primaryColor.copy(alpha = 0.1f),
          border = androidx.compose.foundation.BorderStroke(1.dp, primaryColor.copy(alpha = 0.3f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            Icon(
              imageVector = Icons.Default.Add,
              contentDescription = null,
              tint = primaryColor,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Create New Tab Group",
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = primaryColor
            )
          }
        }
      }
      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}
