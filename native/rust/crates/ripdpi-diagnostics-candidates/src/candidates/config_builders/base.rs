use crate::candidates::prelude::*;

pub fn sanitize_current_probe_config(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = base.clone();
    config.host_autolearn.enabled = false;
    config.host_autolearn.store_path = None;
    config
}

pub fn strategy_probe_base(base: &ProxyUiConfig) -> ProxyUiConfig {
    let mut config = sanitize_current_probe_config(base);
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
