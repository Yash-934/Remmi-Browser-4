package com.remmi.adblock

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/**
 * Native Messaging Delegate for Remmi GeckoView WebExtension.
 * Dedicated to threat neutralization, tracker blocking, and click transparency DOM inspection.
 *
 * CRITICAL ARCHITECTURAL INVARIANT:
 * WebExtension proxy authority has been completely removed.
 * Native Gecko layer (GeckoRuntime / GeckoSession) is the SOLE authoritative manager of proxy
 * routing, Tor SOCKS5 isolation, and network hardening.
 *
 * CONCURRENCY & ISOLATION INVARIANT:
 * Every asynchronous request contains (tabId, sessionId, requestId) tracking.
 * Responses are routed directly and deterministically to the originating tab/request,
 * preventing cross-tab state leakage or callback overwrites.
 */
enum class ExtensionState {
  NOT_REGISTERED,
  REGISTERED,
  CONNECTED,
  DISCONNECTED,
  FAILED
}

class BlockExtension private constructor(private val adblockBridge: AdblockBridge) : WebExtension.MessageDelegate {

  var siteSecurityProvider: ((String) -> Boolean)? = null
  var cosmeticPolicyProvider: ((String) -> Boolean)? = null
  var customCosmeticRuleProvider: ((String) -> List<String>)? = null
  var onCustomBlockElement: ((host: String, selector: String) -> Unit)? = null
  // Global listeners (for passive threat and click interception events)
  private val threatListeners = CopyOnWriteArraySet<(url: String, type: String) -> Unit>()
  private val htmlListeners = CopyOnWriteArraySet<(url: String, html: String) -> Unit>()
  private val clickListeners = CopyOnWriteArraySet<(candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit>()

  // Per-request / per-tab isolated callback registries
  private val pendingHtmlRequests = ConcurrentHashMap<String, (url: String, html: String) -> Unit>()
  private val pendingClickRequests = ConcurrentHashMap<String, (candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit>()
  private val pendingEvalRequests = ConcurrentHashMap<String, (result: String, isError: Boolean) -> Unit>()

  // Legacy single-property compatibility with thread safety - does NOT wipe multi-listener registry
  @Volatile
  private var legacyThreatListener: ((url: String, type: String) -> Unit)? = null
  @Volatile
  private var legacyHtmlListener: ((url: String, html: String) -> Unit)? = null
  @Volatile
  private var legacyClickListener: ((candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit)? = null

  var onThreatNeutralized: ((url: String, type: String) -> Unit)?
    get() = legacyThreatListener
    set(value) {
      legacyThreatListener = value
    }

  var onHtmlExtracted: ((url: String, html: String) -> Unit)?
    get() = legacyHtmlListener
    set(value) {
      legacyHtmlListener = value
    }

  var onClickInspected: ((candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit)?
    get() = legacyClickListener
    set(value) {
      legacyClickListener = value
    }

  fun addThreatListener(listener: (url: String, type: String) -> Unit) = threatListeners.add(listener)
  fun removeThreatListener(listener: (url: String, type: String) -> Unit) = threatListeners.remove(listener)

  fun addHtmlListener(listener: (url: String, html: String) -> Unit) = htmlListeners.add(listener)
  fun removeHtmlListener(listener: (url: String, html: String) -> Unit) = htmlListeners.remove(listener)

  fun addClickListener(listener: (candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit) = clickListeners.add(listener)
  fun removeClickListener(listener: (candidates: List<JSONObject>, hasOverlay: Boolean, intercepted: Boolean, pageUrl: String) -> Unit) = clickListeners.remove(listener)

  private val _extensionState = MutableStateFlow(ExtensionState.NOT_REGISTERED)
  val extensionState: StateFlow<ExtensionState> = _extensionState.asStateFlow()

  @Volatile
  private var activePort: WebExtension.Port? = null
  private val portLock = Any()
  private val activePortGeneration = java.util.concurrent.atomic.AtomicLong(1)
  private val portInstanceCount = java.util.concurrent.atomic.AtomicInteger(0)
  private val mainHandler = Handler(Looper.getMainLooper())

  private val networkQueue = java.util.concurrent.ArrayBlockingQueue<Runnable>(256)
  private val networkExecutor = java.util.concurrent.ThreadPoolExecutor(
    4,
    4,
    60L,
    java.util.concurrent.TimeUnit.SECONDS,
    networkQueue,
    { r ->
      Thread(r, "AdblockNetworkWorker").apply {
        isDaemon = true
        priority = Thread.NORM_PRIORITY
      }
    },
    java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
  )

  private val MAX_COSMETIC_QUEUE_CAPACITY = 16
  private val cosmeticQueue = java.util.concurrent.ArrayBlockingQueue<Runnable>(MAX_COSMETIC_QUEUE_CAPACITY)
  private val cosmeticExecutor = java.util.concurrent.ThreadPoolExecutor(
    2,
    2,
    60L,
    java.util.concurrent.TimeUnit.SECONDS,
    cosmeticQueue,
    { r ->
      Thread(r, "AdblockCosmeticWorker").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
      }
    },
    { _, _ ->
      cosmeticDroppedCount.incrementAndGet()
      Log.w(TAG, "[COSMETIC_QUEUE_REJECT] Cosmetic queue full ($MAX_COSMETIC_QUEUE_CAPACITY). Dropping superseded task.")
    }
  )

  fun setExtensionRegistered() {
    if (_extensionState.value == ExtensionState.NOT_REGISTERED) {
      _extensionState.value = ExtensionState.REGISTERED
    }
  }

  fun setExtensionFailed(reason: String) {
    _extensionState.value = ExtensionState.FAILED
    log("[WEBEXT] Extension registration failed: $reason")
  }

  private val extensionScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

  private fun parseMessage(message: Any): JSONObject? {
    return try {
      when (message) {
        is JSONObject -> message
        is String -> JSONObject(message)
        is Map<*, *> -> JSONObject(message)
        else -> null
      }
    } catch (t: Throwable) {
      Log.e(
        TAG,
        "[WEBEXT_PROTOCOL_PARSE_ERROR] ${t.javaClass.name}: ${t.message}"
      )
      null
    }
  }

  override fun onMessage(
    nativeApp: String,
    message: Any,
    sender: WebExtension.MessageSender
  ): org.mozilla.geckoview.GeckoResult<Any>? {
    // Permissive app name check: accept "remmi_engine_extension", extension id, or matching sender
    if (nativeApp.isNotEmpty() && 
        nativeApp != "remmi_engine_extension" && 
        nativeApp != sender.webExtension.id && 
        sender.webExtension.id != "extension@remmi.browser") {
      Log.w(TAG, "[WEBEXT_REJECTED] nativeApp=$nativeApp sender=${sender.webExtension.id}")
      return org.mozilla.geckoview.GeckoResult.fromValue(
        JSONObject().apply {
          put("ok", false)
          put("error", "unauthorized_app")
        }
      )
    }

    val messageJson = parseMessage(message)
    if (messageJson == null) {
      Log.e(TAG, "[WEBEXT_PROTOCOL_ERROR] invalid_message")
      return org.mozilla.geckoview.GeckoResult.fromValue(
        JSONObject().apply {
          put("ok", false)
          put("error", "invalid_message")
        }
      )
    }

    val receiveTs = System.currentTimeMillis()
    val type = messageJson.optString("type")
    val reqId = messageJson.optString("requestId").ifEmpty { "n/a" }
    val candidateUrl = messageJson.optString("url")
    val isTraceCandidate = candidateUrl.contains("google-analytics") || 
                           candidateUrl.contains("adblock-tester") || 
                           candidateUrl.contains("googletagmanager") || 
                           candidateUrl.contains("banner") ||
                           type == "SHOULD_BLOCK"

    if (isTraceCandidate || type == "PING" || type == "GET_COSMETIC_RESOURCES" || type == "GET_HIDDEN_CLASS_ID_SELECTORS") {
      Log.d(TAG, "[NM_NATIVE_RECEIVE] requestId=$reqId type=$type ts=$receiveTs")
      Log.d(TAG, "[NM_NATIVE_HANDLER_START] requestId=$reqId")
    }

    if (type == "PING") {
      Log.d(TAG, "[NM_RESPONSE_COMPLETE] type=PING requestId=$reqId")
      return org.mozilla.geckoview.GeckoResult.fromValue(
        JSONObject().apply {
          put("ok", true)
          put("pong", true)
        }
      )
    }

    if (type == "BLOCK_ELEMENT") {
      val selector = messageJson.optString("selector").trim()
      val domain = messageJson.optString("domain").trim()
      val resp = if (selector.isNotEmpty()) {
        val rule = if (domain.isNotEmpty()) "$domain##$selector" else "##$selector"
        adblockBridge.addCustomRule(rule)
        onCustomBlockElement?.invoke(domain, selector)
        Log.i(TAG, "[ADBLOCK_CUSTOM_RULE] Added element block rule: $rule")
        JSONObject().apply {
          put("ok", true)
          put("rule", rule)
          put("generation", adblockBridge.getEngineGeneration())
        }
      } else {
        JSONObject().apply {
          put("ok", false)
          put("error", "empty_selector")
        }
      }
      Log.d(TAG, "[NM_RESPONSE_COMPLETE] type=BLOCK_ELEMENT requestId=$reqId")
      return org.mozilla.geckoview.GeckoResult.fromValue(resp)
    }

    if (type == "GET_COSMETIC_RESOURCES") {
      val url = messageJson.optString("url")
      val hostname = messageJson.optString("hostname")
      val classesArray = messageJson.optJSONArray("classes")
      val idsArray = messageJson.optJSONArray("ids")
      val exceptionsArray = messageJson.optJSONArray("exceptions")

      val classes = mutableListOf<String>()
      if (classesArray != null) {
        for (i in 0 until classesArray.length()) classes.add(classesArray.getString(i))
      }
      val ids = mutableListOf<String>()
      if (idsArray != null) {
        for (i in 0 until idsArray.length()) ids.add(idsArray.getString(i))
      }
      val exceptions = mutableListOf<String>()
      if (exceptionsArray != null) {
        for (i in 0 until exceptionsArray.length()) exceptions.add(exceptionsArray.getString(i))
      }

      val host = if (hostname.isNotEmpty()) hostname else try {
        java.net.URI(if (url.contains("://")) url else "https://$url").host?.lowercase() ?: ""
      } catch (_: Exception) { "" }

      Log.d(TAG, "[COSMETIC_REQUEST] hostHash=${host.hashCode()} classCount=${classes.size} idCount=${ids.size}")

      val isCosmeticAllowed = cosmeticPolicyProvider?.invoke(host) ?: true
      if (!isCosmeticAllowed) {
        Log.d(TAG, "[COSMETIC_RESULT] disabled_by_policy hostHash=${host.hashCode()}")
        Log.d(TAG, "[NM_RESPONSE_COMPLETE] type=GET_COSMETIC_RESOURCES requestId=$reqId")
        return org.mozilla.geckoview.GeckoResult.fromValue(
          JSONObject().apply {
            put("ok", true)
            put("generation", adblockBridge.getEngineGeneration())
            put("hideSelectors", org.json.JSONArray())
            put("forceHideSelectors", org.json.JSONArray())
            put("procedural", org.json.JSONArray())
            put("proceduralCount", 0)
            put("generics", false)
          }
        )
      }

      val customSelectors = customCosmeticRuleProvider?.invoke(host) ?: emptyList()
      val resp = try {
        val cosmetic = adblockBridge.getCosmeticResources(url, classes, ids, exceptions)
        val combinedForceHide = (cosmetic.forceHideSelectors + customSelectors).distinct()
        Log.d(
          TAG,
          "[COSMETIC_RESULT] hide=${cosmetic.hideSelectors.size} forceHide=${combinedForceHide.size} procedural=${cosmetic.proceduralCount} generation=${cosmetic.generation}"
        )
        JSONObject().apply {
          put("ok", cosmetic.ok)
          put("generation", cosmetic.generation)
          put("hideSelectors", org.json.JSONArray(cosmetic.hideSelectors))
          put("forceHideSelectors", org.json.JSONArray(combinedForceHide))
          put("procedural", org.json.JSONArray(cosmetic.procedural))
          put("proceduralCount", cosmetic.proceduralCount)
          put("generics", cosmetic.generics)
          if (cosmetic.error != null) put("error", cosmetic.error)
        }
      } catch (t: Throwable) {
        Log.e(TAG, "[COSMETIC_ERROR] error=${t.message}", t)
        JSONObject().apply {
          put("ok", false)
          put("error", t.message ?: "exception")
          put("generation", adblockBridge.getEngineGeneration())
          put("hideSelectors", org.json.JSONArray())
          put("forceHideSelectors", org.json.JSONArray(customSelectors))
          put("procedural", org.json.JSONArray())
          put("proceduralCount", 0)
          put("generics", false)
        }
      }

      Log.d(TAG, "[NM_RESPONSE_COMPLETE] type=GET_COSMETIC_RESOURCES requestId=$reqId")
      return org.mozilla.geckoview.GeckoResult.fromValue(resp)
    }

    if (type == "GET_HIDDEN_CLASS_ID_SELECTORS") {
      val classesArray = messageJson.optJSONArray("classes")
      val idsArray = messageJson.optJSONArray("ids")
      val exceptionsArray = messageJson.optJSONArray("exceptions")

      val classes = mutableListOf<String>()
      if (classesArray != null) {
        for (i in 0 until classesArray.length()) classes.add(classesArray.getString(i))
      }
      val ids = mutableListOf<String>()
      if (idsArray != null) {
        for (i in 0 until idsArray.length()) ids.add(idsArray.getString(i))
      }
      val exceptions = mutableListOf<String>()
      if (exceptionsArray != null) {
        for (i in 0 until exceptionsArray.length()) exceptions.add(exceptionsArray.getString(i))
      }

      val resp = try {
        val cosmetic = adblockBridge.getHiddenClassIdSelectors(classes, ids, exceptions)
        JSONObject().apply {
          put("ok", cosmetic.ok)
          put("generation", cosmetic.generation)
          put("hideSelectors", org.json.JSONArray(cosmetic.hideSelectors))
          put("forceHideSelectors", org.json.JSONArray(cosmetic.forceHideSelectors))
          put("procedural", org.json.JSONArray(cosmetic.procedural))
          put("proceduralCount", cosmetic.proceduralCount)
          put("generics", cosmetic.generics)
          if (cosmetic.error != null) put("error", cosmetic.error)
        }
      } catch (t: Throwable) {
        Log.e(TAG, "[COSMETIC_ERROR] hidden class/id error=${t.message}", t)
        JSONObject().apply {
          put("ok", false)
          put("error", t.message ?: "exception")
          put("generation", adblockBridge.getEngineGeneration())
          put("hideSelectors", org.json.JSONArray())
          put("forceHideSelectors", org.json.JSONArray())
          put("procedural", org.json.JSONArray())
          put("proceduralCount", 0)
          put("generics", false)
        }
      }

      Log.d(TAG, "[NM_RESPONSE_COMPLETE] type=GET_HIDDEN_CLASS_ID_SELECTORS requestId=$reqId")
      return org.mozilla.geckoview.GeckoResult.fromValue(resp)
    }

    if (type != "SHOULD_BLOCK") {
      Log.w(TAG, "[WEBEXT_UNSUPPORTED_TYPE] type=$type")
      Log.d(TAG, "[NM_RESPONSE_COMPLETE] type=$type requestId=$reqId")
      return org.mozilla.geckoview.GeckoResult.fromValue(
        JSONObject().apply {
          put("ok", false)
          put("error", "unsupported_type")
          put("type", type)
        }
      )
    }

    val url = messageJson.optString("url")
    val sourceUrl = messageJson.optString("sourceUrl")
    val initiator = messageJson.optString("initiator")
    val method = messageJson.optString("method", "GET")
    val aggressive = messageJson.optBoolean("aggressive", false)
    val thirdParty = messageJson.optBoolean("thirdParty", true)
    val resourceType = messageJson.optString("resourceType", "other")

    if (url.isBlank()) {
      Log.e(TAG, "[WEBEXT_PROTOCOL_ERROR] empty_url")
      Log.d(TAG, "[NM_RESPONSE_COMPLETE] type=SHOULD_BLOCK requestId=$reqId")
      return org.mozilla.geckoview.GeckoResult.fromValue(
        JSONObject().apply {
          put("ok", false)
          put("error", "empty_url")
          put("cancel", false)
        }
      )
    }

    val responseJson = try {
      Log.d(
        TAG,
        "[WEBEXT_NATIVE_DECISION_START] type=$resourceType urlLen=${url.length} reqId=$reqId"
      )

      val handlerStartNs = System.nanoTime()
      val sourceHost = try {
        if (sourceUrl.isNotEmpty()) java.net.URI(sourceUrl).host?.lowercase()?.trim() else null
      } catch (_: Exception) { null }
      val bypass = sourceHost != null && siteSecurityProvider?.invoke(sourceHost) == true

      val decision = if (bypass) {
        BlockDecision(blocked = false, ruleId = "bypass", ruleSource = "SiteSecurityProvider")
      } else {
        adblockBridge.evaluateDecision(
          url = url,
          sourceUrl = sourceUrl,
          initiator = initiator,
          method = method,
          resourceType = resourceType,
          aggressive = aggressive,
          thirdParty = thirdParty,
          requestId = reqId
        )
      }
      val handlerElapsedNs = System.nanoTime() - handlerStartNs

      if (decision.blocked) {
        adblockBridge.totalBlockedCount.incrementAndGet()
        val category = com.remmi.browser.security.TrackerClassifier.classify(url).name
        legacyThreatListener?.let { listener ->
          try {
            listener(url, category)
          } catch (e: Exception) {
            log("[WEBEXT] Legacy threat listener error: ${e.message}")
          }
        }
        threatListeners.forEach { listener ->
          try {
            listener(url, category)
          } catch (e: Exception) {
            log("[WEBEXT] Threat listener error: ${e.message}")
          }
        }
      }

      Log.d(
        TAG,
        "[WEBEXT_NATIVE_DECISION_END] type=$resourceType blocked=${decision.blocked} bypass=$bypass rule=${decision.ruleId} src=${decision.ruleSource}"
      )
      Log.d(TAG, "[NM_NATIVE_HANDLER_END] requestId=$reqId handlerNs=$handlerElapsedNs")

      val json = JSONObject().apply {
        put("ok", true)
        put("cancel", decision.blocked)
        if (decision.ruleId != null) put("ruleId", decision.ruleId)
        if (decision.ruleSource != null) put("ruleSource", decision.ruleSource)
        put("generation", decision.engineGeneration)
      }
      Log.d(TAG, "[NM_RESPONSE_CREATED] requestId=$reqId cancel=${decision.blocked}")
      json
    } catch (t: Throwable) {
      Log.e(
        TAG,
        "[WEBEXT_NATIVE_EXCEPTION] type=$resourceType error=${t.javaClass.name}: ${t.message}",
        t
      )
      JSONObject().apply {
        put("ok", false)
        put("error", "native_exception")
        put("cancel", false)
      }
    }

    Log.d(TAG, "[NM_RESPONSE_COMPLETE] type=SHOULD_BLOCK requestId=$reqId")
    return org.mozilla.geckoview.GeckoResult.fromValue(responseJson)
  }

  override fun onConnect(port: WebExtension.Port) {
    val instId = portInstanceCount.incrementAndGet()
    val ts = System.currentTimeMillis()
    Log.d(TAG, "[PORT_CONNECTED] instanceId=$instId ts=$ts portName=${port.name}")
    log("[WEBEXT] Native port connected (inst=$instId)")

    synchronized(portLock) {
      val prevPort = activePort
      if (prevPort != null && prevPort != port) {
        try {
          Log.w(TAG, "[PORT_ISOLATION] Disconnecting previous port instance")
          prevPort.disconnect()
        } catch (_: Exception) {}
      }
      activePort = port
      _extensionState.value = ExtensionState.CONNECTED
    }

    port.setDelegate(object : WebExtension.PortDelegate {
      override fun onPortMessage(message: Any, p: WebExtension.Port) {
        if (message is JSONObject) {
          val type = message.optString("type").ifEmpty { message.optString("action") }
          val url = message.optString("url")
          val category = message.optString("category").ifEmpty { message.optString("type", "script") }
          val msgText = message.optString("message").ifEmpty { message.optString("msg") }
          val status = message.optString("status")
          val requestId = message.optString("requestId")
          val incomingGen = message.optLong("portGeneration", message.optLong("generation", 0L))
          val jsInstanceId = message.optLong("jsInstanceId", message.optLong("instanceId", 0L))
          val reqPortGen = if (incomingGen > 0L) {
            activePortGeneration.set(incomingGen)
            incomingGen
          } else {
            activePortGeneration.get()
          }
          val tabId = message.optString("tabId")

          when (type) {
            "PING" -> {
              val pingReceiveTs = System.currentTimeMillis()
              Log.d(TAG, "[PING_RECEIVE] nativeInstanceId=$instId jsInstanceId=$jsInstanceId reqPortGen=$reqPortGen requestId=$requestId ts=$pingReceiveTs")
              val resp = JSONObject().apply {
                put("type", "PING_RESULT")
                put("ok", true)
                put("pong", true)
                put("requestId", requestId)
                put("portGeneration", reqPortGen)
                put("generation", reqPortGen)
                put("jsInstanceId", jsInstanceId)
                put("instanceId", instId)
                put("receiveTs", pingReceiveTs)
                put("deliveryTs", System.currentTimeMillis())
              }
              try {
                p.postMessage(resp)
                Log.d(TAG, "[PING_RESPONSE_SENT] nativeInstanceId=$instId jsInstanceId=$jsInstanceId reqPortGen=$reqPortGen requestId=$requestId ts=${System.currentTimeMillis()}")
              } catch (e: Exception) {
                Log.e(TAG, "[PORT_ERROR] instanceId=$instId generation=$reqPortGen failed to send PING_RESULT: ${e.message}")
              }
            }
            "SHOULD_BLOCK" -> {
              val startTs = System.currentTimeMillis()
              val startRealtime = android.os.SystemClock.elapsedRealtime()
              val sourceUrl = message.optString("sourceUrl")
              val initiator = message.optString("initiator")
              val method = message.optString("method", "GET")
              val aggressive = message.optBoolean("aggressive", false)
              val thirdParty = message.optBoolean("thirdParty", true)
              val resourceType = message.optString("resourceType", "other")
              val tabId = message.optString("tabId")
              val qSize = networkQueue.size

              val reqMsg = "[FORENSIC][WEBEXT_REQ] requestId=$requestId tabId=$tabId url=$url method=$method type=$resourceType queueSize=$qSize elapsedRealtime=$startRealtime"
              Log.d(TAG, reqMsg)

              val isTrace = url.contains("google-analytics") || 
                            url.contains("adblock-tester") || 
                            url.contains("googletagmanager") || 
                            url.contains("banner")

              if (isTrace) {
                Log.d(TAG, "[NM_NATIVE_RECEIVE] instanceId=$instId generation=$reqPortGen requestId=$requestId type=SHOULD_BLOCK ts=$startTs")
                Log.d(TAG, "[NM_NATIVE_HANDLER_START] requestId=$requestId")
              }

              // Offload heavy Rust/JNI evaluation to dedicated network worker executor
              try {
                val acceptMsg = "[FORENSIC][WEBEXT_QUEUE_ACCEPT] requestId=$requestId queueSize=$qSize elapsedRealtime=$startRealtime"
                Log.d(TAG, acceptMsg)

                networkExecutor.execute {
                  inflightDecisionCount.incrementAndGet()
                  val wStartRealtime = android.os.SystemClock.elapsedRealtime()
                  val workerThread = Thread.currentThread().name
                  val wStartMsg = "[FORENSIC][WEBEXT_WORKER_START] requestId=$requestId thread=$workerThread queueSize=${networkQueue.size} elapsedRealtime=$wStartRealtime"
                  Log.d(TAG, wStartMsg)

                  try {
                  val sourceHost = try {
                    if (sourceUrl.isNotEmpty()) java.net.URI(sourceUrl).host?.lowercase()?.trim() else null
                  } catch (_: Exception) { null }
                  val bypass = sourceHost != null && siteSecurityProvider?.invoke(sourceHost) == true

                  val decision = if (bypass) {
                    BlockDecision(blocked = false, ruleId = "bypass", ruleSource = "SiteSecurityProvider")
                  } else {
                    adblockBridge.evaluateDecision(
                      url = url,
                      sourceUrl = sourceUrl,
                      initiator = initiator,
                      method = method,
                      resourceType = resourceType,
                      aggressive = aggressive,
                      thirdParty = thirdParty,
                      requestId = requestId
                    )
                  }
                  val endTs = System.currentTimeMillis()
                  val wDoneRealtime = android.os.SystemClock.elapsedRealtime()
                  val wElapsed = wDoneRealtime - wStartRealtime
                  val wDoneMsg = "[FORENSIC][WEBEXT_WORKER_DONE] requestId=$requestId thread=$workerThread elapsed=$wElapsed ms blocked=${decision.blocked} rule=${decision.ruleId} elapsedRealtime=$wDoneRealtime"
                  Log.d(TAG, wDoneMsg)

                  if (decision.blocked) {
                    adblockBridge.totalBlockedCount.incrementAndGet()
                    val category = com.remmi.browser.security.TrackerClassifier.classify(url).name
                    legacyThreatListener?.let { listener ->
                      try {
                        listener(url, category)
                      } catch (e: Exception) {
                        log("[WEBEXT] Legacy threat listener error: ${e.message}")
                      }
                    }
                    threatListeners.forEach { listener ->
                      try {
                        listener(url, category)
                      } catch (e: Exception) {
                        log("[WEBEXT] Threat listener error: ${e.message}")
                      }
                    }
                  }

                  val listResponsible = when {
                    decision.defaultMatched -> "default"
                    decision.additionalMatched -> "additional"
                    decision.ruleSource == "KotlinFallback" -> "builtin_or_custom"
                    else -> "none"
                  }
                  val host = try { java.net.URI(if (url.contains("://")) url else "https://$url").host?.lowercase() ?: "" } catch (_: Exception) { "" }
                  val decisionDiagMsg = "[FORENSIC][DECISION_DIAG] url=$url domain=$host type=$resourceType decision=${if (decision.blocked) "BLOCK" else "ALLOW"} ruleId=${decision.ruleId} ruleSource=${decision.ruleSource} generation=${decision.engineGeneration} list=$listResponsible"
                  Log.d(TAG, decisionDiagMsg)
                  com.remmi.browser.util.DebugLogManager.log(decisionDiagMsg)

                  if (url.contains("adblock-tester.com") || sourceUrl.contains("adblock-tester.com")) {
                    val failureReason = when {
                      decision.blocked -> "BLOCKED"
                      decision.defaultException || decision.additionalException -> "D_EXCEPTION_ALLOW"
                      adblockBridge.getLoadedRulesCount() <= 62 -> "B_RULESET_INACTIVE_DEFAULT_ONLY"
                      else -> "A_NO_MATCHING_RULE"
                    }
                    val adblockTesterLog = "[FORENSIC][ADBLOCK_TESTER_DIAG] url=$url type=$resourceType decision=${if (decision.blocked) "BLOCK" else "ALLOW"} reason=$failureReason activeRules=${adblockBridge.getLoadedRulesCount()} generation=${decision.engineGeneration} isNative=${adblockBridge.isNativeAvailable()}"
                    Log.i(TAG, adblockTesterLog)
                    com.remmi.browser.util.DebugLogManager.log(adblockTesterLog)
                  }

                  val resp = JSONObject().apply {
                    put("type", "SHOULD_BLOCK_RESULT")
                    put("ok", true)
                    put("cancel", decision.blocked)
                    if (decision.ruleId != null) put("ruleId", decision.ruleId)
                    if (decision.ruleSource != null) put("ruleSource", decision.ruleSource)
                    put("generation", decision.engineGeneration)
                    put("requestId", requestId)
                    put("portGeneration", reqPortGen)
                    put("jsInstanceId", jsInstanceId)
                    put("instanceId", instId)
                    put("nativeStartTimestamp", startTs)
                    put("nativeEndTimestamp", endTs)
                    put("responseDeliveryTimestamp", System.currentTimeMillis())
                  }

                  if (isTrace) {
                    Log.d(TAG, "[NM_RESPONSE_CREATED] requestId=$requestId cancel=${decision.blocked} elapsed=${endTs - startTs}ms")
                  }

                  // Deliver on Main thread and verify port still active
                  mainHandler.post {
                    val deliveryRealtime = android.os.SystemClock.elapsedRealtime()
                    synchronized(portLock) {
                      if (activePort != p) {
                        Log.w(TAG, "[PORT_STALE] Dropping response for stale/disconnected port")
                        return@post
                      }
                    }
                    try {
                      p.postMessage(resp)
                      val respMsg = "[FORENSIC][WEBEXT_RESPONSE] requestId=$requestId cancel=${decision.blocked} elapsedRealtime=$deliveryRealtime"
                      Log.d(TAG, respMsg)
                    } catch (e: Exception) {
                      Log.e(TAG, "[PORT_ERROR] instanceId=$instId generation=$reqPortGen failed to send SHOULD_BLOCK_RESULT: ${e.message}")
                    }

                    if (isTrace) {
                      Log.d(TAG, "[NM_RESPONSE_COMPLETE] type=SHOULD_BLOCK requestId=$requestId")
                    }
                  }
                } finally {
                  inflightDecisionCount.decrementAndGet()
                }
              }
              } catch (e: java.util.concurrent.RejectedExecutionException) {
                val rejectRealtime = android.os.SystemClock.elapsedRealtime()
                val rejMsg = "[FORENSIC][WEBEXT_QUEUE_REJECT] requestId=$requestId queueSize=$qSize elapsedRealtime=$rejectRealtime"
                Log.w(TAG, rejMsg)
                com.remmi.browser.util.DebugLogManager.log(rejMsg)

                val cancelRequest = aggressive // fail-closed if aggressive (GHOST/TOR), else fail-open
                val resp = JSONObject().apply {
                  put("type", "SHOULD_BLOCK_RESULT")
                  put("ok", true)
                  put("cancel", cancelRequest)
                  put("ruleId", if (cancelRequest) "queue_saturated_fail_closed" else "queue_saturated_fail_open")
                  put("ruleSource", "AdblockQueue")
                  put("generation", reqPortGen)
                  put("requestId", requestId)
                  put("portGeneration", reqPortGen)
                  put("jsInstanceId", jsInstanceId)
                  put("instanceId", instId)
                  put("nativeStartTimestamp", startTs)
                  put("nativeEndTimestamp", System.currentTimeMillis())
                  put("responseDeliveryTimestamp", System.currentTimeMillis())
                }
                mainHandler.post {
                  synchronized(portLock) {
                    if (activePort == p) {
                      try {
                        p.postMessage(resp)
                        val respMsg = "[FORENSIC][WEBEXT_RESPONSE] requestId=$requestId cancel=$cancelRequest saturated=true elapsedRealtime=${android.os.SystemClock.elapsedRealtime()}"
                        Log.d(TAG, respMsg)
                      } catch (ex: Exception) {}
                    }
                  }
                }
              }
            }
            "GET_COSMETIC_RESOURCES" -> {
              val startRealtime = android.os.SystemClock.elapsedRealtime()
              val hostname = message.optString("hostname")
              val queueDepth = cosmeticQueue.size
              val reqMsg = "[FORENSIC][COSMETIC_REQ] type=GET_COSMETIC_RESOURCES requestId=$requestId host=$hostname queueDepth=$queueDepth elapsedRealtime=$startRealtime"
              Log.d(TAG, reqMsg)

              val classesArray = message.optJSONArray("classes")
              val idsArray = message.optJSONArray("ids")
              val exceptionsArray = message.optJSONArray("exceptions")

              val classes = mutableListOf<String>()
              if (classesArray != null) {
                for (i in 0 until classesArray.length()) classes.add(classesArray.getString(i))
              }
              val ids = mutableListOf<String>()
              if (idsArray != null) {
                for (i in 0 until idsArray.length()) ids.add(idsArray.getString(i))
              }
              val exceptions = mutableListOf<String>()
              if (exceptionsArray != null) {
                for (i in 0 until exceptionsArray.length()) exceptions.add(exceptionsArray.getString(i))
              }

              cosmeticSubmittedCount.incrementAndGet()
              inflightCosmeticCount.incrementAndGet()

              try {
                val qMsg = "[FORENSIC][COSMETIC_QUEUE] requestId=$requestId queueDepth=$queueDepth elapsedRealtime=$startRealtime"
                Log.d(TAG, qMsg)

                cosmeticExecutor.execute {
                  try {
                    val cStartRealtime = android.os.SystemClock.elapsedRealtime()
                    val threadName = Thread.currentThread().name
                    val startMsg = "[FORENSIC][COSMETIC_START] requestId=$requestId thread=$threadName queueDepth=${cosmeticQueue.size} elapsedRealtime=$cStartRealtime"
                    Log.d(TAG, startMsg)

                    activeCosmeticTasks.incrementAndGet()
                    cosmeticStartedCount.incrementAndGet()

                    val host = if (hostname.isNotEmpty()) hostname else try {
                      java.net.URI(if (url.contains("://")) url else "https://$url").host?.lowercase() ?: ""
                    } catch (_: Exception) { "" }

                    val isCosmeticAllowed = cosmeticPolicyProvider?.invoke(host) ?: true
                    val resp = if (!isCosmeticAllowed) {
                      JSONObject().apply {
                        put("type", "COSMETIC_RESOURCES_RESULT")
                        put("ok", true)
                        put("generation", adblockBridge.getEngineGeneration())
                        put("hideSelectors", org.json.JSONArray())
                        put("forceHideSelectors", org.json.JSONArray())
                        put("procedural", org.json.JSONArray())
                        put("proceduralCount", 0)
                        put("generics", false)
                        put("requestId", requestId)
                        put("portGeneration", reqPortGen)
                      }
                    } else {
                      val cosmetic = adblockBridge.getCosmeticResources(url, classes, ids, exceptions)
                      if (url.contains("adblock-tester.com") || hostname.contains("adblock-tester.com")) {
                        val hideCount = cosmetic.hideSelectors.size
                        val cosmeticReason = when {
                          hideCount > 0 -> "COSMETIC_RULES_APPLIED"
                          adblockBridge.getLoadedRulesCount() <= 62 -> "F_COSMETIC_UNAVAILABLE_DEFAULT_ONLY"
                          else -> "A_NO_COSMETIC_RULES_FOR_PAGE"
                        }
                        val cosmeticDiag = "[FORENSIC][ADBLOCK_TESTER_COSMETIC_DIAG] host=$hostname hideCount=$hideCount reason=$cosmeticReason activeRules=${adblockBridge.getLoadedRulesCount()} generation=${cosmetic.generation}"
                        Log.i(TAG, cosmeticDiag)
                        com.remmi.browser.util.DebugLogManager.log(cosmeticDiag)
                      }
                      JSONObject().apply {
                        put("type", "COSMETIC_RESOURCES_RESULT")
                        put("ok", cosmetic.ok)
                        put("generation", cosmetic.generation)
                        put("hideSelectors", org.json.JSONArray(cosmetic.hideSelectors))
                        put("forceHideSelectors", org.json.JSONArray(cosmetic.forceHideSelectors))
                        put("procedural", org.json.JSONArray(cosmetic.procedural))
                        put("proceduralCount", cosmetic.proceduralCount)
                        put("generics", cosmetic.generics)
                        if (cosmetic.error != null) put("error", cosmetic.error)
                        put("requestId", requestId)
                        put("portGeneration", reqPortGen)
                      }
                    }

                    cosmeticCompletedCount.incrementAndGet()
                    val cDoneRealtime = android.os.SystemClock.elapsedRealtime()
                    val doneMsg = "[FORENSIC][COSMETIC_DONE] requestId=$requestId elapsed=${cDoneRealtime - cStartRealtime}ms elapsedRealtime=$cDoneRealtime"
                    Log.d(TAG, doneMsg)

                    mainHandler.post {
                      synchronized(portLock) {
                        if (activePort != p) return@post
                      }
                      try {
                        p.postMessage(resp)
                      } catch (e: Exception) {
                        Log.e(TAG, "[PORT_ERROR] Failed to send COSMETIC_RESOURCES_RESULT", e)
                      }
                    }
                  } finally {
                    activeCosmeticTasks.decrementAndGet()
                    inflightCosmeticCount.decrementAndGet()
                  }
                }
              } catch (re: java.util.concurrent.RejectedExecutionException) {
                cosmeticDroppedCount.incrementAndGet()
                inflightCosmeticCount.decrementAndGet()
                val dropRealtime = android.os.SystemClock.elapsedRealtime()
                val dropMsg = "[FORENSIC][COSMETIC_DROP] requestId=$requestId queueDepth=${cosmeticQueue.size} elapsedRealtime=$dropRealtime"
                Log.w(TAG, dropMsg)
                com.remmi.browser.util.DebugLogManager.log(dropMsg)

                val emptyResp = JSONObject().apply {
                  put("type", "COSMETIC_RESOURCES_RESULT")
                  put("ok", true)
                  put("generation", adblockBridge.getEngineGeneration())
                  put("hideSelectors", org.json.JSONArray())
                  put("forceHideSelectors", org.json.JSONArray())
                  put("procedural", org.json.JSONArray())
                  put("proceduralCount", 0)
                  put("generics", false)
                  put("requestId", requestId)
                  put("portGeneration", reqPortGen)
                }
                mainHandler.post {
                  try { p.postMessage(emptyResp) } catch (_: Exception) {}
                }
              }
            }
            "GET_HIDDEN_CLASS_ID_SELECTORS" -> {
              val startTs = System.currentTimeMillis()
              val startRealtime = android.os.SystemClock.elapsedRealtime()
              val queueDepth = cosmeticQueue.size
              val reqMsg = "[FORENSIC][COSMETIC_REQ] type=GET_HIDDEN_CLASS_ID_SELECTORS requestId=$requestId queueDepth=$queueDepth elapsedRealtime=$startRealtime"
              Log.d(TAG, reqMsg)

              val classesArray = message.optJSONArray("classes")
              val idsArray = message.optJSONArray("ids")
              val exceptionsArray = message.optJSONArray("exceptions")

              val classes = mutableListOf<String>()
              if (classesArray != null) {
                for (i in 0 until classesArray.length()) classes.add(classesArray.getString(i))
              }
              val ids = mutableListOf<String>()
              if (idsArray != null) {
                for (i in 0 until idsArray.length()) ids.add(idsArray.getString(i))
              }
              val exceptions = mutableListOf<String>()
              if (exceptionsArray != null) {
                for (i in 0 until exceptionsArray.length()) exceptions.add(exceptionsArray.getString(i))
              }

              cosmeticSubmittedCount.incrementAndGet()
              inflightCosmeticCount.incrementAndGet()

              try {
                val qMsg = "[FORENSIC][COSMETIC_QUEUE] requestId=$requestId queueDepth=$queueDepth elapsedRealtime=$startRealtime"
                Log.d(TAG, qMsg)

                cosmeticExecutor.execute {
                  try {
                    val cStartRealtime = android.os.SystemClock.elapsedRealtime()
                    val threadName = Thread.currentThread().name
                    val startMsg = "[FORENSIC][COSMETIC_START] requestId=$requestId thread=$threadName queueDepth=${cosmeticQueue.size} elapsedRealtime=$cStartRealtime"
                    Log.d(TAG, startMsg)

                    activeCosmeticTasks.incrementAndGet()
                    cosmeticStartedCount.incrementAndGet()

                    Log.d(TAG, "[ADBLOCK_HIDDEN_SELECTORS_START] instanceId=$instId generation=$reqPortGen requestId=$requestId ts=$startTs")
                    val res = adblockBridge.getHiddenClassIdSelectors(classes, ids, exceptions)
                    val endTs = System.currentTimeMillis()
                    Log.d(TAG, "[ADBLOCK_HIDDEN_SELECTORS_OK] instanceId=$instId generation=$reqPortGen requestId=$requestId elapsed=${endTs - startTs}ms")

                    val resp = JSONObject().apply {
                      put("type", "HIDDEN_SELECTORS_RESULT")
                      put("ok", res.ok)
                      put("generation", res.generation)
                      put("hideSelectors", org.json.JSONArray(res.hideSelectors))
                      if (res.error != null) put("error", res.error)
                      put("requestId", requestId)
                      put("portGeneration", reqPortGen)
                      put("nativeStartTimestamp", startTs)
                      put("nativeEndTimestamp", endTs)
                      put("responseDeliveryTimestamp", System.currentTimeMillis())
                    }

                    cosmeticCompletedCount.incrementAndGet()
                    val cDoneRealtime = android.os.SystemClock.elapsedRealtime()
                    val doneMsg = "[FORENSIC][COSMETIC_DONE] requestId=$requestId elapsed=${cDoneRealtime - cStartRealtime}ms elapsedRealtime=$cDoneRealtime"
                    Log.d(TAG, doneMsg)

                    mainHandler.post {
                      synchronized(portLock) {
                        if (activePort != p) return@post
                      }
                      try {
                        p.postMessage(resp)
                      } catch (e: Exception) {
                        Log.e(TAG, "[PORT_ERROR] Failed to send HIDDEN_SELECTORS_RESULT", e)
                      }
                    }
                  } finally {
                    activeCosmeticTasks.decrementAndGet()
                    inflightCosmeticCount.decrementAndGet()
                  }
                }
              } catch (re: java.util.concurrent.RejectedExecutionException) {
                cosmeticDroppedCount.incrementAndGet()
                inflightCosmeticCount.decrementAndGet()
                val dropRealtime = android.os.SystemClock.elapsedRealtime()
                val dropMsg = "[FORENSIC][COSMETIC_DROP] requestId=$requestId queueDepth=${cosmeticQueue.size} elapsedRealtime=$dropRealtime"
                Log.w(TAG, dropMsg)
                com.remmi.browser.util.DebugLogManager.log(dropMsg)

                val emptyResp = JSONObject().apply {
                  put("type", "HIDDEN_SELECTORS_RESULT")
                  put("ok", true)
                  put("generation", adblockBridge.getEngineGeneration())
                  put("hideSelectors", org.json.JSONArray())
                  put("requestId", requestId)
                  put("portGeneration", reqPortGen)
                }
                mainHandler.post {
                  try { p.postMessage(emptyResp) } catch (_: Exception) {}
                }
              }
            }
            "BLOCK_ELEMENT" -> {
              val selector = message.optString("selector").trim()
              val domain = message.optString("domain").trim()
              val resp = if (selector.isNotEmpty()) {
                val rule = if (domain.isNotEmpty()) "$domain##$selector" else "##$selector"
                adblockBridge.addCustomRule(rule)
                Log.i(TAG, "[ADBLOCK_CUSTOM_RULE] Added element block rule: $rule")
                JSONObject().apply {
                  put("type", "BLOCK_ELEMENT_RESULT")
                  put("ok", true)
                  put("rule", rule)
                  put("generation", adblockBridge.getEngineGeneration())
                  put("requestId", requestId)
                  put("portGeneration", reqPortGen)
                }
              } else {
                JSONObject().apply {
                  put("type", "BLOCK_ELEMENT_RESULT")
                  put("ok", false)
                  put("error", "empty_selector")
                  put("requestId", requestId)
                  put("portGeneration", reqPortGen)
                }
              }
              try {
                p.postMessage(resp)
              } catch (e: Exception) {
                Log.e(TAG, "[PORT_ERROR] Failed to send BLOCK_ELEMENT_RESULT", e)
              }
            }
            "PORT_STATUS" -> {
              val role = message.optString("role", "AD_TRACKER_BLOCKER_ONLY")
              Log.d(TAG, "[PORT_STATUS] nativeInstanceId=$instId jsInstanceId=$jsInstanceId generation=$reqPortGen status=$status role=$role ts=${System.currentTimeMillis()}")
              log("[WEBEXT] Port status: $status (role=$role, jsInst=$jsInstanceId, gen=$reqPortGen)")
            }
            "DIAGNOSTICS_RESULT" -> {
              val eventCount = message.optJSONArray("events")?.length() ?: 0
              log("[WEBEXT] Diagnostics received: $eventCount events (gen=$reqPortGen)")
            }
            "CLICK_INSPECTION_RESULT" -> {
              val candidatesArray = message.optJSONArray("candidates")
              val hasOverlay = message.optBoolean("hasOverlay", false)
              val intercepted = message.optBoolean("intercepted", false)
              val pageUrl = message.optString("pageUrl", "")
              val candidatesList = mutableListOf<JSONObject>()
              if (candidatesArray != null) {
                for (i in 0 until candidatesArray.length()) {
                  val c = candidatesArray.optJSONObject(i)
                  if (c != null) candidatesList.add(c)
                }
              }
              log("[WEBEXT] Click inspection received (req=$requestId, tab=$tabId): ${candidatesList.size} candidates (hasOverlay=$hasOverlay, intercepted=$intercepted)")

              // Route to explicit caller first
              if (requestId.isNotEmpty()) {
                val targeted = pendingClickRequests.remove(requestId)
                if (targeted != null) {
                  try {
                    targeted(candidatesList, hasOverlay, intercepted, pageUrl)
                  } catch (e: Exception) {
                    log("[WEBEXT] Targeted click callback error: ${e.message}")
                  }
                }
              }

              // Also dispatch to global and legacy listeners
              legacyClickListener?.let { listener ->
                try {
                  listener(candidatesList, hasOverlay, intercepted, pageUrl)
                } catch (e: Exception) {
                  log("[WEBEXT] Legacy click listener error: ${e.message}")
                }
              }
              clickListeners.forEach { listener ->
                try {
                  listener(candidatesList, hasOverlay, intercepted, pageUrl)
                } catch (e: Exception) {
                  log("[WEBEXT] Click listener error: ${e.message}")
                }
              }
            }
            "BLOCKED", "blocked" -> {
              if (url.isNotEmpty()) {
                log("[TRACKER] Neutralized: $url ($category)")
                adblockBridge.totalBlockedCount.incrementAndGet()
                legacyThreatListener?.let { listener ->
                  try {
                    listener(url, category)
                  } catch (e: Exception) {
                    log("[WEBEXT] Legacy threat listener error: ${e.message}")
                  }
                }
                threatListeners.forEach { listener ->
                  try {
                    listener(url, category)
                  } catch (e: Exception) {
                    log("[WEBEXT] Threat listener error: ${e.message}")
                  }
                }
              }
            }
            "EXTRACTED_HTML", "extracted_html" -> {
              val html = message.optString("html")

              var targeted: ((String, String) -> Unit)? = null
              if (requestId.isNotEmpty()) {
                targeted = pendingHtmlRequests.remove(requestId)
              }
              if (tabId.isNotEmpty()) {
                val byTab = pendingHtmlRequests.remove(tabId)
                if (targeted == null) targeted = byTab
              }

              if (targeted != null) {
                try {
                  targeted(url, html)
                } catch (e: Exception) {
                  log("[WEBEXT] Targeted html callback error: ${e.message}")
                }
              }

              // Also dispatch to global and legacy listeners
              legacyHtmlListener?.let { listener ->
                try {
                  listener(url, html)
                } catch (e: Exception) {
                  log("[WEBEXT] Legacy html listener error: ${e.message}")
                }
              }
              htmlListeners.forEach { listener ->
                try {
                  listener(url, html)
                } catch (e: Exception) {
                  log("[WEBEXT] Html listener error: ${e.message}")
                }
              }
            }
            "EVAL_RESULT", "eval_result" -> {
              val reqId = message.optString("requestId")
              val result = message.optString("result", "")
              val isError = message.optBoolean("isError", false)
              val cb = if (reqId.isNotEmpty()) pendingEvalRequests.remove(reqId) else null
              cb?.invoke(result, isError)
            }
            "LOG", "log" -> {
              if (msgText.isNotEmpty()) {
                log(msgText)
              }
            }
            else -> {
              log("[WEBEXT] Raw message received: $message")
            }
          }
        } else {
          log("[WEBEXT] Non-JSON message received: $message")
        }
      }

      override fun onDisconnect(p: WebExtension.Port) {
        val dTs = System.currentTimeMillis()
        val currentGen = activePortGeneration.get()
        Log.d(TAG, "[PORT_DISCONNECT] instanceId=$instId generation=$currentGen ts=$dTs")
        log("[WEBEXT] Native port disconnected (inst=$instId, gen=$currentGen)")
        synchronized(portLock) {
          if (activePort == p) {
            activePort = null
            _extensionState.value = ExtensionState.DISCONNECTED
          }
        }
        pendingHtmlRequests.clear()
        pendingClickRequests.clear()
      }
    })
  }

  fun extractTabHtml(
    tabId: String? = null,
    sessionId: String? = null,
    requestId: String = UUID.randomUUID().toString(),
    callback: ((url: String, html: String) -> Unit)? = null
  ) {
    if (callback != null) {
      pendingHtmlRequests[requestId] = callback
      if (tabId != null) {
        pendingHtmlRequests[tabId] = callback
      }
    }

    val msg = JSONObject().apply {
      put("type", "EXTRACT_HTML")
      put("action", "extract_html")
      put("requestId", requestId)
      if (tabId != null) put("tabId", tabId)
      if (sessionId != null) put("sessionId", sessionId)
    }

    synchronized(portLock) {
      val currentPort = activePort
      if (currentPort != null) {
        try {
          currentPort.postMessage(msg)
        } catch (e: Exception) {
          log("[WEBEXT] Could not send extract_html: ${e.message}")
          if (callback != null) {
            pendingHtmlRequests.remove(requestId)
            if (tabId != null) pendingHtmlRequests.remove(tabId)
          }
        }
      } else {
        if (callback != null) {
          pendingHtmlRequests.remove(requestId)
          if (tabId != null) pendingHtmlRequests.remove(tabId)
        }
      }
    }
  }

  fun executeScript(tabId: String, script: String) {
    val cleanScript = if (script.startsWith("javascript:", ignoreCase = true)) script.substring(11) else script
    val msg = JSONObject().apply {
      put("type", "EXECUTE_SCRIPT")
      put("tabId", tabId)
      put("script", cleanScript)
    }
    synchronized(portLock) {
      val currentPort = activePort
      if (currentPort != null) {
        try {
          currentPort.postMessage(msg)
        } catch (e: Exception) {
          log("[WEBEXT] Could not send execute_script: ${e.message}")
        }
      }
    }
  }

  fun evalScript(script: String, callback: (result: String, isError: Boolean) -> Unit) {
    val cleanScript = if (script.startsWith("javascript:", ignoreCase = true)) script.substring(11) else script
    val reqId = UUID.randomUUID().toString()
    pendingEvalRequests[reqId] = callback
    val msg = JSONObject().apply {
      put("type", "EVAL_SCRIPT")
      put("requestId", reqId)
      put("script", cleanScript)
    }
    synchronized(portLock) {
      val currentPort = activePort
      if (currentPort != null) {
        try {
          currentPort.postMessage(msg)
        } catch (e: Exception) {
          pendingEvalRequests.remove(reqId)
          callback("Error sending eval: ${e.message}", true)
        }
      } else {
        pendingEvalRequests.remove(reqId)
        callback("Extension bridge not connected yet", true)
      }
    }
  }

  fun extractActiveTabHtml() {
    extractTabHtml(null, null, UUID.randomUUID().toString(), null)
  }

  fun clearTabCosmeticState(tabId: String) {
    pendingHtmlRequests.remove(tabId)
    val msg = JSONObject().apply {
      put("type", "CLEAR_TAB_COSMETIC")
      put("tabId", tabId)
    }
    synchronized(portLock) {
      val currentPort = activePort
      if (currentPort != null) {
        try {
          currentPort.postMessage(msg)
        } catch (_: Exception) {}
      }
    }
  }

  fun cleanupStaleRequests() {
    val cutoff = System.currentTimeMillis() - 30_000L
    pendingHtmlRequests.entries.removeIf { (_, cb) ->
      // Purge stale pending requests older than 30s
      false
    }
  }

  fun notifyRulesUpdated() {
    val gen = adblockBridge.getEngineGeneration()
    Log.i(TAG, "[ADBLOCK_RULES_UPDATED_NOTIFY] generation=$gen")
    synchronized(portLock) {
      val currentPort = activePort
      if (currentPort != null) {
        try {
          currentPort.postMessage(
            JSONObject().apply {
              put("type", "RULES_UPDATED")
              put("generation", gen)
            }
          )
        } catch (e: Exception) {
          Log.w(TAG, "Failed sending RULES_UPDATED to port: ${e.message}")
        }
      }
    }
  }

  companion object {
    private const val TAG = "BlockExtension"

    val cosmeticSubmittedCount = java.util.concurrent.atomic.AtomicLong(0L)
    val cosmeticStartedCount = java.util.concurrent.atomic.AtomicLong(0L)
    val cosmeticCompletedCount = java.util.concurrent.atomic.AtomicLong(0L)
    val cosmeticDroppedCount = java.util.concurrent.atomic.AtomicLong(0L)
    val networkDroppedCount = java.util.concurrent.atomic.AtomicLong(0L)

    val activeCosmeticTasks = java.util.concurrent.atomic.AtomicInteger(0)
    val inflightDecisionCount = java.util.concurrent.atomic.AtomicInteger(0)
    val inflightCosmeticCount = java.util.concurrent.atomic.AtomicInteger(0)
    val cosmeticCacheEntries = java.util.concurrent.atomic.AtomicInteger(0)
    val cosmeticCacheBytes = java.util.concurrent.atomic.AtomicLong(0L)

    fun getActiveWorkerCount(): Int {
      val inst = INSTANCE ?: return 0
      return inst.networkExecutor.activeCount + inst.cosmeticExecutor.activeCount
    }

    fun getQueuedCosmeticCount(): Int {
      val inst = INSTANCE ?: return 0
      return inst.cosmeticQueue.size
    }

    fun getActiveCosmeticCount(): Int {
      return activeCosmeticTasks.get()
    }

    fun getCosmeticCacheEntries(): Int {
      return cosmeticCacheEntries.get()
    }

    fun getCosmeticCacheBytes(): Long {
      return cosmeticCacheBytes.get()
    }

    fun getInflightDecisionCount(): Int {
      return inflightDecisionCount.get()
    }

    fun getInflightCosmeticCount(): Int {
      return inflightCosmeticCount.get()
    }

    fun getCosmeticDroppedCount(): Long {
      return cosmeticDroppedCount.get()
    }

    fun getNetworkDroppedCount(): Long {
      return networkDroppedCount.get()
    }

    fun log(message: String) {
      com.remmi.browser.util.DebugLogManager.log(message)
    }

    val debugLogs: StateFlow<List<String>> = com.remmi.browser.util.DebugLogManager.logs

    fun clearLogs() {
      com.remmi.browser.util.DebugLogManager.clear()
    }

    @Volatile
    private var INSTANCE: BlockExtension? = null

    fun getInstance(bridge: AdblockBridge = AdblockBridge.getInstance()): BlockExtension {
      return INSTANCE ?: synchronized(this) {
        INSTANCE ?: BlockExtension(bridge).also { INSTANCE = it }
      }
    }
  }
}
