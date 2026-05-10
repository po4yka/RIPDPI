use jni::objects::JObject;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};
use serde_json::json;
use std::fs;
use std::path::Path;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_poyka_ripdpi_core_detection_checker_JniNativeSignsBridge_jniSnapshot(
    mut env: EnvUnowned<'_>,
    _thiz: JObject<'_>,
) -> jstring {
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
}

fn native_signs_snapshot_json() -> String {
    json!({
        "nativeInterfaces": proc_net_devices(),
        "mapsLines": read_lines("/proc/self/maps"),
        "mountsLines": read_lines("/proc/mounts"),
        "rootArtifacts": root_artifacts(),
        "selinuxMode": selinux_mode(),
    })
    .to_string()
}

fn proc_net_devices() -> Vec<String> {
    read_lines("/proc/net/dev")
        .into_iter()
        .skip(2)
        .filter_map(|line| line.split_once(':').map(|(name, _)| name.trim().to_owned()))
        .filter(|name| !name.is_empty())
        .collect()
}

fn root_artifacts() -> Vec<String> {
    let mut artifacts = Vec::new();
    if let Some(path) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&path) {
            let candidate = dir.join("su");
            if candidate.exists() {
                artifacts.push(candidate.display().to_string());
            }
        }
    }
    for path in ["/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap", "/data/adb/modules"] {
        if Path::new(path).exists() {
            artifacts.push(path.to_owned());
        }
    }
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
    fs::read_to_string(path).map(|content| content.lines().map(str::to_owned).collect()).unwrap_or_default()
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
        assert!(value.get("mountsLines").is_some());
        assert!(value.get("rootArtifacts").is_some());
        assert!(value.get("selinuxMode").is_some());
    }
}
