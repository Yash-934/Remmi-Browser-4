package com.remmi.browser.engine

import java.util.UUID

data class TabGroup(
  val id: String = UUID.randomUUID().toString(),
  val title: String = "Group",
  val colorHex: Long = 0xFF00E5FF,
  val isCollapsed: Boolean = false,
  val createdAt: Long = System.currentTimeMillis(),
  val isInactive: Boolean = false,
) {
  companion object {
    val PRESET_COLORS = listOf(
      0xFF00E5FF, // Cyber Cyan
      0xFFBB86FC, // Tor Purple
      0xFF00E676, // Matrix Green
      0xFFFF9100, // Neon Orange
      0xFFFF5252, // Coral Red
      0xFFFFD600, // Electric Yellow
      0xFFE040FB, // Synthwave Pink
      0xFF448AFF, // Deep Blue
    )
  }
}
