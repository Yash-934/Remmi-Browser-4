package com.remmi.browser.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiteSecurityPolicyEnforcementTest {

  private lateinit var context: Context
  private lateinit var policyManager: SiteSecurityPolicyManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    policyManager = SiteSecurityPolicyManager.getInstance(context)
  }

  @Test
  fun testPolicyPersistenceAndRetrieval() {
    val host = "bank.example.com"
    val customPolicy = SiteSecuritySettings(
      host = host,
      javascriptEnabled = false,
      blockPopups = true,
      autoplayAllowed = false,
      cookiePolicy = "BLOCK",
      customSecurityLevel = SecurityLevel.SAFEST
    )

    policyManager.setPolicyForHost(customPolicy)

    val retrieved = policyManager.getPolicyForHost(host)
    assertEquals(host, retrieved.host)
    assertEquals(false, retrieved.javascriptEnabled)
    assertTrue(retrieved.blockPopups)
    assertFalse(retrieved.autoplayAllowed)
    assertEquals("BLOCK", retrieved.cookiePolicy)
    assertEquals(SecurityLevel.SAFEST, retrieved.customSecurityLevel)

    // Remove policy
    policyManager.removePolicy(host)
    val defaultRetrieved = policyManager.getPolicyForHost(host)
    assertNull(defaultRetrieved.javascriptEnabled)
    assertNull(defaultRetrieved.customSecurityLevel)
  }

  @Test
  fun testDefaultFallbackPolicy() {
    val unconfigured = policyManager.getPolicyForHost("unknown.site.org")
    assertEquals("unknown.site.org", unconfigured.host)
    assertNull(unconfigured.javascriptEnabled)
    assertTrue(unconfigured.blockPopups)
    assertFalse(unconfigured.autoplayAllowed)
    assertEquals("ISOLATE", unconfigured.cookiePolicy)
  }
}
