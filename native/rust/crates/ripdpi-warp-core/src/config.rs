use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use socket2::Domain;

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedWarpRuntimeEndpoint {
    pub host: String,
    pub ipv4: Option<String>,
    pub ipv6: Option<String>,
    pub port: i32,
    pub source: String,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResolvedWarpRuntimeConfig {
    pub enabled: bool,
    pub profile_id: String,
    pub account_kind: String,
    pub device_id: String,
    pub access_token: String,
    pub client_id: Option<String>,
    pub private_key: String,
    pub public_key: String,
    pub peer_public_key: String,
    pub interface_address_v4: Option<String>,
    pub interface_address_v6: Option<String>,
    pub endpoint: ResolvedWarpRuntimeEndpoint,
    pub route_mode: String,
    pub route_hosts: String,
    pub built_in_rules_enabled: bool,
    pub endpoint_selection_mode: String,
    pub manual_endpoint: WarpManualEndpoint,
    pub scanner_enabled: bool,
    pub scanner_parallelism: i32,
    pub scanner_max_rtt_ms: i32,
    pub amnezia: WarpAmneziaConfig,
    pub local_socks_host: String,
    pub local_socks_port: i32,
    pub mtu: i32,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct WarpManualEndpoint {
    pub host: String,
    pub ipv4: String,
    pub ipv6: String,
    pub port: i32,
}

#[derive(Debug, Clone, Deserialize, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct WarpAmneziaConfig {
    pub enabled: bool,
    pub jc: i32,
    pub jmin: i32,
    pub jmax: i32,
    pub h1: i64,
    pub h2: i64,
    pub h3: i64,
    pub h4: i64,
    pub s1: i32,
    pub s2: i32,
    pub s3: i32,
    pub s4: i32,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WarpEndpointProbeRequest {
    pub endpoint: ResolvedWarpRuntimeEndpoint,
    pub private_key: String,
    pub peer_public_key: String,
    pub client_id: Option<String>,
    #[serde(default)]
    pub amnezia: WarpAmneziaConfig,
    pub timeout_ms: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WarpEndpointProbeResult {
    pub host: String,
    pub ipv4: Option<String>,
    pub ipv6: Option<String>,
    pub port: i32,
    pub rtt_ms: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WarpTelemetry {
    pub source: &'static str,
    pub state: String,
    pub health: String,
    pub active_sessions: u64,
    pub total_sessions: u64,
    pub listener_address: Option<String>,
    pub upstream_address: Option<String>,
    pub upstream_rtt_ms: Option<u64>,
    pub profile_id: Option<String>,
    pub last_error: Option<String>,
    pub captured_at: u64,
}

pub(crate) fn resolve_sync_host(host: &str, port: u16) -> io::Result<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }
    (host, port)
        .to_socket_addrs()?
        .find(SocketAddr::is_ipv4)
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "no IPv4 address resolved"))
}

pub(crate) fn resolve_host_for_family(host: &str, port: u16, family: Domain) -> io::Result<SocketAddr> {
    if let Ok(ip) = host.parse::<IpAddr>() {
        let matches_family = match family {
            Domain::IPV4 => ip.is_ipv4(),
            Domain::IPV6 => ip.is_ipv6(),
            _ => true,
        };
        if matches_family {
            return Ok(SocketAddr::new(ip, port));
        }
    }
    let predicate: fn(&SocketAddr) -> bool = match family {
        Domain::IPV6 => SocketAddr::is_ipv6,
        _ => SocketAddr::is_ipv4,
    };
    (host, port)
        .to_socket_addrs()?
        .find(predicate)
        .ok_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "no endpoint address resolved"))
}

pub(crate) async fn resolve_endpoint(endpoint: &ResolvedWarpRuntimeEndpoint) -> io::Result<SocketAddr> {
    let port = u16::try_from(endpoint.port)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid WARP endpoint port"))?;
    if let Some(ipv4) = endpoint.ipv4.as_deref().filter(|value| !value.is_empty()) {
        return resolve_host_for_family(ipv4, port, Domain::IPV4);
    }
    if let Some(ipv6) = endpoint.ipv6.as_deref().filter(|value| !value.is_empty()) {
        return resolve_host_for_family(ipv6, port, Domain::IPV6);
    }
    resolve_sync_host(&endpoint.host, port)
}

pub(crate) fn parse_ipv4_cidr(value: Option<&str>) -> Option<IpAddr> {
    value.and_then(|raw| raw.split('/').next()).and_then(|addr| addr.parse::<Ipv4Addr>().ok()).map(IpAddr::V4)
}

pub(crate) fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}
