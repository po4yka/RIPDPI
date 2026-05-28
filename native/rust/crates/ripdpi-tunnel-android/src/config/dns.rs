mod protocol;

use ripdpi_tunnel_config::MapDnsConfig;

use crate::config::payload::TunnelConfigPayload;

pub(crate) use protocol::mapdns_resolver_protocol;

pub(crate) fn mapdns_config_from_payload(payload: &TunnelConfigPayload) -> Option<MapDnsConfig> {
    payload.mapdns_address.as_ref().map(|address| MapDnsConfig {
        address: address.clone(),
        port: payload.mapdns_port.unwrap_or(53),
        network: payload.mapdns_network.clone(),
        netmask: payload.mapdns_netmask.clone(),
        cache_size: payload.mapdns_cache_size.unwrap_or(10_000),
        resolver_id: payload.encrypted_dns_resolver_id.clone(),
        encrypted_dns_protocol: payload.encrypted_dns_protocol.clone(),
        encrypted_dns_host: payload.encrypted_dns_host.clone(),
        encrypted_dns_port: payload.encrypted_dns_port,
        encrypted_dns_tls_server_name: payload.encrypted_dns_tls_server_name.clone(),
        encrypted_dns_bootstrap_ips: payload.encrypted_dns_bootstrap_ips.clone(),
        encrypted_dns_doh_url: payload.encrypted_dns_doh_url.clone(),
        encrypted_dns_dnscrypt_provider_name: payload.encrypted_dns_dnscrypt_provider_name.clone(),
        encrypted_dns_dnscrypt_public_key: payload.encrypted_dns_dnscrypt_public_key.clone(),
        encrypted_dns_odoh_proxy_url: payload.encrypted_dns_odoh_proxy_url.clone(),
        encrypted_dns_odoh_proxy_operator_id: payload.encrypted_dns_odoh_proxy_operator_id.clone(),
        encrypted_dns_odoh_target_host: payload.encrypted_dns_odoh_target_host.clone(),
        encrypted_dns_odoh_target_path: payload.encrypted_dns_odoh_target_path.clone(),
        encrypted_dns_odoh_target_operator_id: payload.encrypted_dns_odoh_target_operator_id.clone(),
        encrypted_dns_odoh_config_source: payload.encrypted_dns_odoh_config_source.clone(),
        encrypted_dns_odoh_configs_hex: payload.encrypted_dns_odoh_configs_hex.clone(),
        encrypted_dns_odoh_configs_retrieved_at_secs: payload.encrypted_dns_odoh_configs_retrieved_at_secs,
        encrypted_dns_odoh_configs_ttl_secs: payload.encrypted_dns_odoh_configs_ttl_secs,
        encrypted_dns_tls_roots_pem: payload.encrypted_dns_tls_roots_pem.clone(),
        dns_query_timeout_ms: payload.dns_query_timeout_ms.unwrap_or(4_000),
        resolver_fallback_active: payload.resolver_fallback_active.unwrap_or(false),
        resolver_fallback_reason: payload.resolver_fallback_reason.clone(),
        route_dns_through_socks5: payload.route_dns_through_socks5.unwrap_or(false),
    })
}
