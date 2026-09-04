extern "C" {
    fn __android_log_print(prio: std::os::raw::c_int, tag: *const std::os::raw::c_char, fmt: *const std::os::raw::c_char, ...) -> std::os::raw::c_int;
    fn gettid() -> i32;
}

fn log_checkpoint(name: &str) {
    let pid = std::process::id();
    let tid = unsafe { gettid() };
    let time_ms = match std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH) {
        Ok(d) => d.as_millis() as u64,
        Err(_) => 0,
    };
    let mut rss_pages: usize = 0;
    if let Ok(contents) = std::fs::read_to_string("/proc/self/statm") {
        if let Some(rss_str) = contents.split_whitespace().nth(1) {
            if let Ok(p) = rss_str.parse::<usize>() {
                rss_pages = p;
            }
        }
    }
    let rss_mb = (rss_pages * 4096) / (1024 * 1024);

    let mut pss_kb: u64 = 0;
    if let Ok(smaps) = std::fs::read_to_string("/proc/self/smaps_rollup") {
        for line in smaps.lines() {
            if line.starts_with("Pss:") {
                let parts: Vec<&str> = line.split_whitespace().collect();
                if parts.len() >= 2 {
                    if let Ok(k) = parts[1].parse::<u64>() {
                        pss_kb = k;
                        break;
                    }
                }
            }
        }
    }
    let pss_mb = pss_kb / 1024;

    let tag = b"AdblockNative\0";
    let fmt = b"[NATIVE_CHECKPOINT] %s | timestamp=%llu | pid=%u | tid=%d | rss=%zuMB | pss=%lluMB\n\0";
    let mut name_buf = [0u8; 64];
    let name_bytes = name.as_bytes();
    let copy_len = std::cmp::min(name_bytes.len(), 63);
    name_buf[..copy_len].copy_from_slice(&name_bytes[..copy_len]);

    unsafe {
        __android_log_print(
            4, // ANDROID_LOG_INFO
            tag.as_ptr() as *const std::os::raw::c_char,
            fmt.as_ptr() as *const std::os::raw::c_char,
            name_buf.as_ptr() as *const std::os::raw::c_char,
            time_ms,
            pid,
            tid,
            rss_mb,
            pss_mb,
        );
    }
}

use std::collections::HashSet;
use std::sync::RwLock;
use std::sync::atomic::{AtomicU64, Ordering};
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_TRUE, JNI_FALSE};
use lazy_static::lazy_static;
use serde::{Serialize, Deserialize};
use adblock::Engine;
use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;

struct EngineSet {
    default_engine: Option<Engine>,
    additional_engine: Option<Engine>,
    generation: u64,
}

struct AdblockEngineState {
    engines: RwLock<EngineSet>,
    filter_count: AtomicU64,
    blocked_count: AtomicU64,
    allowed_count: AtomicU64,
}

lazy_static! {
    static ref GLOBAL_STATE: AdblockEngineState = AdblockEngineState {
        engines: RwLock::new(EngineSet {
            default_engine: None,
            additional_engine: None,
            generation: 0,
        }),
        filter_count: AtomicU64::new(0),
        blocked_count: AtomicU64::new(0),
        allowed_count: AtomicU64::new(0),
    };
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RequestContext {
    url: String,
    request_initiator: Option<String>,
    source_url: Option<String>,
    resource_type: String,
    method: String,
    aggressive: bool,
    third_party: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MatchResult {
    blocked: bool,
    redirect: Option<String>,
    rewritten_url: Option<String>,
    csp: Option<String>,
    default_matched: bool,
    default_exception: bool,
    default_important: bool,
    additional_matched: bool,
    additional_exception: bool,
    additional_important: bool,
}


const DEFAULT_RULES: &[&str] = &[
    "||google-analytics.com^$third-party",
    "||googletagmanager.com^$third-party",
    "||doubleclick.net^$third-party",
    "||facebook.net^$third-party",
    "||scorecardresearch.com^$third-party",
    "||criteo.com^$third-party",
    "||taboola.com^$third-party",
    "||outbrain.com^$third-party",
    "||hotjar.com^$third-party",
    "||adnxs.com^$third-party",
];

#[derive(Serialize)]
struct CosmeticResponse {
    ok: bool,
    generation: u64,
    #[serde(rename = "hideSelectors")]
    hide_selectors: Vec<String>,
    #[serde(rename = "forceHideSelectors")]
    force_hide_selectors: Vec<String>,
    procedural: Vec<String>,
    #[serde(rename = "proceduralCount")]
    procedural_count: usize,
    generics: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeInit(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut filter_set = FilterSet::new(true);
        filter_set.add_filters(DEFAULT_RULES, ParseOptions::default());
        let initial_engine = Engine::from_filter_set(filter_set, true);

        match GLOBAL_STATE.engines.write() {
            Ok(mut guard) => {
                guard.default_engine = Some(initial_engine);
                guard.generation = 1;
                GLOBAL_STATE.filter_count.store(DEFAULT_RULES.len() as u64, Ordering::SeqCst);
                GLOBAL_STATE.blocked_count.store(0, Ordering::SeqCst);
                GLOBAL_STATE.allowed_count.store(0, Ordering::SeqCst);
                JNI_TRUE
            }
            Err(_) => JNI_FALSE,
        }
    }));
    match result {
        Ok(val) => val,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeMatchesJson(
    mut env: JNIEnv,
    _class: JClass,
    context_json: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let json_str: String = match env.get_string(&context_json) {
            Ok(s) => s.into(),
            Err(_) => return std::ptr::null_mut(),
        };

        let ctx: RequestContext = match serde_json::from_str(&json_str) {
            Ok(c) => c,
            Err(_) => return std::ptr::null_mut(),
        };

        let mut out = MatchResult {
            blocked: false,
            redirect: None,
            rewritten_url: None,
            csp: None,
            default_matched: false,
            default_exception: false,
            default_important: false,
            additional_matched: false,
            additional_exception: false,
            additional_important: false,
        };

        let engines_guard = match GLOBAL_STATE.engines.read() {
            Ok(guard) => guard,
            Err(_) => return std::ptr::null_mut(),
        };

        let source_url = ctx.source_url.clone().unwrap_or_default();
        if let Ok(mut req) = Request::new(&ctx.url, &source_url, &ctx.resource_type) {
            // Wait, we need to handle method and third_party in the future?
            // Actually, we can use the advanced adblock::request API to set method and third_party if available,
            // or just rely on standard matching. adblock 0.8 Request has some builder fields maybe?
            // Let's check network request.
            
            // For now, we will perform pure final-result merge.
            let mut final_important = false;

            if let Some(ref default_eng) = engines_guard.default_engine {
                let res = default_eng.check_network_request(&req);
                if res.matched {
                    out.default_matched = true;
                    out.default_exception = res.exception.is_some();
                    out.default_important = res.important;
                    
                    if res.important {
                        final_important = true;
                    }
                    out.blocked = res.exception.is_none();
                    
                    if out.blocked {
                        out.redirect = res.redirect.clone();
                        out.rewritten_url = res.redirect.clone(); // Workaround for older adblock missing rewritten_url
                    }
                }
            }

            if let Some(ref additional) = engines_guard.additional_engine {
                let res = additional.check_network_request(&req);
                if res.matched {
                    out.additional_matched = true;
                    out.additional_exception = res.exception.is_some();
                    out.additional_important = res.important;
                    
                    if !final_important || res.important {
                        if res.exception.is_some() {
                            out.blocked = false;
                            out.redirect = None;
                            out.rewritten_url = None;
                        } else {
                            out.blocked = true;
                            if let Some(ref r) = res.redirect {
                                out.redirect = Some(r.clone());
                                out.rewritten_url = Some(r.clone());
                            }
                        }
                    }
                }
            }

            if out.blocked {
                GLOBAL_STATE.blocked_count.fetch_add(1, Ordering::Relaxed);
            } else {
                GLOBAL_STATE.allowed_count.fetch_add(1, Ordering::Relaxed);
            }
            
            let is_test_request = ctx.url.contains("tester_target_trigger") || ctx.url.contains("googletagmanager") || ctx.url.contains("google-analytics");
            if is_test_request {
                let actual_third_party = req.is_third_party;
                println!("[AB_REQUEST_IN]");
                println!("requestType={}", ctx.resource_type);
                println!("method={}", ctx.method);
                println!("requestHost={}", ctx.url);
                println!("topOriginHost={}", ctx.source_url.as_deref().unwrap_or(""));
                println!("initiatorHost={}", ctx.request_initiator.as_deref().unwrap_or(""));
                println!("thirdParty={}", actual_third_party);
                println!("aggressive={}", ctx.aggressive);
                println!("generation={}", engines_guard.generation);
                
                println!("[AB_DEFAULT_RESULT]");
                println!("matched={}", out.default_matched);
                println!("exception={}", out.default_exception);
                println!("important={}", out.default_important);

                println!("[AB_ADDITIONAL_RESULT]");
                println!("matched={}", out.additional_matched);
                println!("exception={}", out.additional_exception);
                println!("important={}", out.additional_important);

                println!("[AB_FINAL_RESULT]");
                println!("matched={}", out.default_matched || out.additional_matched);
                println!("exception={}", !out.blocked && (out.default_exception || out.additional_exception));
                println!("important={}", out.default_important || out.additional_important);
                println!("redirect={}", out.redirect.as_deref().unwrap_or(""));
                println!("rewrittenUrl={}", out.rewritten_url.as_deref().unwrap_or(""));

                println!("[AB_ENFORCEMENT]");
                println!("blocked={}", out.blocked);
            }
        }

        let out_json = serde_json::to_string(&out).unwrap_or_default();
        match env.new_string(&out_json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));

    match result {
        Ok(val) => val,
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeCompileRules(
    mut env: JNIEnv,
    _class: JClass,
    default_rules_text: JString,
    additional_rules_text: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        log_checkpoint("CP01_ENTER");
        let default_str: String = match env.get_string(&default_rules_text) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        log_checkpoint("CP02_AFTER_DEFAULT_STRING");
        let additional_str: String = match env.get_string(&additional_rules_text) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        log_checkpoint("CP03_AFTER_ADDITIONAL_STRING");
        let default_lines: Vec<&str> = default_str.lines().collect();
        log_checkpoint("CP04_AFTER_DEFAULT_LINES");
        let additional_lines: Vec<&str> = additional_str.lines().collect();
        log_checkpoint("CP05_AFTER_ADDITIONAL_LINES");
        let default_valid_count = default_lines
            .iter()
            .filter(|line| !line.trim().is_empty() && !line.starts_with('!'))
            .count();
        let additional_valid_count = additional_lines
            .iter()
            .filter(|line| !line.trim().is_empty() && !line.starts_with('!'))
            .count();

        if default_valid_count == 0 && additional_valid_count == 0 {
            let metrics = serde_json::json!({
                "inputLines": default_lines.len() + additional_lines.len(),
                "parsedCandidates": 0,
                "engineGeneration": 0,
                "activeEnginePresence": false
            });
            log_checkpoint("CP12_EXIT");
        let out_json = serde_json::to_string(&metrics).unwrap_or_default();
            return match env.new_string(&out_json) {
                Ok(s) => s.into_raw(),
                Err(_) => std::ptr::null_mut(),
            };
        }

        let mut default_filter_set = FilterSet::new(true);
        default_filter_set.add_filters(&default_lines, ParseOptions::default());
        log_checkpoint("CP06_AFTER_DEFAULT_FILTERSET");
        let new_default_engine = Engine::from_filter_set(default_filter_set, true);
        log_checkpoint("CP07_AFTER_DEFAULT_ENGINE");
        let mut additional_filter_set = FilterSet::new(true);
        if additional_valid_count > 0 {
            additional_filter_set.add_filters(&additional_lines, ParseOptions::default());
        }
        log_checkpoint("CP08_AFTER_ADDITIONAL_FILTERSET");
        let new_additional_engine = if additional_valid_count > 0 {
            Some(Engine::from_filter_set(additional_filter_set, true))
        } else {
            None
        };

        let total_count = (default_valid_count + additional_valid_count) as u64;
        let mut new_gen = 0;
        log_checkpoint("CP09_AFTER_ADDITIONAL_ENGINE");
        log_checkpoint("CP10_BEFORE_SWAP");
        if let Ok(mut guard) = GLOBAL_STATE.engines.write() {
            guard.default_engine = Some(new_default_engine);
            guard.additional_engine = new_additional_engine;
            guard.generation += 1;
            new_gen = guard.generation;
        }
        log_checkpoint("CP11_AFTER_SWAP");

        GLOBAL_STATE.filter_count.store(total_count, Ordering::SeqCst);
        println!("[ADBLOCK_ENGINE_SWAP] newGeneration={} rules={}", new_gen, total_count);
        
        let metrics = serde_json::json!({
            "inputLines": default_lines.len() + additional_lines.len(),
            "parsedCandidates": total_count,
            "engineGeneration": new_gen,
            "activeEnginePresence": true
        });
        
        // Wait, we can't return JSON from compileRules if it returns jint.
        // We will just return total_count as jint for now, or change the return type.
        // Since Kotlin expects Int, we will leave it as returning jint,
        // and Kotlin will just return that as compiledCount.
        log_checkpoint("CP12_EXIT");
        let out_json = serde_json::to_string(&metrics).unwrap_or_default();
        match env.new_string(&out_json) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));

    match result {
        Ok(val) => val,
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetCosmeticResources(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    classes: JString,
    ids: JString,
    exceptions: JString,
    aggressive: jboolean,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let gen = match GLOBAL_STATE.engines.read() {
            Ok(g) => g.generation,
            Err(_) => 0,
        };
        let url_str: String = match env.get_string(&url) {
            Ok(s) => s.into(),
            Err(_) => {
                let resp = CosmeticResponse {
                    ok: false,
                    generation: gen,
                    hide_selectors: vec![],
                    force_hide_selectors: vec![],
                    procedural: vec![],
                    procedural_count: 0,
                    generics: false,
                    error: Some("invalid_url".to_string()),
                };
                let json_str = serde_json::to_string(&resp).unwrap_or_default();
                return match env.new_string(&json_str) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };

        let classes_str: String = match env.get_string(&classes) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        let ids_str: String = match env.get_string(&ids) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        let exceptions_str: String = match env.get_string(&exceptions) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };

        let classes_vec: Vec<String> = if classes_str.is_empty() {
            vec![]
        } else {
            serde_json::from_str(&classes_str).unwrap_or_else(|_| {
                classes_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
            })
        };

        let ids_vec: Vec<String> = if ids_str.is_empty() {
            vec![]
        } else {
            serde_json::from_str(&ids_str).unwrap_or_else(|_| {
                ids_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
            })
        };

        let exceptions_set: HashSet<String> = if exceptions_str.is_empty() {
            HashSet::new()
        } else {
            serde_json::from_str(&exceptions_str).unwrap_or_else(|_| {
                exceptions_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
            })
        };

        let engines_guard = match GLOBAL_STATE.engines.read() {
            Ok(g) => g,
            Err(_) => {
                let resp = CosmeticResponse {
                    ok: false,
                    generation: gen,
                    hide_selectors: vec![],
                    force_hide_selectors: vec![],
                    procedural: vec![],
                    procedural_count: 0,
                    generics: false,
                    error: Some("lock_error".to_string()),
                };
                let json_str = serde_json::to_string(&resp).unwrap_or_default();
                return match env.new_string(&json_str) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };

        let mut hide_selectors: HashSet<String> = HashSet::new();
        let mut force_hide_selectors: HashSet<String> = HashSet::new();
        let mut procedural: HashSet<String> = HashSet::new();
        let mut generics = false;

        let is_aggressive = aggressive != 0;

        if let Some(ref engine) = engines_guard.default_engine {
            let cosmetic_resources = engine.url_cosmetic_resources(&url_str);
            hide_selectors.extend(cosmetic_resources.hide_selectors);
            
            if is_aggressive {
                if !cosmetic_resources.injected_script.is_empty() {
                    procedural.insert(cosmetic_resources.injected_script);
                }
            }
            generics = generics || cosmetic_resources.generichide;

            if !classes_vec.is_empty() || !ids_vec.is_empty() {
                let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
                hide_selectors.extend(hidden);
            }
        }

        if let Some(ref engine) = engines_guard.additional_engine {
            let cosmetic_resources = engine.url_cosmetic_resources(&url_str);
            force_hide_selectors.extend(cosmetic_resources.hide_selectors);
            
            if is_aggressive {
                if !cosmetic_resources.injected_script.is_empty() {
                    procedural.insert(cosmetic_resources.injected_script);
                }
            }
            generics = generics || cosmetic_resources.generichide;

            if !classes_vec.is_empty() || !ids_vec.is_empty() {
                let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
                force_hide_selectors.extend(hidden);
            }
        }

        let procedural_vec: Vec<String> = procedural.into_iter().collect();
        let procedural_count = procedural_vec.len();
        
        let resp = CosmeticResponse {
            ok: true,
            generation: gen,
            hide_selectors: hide_selectors.into_iter().collect(),
            force_hide_selectors: force_hide_selectors.into_iter().collect(),
            procedural: procedural_vec,
            procedural_count,
            generics,
            error: None,
        };
        let json_str = serde_json::to_string(&resp).unwrap_or_default();
        match env.new_string(&json_str) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));

    match result {
        Ok(raw) => raw,
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetHiddenClassIdSelectors(
    mut env: JNIEnv,
    _class: JClass,
    classes: JString,
    ids: JString,
    exceptions: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let gen = match GLOBAL_STATE.engines.read() {
            Ok(g) => g.generation,
            Err(_) => 0,
        };
        let classes_str: String = match env.get_string(&classes) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        let ids_str: String = match env.get_string(&ids) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };
        let exceptions_str: String = match env.get_string(&exceptions) {
            Ok(s) => s.into(),
            Err(_) => String::new(),
        };

        let classes_vec: Vec<String> = if classes_str.is_empty() {
            vec![]
        } else {
            serde_json::from_str(&classes_str).unwrap_or_else(|_| {
                classes_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
            })
        };

        let ids_vec: Vec<String> = if ids_str.is_empty() {
            vec![]
        } else {
            serde_json::from_str(&ids_str).unwrap_or_else(|_| {
                ids_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
            })
        };

        let exceptions_set: HashSet<String> = if exceptions_str.is_empty() {
            HashSet::new()
        } else {
            serde_json::from_str(&exceptions_str).unwrap_or_else(|_| {
                exceptions_str.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
            })
        };

        let engines_guard = match GLOBAL_STATE.engines.read() {
            Ok(g) => g,
            Err(_) => {
                let resp = CosmeticResponse {
                    ok: false,
                    generation: gen,
                    hide_selectors: vec![],
                    force_hide_selectors: vec![],
                    procedural: vec![],
                    procedural_count: 0,
                    generics: false,
                    error: Some("lock_error".to_string()),
                };
                let json_str = serde_json::to_string(&resp).unwrap_or_default();
                return match env.new_string(&json_str) {
                    Ok(s) => s.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                };
            }
        };

        let mut hide_selectors: HashSet<String> = HashSet::new();
        let mut force_hide_selectors: HashSet<String> = HashSet::new();

        if let Some(ref engine) = engines_guard.default_engine {
            let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
            hide_selectors.extend(hidden);
        }

        if let Some(ref engine) = engines_guard.additional_engine {
            let hidden = engine.hidden_class_id_selectors(&classes_vec, &ids_vec, &exceptions_set);
            force_hide_selectors.extend(hidden);
        }

        let resp = CosmeticResponse {
            ok: true,
            generation: gen,
            hide_selectors: hide_selectors.into_iter().collect(),
            force_hide_selectors: force_hide_selectors.into_iter().collect(),
            procedural: vec![],
            procedural_count: 0,
            generics: false,
            error: None,
        };
        let json_str = serde_json::to_string(&resp).unwrap_or_default();
        match env.new_string(&json_str) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));

    match result {
        Ok(raw) => raw,
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetFilterCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        GLOBAL_STATE.filter_count.load(Ordering::Relaxed) as jint
    }));
    result.unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetBlockedCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        GLOBAL_STATE.blocked_count.load(Ordering::Relaxed) as jint
    }));
    result.unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetGeneration(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        GLOBAL_STATE.engines.read().unwrap().generation as jlong
    }));
    result.unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetEngineGeneration(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        GLOBAL_STATE.engines.read().unwrap().generation as jlong
    }));
    result.unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeSelfTest(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let handle = std::thread::Builder::new()
            .stack_size(2 * 1024 * 1024)
            .spawn(|| {
                let test_rule = "||remmi-self-test.invalid^";
                let mut filter_set = FilterSet::new(true);
                filter_set.add_filters(&[test_rule], ParseOptions::default());
                let engine = Engine::from_filter_set(filter_set, true);

                let request = match Request::new(
                    "https://remmi-self-test.invalid/banner.js",
                    "https://example.com/",
                    "script",
                ) {
                    Ok(r) => r,
                    Err(_) => return JNI_FALSE,
                };

                if engine.check_network_request(&request).matched {
                    JNI_TRUE
                } else {
                    JNI_FALSE
                }
            });
        
        match handle {
            Ok(h) => h.join().unwrap_or(JNI_FALSE),
            Err(_) => JNI_FALSE,
        }
    }));
    match result {
        Ok(val) => val,
        Err(_) => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let version = "adblock-rust-0.8.2-remmi";
        match env.new_string(version) {
            Ok(output) => output.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));
    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetApiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    2
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetBuildId(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let build_id = option_env!("NATIVE_BUILD_ID").unwrap_or("remmi-2026-v2-compat");
        match env.new_string(build_id) {
            Ok(output) => output.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));
    result.unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_remmi_adblock_AdblockBridge_nativeGetAbi(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let abi = option_env!("NATIVE_BUILD_ABI").unwrap_or("universal");
        match env.new_string(abi) {
            Ok(output) => output.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }));
    result.unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    use adblock::lists::{FilterSet, ParseOptions};
    use adblock::Engine;
    use adblock::request::Request;

    #[test]
    fn test_diagnostic_urls() {
        let mut default_filters = FilterSet::new(true);
        default_filters.add_filters(&vec![
            "||google-analytics.com^",
            "||sentry-cdn.com^",
            "||adblock-tester.com/banners/*",
            "||adblock-tester.com/banners/pr_advertising_ads_banner.png",
            "||default-block.com^",
            "@@||default-exception.com^",
            "||default-important.com^$important",
            "||override-block.com^",
        ], ParseOptions::default());
        let default_eng = Engine::from_filter_set(default_filters, true);

        let mut add_filters = FilterSet::new(true);
        add_filters.add_filters(&vec![
            "@@||override-block.com^",
            "||additional-block.com^",
            "@@||default-important.com^" // Weak exception against strong block
        ], ParseOptions::default());
        let add_eng = Engine::from_filter_set(add_filters, true);

        let urls = vec![
            ("GA", "https://www.google-analytics.com/analytics.js", "https://example.com/", "script"),
            ("Sentry", "https://browser.sentry-cdn.com/bundle.min.js", "https://example.com/", "script"),
            ("static banner", "https://adblock-tester.com/banners/pr_advertising_ads_banner.png", "https://adblock-tester.com/", "image"),
            ("gif banner", "https://adblock-tester.com/banners/pr_advertising_ads_banner.gif", "https://adblock-tester.com/", "image"),
            ("a) default block", "https://default-block.com/test", "https://example.com/", "script"),
            ("b) default exception", "https://default-exception.com/test", "https://example.com/", "script"),
            ("c) default important", "https://default-important.com/test", "https://example.com/", "script"),
            ("d) additional exception overrides default block", "https://override-block.com/test", "https://example.com/", "script"),
            ("e) additional ordinary block", "https://additional-block.com/test", "https://example.com/", "script"),
            ("f) important default block NOT overridden", "https://default-important.com/test", "https://example.com/", "script"),
            ("g) no-match => allow", "https://no-match-whatsoever.com/test", "https://example.com/", "script"),
        ];

        println!("\n=== DIAGNOSTIC START ===");
        for (desc, url, source, req_type) in urls {
            let req = Request::new(url, source, req_type).unwrap();
            let def_res = default_eng.check_network_request(&req);
            let add_res = add_eng.check_network_request(&req);

            let mut block = false;
            let mut final_important = false;

            if def_res.matched {
                if def_res.important {
                    final_important = true;
                }
                block = def_res.exception.is_none();
            }

            if add_res.matched {
                if !final_important {
                    if add_res.exception.is_some() {
                        block = false;
                    } else {
                        block = true;
                    }
                }
            }

            println!("{} -> blocked={}", desc, block);
        }
        println!("=== DIAGNOSTIC END ===\n");
    }
}

