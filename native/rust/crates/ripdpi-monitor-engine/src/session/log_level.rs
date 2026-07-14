use log::LevelFilter;

pub(super) fn native_log_level_from_str(value: &str) -> Option<LevelFilter> {
    match value.trim().to_ascii_lowercase().as_str() {
        "off" => Some(LevelFilter::Off),
        "error" => Some(LevelFilter::Error),
        "warn" | "warning" => Some(LevelFilter::Warn),
        "info" => Some(LevelFilter::Info),
        "debug" => Some(LevelFilter::Debug),
        "trace" => Some(LevelFilter::Trace),
        _ => None,
    }
}

pub(super) fn parse_native_log_level(value: Option<&str>) -> Result<Option<LevelFilter>, String> {
    value
        .map(|value| {
            native_log_level_from_str(value).ok_or_else(|| format!("Unsupported diagnostics nativeLogLevel: {value}"))
        })
        .transpose()
}
