use std::io::{self, Read};
use std::net::{IpAddr, SocketAddr, TcpStream};

use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_ws_tunnel::{MtprotoSeedClassification, TelegramDc, WsTunnelConfig};

use crate::ws_bootstrap;

use super::super::state::RuntimeState;

/// Detect Telegram DC number from target IP, independent of WS tunnel config.
pub(super) fn detect_telegram_dc(target: SocketAddr) -> Option<u8> {
    match target.ip() {
        IpAddr::V4(v4) => ripdpi_ws_tunnel::dc_from_ip(v4).map(TelegramDc::number),
        IpAddr::V6(_) => None,
    }
}

/// Format a virtual hostname for a Telegram DC, used as autolearn key.
pub(super) fn telegram_dc_host(dc: u8) -> String {
    format!("telegram-dc{dc}")
}

fn classify_telegram_target(target: SocketAddr) -> Option<TelegramDc> {
    match ripdpi_ws_tunnel::classify_target(target.ip()) {
        ripdpi_ws_tunnel::WsTunnelDecision::Tunnel(dc) => Some(dc),
        ripdpi_ws_tunnel::WsTunnelDecision::Passthrough => None,
    }
}

/// Check if WS tunnel should be tried first (Always mode).
pub(super) fn should_ws_tunnel_first(target: SocketAddr, state: &RuntimeState) -> Option<TelegramDc> {
    if state.config.adaptive.ws_tunnel_mode != ripdpi_config::WsTunnelMode::Always {
        return None;
    }
    let dc = classify_telegram_target(target)?;
    tracing::info!("WS tunnel: sniffing MTProto for known Telegram target {target} (DC{})", dc.number());
    Some(dc)
}

/// Check if WS tunnel should be tried as a last resort (Fallback mode).
pub(super) fn should_ws_tunnel_fallback(target: SocketAddr, state: &RuntimeState) -> Option<TelegramDc> {
    if state.config.adaptive.ws_tunnel_mode != ripdpi_config::WsTunnelMode::Fallback {
        return None;
    }
    classify_telegram_target(target)
}

/// Result of a WS tunnel attempt.
pub(super) enum WsTunnelResult {
    ValidatedMtproto { dc: TelegramDc },
    NotMtproto { seed_request: Vec<u8> },
    UnmappableDc { raw_dc: i32, dc: Option<TelegramDc>, seed_request: Vec<u8> },
    ShortInit { seed_request: Vec<u8>, error: io::Error },
    WsOpenOrRelayFailed { dc: TelegramDc, seed_request: Vec<u8>, error: io::Error },
}

/// Execute the WebSocket tunnel relay after sniffing the first 64 bytes from
/// the client connection.
pub(super) fn run_ws_tunnel(client: TcpStream, state: &RuntimeState) -> WsTunnelResult {
    run_ws_tunnel_with(
        client,
        state,
        read_mtproto_seed,
        ws_bootstrap::resolve_ws_tunnel_addr,
        ripdpi_ws_tunnel::relay_ws_tunnel,
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
        ws_bootstrap::resolve_ws_tunnel_addr,
        ripdpi_ws_tunnel::relay_ws_tunnel,
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
    ResolveAddr: FnOnce(TelegramDc, Option<&ProxyRuntimeContext>) -> io::Result<SocketAddr>,
    RelayWs: FnOnce(TcpStream, TelegramDc, Vec<u8>, &WsTunnelConfig) -> io::Result<()>,
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
    ResolveAddr: FnOnce(TelegramDc, Option<&ProxyRuntimeContext>) -> io::Result<SocketAddr>,
    RelayWs: FnOnce(TcpStream, TelegramDc, Vec<u8>, &WsTunnelConfig) -> io::Result<()>,
{
    if seed_request.len() < 64 {
        return WsTunnelResult::ShortInit {
            seed_request,
            error: io::Error::new(io::ErrorKind::UnexpectedEof, "short MTProto init"),
        };
    }

    match ripdpi_ws_tunnel::classify_mtproto_seed(&seed_request[..64]) {
        MtprotoSeedClassification::NotMtproto => {
            tracing::debug!("WS tunnel skipped: first request is not valid MTProto obfuscated2");
            WsTunnelResult::NotMtproto { seed_request }
        }
        MtprotoSeedClassification::UnmappableDc { raw_dc, dc } => {
            tracing::info!("WS tunnel skipped: MTProto DC raw={raw_dc} is not tunnelable");
            WsTunnelResult::UnmappableDc { raw_dc, dc, seed_request }
        }
        MtprotoSeedClassification::ValidatedMtproto { dc } => {
            let resolved_addr = match resolve_addr(dc, state.runtime_context.as_ref()) {
                Ok(addr) => Some(addr),
                Err(err) => {
                    tracing::warn!(
                        "WS tunnel encrypted DNS bootstrap failed for raw DC {} (class {:?}): {err}",
                        dc.raw(),
                        dc.class()
                    );
                    None
                }
            };
            let config = WsTunnelConfig { protect_path: state.config.process.protect_path.clone(), resolved_addr };
            match relay_ws(client, dc, seed_request.clone(), &config) {
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

#[cfg(test)]
mod tests {
    use super::*;

    use crate::adaptive_fake_ttl::AdaptiveFakeTtlResolver;
    use crate::adaptive_tuning::AdaptivePlannerResolver;
    use crate::retry_stealth::RetryPacer;
    use crate::runtime::state::RuntimeState;
    use crate::runtime_policy::RuntimePolicy;
    use crate::strategy_evolver::StrategyEvolver;
    use crate::sync::{Arc, AtomicUsize, Mutex};
    use aes::cipher::{KeyIvInit, StreamCipher};
    use aes::Aes256;
    use std::net::{Ipv4Addr, TcpListener};
    use std::thread;

    type Aes256Ctr = ctr::Ctr128BE<Aes256>;

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    fn runtime_state() -> RuntimeState {
        let config = ripdpi_config::RuntimeConfig::default();
        RuntimeState {
            config: Arc::new(config.clone()),
            cache: Arc::new(Mutex::new(RuntimePolicy::load(&config))),
            adaptive_fake_ttl: Arc::new(Mutex::new(AdaptiveFakeTtlResolver::default())),
            adaptive_tuning: Arc::new(Mutex::new(AdaptivePlannerResolver::default())),
            retry_stealth: Arc::new(Mutex::new(RetryPacer::default())),
            strategy_evolver: Arc::new(Mutex::new(StrategyEvolver::new(false, 0.0))),
            active_clients: Arc::new(AtomicUsize::new(0)),
            telemetry: None,
            runtime_context: None,
            control: None,
            ttl_unavailable: Arc::new(crate::sync::AtomicBool::new(false)),
        }
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

        assert_eq!(detect_telegram_dc(target), Some(2));
    }

    #[test]
    fn telegram_dc_host_formats_virtual_hostname() {
        assert_eq!(telegram_dc_host(4), "telegram-dc4");
    }

    #[test]
    fn ws_tunnel_target_checks_require_matching_mode_and_known_telegram_ip() {
        let target = SocketAddr::from((Ipv4Addr::new(149, 154, 167, 91), 443));
        let non_telegram_target = SocketAddr::from((Ipv4Addr::new(203, 0, 113, 10), 443));

        let mut always = runtime_state();
        Arc::make_mut(&mut always.config).adaptive.ws_tunnel_mode = ripdpi_config::WsTunnelMode::Always;
        assert_eq!(should_ws_tunnel_first(target, &always), Some(TelegramDc::production(2)));
        assert_eq!(should_ws_tunnel_first(non_telegram_target, &always), None);
        assert_eq!(should_ws_tunnel_fallback(target, &always), None);

        let mut fallback = runtime_state();
        Arc::make_mut(&mut fallback.config).adaptive.ws_tunnel_mode = ripdpi_config::WsTunnelMode::Fallback;
        assert_eq!(should_ws_tunnel_fallback(target, &fallback), Some(TelegramDc::production(2)));
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
            |_dc, _context| unreachable!("should not resolve"),
            |_client, _dc, _seed_request, _config| unreachable!("should not relay"),
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
            |_dc, _context| unreachable!("should not resolve"),
            |_client, _dc, _seed_request, _config| unreachable!("should not relay"),
        );

        assert!(matches!(
            result,
            WsTunnelResult::UnmappableDc {
                raw_dc: -3,
                dc: Some(dc),
                ..
            } if dc == TelegramDc::from_raw(-3).expect("media dc")
        ));
    }

    #[test]
    fn run_ws_tunnel_with_seed_validates_and_relays_test_dc() {
        let (_app, relay_client) = connected_pair();
        let state = runtime_state();
        let seed_request = build_test_init_packet(10_002);
        let resolved_addr = SocketAddr::from((Ipv4Addr::LOCALHOST, 443));

        let result = run_ws_tunnel_with_seed_impl(
            relay_client,
            seed_request.clone(),
            &state,
            |dc, _context| {
                assert_eq!(dc, TelegramDc::from_raw(10_002).expect("test dc"));
                Ok(resolved_addr)
            },
            |_client, dc, forwarded_seed, config| {
                assert_eq!(dc, TelegramDc::from_raw(10_002).expect("test dc"));
                assert_eq!(forwarded_seed, seed_request);
                assert_eq!(config.resolved_addr, Some(resolved_addr));
                Ok(())
            },
        );

        assert!(matches!(
            result,
            WsTunnelResult::ValidatedMtproto { dc }
            if dc == TelegramDc::from_raw(10_002).expect("test dc")
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
            |_dc, _context| Ok(SocketAddr::from((Ipv4Addr::LOCALHOST, 443))),
            |_client, _dc, _forwarded_seed, _config| Err(io::Error::new(io::ErrorKind::ConnectionRefused, "boom")),
        );

        assert!(matches!(
            result,
            WsTunnelResult::WsOpenOrRelayFailed { dc, seed_request: preserved, error }
            if dc == TelegramDc::production(1)
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
            |_dc, _context| unreachable!("should not resolve"),
            |_client, _dc, _seed_request, _config| unreachable!("should not relay"),
        );

        writer.join().expect("join writer");
        assert!(matches!(
            result,
            WsTunnelResult::ShortInit { seed_request, error }
            if seed_request == vec![1, 2, 3] && error.kind() == io::ErrorKind::UnexpectedEof
        ));
    }
}
