package com.remmi.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.remmi.browser.MainActivity

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class StartupCrashTest {
    @Test
    fun testAppStartup() {
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    println("Activity launched successfully")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
