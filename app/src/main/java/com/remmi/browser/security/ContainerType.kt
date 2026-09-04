package com.remmi.browser.security

/**
 * Per-Tab Privacy Container.
 * Enforces strong storage, cookie, and cache isolation across browsing contexts.
 */
enum class ContainerType(
  val displayName: String,
  val prefix: String,
  val isPersistent: Boolean,
  val isTorIsolated: Boolean
) {
  NORMAL(
    displayName = "Normal Container",
    prefix = "std",
    isPersistent = true,
    isTorIsolated = false
  ),
  PRIVATE(
    displayName = "Private Container",
    prefix = "prv",
    isPersistent = false,
    isTorIsolated = false
  ),
  GHOST(
    displayName = "Ghost Onion Container",
    prefix = "gst",
    isPersistent = false,
    isTorIsolated = true
  ),
  TEMPORARY(
    displayName = "Ephemeral Container",
    prefix = "tmp",
    isPersistent = false,
    isTorIsolated = false
  );

  companion object {
    fun fromProfile(profile: PrivacyProfile): ContainerType {
      return when (profile) {
        PrivacyProfile.SHIELD -> NORMAL
        PrivacyProfile.GHOST -> GHOST
        PrivacyProfile.INCOGNITO -> PRIVATE
      }
    }
  }
}
