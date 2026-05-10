use std::time::Duration;

use ripdpi_config::{
    RuntimeConfig, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT, DETECT_TLS_ALERT, DETECT_TLS_HANDSHAKE_FAILURE,
    DETECT_TORST,
};

use super::protect_path_owned;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ResponseFailureEvidenceSettings {
    pub protect_path: Option<String>,
}

pub fn response_failure_evidence_settings(config: &RuntimeConfig) -> ResponseFailureEvidenceSettings {
    ResponseFailureEvidenceSettings { protect_path: protect_path_owned(config) }
}

#[derive(Clone, Copy)]
pub struct FirstResponseSettings {
    pub buffer_size: usize,
    pub partial_timeout_ms: u32,
    pub timeout_ms: u32,
    pub timeout_count_limit: i32,
    pub timeout_bytes_limit: i32,
    pub fallback_timeout_required: bool,
}

pub fn first_response_settings(config: &RuntimeConfig) -> FirstResponseSettings {
    FirstResponseSettings {
        buffer_size: config.network.buffer_size.max(16_384),
        partial_timeout_ms: config.timeouts.partial_timeout_ms,
        timeout_ms: config.timeouts.timeout_ms,
        timeout_count_limit: config.timeouts.timeout_count_limit.max(1),
        timeout_bytes_limit: config.timeouts.timeout_bytes_limit,
        fallback_timeout_required: config.groups.iter().any(|group| {
            [DETECT_HTTP_LOCAT, DETECT_HTTP_BLOCKPAGE, DETECT_TLS_HANDSHAKE_FAILURE, DETECT_TLS_ALERT, DETECT_TORST]
                .iter()
                .any(|flag| group.matches.detect & *flag != 0)
        }),
    }
}

pub fn first_response_timeout(settings: FirstResponseSettings, tls_partial_active: bool) -> Option<Duration> {
    if tls_partial_active {
        Some(Duration::from_millis(settings.partial_timeout_ms as u64))
    } else if settings.timeout_ms != 0 {
        Some(Duration::from_millis(settings.timeout_ms as u64))
    } else if settings.fallback_timeout_required {
        Some(Duration::from_millis(250))
    } else {
        None
    }
}

pub fn first_response_timeout_count_limit(settings: FirstResponseSettings) -> i32 {
    settings.timeout_count_limit
}

pub fn first_response_bytes_limit(settings: FirstResponseSettings, default_limit: usize) -> usize {
    match usize::try_from(settings.timeout_bytes_limit) {
        Ok(limit) if limit != 0 => limit,
        _ => default_limit,
    }
}

#[cfg(test)]
mod tests {
    use ripdpi_config::RuntimeConfig;

    use super::*;

    #[test]
    fn response_failure_evidence_settings_project_protect_path() {
        let mut config = RuntimeConfig::default();
        config.process.protect_path = Some("/tmp/protect.sock".to_string());

        assert_eq!(
            response_failure_evidence_settings(&config),
            ResponseFailureEvidenceSettings { protect_path: Some("/tmp/protect.sock".to_string()) },
        );
    }
}
