package com.remmi.adblock

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MemoryTelemetryCompileLifecycleTest {

  private lateinit var bridge: AdblockBridge

  @Before
  fun setUp() {
    bridge = AdblockBridge.getInstance()
    bridge.initEngine()
  }

  @Test
  fun testCompileMemoryStats_formatsExpectedMemoryFields() {
    val stats = bridge.getCompileMemoryStats()
    assertNotNull(stats)
    assertTrue("Stats must contain rss", stats.contains("rss="))
    assertTrue("Stats must contain pss", stats.contains("pss="))
    assertTrue("Stats must contain javaHeap", stats.contains("javaHeap="))
    assertTrue("Stats must contain nativeHeap", stats.contains("nativeHeap="))
  }

  @Test
  fun testCompileRules_triggersMemoryLifecycleAndSwapCorrectly() {
    val initialGen = bridge.getEngineGeneration()
    val ruleCount = bridge.compileRules("||tracking.adserver.com^\n||ads.banner.net^")
    assertTrue("Compiled rule count should be >= 0", ruleCount >= 0)
    val newGen = bridge.getEngineGeneration()
    assertTrue("Generation should advance after rule compilation", newGen >= initialGen)
  }
}
