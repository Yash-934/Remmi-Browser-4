package com.remmi.browser.engine

import com.remmi.browser.reader.ReaderArticle
import com.remmi.browser.security.ContainerType
import com.remmi.browser.security.PrivacyProfile
import com.remmi.browser.security.SecurityLevel
import java.util.UUID

data class BrowserTab(
  val id: String = UUID.randomUUID().toString(),
  val url: String = "about:blank",
  val title: String = "New Tab",
  val profile: PrivacyProfile = PrivacyProfile.SHIELD,
  val containerType: ContainerType = ContainerType.fromProfile(profile),
  val securityLevel: SecurityLevel = SecurityLevel.STANDARD,
  val isLoading: Boolean = false,
  val progress: Int = 0,
  val canGoBack: Boolean = false,
  val canGoForward: Boolean = false,
  val isSecure: Boolean = true,
  val blockedTrackersCount: Int = 0,
  val blockedLog: List<String> = emptyList(),
  val adsBlockedCount: Int = 0,
  val analyticsBlockedCount: Int = 0,
  val socialBlockedCount: Int = 0,
  val cryptomineBlockedCount: Int = 0,
  val fingerprintBlockedCount: Int = 0,
  val isDesktopMode: Boolean = false,
  val isReaderMode: Boolean = false,
  val readerArticle: ReaderArticle? = null,
  val createdAt: Long = System.currentTimeMillis(),
  val lastAccessedAt: Long = System.currentTimeMillis(),
  val groupId: String? = null,
  val isInactive: Boolean = false,
  val isPinned: Boolean = false,
  val isLocked: Boolean = false,
)
