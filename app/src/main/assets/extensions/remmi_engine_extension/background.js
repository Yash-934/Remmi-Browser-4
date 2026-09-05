// Remmi Engine WebExtension - Dedicated Ad/Tracker Blocker & Click Transparency Bridge
// CRITICAL SECURITY INVARIANT: Native Gecko layer is the SOLE authoritative proxy manager.
// The WebExtension does NOT modify browser.proxy or route settings.

// Port Health State Machine: DISCONNECTED -> CONNECTING -> CONNECTED -> HEALTHY / DEGRADED
console.log("[JS_CONTEXT_RESTART] Background script loaded or restarted. Initializing state...");
let port = null;
let portState = "DISCONNECTED";
let portGeneration = 0;
let jsInstanceId = 0;
let isConnecting = false;
let reconnectTimer = null;
let reqSequence = 0;

const pendingRequests = new Map();

// Local In-Memory Diagnostic Ring Buffer (Max 200 events, strictly ZERO port IPC overhead)
const MAX_DIAGNOSTIC_EVENTS = 200;
const DIAGNOSTIC_RING_BUFFER = [];

function recordDiagnostic(msg, extra = null) {
  const entry = {
    ts: Date.now(),
    msg: typeof msg === "string" ? msg : JSON.stringify(msg)
  };
  if (extra) entry.extra = extra;
  if (DIAGNOSTIC_RING_BUFFER.length >= MAX_DIAGNOSTIC_EVENTS) {
    DIAGNOSTIC_RING_BUFFER.shift();
  }
  DIAGNOSTIC_RING_BUFFER.push(entry);
  try {
    console.debug(`[Remmi Engine] ${entry.msg}`);
  } catch (_e) {}
}

function logToNative(msg) {
  // STRICT PURIFICATION: In-memory logging only. NEVER call port.postMessage({type: "LOG"})!
  recordDiagnostic(msg);
}

function isTraceCandidateUrl(url) {
  if (!url || typeof url !== "string") return false;
  return (
    url.includes("google-analytics") ||
    url.includes("adblock-tester") ||
    url.includes("googletagmanager") ||
    url.includes("banner")
  );
}

// Exactly-once correlated messaging on the persistent native port
function sendPortMessage(type, payload = {}, timeoutMs = 1500, timeoutErrorName = "NATIVE_RESPONSE_TIMEOUT") {
  return new Promise((resolve, reject) => {
    // If port is not healthy and this is not an initial PING handshake, reject early
    if (portState !== "HEALTHY" && type !== "PING") {
      const err = new Error(`port_not_ready_${portState}`);
      err.name = portState === "DISCONNECTED" ? "PORT_DISCONNECTED" : "PORT_NOT_READY";
      return reject(err);
    }

    if (!port) {
      const err = new Error("port_disconnected");
      err.name = "PORT_DISCONNECTED";
      return reject(err);
    }

    const currentGen = portGeneration;
    const currentInst = jsInstanceId;
    const reqId = `${type.toLowerCase()}_${++reqSequence}_${Date.now()}`;
    let isSettled = false;

    const timer = setTimeout(() => {
      if (isSettled) return;
      isSettled = true;
      pendingRequests.delete(reqId);
      const err = new Error(timeoutErrorName);
      err.name = timeoutErrorName === "NATIVE_DECISION_TIMEOUT" ? "NATIVE_MESSAGE_QUEUE_TIMEOUT" : timeoutErrorName;
      reject(err);
    }, timeoutMs);

    pendingRequests.set(reqId, {
      resolve: (data) => {
        if (isSettled) return;
        isSettled = true;
        clearTimeout(timer);
        pendingRequests.delete(reqId);
        resolve(data);
      },
      reject: (err) => {
        if (isSettled) return;
        isSettled = true;
        clearTimeout(timer);
        pendingRequests.delete(reqId);
        reject(err);
      },
      timer: timer,
      portGeneration: currentGen,
      jsInstanceId: currentInst,
      sendTs: Date.now(),
      type: type
    });

    try {
      port.postMessage({
        type: type,
        requestId: reqId,
        portGeneration: currentGen,
        generation: currentGen,
        jsInstanceId: currentInst,
        ...payload
      });
    } catch (sendErr) {
      if (isSettled) return;
      isSettled = true;
      clearTimeout(timer);
      pendingRequests.delete(reqId);
      const err = new Error("port_send_failed");
      err.name = "PORT_DISCONNECTED";
      reject(err);
    }
  });
}

// 1. Initial Handshake & Diagnostic PING
async function executeInitialPing(instanceId, generation) {
  const pingStart = Date.now();
  logToNative(`[PING_SEND] instanceId=${instanceId} generation=${generation} ts=${pingStart}`);
  try {
    const res = await sendPortMessage("PING", {}, 2000, "PING_TIMEOUT");
    const pingElapsed = Date.now() - pingStart;
    if (res && res.ok === true && res.pong === true) {
      if (portGeneration === generation) {
        portState = "HEALTHY";
        logToNative(`[PING_SUCCESS] instanceId=${instanceId} generation=${generation} elapsedMs=${pingElapsed}`);
        logToNative(`[PORT_RECONNECT_SUCCESS] instanceId=${instanceId} generation=${generation} ts=${Date.now()}`);
      }
      return true;
    } else {
      portState = "DEGRADED";
      logToNative(`[PORT_ERROR] instanceId=${instanceId} generation=${generation} error=invalid_ping_response`);
      return false;
    }
  } catch (pingErr) {
    portState = "DEGRADED";
    logToNative(`[PORT_ERROR] instanceId=${instanceId} generation=${generation} error=${pingErr?.name || pingErr?.message || String(pingErr)}`);
    return false;
  }
}

async function runPingBenchmark(count = 100) {
  const latencies = [];
  let successes = 0;
  let failures = 0;

  for (let i = 0; i < count; i++) {
    const reqId = `ping_bm_${i}_${Date.now()}`;
    const start = Date.now();
    try {
      logToNative(`[NM_SEND_START] requestId=${reqId} messageType=PING`);
      const res = await sendPortMessage("PING", {}, 1500, "NATIVE_RESPONSE_TIMEOUT");
      const elapsed = Date.now() - start;
      if (res && res.ok === true && res.pong === true) {
        successes++;
        latencies.push(elapsed);
        logToNative(`[NM_SEND_SUCCESS] requestId=${reqId} responseOk=true elapsedMs=${elapsed}`);
      } else {
        failures++;
        logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=INVALID_RESPONSE errorMessage=bad_pong`);
      }
    } catch (e) {
      failures++;
      const errName = e?.name || "NATIVE_MESSAGE_FAILURE";
      logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=${errName} errorMessage=${e?.message || String(e)}`);
    }
  }

  latencies.sort((a, b) => a - b);
  const p50 = latencies.length > 0 ? latencies[Math.floor(latencies.length * 0.5)] : 0;
  const p95 = latencies.length > 0 ? latencies[Math.floor(latencies.length * 0.95)] : 0;
  const max = latencies.length > 0 ? latencies[latencies.length - 1] : 0;

  logToNative(`[WEBEXT_PING_BENCHMARK] count=${count} success=${successes} failure=${failures} p50=${p50}ms p95=${p95}ms max=${max}ms`);
  return { successes, failures, p50, p95, max };
}

// 2. Native Port Connection & Lifecycle Management
function connectNative() {
  if (isConnecting) return;
  isConnecting = true;
  portState = "CONNECTING";

  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  portGeneration++;
  const currentGen = portGeneration;
  const currentInst = ++jsInstanceId;
  const startTs = Date.now();

  logToNative(`[PORT_CONNECT_START] instanceId=${currentInst} generation=${currentGen} ts=${startTs}`);

  // Discard any existing pending requests from previous generation
  for (const [rId, entry] of pendingRequests.entries()) {
    clearTimeout(entry.timer);
    const err = new Error("port_reconnecting");
    err.name = "PORT_DISCONNECTED";
    entry.reject(err);
  }
  pendingRequests.clear();

  try {
    port = browser.runtime.connectNative("remmi_engine_extension");
    if (!port) {
      logToNative(`[PORT_RECONNECT_FAILURE] instanceId=${currentInst} generation=${currentGen} ts=${Date.now()} error=null_port`);
      portState = "DISCONNECTED";
      isConnecting = false;
      if (!reconnectTimer) {
        reconnectTimer = setTimeout(connectNative, 3000);
      }
      return;
    }

    portState = "CONNECTED";
    logToNative(`[PORT_CONNECTED] instanceId=${currentInst} generation=${currentGen} ts=${Date.now()}`);

    try {
      port.postMessage({
        type: "PORT_STATUS",
        status: "CONNECTED",
        role: "AD_TRACKER_BLOCKER_ONLY",
        jsInstanceId: currentInst,
        instanceId: currentInst,
        portGeneration: currentGen,
        generation: currentGen
      });
    } catch (_err) {}

    port.onMessage.addListener((msg) => {
      if (!msg) return;

      // Check if message is a response to a pending request
      if (msg.requestId && pendingRequests.has(msg.requestId)) {
        const entry = pendingRequests.get(msg.requestId);
        if (msg.portGeneration !== undefined && msg.portGeneration !== entry.portGeneration) {
          // Discard response belonging to old port generation safely
          return;
        }

        if (msg.ok === false) {
          const err = new Error(msg.error || "NATIVE_HANDLER_ERROR");
          err.name = "NATIVE_HANDLER_ERROR";
          entry.reject(err);
        } else {
          entry.resolve(msg);
        }
        return;
      }

      // Handle server-push / broadcast messages
      if (msg.type === "CLEAR_CACHE" || msg.type === "RULES_UPDATED") {
        rulesGeneration++;
        DECISION_CACHE.clear();
        INFLIGHT_DECISIONS.clear();
        COSMETIC_CACHE.clear();
        INFLIGHT_COSMETIC.clear();
        console.log(`[Remmi] Cache cleared on rules update (gen=${rulesGeneration})`);
      } else if (msg.type === "PROFILE_CHANGED") {
        currentProfile = msg.profile || "SHIELD";
        rulesGeneration++;
        DECISION_CACHE.clear();
        INFLIGHT_DECISIONS.clear();
        COSMETIC_CACHE.clear();
        INFLIGHT_COSMETIC.clear();
        console.log(`[Remmi] Profile changed to ${currentProfile} (gen=${rulesGeneration})`);
      } else if (msg.type === "EXTRACT_HTML") {
        const requestId = msg.requestId || "";
        const origTabId = msg.tabId || "";
        const execExtract = () => {
          return browser.tabs.executeScript({
            code: "document.documentElement ? document.documentElement.outerHTML : (document.body ? document.body.outerHTML : '');"
          });
        };

        execExtract().then((res) => {
          let html = (res && res[0]) ? res[0] : "";
          const MAX_HTML_BYTES = 5 * 1024 * 1024;
          if (new Blob([html]).size > MAX_HTML_BYTES) {
            html = html.substring(0, MAX_HTML_BYTES) + "\n<!-- Truncated by Remmi Native Bridge -->";
          }
          if (port) port.postMessage({ type: "EXTRACTED_HTML", html: html, url: "", requestId: requestId, tabId: origTabId });
        }).catch(_e => {
          browser.tabs.query({ active: true }).then(tabs => {
            if (tabs && tabs[0] && typeof tabs[0].id === "number") {
              return browser.tabs.executeScript(tabs[0].id, { code: "document.documentElement ? document.documentElement.outerHTML : (document.body ? document.body.outerHTML : '');" });
            }
            throw new Error("No active tab");
          }).then(res => {
            let html = (res && res[0]) ? res[0] : "";
            if (port) port.postMessage({ type: "EXTRACTED_HTML", html: html, url: "", requestId: requestId, tabId: origTabId });
          }).catch(err => {
            console.error("[Remmi] EXTRACT_HTML error:", err);
            if (port) port.postMessage({ type: "EXTRACTED_HTML", html: "", url: "", requestId: requestId, tabId: origTabId });
          });
        });
      } else if (msg.type === "EXECUTE_SCRIPT") {
        const scriptCode = msg.script;
        if (scriptCode) {
          browser.tabs.executeScript({ code: scriptCode }).catch(e => {
            browser.tabs.query({ active: true }).then(tabs => {
              if (tabs && tabs[0] && typeof tabs[0].id === "number") {
                browser.tabs.executeScript(tabs[0].id, { code: scriptCode }).catch(err => {
                  console.error("[Remmi] EXECUTE_SCRIPT failed:", err);
                });
              }
            }).catch(_ => {});
          });
        }
      } else if (msg.type === "EVAL_SCRIPT") {
        const scriptCode = msg.script || "";
        const requestId = msg.requestId || "";
        if (scriptCode) {
          const wrapped = "(function(){\n" +
            "try {\n" +
            "  var res = (" + scriptCode + ");\n" +
            "  if (res === undefined) return 'undefined';\n" +
            "  if (res === null) return 'null';\n" +
            "  if (typeof res === 'object') {\n" +
            "    try { return JSON.stringify(res, null, 2); } catch(e) { return String(res); }\n" +
            "  }\n" +
            "  return String(res);\n" +
            "} catch(err) {\n" +
            "  return 'EXCEPTION: ' + (err.stack || err.message || String(err));\n" +
            "}\n" +
            "})();";

          const doEval = () => browser.tabs.executeScript({ code: wrapped });

          doEval().then(res => {
            const out = (res && res[0] !== undefined) ? String(res[0]) : "undefined";
            const isError = out.startsWith("EXCEPTION:");
            if (port) port.postMessage({ type: "EVAL_RESULT", result: out, requestId: requestId, isError: isError });
          }).catch(_e => {
            browser.tabs.query({ active: true }).then(tabs => {
              if (tabs && tabs[0] && typeof tabs[0].id === "number") {
                return browser.tabs.executeScript(tabs[0].id, { code: wrapped });
              }
              throw new Error("No active tab");
            }).then(res => {
              const out = (res && res[0] !== undefined) ? String(res[0]) : "undefined";
              const isError = out.startsWith("EXCEPTION:");
              if (port) port.postMessage({ type: "EVAL_RESULT", result: out, requestId: requestId, isError: isError });
            }).catch(err => {
              if (port) port.postMessage({ type: "EVAL_RESULT", result: "EXCEPTION: " + err.message, requestId: requestId, isError: true });
            });
          });
        }
      }
      } else if (msg.type === "RUN_BENCHMARK") {
        runPingBenchmark(msg.count || 100);
      } else if (msg.type === "GET_DIAGNOSTICS") {
        if (port && (portState === "HEALTHY" || portState === "CONNECTED")) {
          try {
            port.postMessage({
              type: "DIAGNOSTICS_RESULT",
              requestId: msg.requestId || "",
              portGeneration: currentGen,
              events: DIAGNOSTIC_RING_BUFFER.slice(),
              metrics: { ...BLOCKER_METRICS },
              portState: portState,
              rulesGeneration: rulesGeneration
            });
          } catch (_e) {}
        }
      }
    });

    port.onDisconnect.addListener(() => {
      const dTs = Date.now();
      logToNative(`[PORT_DISCONNECT] instanceId=${currentInst} generation=${currentGen} ts=${dTs}`);
      port = null;
      portState = "DISCONNECTED";
      isConnecting = false;

      // Reject pending requests on disconnect
      for (const [rId, entry] of pendingRequests.entries()) {
        clearTimeout(entry.timer);
        const err = new Error("port_disconnected");
        err.name = "PORT_DISCONNECTED";
        entry.reject(err);
      }
      pendingRequests.clear();

      if (!reconnectTimer) {
        logToNative(`[PORT_RECONNECT_START] instanceId=${currentInst + 1} nextGeneration=${portGeneration + 1} ts=${Date.now()}`);
        reconnectTimer = setTimeout(connectNative, 3000);
      }
    });

    // PING MUST BE THE FIRST TEST before enabling network or cosmetic traffic
    executeInitialPing(currentInst, currentGen).finally(() => {
      isConnecting = false;
    });

  } catch (_e) {
    logToNative(`[PORT_RECONNECT_FAILURE] instanceId=${currentInst} generation=${currentGen} ts=${Date.now()} error=${_e?.message || String(_e)}`);
    port = null;
    portState = "DISCONNECTED";
    isConnecting = false;
    if (!reconnectTimer) {
      reconnectTimer = setTimeout(connectNative, 5000);
    }
  }
}

connectNative();

// 3. Priority scheduling for dynamic cosmetic queries
const MAX_CONCURRENT_CLASS_ID_COSMETICS = 2;
const MAX_COSMETIC_QUEUE_DEPTH = 10;
let activeClassIdCosmetics = 0;
const classIdCosmeticQueue = [];

function scheduleClassIdCosmetic(task) {
  return new Promise((resolve, reject) => {
    const execute = () => {
      activeClassIdCosmetics++;
      task()
        .then(resolve)
        .catch(reject)
        .finally(() => {
          activeClassIdCosmetics--;
          if (classIdCosmeticQueue.length > 0) {
            const next = classIdCosmeticQueue.shift();
            if (next) next();
          }
        });
    };

    if (activeClassIdCosmetics < MAX_CONCURRENT_CLASS_ID_COSMETICS) {
      execute();
    } else {
      if (classIdCosmeticQueue.length >= MAX_COSMETIC_QUEUE_DEPTH) {
        // Coalesce / drop oldest superseded cosmetic task
        const dropped = classIdCosmeticQueue.shift();
        if (dropped && dropped.cancel) {
          dropped.cancel();
        }
      }
      const queuedTask = () => execute();
      queuedTask.cancel = () => resolve({ ok: true, hideSelectors: [] });
      classIdCosmeticQueue.push(queuedTask);
    }
  });
}

// Cosmetic decision cache with byte bounding & eviction telemetry
const COSMETIC_CACHE = new Map();
const INFLIGHT_COSMETIC = new Map();
const TAB_COSMETIC_KEYS = new Map(); // tabId -> Set of cacheKeys
const MAX_COSMETIC_CACHE_SIZE = 200;
const MAX_COSMETIC_CACHE_BYTES = 2 * 1024 * 1024; // 2MB limit
const COSMETIC_CACHE_TTL_MS = 300000; // 5 minutes

let COSMETIC_CACHE_BYTES = 0;
let COSMETIC_CACHE_EVICTIONS = 0;

function estimateItemBytes(key, data) {
  try {
    return (key ? key.length * 2 : 0) + (data ? JSON.stringify(data).length * 2 : 0) + 64;
  } catch (_e) {
    return 256;
  }
}

function getCachedCosmetic(key) {
  const item = COSMETIC_CACHE.get(key);
  if (!item) return null;
  if (Date.now() - item.ts < COSMETIC_CACHE_TTL_MS) {
    return item.data;
  }
  COSMETIC_CACHE.delete(key);
  COSMETIC_CACHE_BYTES = Math.max(0, COSMETIC_CACHE_BYTES - (item.bytes || 256));
  COSMETIC_CACHE_EVICTIONS++;
  return null;
}

function setCachedCosmetic(key, data, tabId) {
  const itemBytes = estimateItemBytes(key, data);
  
  while (COSMETIC_CACHE.size >= MAX_COSMETIC_CACHE_SIZE || (COSMETIC_CACHE_BYTES + itemBytes > MAX_COSMETIC_CACHE_BYTES && COSMETIC_CACHE.size > 0)) {
    const firstKey = COSMETIC_CACHE.keys().next().value;
    if (!firstKey) break;
    const oldItem = COSMETIC_CACHE.get(firstKey);
    COSMETIC_CACHE.delete(firstKey);
    if (oldItem) {
      COSMETIC_CACHE_BYTES = Math.max(0, COSMETIC_CACHE_BYTES - (oldItem.bytes || 256));
    }
    COSMETIC_CACHE_EVICTIONS++;
  }

  COSMETIC_CACHE.set(key, {
    data: data,
    ts: Date.now(),
    bytes: itemBytes
  });
  COSMETIC_CACHE_BYTES += itemBytes;

  if (tabId !== undefined && tabId !== null) {
    if (!TAB_COSMETIC_KEYS.has(tabId)) {
      TAB_COSMETIC_KEYS.set(tabId, new Set());
    }
    TAB_COSMETIC_KEYS.get(tabId).add(key);
  }
}

function purgeTabCosmeticState(tabId) {
  if (!tabId || !TAB_COSMETIC_KEYS.has(tabId)) return;
  const keys = TAB_COSMETIC_KEYS.get(tabId);
  if (keys) {
    for (const k of keys) {
      const item = COSMETIC_CACHE.get(k);
      if (item) {
        COSMETIC_CACHE_BYTES = Math.max(0, COSMETIC_CACHE_BYTES - (item.bytes || 256));
      }
      COSMETIC_CACHE.delete(k);
      INFLIGHT_COSMETIC.delete(k);
    }
  }
  TAB_COSMETIC_KEYS.delete(tabId);
}

// Tab navigation & destruction listeners to enforce per-page lifecycle release
if (typeof browser !== 'undefined' && browser.tabs) {
  if (browser.tabs.onRemoved) {
    browser.tabs.onRemoved.addListener((tabId) => {
      purgeTabCosmeticState(tabId);
    });
  }
  if (browser.tabs.onUpdated) {
    browser.tabs.onUpdated.addListener((tabId, changeInfo) => {
      if (changeInfo.status === "loading" || changeInfo.url) {
        purgeTabCosmeticState(tabId);
      }
    });
  }
}

// 4. Content Script Message Listener (Cosmetics & Click Inspection)
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message) return;
  
  const senderUrl = (sender && sender.tab && sender.tab.url) || (sender && sender.url) || message.url || "";
  if (!senderUrl || (!senderUrl.startsWith("http://") && !senderUrl.startsWith("https://"))) {
    return;
  }

  if (message.type === "CLICK_INSPECTED") {
    const payload = {
      type: "CLICK_INSPECTION_RESULT",
      candidates: message.candidates || [],
      hasOverlay: !!message.hasOverlay,
      intercepted: !!message.intercepted,
      pageUrl: message.pageUrl || "",
      timestamp: message.timestamp || Date.now()
    };

    if (port && portState === "HEALTHY") {
      try {
        port.postMessage(payload);
      } catch (_e) {}
    }
    if (sendResponse) sendResponse({ received: true });
    return true;
  }

  if (message.type === "BLOCK_ELEMENT") {
    if (port && portState === "HEALTHY") {
      try {
        port.postMessage({
          type: "BLOCK_ELEMENT",
          selector: message.selector || "",
          domain: message.domain || (sender.tab ? (new URL(sender.tab.url)).hostname : "")
        });
      } catch (_e) {}
    }
    if (sendResponse) sendResponse({ ok: true });
    return true;
  }

  if (message.type === "GET_COSMETIC_RESOURCES") {
    // COSMETIC ISOLATION: Until network blocking is healthy, cosmetic traffic must be disabled or strictly deferred
    if (portState !== "HEALTHY") {
      if (sendResponse) sendResponse({ ok: true, hideSelectors: [], forceHideSelectors: [], procedural: [], proceduralCount: 0, generics: false });
      return true;
    }

    const url = message.url || (sender.tab ? sender.tab.url : "");
    const hostname = message.hostname || "";
    const cacheKey = [
      currentProfile,
      rulesGeneration,
      "URL_COSMETIC",
      hostname,
      url
    ].join("|");

    const cached = getCachedCosmetic(cacheKey);
    if (cached) {
      if (sendResponse) sendResponse(cached);
      return true;
    }

    if (INFLIGHT_COSMETIC.has(cacheKey)) {
      INFLIGHT_COSMETIC.get(cacheKey).then((data) => {
        if (sendResponse) sendResponse(data);
      });
      return true;
    }

    const promise = (async () => {
      try {
        const startTs = Date.now();
        const resp = await sendPortMessage("GET_COSMETIC_RESOURCES", {
          url: url,
          hostname: hostname,
          classes: message.classes || [],
          ids: message.ids || [],
          exceptions: message.exceptions || []
        }, 2000, "COSMETIC_RESOURCES_TIMEOUT");
        const elapsed = Date.now() - startTs;
        if (resp && resp.ok) {
          const tabId = sender && sender.tab ? sender.tab.id : null;
          setCachedCosmetic(cacheKey, resp, tabId);
        }
        return resp || { ok: false, hideSelectors: [] };
      } catch (e) {
        const errCategory = e?.name || "NATIVE_MESSAGE_FAILURE";
        logToNative(`[NM_SEND_ERROR] type=GET_COSMETIC_RESOURCES errorName=${errCategory} errorMessage=${e?.message || String(e)}`);
        return { ok: false, error: e?.message || "error", hideSelectors: [] };
      } finally {
        INFLIGHT_COSMETIC.delete(cacheKey);
      }
    })();

    INFLIGHT_COSMETIC.set(cacheKey, promise);
    promise.then((res) => {
      if (sendResponse) sendResponse(res);
    });
    return true;
  }

  if (message.type === "GET_HIDDEN_CLASS_ID_SELECTORS") {
    const senderTabId = sender?.tab?.id || "unknown";
    const senderFrameId = sender?.frameId || 0;
    const senderUrl = sender?.url || sender?.tab?.url || "unknown";

    // COSMETIC ISOLATION: Until network blocking is healthy, cosmetic traffic must be disabled or strictly deferred
    if (portState !== "HEALTHY") {
      logToNative(`[FORENSIC] GET_HIDDEN_CLASS_ID_SELECTORS | tabId=${senderTabId} frameId=${senderFrameId} url=${senderUrl} reason=PORT_UNHEALTHY classes=${(message.classes || []).length} ids=${(message.ids || []).length} cache=SKIP`);
      if (sendResponse) sendResponse({ ok: true, hideSelectors: [] });
      return true;
    }

    const classes = message.classes || [];
    const ids = message.ids || [];
    const cacheKey = [
      currentProfile,
      rulesGeneration,
      "CLASS_ID_COSMETIC",
      classes.slice().sort().join(","),
      ids.slice().sort().join(",")
    ].join("|");

    const cached = getCachedCosmetic(cacheKey);
    if (cached) {
      logToNative(`[FORENSIC] GET_HIDDEN_CLASS_ID_SELECTORS | tabId=${senderTabId} frameId=${senderFrameId} url=${senderUrl} reason=NORMAL classes=${classes.length} ids=${ids.length} cache=HIT`);
      if (sendResponse) sendResponse(cached);
      return true;
    }

    logToNative(`[FORENSIC] GET_HIDDEN_CLASS_ID_SELECTORS | tabId=${senderTabId} frameId=${senderFrameId} url=${senderUrl} reason=NORMAL classes=${classes.length} ids=${ids.length} cache=MISS`);

    if (INFLIGHT_COSMETIC.has(cacheKey)) {
      INFLIGHT_COSMETIC.get(cacheKey).then((data) => {
        if (sendResponse) sendResponse(data);
      });
      return true;
    }

    const promise = scheduleClassIdCosmetic(async () => {
      try {
        const startTs = Date.now();
        const resp = await sendPortMessage("GET_HIDDEN_CLASS_ID_SELECTORS", {
          classes: classes,
          ids: ids,
          exceptions: message.exceptions || []
        }, 2000, "HIDDEN_CLASS_ID_TIMEOUT");
        const elapsed = Date.now() - startTs;
        if (resp && resp.ok) {
          const tabId = sender && sender.tab ? sender.tab.id : null;
          setCachedCosmetic(cacheKey, resp, tabId);
        }
        return resp || { ok: false, hideSelectors: [] };
      } catch (e) {
        const errCategory = e?.name || "NATIVE_MESSAGE_FAILURE";
        logToNative(`[NM_SEND_ERROR] type=GET_HIDDEN_CLASS_ID_SELECTORS errorName=${errCategory} errorMessage=${e?.message || String(e)}`);
        return { ok: false, error: e?.message || "error", hideSelectors: [] };
      } finally {
        INFLIGHT_COSMETIC.delete(cacheKey);
      }
    });

    INFLIGHT_COSMETIC.set(cacheKey, promise);
    promise.then((res) => {
      if (sendResponse) sendResponse(res);
    });
    return true;
  }

  if (message.type === "GET_DIAGNOSTICS") {
    if (sendResponse) {
      sendResponse({
        ok: true,
        events: DIAGNOSTIC_RING_BUFFER.slice(),
        metrics: { ...BLOCKER_METRICS },
        portState: portState,
        portGeneration: portGeneration,
        rulesGeneration: rulesGeneration
      });
    }
    return true;
  }

  if (sendResponse) sendResponse({ received: true });
  return true;
});

// 5. Network Request Blocker with Persistent Port Delegation
let currentProfile = "SHIELD";
let rulesGeneration = 0;
const DECISION_CACHE = new Map();
const INFLIGHT_DECISIONS = new Map();

const MAX_CACHE_SIZE = 800;
const NATIVE_DECISION_TIMEOUT_MS = 1500;

const BLOCKABLE_TYPES = new Set([
  "main_frame",
  "sub_frame",
  "script",
  "stylesheet",
  "image",
  "imageset",
  "font",
  "xmlhttprequest",
  "web_manifest",
  "object",
  "media",
  "beacon",
  "ping",
  "csp_report",
  "websocket",
  "other"
]);

const BLOCKER_METRICS = {
  requests: 0,
  cacheHits: 0,
  inflightHits: 0,
  nativeCalls: 0,
  nativeErrors: 0,
  blocked: 0
};

function getCacheTtl(resourceType) {
  switch (resourceType) {
    case "script":
    case "stylesheet":
    case "font":
      return 5 * 60 * 1000;
    case "image":
    case "imageset":
    case "media":
    case "beacon":
    case "ping":
    case "csp_report":
    case "websocket":
    case "other":
      return 2 * 60 * 1000;
    case "main_frame":
    case "sub_frame":
      return 60 * 1000;
    default:
      return 60 * 1000;
  }
}

function getCachedDecision(key) {
  const item = DECISION_CACHE.get(key);
  if (!item) return null;
  if (Date.now() - item.ts < item.ttl) {
    return item.cancel;
  }
  DECISION_CACHE.delete(key);
  return null;
}

function setCachedDecision(key, cancel, resourceType = "other") {
  if (DECISION_CACHE.size >= MAX_CACHE_SIZE) {
    const firstKey = DECISION_CACHE.keys().next().value;
    if (firstKey) DECISION_CACHE.delete(firstKey);
  }
  DECISION_CACHE.set(key, {
    cancel: !!cancel,
    ts: Date.now(),
    ttl: getCacheTtl(resourceType)
  });
}

function buildDecisionKey(details) {
  const method = (details.method || "GET").toUpperCase();
  const resType = details.type || "other";
  const origin = details.originUrl || details.documentUrl || "";

  return [
    currentProfile,
    rulesGeneration,
    method,
    resType,
    origin,
    details.url
  ].join("|");
}

function calculateIsThirdParty(targetUrl, sourceUrl) {
  try {
    if (!sourceUrl || !targetUrl) return false;
    const target = new URL(targetUrl);
    const source = new URL(sourceUrl);
    const targetHost = target.hostname.toLowerCase();
    const sourceHost = source.hostname.toLowerCase();
    if (!targetHost || !sourceHost) return false;
    if (targetHost === sourceHost) return false;
    const tParts = targetHost.split('.');
    const sParts = sourceHost.split('.');
    if (tParts.length >= 2 && sParts.length >= 2) {
      const tBase = tParts.slice(-2).join('.');
      const sBase = sParts.slice(-2).join('.');
      if (tBase === sBase) return false;
    }
    return true;
  } catch (_e) {
    return false;
  }
}

async function getNativeDecision(details, cacheKey, requestId) {
  const reqId = requestId || ("req_" + Math.random().toString(36).substring(2, 9));
  const isTrace = isTraceCandidateUrl(details.url);

  if (INFLIGHT_DECISIONS.has(cacheKey)) {
    BLOCKER_METRICS.inflightHits++;
    if (isTrace) {
      logToNative(`[WEBEXT_INFLIGHT_REUSE] requestId=${reqId} key=${cacheKey}`);
    }
    return INFLIGHT_DECISIONS.get(cacheKey);
  }

  if (isTrace) {
    logToNative(`[WEBEXT_INFLIGHT_OWNER] requestId=${reqId} key=${cacheKey}`);
  }

  const promise = (async () => {
    BLOCKER_METRICS.nativeCalls++;
    const sourceUrl = details.documentUrl || details.originUrl || "";
    const is3p = calculateIsThirdParty(details.url, sourceUrl);
    const startTs = Date.now();

    if (isTrace) {
      logToNative(`[NM_SEND_START] requestId=${reqId} messageType=SHOULD_BLOCK url=${details.url} ts=${startTs}`);
    }

    // GATING: If port is not healthy, fail-open safely without queueing behind dead port
    if (portState !== "HEALTHY") {
      logToNative(`[PORT_NOT_READY] requestId=${reqId} portState=${portState}`);
      return { cancel: false, generation: rulesGeneration };
    }

    let response;
    try {
      response = await sendPortMessage(
        "SHOULD_BLOCK",
        {
          url: details.url,
          sourceUrl: sourceUrl,
          initiator: details.originUrl || "",
          method: details.method || "GET",
          resourceType: details.type || "other",
          aggressive: currentProfile === "GHOST" || currentProfile === "TOR",
          thirdParty: is3p
        },
        NATIVE_DECISION_TIMEOUT_MS,
        "NATIVE_DECISION_TIMEOUT"
      );
    } catch (e) {
      const errName = e?.name || "NATIVE_MESSAGE_FAILURE";
      logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=${errName} errorMessage=${e?.message || String(e)}`);
      throw e;
    }

    const elapsedMs = Date.now() - startTs;

    if (!response || response.ok !== true) {
      const errName = response ? "NATIVE_HANDLER_ERROR" : "INVALID_RESPONSE";
      const errMsg = response?.error || "null_or_invalid_response";
      logToNative(`[NM_SEND_ERROR] requestId=${reqId} errorName=${errName} errorMessage=${errMsg}`);
      throw new Error(`native_decision_invalid:${errMsg}`);
    }

    if (isTrace) {
      logToNative(`[NM_SEND_SUCCESS] requestId=${reqId} responseOk=true elapsedMs=${elapsedMs} cancel=${response.cancel}`);
    }

    if (response.generation && response.generation > rulesGeneration) {
      rulesGeneration = response.generation;
    }

    return {
      cancel: response.cancel === true,
      redirect: response.redirectUrl || response.redirect || null,
      rewrittenUrl: response.rewrittenUrl || null,
      csp: response.csp || null,
      ruleId: response.ruleId || null,
      ruleSource: response.ruleSource || null,
      generation: response.generation || rulesGeneration
    };
  })();

  INFLIGHT_DECISIONS.set(cacheKey, promise);

  try {
    return await promise;
  } finally {
    INFLIGHT_DECISIONS.delete(cacheKey);
  }
}

browser.webRequest.onBeforeRequest.addListener(
  async function(details) {
    const url = details.url;
    if (!url) {
      return { cancel: false };
    }

    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      return { cancel: false };
    }

    const resType = details.type || "other";
    const method = (details.method || "GET").toUpperCase();

    if (!BLOCKABLE_TYPES.has(resType)) {
      return { cancel: false };
    }

    BLOCKER_METRICS.requests++;

    const isIdempotent = method === "GET" || method === "HEAD" || method === "OPTIONS";
    const cacheKey = buildDecisionKey(details);

    if (isIdempotent) {
      const cached = getCachedDecision(cacheKey);
      if (cached !== null) {
        BLOCKER_METRICS.cacheHits++;
        if (cached === true) BLOCKER_METRICS.blocked++;
        if ((BLOCKER_METRICS.requests) % 50 === 0) {
          logToNative(
            `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
          );
        }
        return { cancel: cached === true };
      }
    }

    const traceId = details.requestId || Math.random().toString(36).substring(7);
    const isTraceCandidate = isTraceCandidateUrl(url);
    if (isTraceCandidate) {
      logToNative(`[AB_REQUEST_IN] requestId=${traceId} url=${url} type=${details.type} method=${details.method}`);
    }

    try {
      const response = await getNativeDecision(details, cacheKey, traceId);
      const shouldCancel = response.cancel === true;

      if (isTraceCandidate) {
        logToNative(`[AB_ENFORCEMENT_RESULT] requestId=${traceId} cancel=${shouldCancel}`);
      }

      if (isIdempotent && !response.redirect && !response.rewrittenUrl && !response.csp) {
        setCachedDecision(cacheKey, shouldCancel, resType);
      }
      
      let finalResult = { cancel: shouldCancel };
      
      if (shouldCancel) {
        BLOCKER_METRICS.blocked++;
      } else if (response.redirect) {
        finalResult = { redirectUrl: response.redirect };
      } else if (response.rewrittenUrl) {
        finalResult = { redirectUrl: response.rewrittenUrl };
      }
      
      if (response.csp) {
        logToNative(`[WEBEXT_PARTIAL] unsupported action: csp`);
      }

      if ((BLOCKER_METRICS.requests) % 50 === 0) {
        logToNative(
          `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
        );
      }
      return finalResult;
    } catch (e) {
      BLOCKER_METRICS.nativeErrors++;
      if ((BLOCKER_METRICS.requests) % 50 === 0) {
        logToNative(
          `[WEBEXT_METRICS] requests=${BLOCKER_METRICS.requests} cacheHits=${BLOCKER_METRICS.cacheHits} inflightHits=${BLOCKER_METRICS.inflightHits} nativeCalls=${BLOCKER_METRICS.nativeCalls} errors=${BLOCKER_METRICS.nativeErrors} blocked=${BLOCKER_METRICS.blocked}`
        );
      }
      
      const errorCategory = e?.name || "NATIVE_MESSAGE_FAILURE";
      logToNative(
        `[WEBEXT_NATIVE_ERROR] type=${resType} name=${errorCategory} message=${e?.message || String(e)}`
      );

      return { cancel: false };
    }
  },
  { urls: ["<all_urls>"] },
  ["blocking"]
);

browser.webRequest.onCompleted.addListener(
  (details) => {
    if (details.url.includes("google-analytics") || details.url.includes("adblock-tester") || details.url.includes("googletagmanager")) {
      logToNative(`[AB_REQUEST_COMPLETION] requestId=${details.requestId} url=${details.url} completed=true status=${details.statusCode}`);
    }
  },
  { urls: ["<all_urls>"] }
);

browser.webRequest.onErrorOccurred.addListener(
  (details) => {
    if (details.url.includes("google-analytics") || details.url.includes("adblock-tester") || details.url.includes("googletagmanager")) {
      logToNative(`[AB_REQUEST_COMPLETION] requestId=${details.requestId} url=${details.url} completed=false error=${details.error}`);
    }
  },
  { urls: ["<all_urls>"] }
);
