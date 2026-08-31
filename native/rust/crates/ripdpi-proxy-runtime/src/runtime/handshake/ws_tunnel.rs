use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};

use super::super::state::RuntimeState;
use super::super::ws::{RuntimeTelegramDc, RuntimeWsTunnelConfig, WsSeedClassification};

/// Check if WS tunnel should be tried first (Always mode).
pub(super) fn should_ws_tunnel_first(target: SocketAddr, state: &RuntimeState) -> Option<RuntimeTelegramDc> {
    let dc = state.should_ws_tunnel_first(target)?;
    tracing::info!("WS tunnel: sniffing MTProto for known Telegram target {target} (DC{})", dc.number());
    Some(dc)
}

/// Check if WS tunnel should be tried as a last resort (Fallback mode).
pub(super) fn should_ws_tunnel_fallback(target: SocketAddr, state: &RuntimeState) -> Option<RuntimeTelegramDc> {
    state.should_ws_tunnel_fallback(target)
}

/// Result of a WS tunnel attempt.
pub(super) enum WsTunnelResult {
    ValidatedMtproto { dc: RuntimeTelegramDc },
    NotMtproto { seed_request: Vec<u8> },
    UnmappableDc { raw_dc: i32, dc: Option<RuntimeTelegramDc>, seed_request: Vec<u8> },
    ShortInit { seed_request: Vec<u8>, error: io::Error },
    BootstrapFailed { dc: RuntimeTelegramDc, seed_request: Vec<u8>, error: io::Error },
    WsOpenOrRelayFailed { dc: RuntimeTelegramDc, seed_request: Vec<u8>, error: io::Error },
}

/// Execute the WebSocket tunnel relay after sniffing the first 64 bytes from
/// the client connection.
pub(super) fn run_ws_tunnel(client: TcpStream, state: &RuntimeState) -> WsTunnelResult {
    run_ws_tunnel_with(
        client,
        state,
        read_mtproto_seed,
        RuntimeState::resolve_ws_tunnel_addr,
        RuntimeState::relay_ws_tunnel,
    )
}

/// Execute the WebSocket tunnel relay with a first request already preserved by
/// the desync pipeline.
pub(super) fn run_ws_tunnel_with_seed(
    client: TcpStream,
    seed_request: Vec<u8>,
    state: &RuntimeState,
) -> WsTunnelResult {
    run_ws_tunnel_with_seed_impl(
        client,
        seed_request,
        state,
        RuntimeState::resolve_ws_tunnel_addr,
        RuntimeState::relay_ws_tunnel,
    )
}

fn run_ws_tunnel_with<ReadSeed, ResolveAddr, RelayWs>(
    mut client: TcpStream,
    state: &RuntimeState,
    read_seed: ReadSeed,
    resolve_addr: ResolveAddr,
    relay_ws: RelayWs,
) -> WsTunnelResult
where
    ReadSeed: FnOnce(&mut TcpStream) -> Result<Vec<u8>, SeedReadError>,
    ResolveAddr: FnOnce(&RuntimeState, RuntimeTelegramDc) -> io::Result<SocketAddr>,
    RelayWs: FnOnce(&RuntimeState, TcpStream, RuntimeTelegramDc, Vec<u8>, &RuntimeWsTunnelConfig) -> io::Result<()>,
{
    let seed_request = match read_seed(&mut client) {
        Ok(seed_request) => seed_request,
        Err(error) => {
            return WsTunnelResult::ShortInit { seed_request: error.seed_request, error: error.error };
        }
    };

    run_ws_tunnel_with_seed_impl(client, seed_request, state, resolve_addr, relay_ws)
}

fn run_ws_tunnel_with_seed_impl<ResolveAddr, RelayWs>(
    client: TcpStream,
    seed_request: Vec<u8>,
    state: &RuntimeState,
    resolve_addr: ResolveAddr,
    relay_ws: RelayWs,
) -> WsTunnelResult
where
    ResolveAddr: FnOnce(&RuntimeState, RuntimeTelegramDc) -> io::Result<SocketAddr>,
    RelayWs: FnOnce(&RuntimeState, TcpStream, RuntimeTelegramDc, Vec<u8>, &RuntimeWsTunnelConfig) -> io::Result<()>,
{
    if seed_request.len() < 64 {
        return WsTunnelResult::ShortInit {
            seed_request,
            error: io::Error::new(io::ErrorKind::UnexpectedEof, "short MTProto init"),
        };
    }

    match state.classify_mtproto_seed(&seed_request[..64]) {
        WsSeedClassification::NotMtproto => {
            tracing::debug!("WS tunnel skipped: first request is not valid MTProto obfuscated2");
            WsTunnelResult::NotMtproto { seed_request }
        }
        WsSeedClassification::UnmappableDc { raw_dc, dc } => {
            tracing::info!("WS tunnel skipped: MTProto DC raw={raw_dc} is not tunnelable");
            WsTunnelResult::UnmappableDc { raw_dc, dc, seed_request }
        }
        WsSeedClassification::ValidatedMtproto { dc } => {
            let resolved_addr = match resolve_addr(state, dc) {
                Ok(addr) => addr,
                Err(error) => {
                    tracing::warn!(
                        "WS tunnel encrypted DNS bootstrap failed for raw DC {} (class {:?}): {error}",
                        dc.raw(),
                        dc.class()
                    );
                    return WsTunnelResult::BootstrapFailed { dc, seed_request, error };
                }
            };
            let config = state.ws_tunnel_config(Some(resolved_addr));
            match relay_ws(state, client, dc, seed_request.clone(), &config) {
                Ok(()) => WsTunnelResult::ValidatedMtproto { dc },
                Err(error) => {
                    tracing::warn!(
                        "WS tunnel relay failed for raw DC {} (class {:?}), falling back to desync: {error}",
                        dc.raw(),
                        dc.class()
                    );
                    WsTunnelResult::WsOpenOrRelayFailed { dc, seed_request, error }
                }
            }
        }
    }
}

struct SeedReadError {
    seed_request: Vec<u8>,
    error: io::Error,
}

fn read_mtproto_seed(client: &mut TcpStream) -> Result<Vec<u8>, SeedReadError> {
    let mut seed_request = vec![0u8; 64];
    let mut read = 0usize;

    while read < seed_request.len() {
        match client.read(&mut seed_request[read..]) {
            Ok(0) => {
                seed_request.truncate(read);
                return Err(SeedReadError {
                    seed_request,
                    error: io::Error::new(io::ErrorKind::UnexpectedEof, "short MTProto init"),
                });
            }
            Ok(count) => {
                read += count;
            }
            Err(error) => {
                seed_request.truncate(read);
                return Err(SeedReadError { seed_request, error });
            }
        }
    }

    Ok(seed_request)
}

#[cfg(all(test, not(feature = "loom")))]
mod tests {
    use super::*;

    use crate::runtime::config::{RuntimeConfig, WsTunnelMode};
    use crate::runtime::state::RuntimeState;
    use aes::Aes256;
    use aes::cipher::{KeyIvInit, StreamCipher};
    use std::net::{IpAddr, Ipv4Addr, TcpListener};
    use std::thread;
    use std::time::Duration;

    type Aes256Ctr = ctr::Ctr128BE<Aes256>;

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    fn runtime_state() -> RuntimeState {
        RuntimeState::test(RuntimeConfig::default())
    }

    fn runtime_state_with_config(config: RuntimeConfig) -> RuntimeState {
        RuntimeState::test(config)
    }

    fn build_test_init_packet(raw_dc: i32) -> Vec<u8> {
        let mut plaintext = [0u8; 64];
        plaintext[56..60].copy_from_slice(&[0xee; 4]);
        plaintext[60..64].copy_from_slice(&raw_dc.to_le_bytes());

        let key: [u8; 32] = [
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
            0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20,
        ];
        let iv: [u8; 16] =
            [0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab, 0xac, 0xad, 0xae, 0xaf, 0xb0];

        let mut ciphertext = plaintext;
        let mut cipher = Aes256Ctr::new((&key).into(), (&iv).into());
        cipher.apply_keystream(&mut ciphertext);
        ciphertext[8..40].copy_from_slice(&key);
        ciphertext[40..56].copy_from_slice(&iv);
        ciphertext.to_vec()
    }

    #[test]
    fn detect_telegram_dc_extracts_dc_number_from_known_ipv4_target() {
        let target = SocketAddr::from((Ipv4Addr::new(149, 154, 167, 91), 443));

        assert_eq!(RuntimeState::detect_telegram_dc(target), Some(2));
    }

    #[test]
    fn detect_telegram_dc_extracts_dc_number_from_known_ipv6_target() {
        let target = SocketAddr::new("2001:67c:4e8::1".parse::<IpAddr>().expect("parse Telegram v6"), 443);

        assert_eq!(RuntimeState::detect_telegram_dc(target), Some(2));
    }

    #[test]
    fn telegram_dc_host_formats_virtual_hostname() {
        assert_eq!(RuntimeState::telegram_dc_host(4), "telegram-dc4");
    }

    #[test]
    fn ws_tunnel_target_checks_require_matching_mode_and_known_telegram_ip() {
        let target = SocketAddr::from((Ipv4Addr::new(149, 154, 167, 91), 443));
        let ipv6_target = SocketAddr::new("2001:b28:f23f::1".parse::<IpAddr>().expect("parse Telegram v6"), 443);
        let non_telegram_target = SocketAddr::from((Ipv4Addr::new(203, 0, 113, 10), 443));

        let mut cfg = RuntimeConfig::default();
        cfg.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
        let always = runtime_state_with_config(cfg);
        assert_eq!(should_ws_tunnel_first(target, &always), Some(RuntimeTelegramDc::production(2)));
        assert_eq!(should_ws_tunnel_first(ipv6_target, &always), Some(RuntimeTelegramDc::production(3)));
        assert_eq!(should_ws_tunnel_first(non_telegram_target, &always), None);
        assert_eq!(should_ws_tunnel_fallback(target, &always), None);

        let mut cfg = RuntimeConfig::default();
        cfg.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
        let fallback = runtime_state_with_config(cfg);
        assert_eq!(should_ws_tunnel_fallback(target, &fallback), Some(RuntimeTelegramDc::production(2)));
        assert_eq!(should_ws_tunnel_fallback(ipv6_target, &fallback), Some(RuntimeTelegramDc::production(3)));
        assert_eq!(should_ws_tunnel_fallback(non_telegram_target, &fallback), None);
        assert_eq!(should_ws_tunnel_first(target, &fallback), None);
    }

    #[test]
    fn run_ws_tunnel_with_seed_returns_not_mtproto_for_http_prefix() {
        let (_app, relay_client) = connected_pair();
        let state = runtime_state();
        let mut seed_request = vec![0_u8; 64];
        seed_request[..4].copy_from_slice(b"POST");

        let result = run_ws_tunnel_with_seed_impl(
            relay_client,
            seed_request,
            &state,
            |_state, _dc| unreachable!("should not resolve"),
            |_state, _client, _dc, _seed_request, _config| unreachable!("should not relay"),
        );

        assert!(matches!(result, WsTunnelResult::NotMtproto { .. }));
    }

    #[test]
    fn run_ws_tunnel_with_seed_returns_unmappable_for_media_dc() {
        let (_app, relay_client) = connected_pair();
        let state = runtime_state();

        let result = run_ws_tunnel_with_seed_impl(
            relay_client,
            build_test_init_packet(-3),
            &state,
            |_state, _dc| unreachable!("should not resolve"),
            |_state, _client, _dc, _seed_request, _config| unreachable!("should not relay"),
        );

        assert!(matches!(
            result,
            WsTunnelResult::UnmappableDc {
                raw_dc: -3,
                dc: Some(dc),
                ..
            } if dc == RuntimeTelegramDc::from_raw(-3).expect("media dc")
        ));
    }

    #[test]
    fn run_ws_tunnel_with_seed_validates_and_relays_test_dc() {
        let (_app, relay_client) = connected_pair();
        let mut cfg = RuntimeConfig::default();
        cfg.timeouts.connect_timeout_ms = 1_500;
        let state = runtime_state_with_config(cfg);
        let seed_request = build_test_init_packet(10_002);
        let resolved_addr = SocketAddr::from((Ipv4Addr::LOCALHOST, 443));

        let result = run_ws_tunnel_with_seed_impl(
            relay_client,
            seed_request.clone(),
            &state,
            |_state, dc| {
                assert_eq!(dc, RuntimeTelegramDc::from_raw(10_002).expect("test dc"));
                Ok(resolved_addr)
            },
            |_state, _client, dc, forwarded_seed, config| {
                assert_eq!(dc, RuntimeTelegramDc::from_raw(10_002).expect("test dc"));
                assert_eq!(forwarded_seed, seed_request);
                assert_eq!(config.resolved_addr, Some(resolved_addr));
                assert_eq!(config.connect_timeout, Some(Duration::from_millis(1_500)));
                Ok(())
            },
        );

        assert!(matches!(
            result,
            WsTunnelResult::ValidatedMtproto { dc }
            if dc == RuntimeTelegramDc::from_raw(10_002).expect("test dc")
        ));
    }

    #[test]
    fn run_ws_tunnel_with_seed_fails_closed_on_bootstrap_error() {
        let (_app, relay_client) = connected_pair();
        let state = runtime_state();
        let seed_request = build_test_init_packet(10_002);

        let result = run_ws_tunnel_with_seed_impl(
            relay_client,
            seed_request.clone(),
            &state,
            |_state, _dc| Err(io::Error::new(io::ErrorKind::TimedOut, "bootstrap timed out")),
            |_state, _client, _dc, _seed_request, _config| {
                unreachable!("relay must not run without a resolved address")
            },
        );

        assert!(matches!(
            result,
            WsTunnelResult::BootstrapFailed { dc, seed_request: preserved, error }
            if dc == RuntimeTelegramDc::from_raw(10_002).expect("test dc")
                && preserved == seed_request
                && error.kind() == io::ErrorKind::TimedOut
        ));
    }

    #[test]
    fn run_ws_tunnel_with_seed_preserves_seed_on_ws_failure() {
        let (_app, relay_client) = connected_pair();
        let state = runtime_state();
        let seed_request = build_test_init_packet(1);

        let result = run_ws_tunnel_with_seed_impl(
            relay_client,
            seed_request.clone(),
            &state,
            |_state, _dc| Ok(SocketAddr::from((Ipv4Addr::LOCALHOST, 443))),
            |_state, _client, _dc, _forwarded_seed, _config| {
                Err(io::Error::new(io::ErrorKind::ConnectionRefused, "boom"))
            },
        );

        assert!(matches!(
            result,
            WsTunnelResult::WsOpenOrRelayFailed { dc, seed_request: preserved, error }
            if dc == RuntimeTelegramDc::production(1)
                && preserved == seed_request
                && error.kind() == io::ErrorKind::ConnectionRefused
        ));
    }

    #[test]
    fn run_ws_tunnel_reports_partial_init_as_short_seed() {
        let state = runtime_state();
        let (mut app, relay_client) = connected_pair();
        let writer = thread::spawn(move || {
            use std::io::Write;
            app.write_all(&[1, 2, 3]).expect("write partial init");
        });

        let result = run_ws_tunnel_with(
            relay_client,
            &state,
            read_mtproto_seed,
            |_state, _dc| unreachable!("should not resolve"),
            |_state, _client, _dc, _seed_request, _config| unreachable!("should not relay"),
        );

        writer.join().expect("join writer");
        assert!(matches!(
            result,
            WsTunnelResult::ShortInit { seed_request, error }
            if seed_request == vec![1, 2, 3] && error.kind() == io::ErrorKind::UnexpectedEof
        ));
    }
}
