use ripdpi_config::{DesyncGroup, RuntimeConfig, DETECT_CONNECT, DETECT_HTTP_LOCAT, DETECT_TLS_ERR, DETECT_TORST};

use crate::types::ProxyUiAdaptiveFallbackConfig;

pub(crate) fn append_fallback_groups(
    groups: &mut Vec<DesyncGroup>,
    config: &RuntimeConfig,
    tcp_proto: u32,
    udp_enabled: bool,
    adaptive_fallback: &ProxyUiAdaptiveFallbackConfig,
) {
    let has_tcp_proto = tcp_proto != 0;
    if !(has_tcp_proto || udp_enabled) {
        return;
    }

    let adaptive_detect = adaptive_detect_mask(adaptive_fallback);
    if adaptive_fallback.enabled && adaptive_detect != 0 && has_tcp_proto {
        let mut adaptive_direct = DesyncGroup::new(groups.len());
        adaptive_direct.matches.detect = adaptive_detect;
        adaptive_direct.matches.proto = tcp_proto;
        adaptive_direct.policy.label = "adaptive_direct".to_string();
        adaptive_direct.policy.cache_ttl = config.adaptive.cache_ttl;
        groups.push(adaptive_direct);
    }

    let mut fallback = DesyncGroup::new(groups.len());
    fallback.matches.detect = DETECT_CONNECT;
    groups.push(fallback);
}

fn adaptive_detect_mask(config: &ProxyUiAdaptiveFallbackConfig) -> u32 {
    let mut detect = 0;
    if config.torst {
        detect |= DETECT_TORST;
    }
    if config.tls_err {
        detect |= DETECT_TLS_ERR;
    }
    if config.http_redirect {
        detect |= DETECT_HTTP_LOCAT;
    }
    if config.connect_failure {
        detect |= DETECT_CONNECT;
    }
    detect
}
