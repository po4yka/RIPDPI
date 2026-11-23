use android_support::ffi_boundary;
use jni::objects::JObject;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};
use serde_json::json;
use std::fs;
#[cfg(feature = "privileged-packets")]
use std::path::Path;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_detection_checker_JniNativeSignsBridge_jniSnapshot(
    env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jstring {
    ffi_boundary(core::ptr::null_mut(), move || {
        let mut env = env;
        match env
            .with_env(|env| -> jni::errors::Result<jstring> {
                let payload = native_signs_snapshot_json();
                Ok(env.new_string(payload)?.into_raw())
            })
            .into_outcome()
        {
            Outcome::Ok(value) => value,
            _ => std::ptr::null_mut(),
        }
    })
}

fn native_signs_snapshot_json() -> String {
    let snapshot = json!({
        "nativeInterfaces": proc_net_devices(),
        "mapsLines": read_lines("/proc/self/maps"),
        "selinuxMode": selinux_mode(),
    });
    #[cfg(feature = "privileged-packets")]
    let snapshot = {
        let mut snapshot = snapshot;
        snapshot["reviewSignals"] = json!(root_review_signals());
        snapshot
    };

    snapshot.to_string()
}

fn proc_net_devices() -> Vec<String> {
    read_lines("/proc/net/dev")
        .into_iter()
        .skip(2)
        .filter_map(|line| line.split_once(':').map(|(name, _)| name.trim().to_owned()))
        .filter(|name| !name.is_empty())
        .collect()
}

#[cfg(feature = "privileged-packets")]
fn root_review_signals() -> Vec<String> {
    let mut artifacts = Vec::new();
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            let candidate = dir.join("su");
            if candidate.exists() {
                artifacts.push(format!("Root artifact: {}", candidate.display()));
            }
        }
    }
    for path in ["/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap", "/data/adb/modules"] {
        if Path::new(path).exists() {
            artifacts.push(format!("Root artifact: {path}"));
        }
    }
    artifacts.extend(
        read_lines("/proc/mounts")
            .into_iter()
            .filter(|line| line.contains(" overlay ") || line.contains(" bind "))
            .map(|line| {
                let mount: String = line.trim().chars().take(160).collect();
                format!("Root artifact: mount:{mount}")
            }),
    );
    artifacts.sort();
    artifacts.dedup();
    artifacts
}

fn selinux_mode() -> Option<&'static str> {
    match fs::read_to_string("/sys/fs/selinux/enforce").ok()?.trim() {
        "0" => Some("permissive"),
        "1" => Some("enforcing"),
        _ => None,
    }
}

fn read_lines(path: &str) -> Vec<String> {
    fs::read_to_string(path).map_or_default(|content| content.lines().map(str::to_owned).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_json_has_expected_keys() {
        let payload = native_signs_snapshot_json();
        let value: serde_json::Value = serde_json::from_str(&payload).expect("valid json");
        assert!(value.get("nativeInterfaces").is_some());
        assert!(value.get("mapsLines").is_some());
        assert!(value.get("mountsLines").is_none());
        assert!(value.get("rootArtifacts").is_none());
        assert_eq!(
            value.get("reviewSignals").is_some_and(serde_json::Value::is_array),
            cfg!(feature = "privileged-packets")
        );
        assert!(value.get("selinuxMode").is_some());
    }
}
