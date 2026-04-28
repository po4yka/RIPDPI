mod config;

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use ripdpi_dns_resolver::{EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess, EncryptedDnsResolver};
use tokio_util::sync::CancellationToken;
use tracing::{debug, warn};

pub(super) use self::config::{build_encrypted_dns_resolver, parse_dns_cache, parse_mapdns_runtime};

use crate::dns_cache::DnsCache;
use crate::{Stats, TunDevice};

use super::bridge::enqueue_tun_packet;
use super::packet::build_udp_response;

#[derive(Debug, Clone, Copy)]
pub(super) struct MapDnsRuntime {
    pub(super) intercept_addr: SocketAddr,
    pub(super) synthetic_net: u32,
    pub(super) synthetic_mask: u32,
    pub(super) intercept_port: u16,
}

#[derive(Debug, Clone)]
pub(super) struct DnsRequest {
    pub(super) src: SocketAddr,
    pub(super) query: Vec<u8>,
    pub(super) host: Option<String>,
}

#[derive(Debug, Clone)]
pub(super) struct DnsResponse {
    pub(super) src: SocketAddr,
    pub(super) query: Vec<u8>,
    pub(super) host: Option<String>,
    pub(super) upstream: Result<EncryptedDnsExchangeSuccess, String>,
    pub(super) resolver_error_kind: Option<EncryptedDnsErrorKind>,
}

pub(super) fn dns_query_name(packet: &[u8]) -> Option<String> {
    if packet.len() < 12 {
        return None;
    }
    if u16::from_be_bytes([packet[4], packet[5]]) == 0 {
        return None;
    }

    let mut offset = 12usize;
    let mut labels = Vec::new();
    loop {
        let length = *packet.get(offset)? as usize;
        offset += 1;
        if length == 0 {
            break;
        }
        if length & 0b1100_0000 != 0 {
            return None;
        }
        let label = packet.get(offset..offset + length)?;
        labels.push(std::str::from_utf8(label).ok()?.to_string());
        offset += length;
    }
    if labels.is_empty() {
        None
    } else {
        Some(labels.join("."))
    }
}

pub(super) fn handle_dns_result(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    dns_cache: &mut DnsCache,
    response: DnsResponse,
) {
    match response.upstream {
        Ok(upstream) => match dns_cache.rewrite_response(&response.query, &upstream.response_bytes) {
            Ok(result) => {
                stats.record_dns_success(
                    &result.host,
                    result.cache_hits,
                    result.cache_misses,
                    Some(&upstream.endpoint_label),
                    Some(upstream.latency_ms),
                );
                let raw = build_udp_response(mapdns.intercept_addr, response.src, &result.response);
                enqueue_tun_packet(device, raw, "dns");
            }
            Err(err) => {
                let message = err.to_string();
                stats.record_dns_failure(response.host.as_deref(), &message, Some(&upstream.endpoint_label));
                match dns_cache.servfail_response(&response.query) {
                    Ok(servfail) => {
                        let raw = build_udp_response(mapdns.intercept_addr, response.src, &servfail);
                        enqueue_tun_packet(device, raw, "dns-servfail");
                    }
                    Err(servfail_err) => debug!("failed to synthesize SERVFAIL after rewrite error: {servfail_err}"),
                }
            }
        },
        Err(err) => {
            let formatted_error = response.resolver_error_kind.map(|kind| format!("{kind:?}: {err}")).unwrap_or(err);
            stats.record_dns_failure(response.host.as_deref(), &formatted_error, None);
            match dns_cache.servfail_response(&response.query) {
                Ok(servfail) => {
                    let raw = build_udp_response(mapdns.intercept_addr, response.src, &servfail);
                    enqueue_tun_packet(device, raw, "dns-servfail");
                }
                Err(servfail_err) => debug!("failed to synthesize SERVFAIL after upstream failure: {servfail_err}"),
            }
        }
    }
}

pub(super) fn resolve_mapped_target(
    stats: &Arc<Stats>,
    dns_cache: &mut Option<DnsCache>,
    dst: SocketAddr,
) -> Option<SocketAddr> {
    let Some(cache) = dns_cache.as_mut() else {
        return Some(dst);
    };
    let IpAddr::V4(v4) = dst.ip() else {
        return Some(dst);
    };
    let ip = u32::from(v4);
    if !cache.contains_mapped_ip(ip) {
        return Some(dst);
    }
    let Some(entry) = cache.lookup(ip) else {
        warn!("mapdns reverse lookup miss for synthetic target {}; dropping connection", dst);
        return None;
    };
    stats.record_last_host(Some(&entry.host));
    Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(entry.real_ip)), dst.port()))
}

pub(super) fn spawn_dns_worker(
    resolver: EncryptedDnsResolver,
    cancel: CancellationToken,
) -> (tokio::sync::mpsc::Sender<DnsRequest>, tokio::sync::mpsc::Receiver<DnsResponse>) {
    let (req_tx, mut req_rx) = tokio::sync::mpsc::channel::<DnsRequest>(super::DNS_QUEUE_CAPACITY);
    let (resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(super::DNS_QUEUE_CAPACITY);
    tokio::spawn(async move {
        loop {
            tokio::select! {
                _ = cancel.cancelled() => break,
                request = req_rx.recv() => {
                    let Some(request) = request else {
                        break;
                    };
                    let upstream =
                        resolver
                            .exchange_with_metadata(&request.query)
                            .await
                            .map_err(|err| {
                                let kind = err.kind();
                                (kind, err.to_string())
                            });
                    let (resolver_error_kind, upstream) = match upstream {
                        Ok(success) => (None, Ok(success)),
                        Err((kind, message)) => (Some(kind), Err(message)),
                    };
                    if resp_tx.send(DnsResponse {
                        src: request.src,
                        query: request.query,
                        host: request.host,
                        upstream,
                        resolver_error_kind,
                    }).await.is_err() {
                        break;
                    }
                }
            }
        }
    });
    (req_tx, resp_rx)
}

/// Route a DNS query packet: enqueue to the resolver channel, or send SERVFAIL
/// if the channel is full/closed or the resolver is not configured.
///
/// When the resolver channel is closed, `dns_req_tx` and `dns_resp_rx` are set
/// to `None` so that the caller stops attempting to send further queries.
#[allow(clippy::too_many_arguments)]
pub(super) fn route_dns_packet(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns_runtime: Option<MapDnsRuntime>,
    dns_cache: Option<&DnsCache>,
    dns_req_tx: &mut Option<tokio::sync::mpsc::Sender<DnsRequest>>,
    dns_resp_rx: &mut Option<tokio::sync::mpsc::Receiver<DnsResponse>>,
    src: SocketAddr,
    payload: &[u8],
    host: Option<String>,
) {
    let request = DnsRequest { src, query: payload.to_vec(), host };
    match (&mapdns_runtime, dns_cache, dns_req_tx.as_ref()) {
        (Some(_), Some(_), Some(request_tx)) => match request_tx.try_send(request) {
            Ok(()) => {}
            Err(tokio::sync::mpsc::error::TrySendError::Full(request)) => {
                if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache) {
                    super::send_dns_servfail(
                        device,
                        stats,
                        mapdns,
                        cache,
                        request.src,
                        &request.query,
                        request.host.as_deref(),
                        "dns worker queue full",
                    );
                }
            }
            Err(tokio::sync::mpsc::error::TrySendError::Closed(request)) => {
                if let (Some(mapdns), Some(cache)) = (mapdns_runtime, dns_cache) {
                    super::send_dns_servfail(
                        device,
                        stats,
                        mapdns,
                        cache,
                        request.src,
                        &request.query,
                        request.host.as_deref(),
                        "dns worker unavailable",
                    );
                }
                *dns_req_tx = None;
                *dns_resp_rx = None;
            }
        },
        (Some(mapdns), Some(cache), None) => {
            super::send_dns_servfail(
                device,
                stats,
                *mapdns,
                cache,
                src,
                payload,
                request.host.as_deref(),
                "encrypted DNS resolver is not configured",
            );
        }
        _ => {
            debug!("DNS intercept hit without mapdns runtime; dropping packet");
        }
    }
}

/// Drain all pending DNS responses from the receiver channel and process them.
///
/// When the channel is disconnected, `dns_req_tx` and `dns_resp_rx` are set
/// to `None`.
pub(super) fn drain_dns_responses(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    cache: &mut DnsCache,
    dns_resp_rx: &mut Option<tokio::sync::mpsc::Receiver<DnsResponse>>,
    dns_req_tx: &mut Option<tokio::sync::mpsc::Sender<DnsRequest>>,
) {
    loop {
        let dns_response = match dns_resp_rx.as_mut() {
            Some(receiver) => match receiver.try_recv() {
                Ok(response) => Some(response),
                Err(tokio::sync::mpsc::error::TryRecvError::Empty) => None,
                Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => {
                    stats.record_dns_failure(None, "dns worker exited unexpectedly", None);
                    *dns_req_tx = None;
                    *dns_resp_rx = None;
                    None
                }
            },
            None => None,
        };
        let Some(response) = dns_response else {
            break;
        };
        handle_dns_result(device, stats, mapdns, cache, response);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};
    use std::sync::Arc;

    use hickory_proto::op::{Message, MessageType, OpCode, Query, ResponseCode};
    use hickory_proto::rr::rdata::A;
    use hickory_proto::rr::{Name, RData, Record, RecordType};
    use ripdpi_dns_resolver::EncryptedDnsProtocol;

    fn tunnel_config_with_mapdns(mapdns: Option<ripdpi_tunnel_config::MapDnsConfig>) -> ripdpi_tunnel_config::Config {
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
            misc: ripdpi_tunnel_config::MiscConfig::default(),
        }
    }

    fn build_query(name: &str) -> Vec<u8> {
        let mut message = Message::new(0x1234, MessageType::Query, OpCode::Query);
        message.metadata.recursion_desired = true;
        message.add_query(Query::query(Name::from_ascii(name).expect("name"), RecordType::A));
        message.to_vec().expect("query encodes")
    }

    fn build_response(name: &str, ip: Ipv4Addr) -> Vec<u8> {
        let mut message = Message::response(0x1234, OpCode::Query);
        message.metadata.recursion_desired = true;
        message.metadata.recursion_available = true;
        message.metadata.response_code = ResponseCode::NoError;
        message.add_query(Query::query(Name::from_ascii(name).expect("name"), RecordType::A));
        message.add_answer(Record::from_rdata(Name::from_ascii(name).expect("name"), 60, RData::A(A(ip))));
        message.to_vec().expect("response encodes")
    }

    #[test]
    fn parse_mapdns_runtime_uses_address_defaults_for_network_and_mask() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 5300,
            network: None,
            netmask: None,
            cache_size: 8,
            resolver_id: None,
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: Vec::new(),
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            dns_query_timeout_ms: 4000,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let runtime = parse_mapdns_runtime(&config).expect("runtime").expect("mapdns runtime");

        assert_eq!(runtime.intercept_addr, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 5300));
        assert_eq!(runtime.synthetic_net, u32::from(Ipv4Addr::new(198, 18, 0, 0)));
        assert_eq!(runtime.synthetic_mask, u32::from(Ipv4Addr::new(255, 254, 0, 0)));
        assert_eq!(runtime.intercept_port, 5300);
    }

    #[test]
    fn parse_mapdns_runtime_normalizes_explicit_network_with_host_bits() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 5300,
            network: Some("100.64.0.123".to_string()),
            netmask: Some("255.192.0.0".to_string()),
            cache_size: 8,
            resolver_id: None,
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: Vec::new(),
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            dns_query_timeout_ms: 4000,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let runtime = parse_mapdns_runtime(&config).expect("runtime").expect("mapdns runtime");

        assert_eq!(runtime.synthetic_net, u32::from(Ipv4Addr::new(100, 64, 0, 0)));
        assert_eq!(runtime.synthetic_mask, u32::from(Ipv4Addr::new(255, 192, 0, 0)));
    }

    #[test]
    fn normalized_default_mapdns_network_preserves_reverse_lookup() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 5300,
            network: None,
            netmask: None,
            cache_size: 8,
            resolver_id: None,
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: Vec::new(),
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            dns_query_timeout_ms: 4000,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let runtime = parse_mapdns_runtime(&config).expect("runtime").expect("mapdns runtime");
        let mut cache = DnsCache::new(runtime.synthetic_net, runtime.synthetic_mask, 8);
        let query = build_query("fixture.test");
        let upstream = build_response("fixture.test", Ipv4Addr::new(203, 0, 113, 10));
        let rewritten = cache.rewrite_response(&query, &upstream).expect("rewrite succeeds");
        let message = Message::from_vec(&rewritten.response).expect("rewritten response parses");
        let synthetic_ip = message
            .answers
            .iter()
            .find_map(|record| match &record.data {
                RData::A(address) => Some(address.0),
                _ => None,
            })
            .expect("rewritten ipv4 answer");
        let stats = Arc::new(Stats::default());
        let resolved = resolve_mapped_target(&stats, &mut Some(cache), SocketAddr::new(IpAddr::V4(synthetic_ip), 443));

        assert_eq!(resolved, Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443)));
    }

    #[test]
    fn resolve_mapped_target_returns_none_for_unmapped_synthetic_ip() {
        let mapdns = test_mapdns();
        let cache = DnsCache::new(mapdns.synthetic_net, mapdns.synthetic_mask, 8);
        let stats = Arc::new(Stats::default());
        // 198.18.0.53 is within the synthetic range but has no cache entry (e.g. the
        // VPN's own DNS intercept address). Attempting to proxy traffic to it would
        // loop and time out, so resolve_mapped_target must return None.
        let synthetic_dns = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 53)), 853);
        let resolved = resolve_mapped_target(&stats, &mut Some(cache), synthetic_dns);
        assert_eq!(resolved, None);
    }

    #[test]
    fn parse_dns_cache_rejects_zero_cache_size() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 53,
            network: None,
            netmask: None,
            cache_size: 0,
            resolver_id: None,
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: Vec::new(),
            encrypted_dns_doh_url: None,
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            dns_query_timeout_ms: 4000,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let Err(err) = parse_dns_cache(&config, None) else {
            panic!("zero cache size should fail");
        };

        assert_eq!(err.kind(), std::io::ErrorKind::InvalidInput);
        assert_eq!(err.to_string(), "mapdns.cache_size must be greater than zero");
    }

    #[test]
    fn build_encrypted_dns_resolver_uses_doh_url_defaults() {
        let config = tunnel_config_with_mapdns(Some(ripdpi_tunnel_config::MapDnsConfig {
            address: "198.18.0.10".to_string(),
            port: 53,
            network: None,
            netmask: None,
            cache_size: 16,
            resolver_id: Some("fixture".to_string()),
            encrypted_dns_protocol: None,
            encrypted_dns_host: None,
            encrypted_dns_port: None,
            encrypted_dns_tls_server_name: None,
            encrypted_dns_bootstrap_ips: vec!["1.1.1.1".to_string(), "1.0.0.1".to_string()],
            encrypted_dns_doh_url: Some("https://dns.example.test/dns-query".to_string()),
            encrypted_dns_dnscrypt_provider_name: None,
            encrypted_dns_dnscrypt_public_key: None,
            dns_query_timeout_ms: 2500,
            resolver_fallback_active: false,
            resolver_fallback_reason: None,
        }));

        let resolver = build_encrypted_dns_resolver(&config).expect("resolver build").expect("resolver");
        let endpoint = resolver.endpoint();

        assert_eq!(endpoint.protocol, EncryptedDnsProtocol::Doh);
        assert_eq!(endpoint.host, "dns.example.test");
        assert_eq!(endpoint.port, 443);
        assert_eq!(endpoint.tls_server_name.as_deref(), Some("dns.example.test"));
        assert_eq!(
            endpoint.bootstrap_ips,
            vec![IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1))],
        );
        assert_eq!(endpoint.doh_url.as_deref(), Some("https://dns.example.test/dns-query"));
    }

    #[test]
    fn dns_query_name_extracts_labels_and_rejects_compression_pointers() {
        let plain_query = [
            0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, b'w', b'w', b'w', 0x07, b'e',
            b'x', b'a', b'm', b'p', b'l', b'e', 0x03, b'c', b'o', b'm', 0x00, 0x00, 0x01, 0x00, 0x01,
        ];
        let compressed_query = [0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xc0, 0x0c];

        assert_eq!(dns_query_name(&plain_query), Some("www.example.com".to_string()));
        assert_eq!(dns_query_name(&compressed_query), None);
    }

    #[test]
    fn handle_dns_result_queues_response_for_later_tun_flush() {
        let mapdns = MapDnsRuntime {
            intercept_addr: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 53),
            synthetic_net: u32::from(Ipv4Addr::new(198, 18, 0, 0)),
            synthetic_mask: u32::from(Ipv4Addr::new(255, 254, 0, 0)),
            intercept_port: 53,
        };
        let mut cache = DnsCache::new(mapdns.synthetic_net, mapdns.synthetic_mask, 8);
        let mut device = TunDevice::new(1500);
        let stats = Arc::new(Stats::default());
        let query = build_query("fixture.test");
        let upstream = build_response("fixture.test", Ipv4Addr::new(203, 0, 113, 10));

        handle_dns_result(
            &mut device,
            &stats,
            mapdns,
            &mut cache,
            DnsResponse {
                src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000),
                query,
                host: Some("fixture.test".to_string()),
                upstream: Ok(EncryptedDnsExchangeSuccess {
                    response_bytes: upstream,
                    endpoint_label: "fixture".to_string(),
                    latency_ms: 12,
                }),
                resolver_error_kind: None,
            },
        );

        assert_eq!(device.tx_queue.len(), 1);
        assert!(!device.tx_queue.front().expect("queued packet").is_empty());
    }

    // ── route_dns_packet tests ───────────────────────────────────────────────

    fn test_mapdns() -> MapDnsRuntime {
        MapDnsRuntime {
            intercept_addr: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(198, 18, 0, 10)), 53),
            synthetic_net: u32::from(Ipv4Addr::new(198, 18, 0, 0)),
            synthetic_mask: u32::from(Ipv4Addr::new(255, 254, 0, 0)),
            intercept_port: 53,
        }
    }

    fn test_dns_cache() -> DnsCache {
        let mapdns = test_mapdns();
        DnsCache::new(mapdns.synthetic_net, mapdns.synthetic_mask, 8)
    }

    #[test]
    fn route_dns_sends_to_resolver() {
        let (tx, mut rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
        let (_resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
        let mut dns_req_tx = Some(tx);
        let mut dns_resp_rx = Some(resp_rx);
        let cache = test_dns_cache();
        let mut device = TunDevice::new(1500);
        let stats = Arc::new(Stats::default());
        let query = build_query("example.test");
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

        route_dns_packet(
            &mut device,
            &stats,
            Some(test_mapdns()),
            Some(&cache),
            &mut dns_req_tx,
            &mut dns_resp_rx,
            src,
            &query,
            Some("example.test".to_string()),
        );

        let request = rx.try_recv().expect("request should be enqueued");
        assert_eq!(request.src, src);
        assert_eq!(request.host.as_deref(), Some("example.test"));
        assert!(device.tx_queue.is_empty(), "no response packet should be queued");
        assert!(dns_req_tx.is_some(), "channels should remain open");
    }

    #[test]
    fn route_dns_full_queue_sends_servfail() {
        // Create a channel with capacity 1 and fill it
        let (tx, _rx) = tokio::sync::mpsc::channel::<DnsRequest>(1);
        let (_resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
        let mut dns_req_tx = Some(tx);
        let mut dns_resp_rx = Some(resp_rx);
        let cache = test_dns_cache();
        let mut device = TunDevice::new(1500);
        let stats = Arc::new(Stats::default());
        let query = build_query("first.test");
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

        // Fill the channel
        route_dns_packet(
            &mut device,
            &stats,
            Some(test_mapdns()),
            Some(&cache),
            &mut dns_req_tx,
            &mut dns_resp_rx,
            src,
            &query,
            Some("first.test".to_string()),
        );
        assert!(device.tx_queue.is_empty());

        // Second request should trigger SERVFAIL
        let query2 = build_query("second.test");
        route_dns_packet(
            &mut device,
            &stats,
            Some(test_mapdns()),
            Some(&cache),
            &mut dns_req_tx,
            &mut dns_resp_rx,
            src,
            &query2,
            Some("second.test".to_string()),
        );

        assert!(!device.tx_queue.is_empty(), "SERVFAIL response should be enqueued");
        assert!(dns_req_tx.is_some(), "channels should remain open after full queue");
    }

    #[test]
    fn route_dns_closed_channel_nulls_tx_rx() {
        let (tx, rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
        let (_resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
        let mut dns_req_tx = Some(tx);
        let mut dns_resp_rx = Some(resp_rx);
        let cache = test_dns_cache();
        let mut device = TunDevice::new(1500);
        let stats = Arc::new(Stats::default());
        let query = build_query("closed.test");
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

        // Drop the receiver to close the channel
        drop(rx);

        route_dns_packet(
            &mut device,
            &stats,
            Some(test_mapdns()),
            Some(&cache),
            &mut dns_req_tx,
            &mut dns_resp_rx,
            src,
            &query,
            Some("closed.test".to_string()),
        );

        assert!(dns_req_tx.is_none(), "dns_req_tx should be set to None after closed channel");
        assert!(dns_resp_rx.is_none(), "dns_resp_rx should be set to None after closed channel");
        assert!(!device.tx_queue.is_empty(), "SERVFAIL response should be enqueued");
    }

    #[test]
    fn route_dns_no_resolver_sends_servfail() {
        let mut dns_req_tx: Option<tokio::sync::mpsc::Sender<DnsRequest>> = None;
        let mut dns_resp_rx: Option<tokio::sync::mpsc::Receiver<DnsResponse>> = None;
        let cache = test_dns_cache();
        let mut device = TunDevice::new(1500);
        let stats = Arc::new(Stats::default());
        let query = build_query("no-resolver.test");
        let src = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000);

        route_dns_packet(
            &mut device,
            &stats,
            Some(test_mapdns()),
            Some(&cache),
            &mut dns_req_tx,
            &mut dns_resp_rx,
            src,
            &query,
            Some("no-resolver.test".to_string()),
        );

        assert!(!device.tx_queue.is_empty(), "SERVFAIL should be sent when no resolver");
    }

    // ── drain_dns_responses tests ────────────────────────────────────────────

    #[test]
    fn drain_dns_responses_processes_pending() {
        let mapdns = test_mapdns();
        let mut cache = test_dns_cache();
        let mut device = TunDevice::new(1500);
        let stats = Arc::new(Stats::default());
        let (resp_tx, resp_rx) = tokio::sync::mpsc::channel::<DnsResponse>(8);
        let (req_tx, _req_rx) = tokio::sync::mpsc::channel::<DnsRequest>(8);
        let mut dns_resp_rx = Some(resp_rx);
        let mut dns_req_tx = Some(req_tx);

        let query = build_query("drain.test");
        let upstream = build_response("drain.test", Ipv4Addr::new(1, 2, 3, 4));

        // Pre-fill the channel with a response
        resp_tx
            .try_send(DnsResponse {
                src: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 0, 0, 2)), 53000),
                query,
                host: Some("drain.test".to_string()),
                upstream: Ok(EncryptedDnsExchangeSuccess {
                    response_bytes: upstream,
                    endpoint_label: "test".to_string(),
                    latency_ms: 5,
                }),
                resolver_error_kind: None,
            })
            .expect("send response");

        drain_dns_responses(&mut device, &stats, mapdns, &mut cache, &mut dns_resp_rx, &mut dns_req_tx);

        assert_eq!(device.tx_queue.len(), 1, "response should have been processed and queued");
        assert!(dns_req_tx.is_some(), "channels should remain open");
    }
}
