pub mod config {
    use std::time::Duration;

    pub use ripdpi_config::*;

    pub fn selected_desync_group(config: &RuntimeConfig, group_index: usize) -> Option<&DesyncGroup> {
        config.groups.get(group_index)
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
}

pub mod proxy_config {
    pub use ripdpi_proxy_config::*;

    pub fn morph_policy(context: Option<&ProxyRuntimeContext>) -> Option<&ProxyMorphPolicy> {
        context?.morph_policy.as_ref()
    }

    pub fn morph_policy_id(policy: &ProxyMorphPolicy) -> &str {
        policy.id.as_str()
    }

    pub fn apply_udp_morph_policy_to_hints(
        policy: Option<&ProxyMorphPolicy>,
        hints: crate::desync::AdaptivePlannerHints,
    ) -> crate::desync::AdaptivePlannerHints {
        ripdpi_runtime_services::decision_helpers::apply_udp_morph_policy_to_hints(policy, hints)
    }

    pub fn apply_tcp_morph_policy_to_group(
        policy: Option<&ProxyMorphPolicy>,
        group: &crate::model::config::DesyncGroup,
        payload: &[u8],
        hints: crate::desync::AdaptivePlannerHints,
    ) -> crate::model::config::DesyncGroup {
        ripdpi_runtime_services::decision_helpers::apply_tcp_morph_policy_to_group(policy, group, payload, hints)
    }

    pub fn tcp_morph_hint_family(
        policy: Option<&ProxyMorphPolicy>,
        payload: &[u8],
        hints: crate::desync::AdaptivePlannerHints,
    ) -> Option<String> {
        ripdpi_runtime_services::decision_helpers::tcp_morph_hint_family(policy, payload, hints)
    }

    pub fn udp_morph_hint_family(
        policy: Option<&ProxyMorphPolicy>,
        hints: crate::desync::AdaptivePlannerHints,
    ) -> Option<String> {
        ripdpi_runtime_services::decision_helpers::udp_morph_hint_family(policy, hints)
    }

    pub fn emit_morph_hint_applied(
        telemetry: Option<&dyn crate::model::runtime_api::RuntimeTelemetrySink>,
        policy: Option<&ProxyMorphPolicy>,
        target: std::net::SocketAddr,
        family: Option<String>,
    ) {
        let Some(telemetry) = telemetry else {
            return;
        };
        let Some(policy) = policy else {
            return;
        };
        let Some(family) = family.as_deref().filter(|value| !value.is_empty()) else {
            return;
        };
        telemetry.on_morph_hint_applied(target, morph_policy_id(policy), family);
    }

    pub fn emit_morph_rollback(
        telemetry: Option<&dyn crate::model::runtime_api::RuntimeTelemetrySink>,
        policy: Option<&ProxyMorphPolicy>,
        target: std::net::SocketAddr,
        reason: impl AsRef<str>,
    ) {
        let Some(telemetry) = telemetry else {
            return;
        };
        let Some(policy) = policy else {
            return;
        };
        let reason = reason.as_ref();
        if reason.is_empty() {
            return;
        }
        telemetry.on_morph_rollback(target, morph_policy_id(policy), reason);
    }
}

pub mod runtime_api {
    pub use ripdpi_runtime_api::*;
}

pub mod session {
    pub use ripdpi_session::*;

    pub fn is_tls_client_hello_payload(payload: &[u8]) -> bool {
        ripdpi_runtime_services::decision_helpers::is_tls_client_hello_payload(payload)
    }
}
