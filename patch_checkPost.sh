sed -i 's/if (record != null && elapsed in 0..15000L) {/if (isNavActive || isRecoveryInFlight) {/g' app/src/main/java/com/remmi/browser/engine/GeckoEngineManager.kt
