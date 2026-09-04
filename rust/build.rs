use std::env;
use std::process::Command;

fn main() {
    let git_hash = env::var("GIT_COMMIT_SHA").ok()
        .filter(|s| !s.trim().is_empty())
        .or_else(|| {
            Command::new("git")
                .args(&["rev-parse", "HEAD"])
                .output()
                .ok()
                .and_then(|output| String::from_utf8(output.stdout).ok())
                .map(|s| s.trim().to_string())
        })
        .unwrap_or_else(|| "unknown-commit".to_string());

    let timestamp = Command::new("date")
        .args(&["-u", "+%Y%m%d-%H%M%S"])
        .output()
        .ok()
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .map(|s| s.trim().to_string())
        .unwrap_or_else(|| "unknown-time".to_string());

    println!("cargo:rustc-env=NATIVE_BUILD_ID={}-{}", git_hash, timestamp);
    println!("cargo:rustc-env=NATIVE_SOURCE_COMMIT={}", git_hash);
    
    let target_arch = env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_else(|_| "unknown".to_string());
    println!("cargo:rustc-env=NATIVE_BUILD_ABI={}", target_arch);
}

