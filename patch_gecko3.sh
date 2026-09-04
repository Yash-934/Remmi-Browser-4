sed -i 's/successfulUrl=${record.url}/successfulUrl=${record?.url ?: "none"}/g' app/src/main/java/com/remmi/browser/engine/GeckoEngineManager.kt
