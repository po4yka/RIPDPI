#![no_main]

mod common;

use libfuzzer_sys::fuzz_target;
use ripdpi_session::{parse_http_connect_request, parse_socks4_request, parse_socks5_request, SessionConfig, SocketType};

fuzz_target!(|data: &[u8]| {
    common::session_request_smoke();

    let resolver = common::session_resolver;
    let config =
        SessionConfig {
            resolve: data.first().copied().unwrap_or(0) & 0x1 == 0,
            ipv6: data.get(1).copied().unwrap_or(0) & 0x1 == 0,
        };
    let socket_type =
        if data.get(2).copied().unwrap_or(0) & 0x1 == 0 {
            SocketType::Stream
        } else {
            SocketType::Datagram
        };

    let _ = parse_socks4_request(data, config, &resolver);
    let _ = parse_socks5_request(data, socket_type, config, &resolver);
    let _ = parse_http_connect_request(data, &resolver);

    let socks4 = common::socks4_request_from_bytes(data);
    let _ = parse_socks4_request(&socks4, config, &resolver);

    let socks5 = common::socks5_request_from_bytes(data);
    let _ = parse_socks5_request(&socks5, socket_type, config, &resolver);

    let http_connect = common::http_connect_request_from_bytes(data);
    let _ = parse_http_connect_request(http_connect.as_bytes(), &resolver);

    if !socks5.is_empty() {
        let truncated_len = socks5.len().saturating_sub(1 + usize::from(data.first().copied().unwrap_or(0) % 4));
        let _ = parse_socks5_request(&socks5[..truncated_len], socket_type, config, &resolver);
    }
});
