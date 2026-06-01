use ripdpi_monitor_adapter::proxy_config::{ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK, ProxyUiConfig};

pub fn freeze_adaptive_fake_ttl_for_probe(runtime_config: &mut ProxyUiConfig) {
    if !runtime_config.fake_packets.adaptive_fake_ttl_enabled {
        return;
    }
    let min_ttl = runtime_config.fake_packets.adaptive_fake_ttl_min.clamp(1, 255);
    let max_ttl = runtime_config.fake_packets.adaptive_fake_ttl_max.clamp(min_ttl, 255);
    let fallback = if runtime_config.fake_packets.adaptive_fake_ttl_fallback > 0 {
        runtime_config.fake_packets.adaptive_fake_ttl_fallback
    } else if runtime_config.fake_packets.fake_ttl > 0 {
        runtime_config.fake_packets.fake_ttl
    } else {
        ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK
    };
    runtime_config.fake_packets.fake_ttl = fallback.clamp(min_ttl, max_ttl);
    runtime_config.fake_packets.adaptive_fake_ttl_enabled = false;
}
