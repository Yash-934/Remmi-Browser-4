package com.remmi.browser.security

import com.remmi.browser.ui.components.getFaviconUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class FaviconPrivacySecurityTest {

  @Test
  fun testFaviconMethodsDoNotQueryExternalServices() {
    val testUrls = listOf(
      "https://example.com",
      "https://secret-bank.com/login",
      "https://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
      "http://192.168.1.1"
    )

    for (url in testUrls) {
      val res1 = getFaviconUrl(url)

      assertFalse("Must never query Google Favicon Service", res1.contains("google.com/s2/favicons"))
      assertFalse("Must never query DuckDuckGo favicon service", res1.contains("icons.duckduckgo.com"))

      assertEquals("", res1)
    }
  }

  @Test
  fun testCodebaseDoesNotContainExternalFaviconEndpoints() {
    val srcDir = File("src/main/java")
    if (srcDir.exists()) {
      srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
        val text = file.readText()
        assertFalse(
          "Found forbidden Google Favicon API leak in ${file.name}",
          text.contains("google.com/s2/favicons")
        )
      }
    }
  }
}
