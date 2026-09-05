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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.remmi.browser.engine.BrowserTab
import com.remmi.browser.engine.TabGroup
import com.remmi.browser.ui.theme.CyberMonoFamily
import com.remmi.browser.ui.theme.ThemeCyber

@Composable
fun TabGroupSelectDialog(
  targetUrl: String,
  groups: List<TabGroup>,
  tabs: List<BrowserTab>,
  onDismiss: () -> Unit,
  onSelectExistingGroup: (groupId: String) -> Unit,
  onCreateNewGroup: (title: String, colorHex: Long) -> Unit,
) {
  var isCreatingNew by remember { mutableStateOf(groups.isEmpty()) }
  var newGroupName by remember {
    mutableStateOf(
      if (groups.isEmpty()) "Group 1" else "Group ${groups.size + 1}"
    )
  }
  var selectedColorHex by remember { mutableStateOf(TabGroup.PRESET_COLORS.first()) }

  val isLight = ThemeCyber.colors.isLight
  val dialogBg = if (isLight) Color(0xFFF9FAFB) else Color(0xFF1B1C22)
  val textColor = if (isLight) Color(0xFF111827) else Color(0xFFF9FAFB)
  val subTextColor = if (isLight) Color(0xFF6B7280) else Color(0xFF9CA3AF)
  val cardBg = if (isLight) Color.White else Color(0xFF262833)
  val borderColor = if (isLight) Color(0xFFE5E7EB) else Color(0xFF374151)

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      shape = RoundedCornerShape(16.dp),
      color = dialogBg,
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp)
        .border(1.dp, borderColor, RoundedCornerShape(16.dp))
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(20.dp)
      ) {
        // Top Header
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
                .background(ThemeCyber.colors.primary.copy(alpha = 0.15f)),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Tab Groups",
                tint = ThemeCyber.colors.primary,
                modifier = Modifier.size(20.dp)
              )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
              Text(
                text = "OPEN IN TAB GROUP",
                fontFamily = CyberMonoFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = ThemeCyber.colors.primary
              )
              Text(
                text = "Choose or create a tab group",
                fontSize = 11.sp,
                color = subTextColor
              )
            }
          }

          IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(32.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = "Close",
              tint = subTextColor,
              modifier = Modifier.size(18.dp)
            )
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (groups.isNotEmpty() && !isCreatingNew) {
          // Existing groups list
          Text(
            text = "EXISTING GROUPS",
            fontFamily = CyberMonoFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = subTextColor,
            modifier = Modifier.padding(bottom = 8.dp)
          )

          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            items(groups, key = { it.id }) { group ->
              val groupTabsCount = tabs.count { it.groupId == group.id }
              val groupColor = Color(group.colorHex)

              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(10.dp))
                  .background(cardBg)
                  .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                  .clickable { onSelectExistingGroup(group.id) }
                  .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.weight(1f)
                ) {
                  Box(
                    modifier = Modifier
                      .size(14.dp)
                      .clip(CircleShape)
                      .background(groupColor)
                  )
                  Spacer(modifier = Modifier.width(12.dp))
                  Text(
                    text = group.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }

                Text(
                  text = "$groupTabsCount tab${if (groupTabsCount != 1) "s" else ""}",
                  fontFamily = CyberMonoFamily,
                  fontSize = 11.sp,
                  color = subTextColor
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(16.dp))
          HorizontalDivider(color = borderColor, thickness = 0.8.dp)
          Spacer(modifier = Modifier.height(12.dp))

          Button(
            onClick = { isCreatingNew = true },
            colors = ButtonDefaults.buttonColors(containerColor = cardBg),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .border(1.dp, ThemeCyber.colors.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
              .testTag("btn_create_new_group_toggle")
          ) {
            Icon(
              imageVector = Icons.Default.Add,
              contentDescription = "New Group",
              tint = ThemeCyber.colors.primary,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "CREATE NEW GROUP",
              fontFamily = CyberMonoFamily,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = ThemeCyber.colors.primary
            )
          }
        } else {
          // Create new group form
          Text(
            text = "NEW GROUP DETAILS",
            fontFamily = CyberMonoFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = subTextColor,
            modifier = Modifier.padding(bottom = 8.dp)
          )

          OutlinedTextField(
            value = newGroupName,
            onValueChange = { newGroupName = it },
            label = { Text("Group Name", fontSize = 12.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = ThemeCyber.colors.primary,
              unfocusedBorderColor = borderColor,
              focusedTextColor = textColor,
              unfocusedTextColor = textColor,
              focusedLabelColor = ThemeCyber.colors.primary,
              unfocusedLabelColor = subTextColor,
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("input_tab_group_name")
          )

          Spacer(modifier = Modifier.height(14.dp))

          Text(
            text = "CHOOSE COLOR",
            fontFamily = CyberMonoFamily,
            fontSize = 10.5.sp,
            color = subTextColor,
            modifier = Modifier.padding(bottom = 8.dp)
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            TabGroup.PRESET_COLORS.forEach { colorHex ->
              val isSelected = selectedColorHex == colorHex
              val itemColor = Color(colorHex)
              Box(
                modifier = Modifier
                  .size(28.dp)
                  .clip(CircleShape)
                  .background(itemColor)
                  .border(
                    width = if (isSelected) 2.5.dp else 1.dp,
                    color = if (isSelected) textColor else Color.Transparent,
                    shape = CircleShape
                  )
                  .clickable { selectedColorHex = colorHex },
                contentAlignment = Alignment.Center
              ) {
                if (isSelected) {
                  Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp)
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(20.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            if (groups.isNotEmpty()) {
              Button(
                onClick = { isCreatingNew = false },
                colors = ButtonDefaults.buttonColors(containerColor = cardBg),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                  .weight(1f)
                  .border(1.dp, borderColor, RoundedCornerShape(8.dp))
              ) {
                Text(
                  text = "BACK",
                  fontFamily = CyberMonoFamily,
                  fontSize = 12.sp,
                  color = subTextColor
                )
              }
            }

            Button(
              onClick = {
                val finalTitle = newGroupName.trim().ifEmpty { "New Group" }
                onCreateNewGroup(finalTitle, selectedColorHex)
              },
              colors = ButtonDefaults.buttonColors(containerColor = ThemeCyber.colors.primary),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier
                .weight(1f)
                .testTag("btn_confirm_create_group")
            ) {
              Text(
                text = "CREATE & OPEN",
                fontFamily = CyberMonoFamily,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
              )
            }
          }
        }
      }
    }
  }
}
