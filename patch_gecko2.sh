sed -i 's/        } else if (record != null && elapsed in 0..15000L) {/        } else if (elapsed in 0..15000L || record == null) {/g' app/src/main/java/com/remmi/browser/engine/GeckoEngineManager.kt
