package com.remmi.browser.engine

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeckoEngineTests {
  private lateinit var context: Application

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext<Application>()
    org.mozilla.gecko.GeckoAppShell.setApplicationContext(context)
  }

  @Test
  fun testGeckoEngineManagerSingleton() {
    val manager1 = GeckoEngineManager.getInstance(context)
    val manager2 = GeckoEngineManager.getInstance(context)
    assertSame("getInstance should return the same singleton instance", manager1, manager2)
  }
}
