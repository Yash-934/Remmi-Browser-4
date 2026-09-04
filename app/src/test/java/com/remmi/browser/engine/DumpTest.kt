package com.remmi.browser.engine
import org.junit.Test
import org.mozilla.geckoview.GeckoSession
class DumpTest {
  @Test
  fun dump() {
    val constructor = GeckoSession.NavigationDelegate.LoadRequest::class.java.getDeclaredConstructor()
    constructor.isAccessible = true
    val req = constructor.newInstance()
    val isRedirectField = req::class.java.getDeclaredField("isRedirect")
    isRedirectField.isAccessible = true
    isRedirectField.setBoolean(req, true)
    
    val uriField = req::class.java.getDeclaredField("uri")
    uriField.isAccessible = true
    uriField.set(req, "test-uri")
    println("MY_LOG: " + req.isRedirect + " " + req.uri)
  }
}
