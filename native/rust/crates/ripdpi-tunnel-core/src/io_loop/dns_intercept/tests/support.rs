use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
use hickory_proto::rr::rdata::A;
use hickory_proto::rr::{Name, RData, Record, RecordType};

use super::super::MapDnsRuntime;

pub(super) fn tunnel_config_with_mapdns(
    mapdns: Option<ripdpi_tunnel_config::MapDnsConfig>,
) -> ripdpi_tunnel_config::Config {
    ripdpi_tunnel_config::Config {
        tunnel: ripdpi_tunnel_config::TunnelConfig::default(),
        socks5: ripdpi_tunnel_config::Socks5Config {
            port: 1080,
            address: "127.0.0.1".to_string(),
            udp: None,
            udp_address: None,
            pipeline: None,
            username: None,
            password: None,
            mark: None,
        },
        mapdns,
        split_dns_policy: None,
        misc: ripdpi_tunnel_config::MiscConfig::default(),
    }
}

pub(super) fn mapdns_config(cache_size: u32) -> ripdpi_tunnel_config::MapDnsConfig {
    ripdpi_tunnel_config::MapDnsConfig {
        address: "198.18.0.10".to_string(),
        port: 53,
        network: None,
        netmask: None,
        cache_size,
        resolver_id: None,
        encrypted_dns_protocol: None,
        encrypted_dns_host: None,
        encrypted_dns_port: None,
        encrypted_dns_tls_server_name: None,
        encrypted_dns_bootstrap_ips: Vec::new(),
        encrypted_dns_doh_url: None,
        encrypted_dns_dnscrypt_provider_name: None,
        encrypted_dns_dnscrypt_public_key: None,
        encrypted_dns_odoh_proxy_url: None,
        encrypted_dns_odoh_proxy_operator_id: None,
        encrypted_dns_odoh_target_host: None,
        encrypted_dns_odoh_target_path: None,
        encrypted_dns_odoh_target_operator_id: None,
        encrypted_dns_odoh_config_source: None,
        encrypted_dns_odoh_configs_hex: None,
        encrypted_dns_odoh_configs_retrieved_at_secs: None,
        encrypted_dns_odoh_configs_ttl_secs: None,
        encrypted_dns_tls_roots_pem: None,
        dns_query_timeout_ms: 4000,
        resolver_fallback_active: false,
        resolver_fallback_reason: None,
        route_dns_through_socks5: false,
    }
}

pub(super) fn test_mapdns() -> MapDnsRuntime {
    MapDnsRuntime {
        intercept_addr: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 53),
        synthetic_net: u32::from(Ipv4Addr::new(198, 18, 0, 0)),
        synthetic_mask: u32::from(Ipv4Addr::new(255, 254, 0, 0)),
        intercept_port: 53,
    }
}

pub(super) fn empty_split_dns_policy() -> ripdpi_tunnel_config::SplitDnsPolicyConfig {
    ripdpi_tunnel_config::SplitDnsPolicyConfig {
        canonical_digest: String::new(),
        destination_routing_digest: String::new(),
        default_action: ripdpi_tunnel_config::SplitDnsAction::Tunneled,
        rules: Vec::new(),
        direct_resolver_candidates: Vec::new(),
        bootstrap_pins: Vec::new(),
        geosite_db_path: None,
        coverage_reason: None,
    }
}

pub(super) fn test_dns_cache() -> crate::dns_cache::DnsCache {
    let mapdns = test_mapdns();
    crate::dns_cache::DnsCache::new(mapdns.synthetic_net, mapdns.synthetic_mask, 8).expect("valid cache")
}

pub(super) fn build_query(name: &str) -> Vec<u8> {
    let mut message = Message::new(0x1234, MessageType::Query, OpCode::Query);
    message.metadata.recursion_desired = true;
    message.add_query(Query::new(Name::from_ascii(name).expect("name"), RecordType::A));
    message.to_vec().expect("query encodes")
}

pub(super) fn build_response(name: &str, ip: Ipv4Addr) -> Vec<u8> {
    let mut message = Message::response(0x1234, OpCode::Query);
    message.metadata.recursion_desired = true;
    message.metadata.recursion_available = true;
    message.metadata.response_code = ResponseCode::NoError;
    message.add_query(Query::new(Name::from_ascii(name).expect("name"), RecordType::A));
    message.add_answer(Record::from_rdata(Name::from_ascii(name).expect("name"), 60, RData::A(A(ip))));
    message.to_vec().expect("response encodes")
}
