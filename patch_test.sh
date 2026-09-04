sed -i 's/private val testCallbacks = object : BrowserTab.TabCallbacks {/private val testCallbacks = object : GeckoTabCallbacks {/' app/src/test/java/com/remmi/browser/engine/Step36TerminalRecoveryTest.kt
sed -i 's/override fun onSecurityLevelChanged(level: SecurityLevel) {}//' app/src/test/java/com/remmi/browser/engine/Step36TerminalRecoveryTest.kt
sed -i 's/override fun onUrlChanged(url: String) {}//' app/src/test/java/com/remmi/browser/engine/Step36TerminalRecoveryTest.kt
sed -i 's/override fun onTitleChanged(title: String) {}//' app/src/test/java/com/remmi/browser/engine/Step36TerminalRecoveryTest.kt
sed -i 's/override fun onProgressChanged(progress: Int) {}//' app/src/test/java/com/remmi/browser/engine/Step36TerminalRecoveryTest.kt
sed -i 's/override fun onLoadingStateChanged(isLoading: Boolean) {}//' app/src/test/java/com/remmi/browser/engine/Step36TerminalRecoveryTest.kt
sed -i 's/override fun onCanGoBackChanged(canGoBack: Boolean) {}//' app/src/test/java/com/remmi/browser/engine/Step36TerminalRecoveryTest.kt
sed -i 's/override fun onCanGoForwardChanged(canGoForward: Boolean) {}//' app/src/test/java/com/remmi/browser/engine/Step36TerminalRecoveryTest.kt
sed -i 's/override fun onScrollChanged(scrollY: Int, isScrollingDown: Boolean) {}//' app/src/test/java/com/remmi/browser/engine/Step36TerminalRecoveryTest.kt
