use std::net::IpAddr;

pub(in crate::runtime_policy) fn normalize_learned_host(host: &str) -> Option<String> {
    let trimmed = host.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return None;
    }
    let normalized = trimmed.to_ascii_lowercase();
    if normalized.parse::<IpAddr>().is_ok() {
        return None;
    }
    if is_system_telemetry_host(&normalized) {
        tracing::debug!(host = normalized.as_str(), "autolearn: skipping system telemetry host");
        return None;
    }
    Some(normalized)
}

/// Returns true for hosts belonging to OS/vendor telemetry, push notification,
/// and cloud infrastructure services that are never DPI-blocked and should not
/// consume autolearn slots.
fn is_system_telemetry_host(host: &str) -> bool {
    const EXCLUDED_SUFFIXES: &[&str] = &[
        ".googleapis.com",
        ".gstatic.com",
        ".googlevideo.com",
        ".google-analytics.com",
        ".googleadservices.com",
        "mtalk.google.com",
        "connectivitycheck.gstatic.com",
        ".hicloud.com",
        ".dbankcloud.com",
        ".dbankcloud.ru",
        ".dbankcdn.com",
        ".hwcdn.net",
        ".icloud.com",
        ".apple.com",
        ".mzstatic.com",
        ".msftconnecttest.com",
        ".windowsupdate.com",
        ".trafficmanager.net",
        ".miui.com",
        ".xiaomi.com",
        ".firebaseio.com",
        ".crashlytics.com",
        ".app-measurement.com",
    ];

    for suffix in EXCLUDED_SUFFIXES {
        if host == suffix.trim_start_matches('.') || host.ends_with(suffix) {
            return true;
        }
    }
    false
}
