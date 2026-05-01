use crate::transport::{relay_udp_direct, relay_udp_via_socks5, resolve_first_socket_addr, TransportConfig};
use crate::util::now_ms;

use super::wire::{build_dns_query_with_type, parse_dns_response, DNS_RECORD_TYPE_A};

/// Resolve a domain via plain UDP DNS, returning both the parsed IP addresses
/// and the raw response bytes for protocol-level tampering analysis.
pub fn resolve_via_udp_with_raw(
    domain: &str,
    server: &str,
    transport: &TransportConfig,
) -> (Result<Vec<String>, String>, Option<Vec<u8>>) {
    let query_id = ((now_ms() & 0xffff) as u16).max(1);
    let packet = match build_dns_query_with_type(domain, query_id, DNS_RECORD_TYPE_A) {
        Ok(pkt) => pkt,
        Err(err) => return (Err(err), None),
    };
    let raw = match transport {
        TransportConfig::Direct { .. } => {
            let server_addr = match resolve_first_socket_addr(server) {
                Ok(addr) => addr,
                Err(err) => return (Err(err), None),
            };
            relay_udp_direct(server_addr, &packet)
        }
        TransportConfig::Socks5 { host, port } => {
            let server_addr = match resolve_first_socket_addr(server) {
                Ok(addr) => addr,
                Err(err) => return (Err(err), None),
            };
            relay_udp_via_socks5(host, *port, server_addr, &packet)
        }
    };
    match raw {
        Ok((response, _local_addr)) => {
            let parsed = parse_dns_response(&response, query_id);
            (parsed, Some(response))
        }
        Err(err) => (Err(err), None),
    }
}
