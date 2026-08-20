use crate::candidates::prelude::*;

pub fn sanitize_current_probe_config(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = base.clone();
    config.host_autolearn.enabled = false;
    config.host_autolearn.store_path = None;
    config
}

/// Builds the transparent direct-path oracle without inheriting any current
/// evasion, relay, tunnel, routing, or host override state.
pub fn plain_direct_probe_config(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = ProxyUiConfig {
        listen: base.listen.clone(),
        native_log_level: base.native_log_level.clone(),
        root_mode: base.root_mode,
        root_helper_socket_path: base.root_helper_socket_path.clone(),
        geoip_db_path: base.geoip_db_path.clone(),
        geosite_db_path: base.geosite_db_path.clone(),
        environment_kind: base.environment_kind.clone(),
        ..ProxyUiConfig::default()
    };
    config.protocols.desync_http = false;
    config.protocols.desync_https = false;
    config.protocols.desync_udp = false;
    config.chains.tcp_steps.clear();
    config.chains.tcp_rotation = None;
    config.chains.udp_steps.clear();
    config.chains.group_activation_filter = None;
    config.chains.any_protocol = false;
    config.chains.payload_disable.clear();
    config.adaptive_fallback.enabled = false;
    config.adaptive_fallback.torst = false;
    config.adaptive_fallback.tls_err = false;
    config.adaptive_fallback.http_redirect = false;
    config.adaptive_fallback.connect_failure = false;
    config.adaptive_fallback.auto_sort = false;
    config.adaptive_fallback.strategy_evolution = false;
    config
}

pub fn strategy_probe_base(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = plain_direct_probe_config(base);
    config.protocols.desync_http = true;
    config.protocols.desync_https = true;
    config.protocols.desync_udp = false;
    config.chains.tcp_steps.clear();
    config.chains.udp_steps.clear();
    config.fake_packets.fake_ttl = 8;
    config.fake_packets.fake_tls_use_original = false;
    config.fake_packets.fake_tls_randomize = false;
    config.fake_packets.fake_tls_dup_session_id = false;
    config.fake_packets.fake_tls_pad_encap = false;
    config.fake_packets.fake_tls_size = 0;
    config.fake_packets.fake_tls_sni_mode = "fixed".to_string();
    config.fake_packets.drop_sack = false;
    config.fake_packets.fake_offset_marker = "0".to_string();
    config.parser_evasions.host_mixed_case = false;
    config.parser_evasions.domain_mixed_case = false;
    config.parser_evasions.host_remove_spaces = false;
    config.parser_evasions.http_method_space = false;
    config.parser_evasions.http_method_eol = false;
    config.parser_evasions.http_host_pad = false;
    config.parser_evasions.http_unix_eol = false;
    config.parser_evasions.http_host_extra_space = false;
    config.parser_evasions.http_host_tab = false;
    config.quic.fake_profile = "disabled".to_string();
    config.quic.fake_host.clear();
    config
}
