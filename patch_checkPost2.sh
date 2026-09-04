sed -i '/"CONTENT_CRASH", "CONTENT_KILL" -> {/,/      }/ {
  s/if (isNavActive || isRecoveryInFlight) {/if (isNavActive || isRecoveryInFlight) {/
  # We just did this replacement! But wait, we need to add the else branch for CONTENT_KILL!
}' app/src/main/java/com/remmi/browser/engine/GeckoEngineManager.kt
