sed -i -e '/\/\/ 3. FOREGROUND ACTIVE TAB/!b' -e ':a' -e 'N' -e '/transitionRecoveryState/!ba' -e 's|// 3. FOREGROUND ACTIVE TAB.*transitionRecoveryState|// 3. FOREGROUND ACTIVE TAB\
    val isNavActive = (navLoadingStates[tabId] == true) || inFlightNavigations.containsKey(tabId)\
    val record = lastSuccessfulNavigations[tabId]\
    val isPostSuccessCrash = !isNavActive \&\& record != null \&\& record.gen == gen\
\
    val (activeNavId, activeGen) = if (isPostSuccessCrash) {\
      allocateNavigationGeneration(tabId, "CONTENT_RECOVERY", currUrl)\
    } else {\
      Pair(getActiveNavId(tabId), gen)\
    }\
\
    // Prevent recovery loops: maximum one automatic recovery attempt per navigation generation\
    val lastRecoveredGen = lastRecoveredGenerations[tabId]\
    if (lastRecoveredGen != null \&\& lastRecoveredGen == activeGen) {\
      val suppMsg = "[FORENSIC][CONTENT_RECOVERY_SUPPRESSED] tabId=$tabId session=$sessId view=$viewId url=$currUrl gen=$activeGen reason=max_attempts_exceeded elapsedRealtime=$now"\
      Log.w(TAG, suppMsg)\
      com.remmi.browser.util.DebugLogManager.log(suppMsg)\
      return\
    }\
\
    // 4. Mark recovery start\
    lastRecoveredGenerations[tabId] = activeGen\
    transitionRecoveryState|' app/src/main/java/com/remmi/browser/engine/GeckoEngineManager.kt
