use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use super::super::state::RuntimeState;
use super::super::ws::{RuntimeTelegramDc, RuntimeWsTunnelConfig, WsSeedClassification, runtime_exact_telegram_dc};

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
pub(super) fn run_ws_tunnel(client: TcpStream, target: SocketAddr, state: &RuntimeState) -> WsTunnelResult {
    run_ws_tunnel_with(
        client,
        target,
        state,
        read_mtproto_seed,
        RuntimeState::resolve_ws_tunnel_addr,
        RuntimeState::relay_ws_tunnel,
    )
}

/// Use the ready carrier when the seed selects its provisional DC.
pub(super) fn run_ws_tunnel_prepared(
    mut client: TcpStream,
    target: SocketAddr,
    provisional_dc: RuntimeTelegramDc,
    prepared: Box<dyn ripdpi_ws_transport_port::PreparedWsTunnel>,
    seed_request: Option<Vec<u8>>,
    state: &RuntimeState,
) -> WsTunnelResult {
    let seed_request = match complete_mtproto_seed(&mut client, seed_request.unwrap_or_default()) {
        Ok(seed) => seed,
        Err(error) => return WsTunnelResult::ShortInit { seed_request: error.seed_request, error: error.error },
    };
    let dc = match classify_seed_for_target(&seed_request, target, state) {
        WsSeedClassification::ValidatedMtproto { dc } => Some(dc),
        _ => None,
    };
    if dc != Some(provisional_dc) {
        drop(prepared);
        return run_ws_tunnel_with_seed(client, target, seed_request, state);
    }
    if state.shutdown_requested() {
        return WsTunnelResult::WsOpenOrRelayFailed {
            dc: provisional_dc,
            seed_request,
            error: io::Error::new(io::ErrorKind::Interrupted, "runtime stopped before WS relay"),
        };
    }
    match prepared.relay(client, seed_request.clone()) {
        Ok(()) => WsTunnelResult::ValidatedMtproto { dc: provisional_dc },
        Err(error) => WsTunnelResult::WsOpenOrRelayFailed { dc: provisional_dc, seed_request, error },
    }
}

/// Execute the WebSocket tunnel relay with a first request already preserved by
/// the desync pipeline.
pub(super) fn run_ws_tunnel_with_seed(
    client: TcpStream,
    target: SocketAddr,
    seed_request: Vec<u8>,
    state: &RuntimeState,
) -> WsTunnelResult {
    run_ws_tunnel_with_seed_impl(
        client,
        target,
        seed_request,
        state,
        RuntimeState::resolve_ws_tunnel_addr,
        RuntimeState::relay_ws_tunnel,
    )
}

fn run_ws_tunnel_with<ReadSeed, ResolveAddr, RelayWs>(
    mut client: TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    read_seed: ReadSeed,
    resolve_addr: ResolveAddr,
    relay_ws: RelayWs,
) -> WsTunnelResult
where
    ReadSeed: FnOnce(&mut TcpStream) -> Result<Vec<u8>, SeedReadError>,
    ResolveAddr: FnOnce(&RuntimeState, RuntimeTelegramDc, &dyn Fn() -> bool) -> io::Result<SocketAddr>,
    RelayWs: FnOnce(
        &RuntimeState,
        TcpStream,
        RuntimeTelegramDc,
        Vec<u8>,
        &RuntimeWsTunnelConfig,
        &dyn Fn() -> bool,
    ) -> io::Result<()>,
{
    let seed_request = match read_seed(&mut client) {
        Ok(seed_request) => seed_request,
        Err(error) => {
            return WsTunnelResult::ShortInit { seed_request: error.seed_request, error: error.error };
        }
    };

    run_ws_tunnel_with_seed_impl(client, target, seed_request, state, resolve_addr, relay_ws)
}

fn run_ws_tunnel_with_seed_impl<ResolveAddr, RelayWs>(
    mut client: TcpStream,
    target: SocketAddr,
    seed_request: Vec<u8>,
    state: &RuntimeState,
    resolve_addr: ResolveAddr,
    relay_ws: RelayWs,
) -> WsTunnelResult
where
    ResolveAddr: FnOnce(&RuntimeState, RuntimeTelegramDc, &dyn Fn() -> bool) -> io::Result<SocketAddr>,
    RelayWs: FnOnce(
        &RuntimeState,
        TcpStream,
        RuntimeTelegramDc,
        Vec<u8>,
        &RuntimeWsTunnelConfig,
        &dyn Fn() -> bool,
    ) -> io::Result<()>,
{
    let seed_request = match complete_mtproto_seed(&mut client, seed_request) {
        Ok(seed) => seed,
        Err(error) => return WsTunnelResult::ShortInit { seed_request: error.seed_request, error: error.error },
    };

    match classify_seed_for_target(&seed_request[..64], target, state) {
        WsSeedClassification::NotMtproto => {
            tracing::debug!("WS tunnel skipped: first request is not valid MTProto obfuscated2");
            WsTunnelResult::NotMtproto { seed_request }
        }
        WsSeedClassification::UnmappableDc { raw_dc, dc } => {
            tracing::info!("WS tunnel skipped: MTProto DC raw={raw_dc} is not tunnelable");
            WsTunnelResult::UnmappableDc { raw_dc, dc, seed_request }
        }
        WsSeedClassification::ValidatedMtproto { dc } => {
            open_ws_tunnel_for_dc(client, seed_request, dc, state, resolve_addr, relay_ws)
        }
    }
}

/// Direct clients can leave the DC field random. An exact bootstrap address
/// is authoritative after the obfuscated2 prefix and transport tag pass.
/// Unknown addresses keep the explicit DC semantics; subnet guesses are excluded.
pub(super) fn classify_seed_for_target(
    seed_request: &[u8],
    target: SocketAddr,
    state: &RuntimeState,
) -> WsSeedClassification {
    let classification = state.classify_mtproto_seed(seed_request);
    if !matches!(classification, WsSeedClassification::NotMtproto)
        && let Some(dc) = runtime_exact_telegram_dc(target)
    {
        return WsSeedClassification::ValidatedMtproto { dc };
    }
    classification
}

fn open_ws_tunnel_for_dc<ResolveAddr, RelayWs>(
    client: TcpStream,
    seed_request: Vec<u8>,
    dc: RuntimeTelegramDc,
    state: &RuntimeState,
    resolve_addr: ResolveAddr,
    relay_ws: RelayWs,
) -> WsTunnelResult
where
    ResolveAddr: FnOnce(&RuntimeState, RuntimeTelegramDc, &dyn Fn() -> bool) -> io::Result<SocketAddr>,
    RelayWs: FnOnce(
        &RuntimeState,
        TcpStream,
        RuntimeTelegramDc,
        Vec<u8>,
        &RuntimeWsTunnelConfig,
        &dyn Fn() -> bool,
    ) -> io::Result<()>,
{
    let timeout = state.ws_tunnel_config(None).connect_timeout();
    let started = Instant::now();
    let dns_cancelled = || state.shutdown_requested() || timeout.is_some_and(|limit| started.elapsed() >= limit);
    let check_stage = || {
        if state.shutdown_requested() {
            Err(io::Error::new(io::ErrorKind::Interrupted, "runtime stopped during WS bootstrap"))
        } else if timeout.is_some_and(|limit| started.elapsed() >= limit) {
            Err(io::Error::new(io::ErrorKind::TimedOut, "WS bootstrap deadline expired"))
        } else {
            Ok(())
        }
    };
    let resolved = check_stage().and_then(|()| resolve_addr(state, dc, &dns_cancelled));
    let resolved_addr = match check_stage().and(resolved) {
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
    let config = match timeout {
        Some(limit) => config.with_connect_timeout(limit.saturating_sub(started.elapsed())),
        None => config,
    };
    match relay_ws(state, client, dc, seed_request.clone(), &config, &|| state.shutdown_requested()) {
        Ok(()) => WsTunnelResult::ValidatedMtproto { dc },
        Err(error) => {
            tracing::warn!("WS tunnel relay failed for raw DC {} (class {:?}): {error}", dc.raw(), dc.class());
            WsTunnelResult::WsOpenOrRelayFailed { dc, seed_request, error }
        }
    }
}

struct SeedReadError {
    seed_request: Vec<u8>,
    error: io::Error,
}

fn read_mtproto_seed(client: &mut TcpStream) -> Result<Vec<u8>, SeedReadError> {
    complete_mtproto_seed(client, Vec::new())
}

fn complete_mtproto_seed(client: &mut TcpStream, mut seed_request: Vec<u8>) -> Result<Vec<u8>, SeedReadError> {
    if seed_request.len() >= 64 {
        return Ok(seed_request);
    }
    let previous_timeout = match client.read_timeout() {
        Ok(timeout) => timeout,
        Err(error) => return Err(SeedReadError { seed_request, error }),
    };
    let budget = previous_timeout.unwrap_or(super::HANDSHAKE_TIMEOUT).min(super::HANDSHAKE_TIMEOUT);
    let deadline = Instant::now() + budget;
    let read_result = (|| {
        let mut remaining = [0; 64];
        while seed_request.len() < 64 {
            let timeout = deadline.saturating_duration_since(Instant::now());
            if timeout.is_zero() {
                return Err(io::Error::new(io::ErrorKind::TimedOut, "MTProto init deadline expired"));
            }
            client.set_read_timeout(Some(timeout))?;
            let needed = 64 - seed_request.len();
            match client.read(&mut remaining[..needed]) {
                Ok(0) => return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "short MTProto init")),
                Ok(count) => seed_request.extend_from_slice(&remaining[..count]),
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(error),
            }
        }
        Ok(())
    })();
    let restored = client.set_read_timeout(previous_timeout);
    match read_result.and(restored) {
        Ok(()) => Ok(seed_request),
        Err(error) => Err(SeedReadError { seed_request, error }),
    }
}

#[cfg(all(test, not(feature = "loom")))]
#[path = "ws_tunnel_bootstrap_tests.rs"]
mod bootstrap_tests;

#[cfg(all(test, not(feature = "loom")))]
pub(super) mod tests {
    use super::*;

    use crate::runtime::config::{RuntimeConfig, WsTunnelMode};
    use crate::runtime::state::RuntimeState;
    use aes::Aes256;
    use aes::cipher::{KeyIvInit, StreamCipher};
    use std::net::{IpAddr, Ipv4Addr, TcpListener};
    use std::thread;
    use std::time::Duration;

    type Aes256Ctr = ctr::Ctr128BE<Aes256>;

    pub(super) fn connected_pair() -> (TcpStream, TcpStream) {
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

    /// An init as tdesktop writes it: the DC is a signed 16-bit LE value at
    /// [60..62] and [62..64] is random.
    pub(in crate::runtime::handshake) fn build_test_init_packet(raw_dc: i32) -> Vec<u8> {
        let raw_dc = i16::try_from(raw_dc).expect("test dc fits in i16");
        let mut dc_field = [0x9c, 0x3f, 0x9c, 0x3f];
        dc_field[..2].copy_from_slice(&raw_dc.to_le_bytes());
        build_test_init_packet_with_dc_field(dc_field)
    }

    /// An init as the official Android client writes it on a direct
    /// connection: all of [60..64] is random, so it names no valid DC.
    fn build_android_direct_init_packet() -> Vec<u8> {
        build_test_init_packet_with_dc_field([0x7b, 0x91, 0x3d, 0xe2])
    }

    fn build_test_init_packet_with_dc_field(dc_field: [u8; 4]) -> Vec<u8> {
        let mut plaintext = [0u8; 64];
        plaintext[56..60].copy_from_slice(&[0xee; 4]);
        plaintext[60..64].copy_from_slice(&dc_field);

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

    struct PreparedCarrier(TcpStream);

    impl ripdpi_ws_transport_port::PreparedWsTunnel for PreparedCarrier {
        fn relay(self: Box<Self>, mut client: TcpStream, seed_request: Vec<u8>) -> io::Result<()> {
            use std::io::Write;
            let mut carrier = self.0;
            carrier.write_all(&seed_request)?;
            client.write_all(b"prepared response")
        }
    }

    #[test]
    fn prepared_carrier_keeps_non_mtproto_seed_for_normal_fallback() {
        use std::io::Write;
        let carrier = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let ready = TcpStream::connect(carrier.local_addr().unwrap()).unwrap();
        let (mut origin, _) = carrier.accept().unwrap();
        origin.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let (mut peer, client) = connected_pair();
        let mut http = [b' '; 64];
        http[..16].copy_from_slice(b"GET / HTTP/1.1\r\n");
        peer.write_all(&http).unwrap();
        let result = run_ws_tunnel_prepared(
            client,
            SocketAddr::from(([149, 154, 167, 51], 443)),
            RuntimeTelegramDc::production(2),
            Box::new(PreparedCarrier(ready)),
            None,
            &runtime_state(),
        );
        let WsTunnelResult::NotMtproto { seed_request } = result else {
            panic!("HTTP must return to the normal route");
        };
        assert_eq!(seed_request, http);
        assert_eq!(origin.read(&mut [0; 1]).unwrap(), 0, "HTTP must not enter the WS carrier");
    }

    #[test]
    fn prepared_carrier_reuses_the_socket_for_seed_and_dialed_dc_fallback() {
        use std::io::Write;
        // Independent OpenSSL AES-256-CTR vectors: key 01..20, IV a1..b0,
        // zero plaintext with tag eeeeeeee, signed DC, and random tail 9c3f.
        // Direct clients leave the DC random, including these valid-looking values.
        for tail in [[0x19, 0xa7], [0xe6, 0x58], [0x0a, 0x80], [0x63, 0x36]] {
            let mut seed = vec![
                0xdc, 0x9a, 0x31, 0x46, 0x7e, 0x1d, 0x21, 0x53, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
                0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a,
                0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xab,
                0xac, 0xad, 0xae, 0xaf, 0xb0, 0x29, 0x92, 0xcf, 0xda, 0, 0, 0x2c, 0xcc,
            ];
            seed[60..62].copy_from_slice(&tail);
            let carrier = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            let ready = TcpStream::connect(carrier.local_addr().unwrap()).unwrap();
            let (mut origin, _) = carrier.accept().unwrap();
            origin.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            let (mut peer, client) = connected_pair();
            peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            peer.write_all(&seed[8..]).unwrap();
            let result = run_ws_tunnel_prepared(
                client,
                SocketAddr::from(([149, 154, 167, 51], 443)),
                RuntimeTelegramDc::production(2),
                Box::new(PreparedCarrier(ready)),
                Some(seed[..8].to_vec()),
                &runtime_state(),
            );
            assert!(matches!(result, WsTunnelResult::ValidatedMtproto { .. }));
            let mut received = [0; 64];
            origin.read_exact(&mut received).unwrap();
            assert_eq!(received.as_slice(), seed);
            let mut response = Vec::new();
            peer.read_to_end(&mut response).unwrap();
            assert_eq!(response, b"prepared response");
            carrier.set_nonblocking(true).unwrap();
            assert_eq!(carrier.accept().unwrap_err().kind(), io::ErrorKind::WouldBlock);
        }
    }

    #[test]
    fn detect_telegram_dc_extracts_dc_number_from_known_ipv4_target() {
        let target = SocketAddr::from((Ipv4Addr::new(149, 154, 167, 91), 443));

        assert_eq!(RuntimeState::detect_telegram_dc(target), Some(4));
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
        assert_eq!(should_ws_tunnel_first(target, &always), Some(RuntimeTelegramDc::production(4)));
        assert_eq!(should_ws_tunnel_first(ipv6_target, &always), Some(RuntimeTelegramDc::production(3)));
        assert_eq!(should_ws_tunnel_first(non_telegram_target, &always), None);
        assert_eq!(should_ws_tunnel_fallback(target, &always), None);

        let mut cfg = RuntimeConfig::default();
        cfg.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
        let fallback = runtime_state_with_config(cfg);
        assert_eq!(should_ws_tunnel_fallback(target, &fallback), Some(RuntimeTelegramDc::production(4)));
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
            SocketAddr::from(([127, 0, 0, 1], 443)),
            seed_request,
            &state,
            |_state, _dc, _cancel| unreachable!("should not resolve"),
            |_state, _client, _dc, _seed_request, _config, _cancel| unreachable!("should not relay"),
        );

        assert!(matches!(result, WsTunnelResult::NotMtproto { .. }));
    }

    #[test]
    fn run_ws_tunnel_with_seed_returns_unmappable_for_media_dc() {
        let (_app, relay_client) = connected_pair();
        let state = runtime_state();

        let result = run_ws_tunnel_with_seed_impl(
            relay_client,
            SocketAddr::from(([127, 0, 0, 1], 443)),
            build_test_init_packet(-3),
            &state,
            |_state, _dc, _cancel| unreachable!("should not resolve"),
            |_state, _client, _dc, _seed_request, _config, _cancel| unreachable!("should not relay"),
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
            SocketAddr::from(([127, 0, 0, 1], 443)),
            seed_request.clone(),
            &state,
            |_state, dc, _cancel| {
                assert_eq!(dc, RuntimeTelegramDc::from_raw(10_002).expect("test dc"));
                Ok(resolved_addr)
            },
            |_state, _client, dc, forwarded_seed, config, _cancel| {
                assert_eq!(dc, RuntimeTelegramDc::from_raw(10_002).expect("test dc"));
                assert_eq!(forwarded_seed, seed_request);
                assert_eq!(config.resolved_addr, Some(resolved_addr));
                assert!(
                    config
                        .connect_timeout
                        .is_some_and(|timeout| !timeout.is_zero() && timeout <= Duration::from_millis(1_500))
                );
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
            SocketAddr::from(([127, 0, 0, 1], 443)),
            seed_request.clone(),
            &state,
            |_state, _dc, _cancel| Err(io::Error::new(io::ErrorKind::TimedOut, "bootstrap timed out")),
            |_state, _client, _dc, _seed_request, _config, _cancel| {
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
            SocketAddr::from(([127, 0, 0, 1], 443)),
            seed_request.clone(),
            &state,
            |_state, _dc, _cancel| Ok(SocketAddr::from((Ipv4Addr::LOCALHOST, 443))),
            |_state, _client, _dc, _forwarded_seed, _config, _cancel| {
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
    fn preserved_partial_init_reads_remaining_bytes_before_relay() {
        use std::io::Write;
        let mut seed = build_test_init_packet(2);
        seed.extend_from_slice(b"coalesced payload");
        for split in [1, 8, 56, 63, 64, seed.len()] {
            let (mut peer, client) = connected_pair();
            peer.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
            peer.write_all(&seed[split..]).unwrap();
            peer.shutdown(std::net::Shutdown::Write).unwrap();
            let result = run_ws_tunnel_with_seed_impl(
                client,
                SocketAddr::from(([149, 154, 167, 51], 443)),
                seed[..split].to_vec(),
                &runtime_state(),
                |_state, _dc, _cancel| Ok(SocketAddr::from(([127, 0, 0, 1], 443))),
                |_state, mut client, _dc, mut seed, _config, _cancel| {
                    client.read_to_end(&mut seed)?;
                    client.write_all(&seed)
                },
            );
            assert!(matches!(result, WsTunnelResult::ValidatedMtproto { .. }), "split={split}");
            let mut received = Vec::new();
            peer.read_to_end(&mut received).unwrap();
            assert_eq!(received, seed);
        }
    }

    #[test]
    fn preserved_partial_init_keeps_all_bytes_on_eof() {
        use std::io::Write;
        let (mut peer, client) = connected_pair();
        peer.write_all(&[4, 5, 6]).unwrap();
        peer.shutdown(std::net::Shutdown::Write).unwrap();
        let result = run_ws_tunnel_with_seed_impl(
            client,
            SocketAddr::from(([149, 154, 167, 51], 443)),
            vec![1, 2, 3],
            &runtime_state(),
            |_state, _dc, _cancel| unreachable!("short init must not resolve"),
            |_state, _client, _dc, _seed, _config, _cancel| unreachable!("short init must not relay"),
        );
        assert!(matches!(result, WsTunnelResult::ShortInit { seed_request, error }
            if seed_request == [1, 2, 3, 4, 5, 6] && error.kind() == io::ErrorKind::UnexpectedEof));
    }

    #[test]
    fn partial_init_has_one_deadline_and_restores_the_socket_timeout() {
        use std::io::Write;
        let (mut peer, mut client) = connected_pair();
        let timeout = Some(Duration::from_millis(100));
        client.set_read_timeout(timeout).unwrap();
        let writer = thread::spawn(move || {
            for _ in 0..64 {
                if peer.write_all(&[7]).is_err() {
                    break;
                }
                thread::sleep(Duration::from_millis(20));
            }
        });
        let started = Instant::now();
        let error = complete_mtproto_seed(&mut client, vec![1, 2, 3]).expect_err("bounded init");
        assert!(matches!(error.error.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock));
        assert!(error.seed_request.starts_with(&[1, 2, 3]));
        assert!(error.seed_request[3..].iter().all(|byte| *byte == 7));
        assert!(error.seed_request.len() < 64);
        assert!(started.elapsed() < Duration::from_millis(500));
        assert_eq!(client.read_timeout().unwrap(), timeout);
        drop(client);
        writer.join().unwrap();
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
            SocketAddr::from(([127, 0, 0, 1], 443)),
            &state,
            read_mtproto_seed,
            |_state, _dc, _cancel| unreachable!("should not resolve"),
            |_state, _client, _dc, _seed_request, _config, _cancel| unreachable!("should not relay"),
        );

        writer.join().expect("join writer");
        assert!(matches!(
            result,
            WsTunnelResult::ShortInit { seed_request, error }
            if seed_request == vec![1, 2, 3] && error.kind() == io::ErrorKind::UnexpectedEof
        ));
    }

    #[test]
    fn exact_target_wins_over_random_direct_dc_field() {
        let state = runtime_state();
        let exact = SocketAddr::from(([149, 154, 167, 51], 443));
        for raw_dc in [1, -2, 10_002, -28_293] {
            assert!(matches!(
                classify_seed_for_target(&build_test_init_packet(raw_dc), exact, &state),
                WsSeedClassification::ValidatedMtproto { dc } if dc == RuntimeTelegramDc::production(2)
            ));
        }
    }

    #[test]
    fn target_classification_does_not_guess_subnets_or_accept_non_mtproto() {
        let state = runtime_state();
        let subnet = SocketAddr::from(([149, 154, 165, 10], 443));
        let exact = SocketAddr::from(([149, 154, 167, 51], 443));
        assert!(matches!(
            classify_seed_for_target(&build_android_direct_init_packet(), subnet, &state),
            WsSeedClassification::UnmappableDc { dc: None, .. }
        ));
        assert!(matches!(
            classify_seed_for_target(&build_test_init_packet(-2), subnet, &state),
            WsSeedClassification::UnmappableDc { dc: Some(_), .. }
        ));
        assert!(matches!(classify_seed_for_target(&[0; 64], exact, &state), WsSeedClassification::NotMtproto));
    }
}
