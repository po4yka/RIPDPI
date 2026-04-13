use std::io::{self, Read};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};
use std::time::Duration;

use ripdpi_config::DETECT_CONNECT;
use ripdpi_packets::{IS_HTTP, IS_HTTPS};
use ripdpi_session::{encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply};

use crate::runtime_policy::{extract_host, group_requires_payload, route_matches_payload, TransportProtocol};

use super::super::state::RuntimeState;
use super::protocol_io::{send_success_reply, HandshakeKind};
use super::ws_tunnel::{
    run_ws_tunnel, run_ws_tunnel_with_seed, should_ws_tunnel_fallback, should_ws_tunnel_first, WsTunnelResult,
};

enum DelayConnect {
    Immediate,
    Delayed { route: crate::runtime_policy::ConnectionRoute, payload: Vec<u8> },
    Closed,
}

pub(super) struct ConnectRelayError {
    error: io::Error,
    success_reply_sent: bool,
    seed_request: Option<Vec<u8>>,
}

impl ConnectRelayError {
    fn new(error: io::Error, success_reply_sent: bool) -> Self {
        Self { error, success_reply_sent, seed_request: None }
    }

    fn with_seed_request(error: io::Error, success_reply_sent: bool, seed_request: Option<Vec<u8>>) -> Self {
        Self { error, success_reply_sent, seed_request }
    }

    pub(super) fn kind(&self) -> io::ErrorKind {
        self.error.kind()
    }

    pub(super) fn success_reply_sent(&self) -> bool {
        self.success_reply_sent
    }

    pub(super) fn mark_success_reply_sent(&mut self) {
        self.success_reply_sent = true;
    }

    pub(super) fn seed_request(&self) -> Option<&[u8]> {
        self.seed_request.as_deref()
    }

    pub(super) fn into_io_error(self) -> io::Error {
        self.error
    }
}

impl std::fmt::Display for ConnectRelayError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        self.error.fmt(formatter)
    }
}

impl std::fmt::Debug for ConnectRelayError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("ConnectRelayError")
            .field("error", &self.error)
            .field("success_reply_sent", &self.success_reply_sent)
            .field("seed_request_len", &self.seed_request.as_ref().map(Vec::len))
            .finish()
    }
}

impl std::error::Error for ConnectRelayError {}

impl From<io::Error> for ConnectRelayError {
    fn from(error: io::Error) -> Self {
        Self::new(error, false)
    }
}

/// Protocol-specific reply sent to the client on successful upstream connect.
pub(super) enum SuccessReply {
    /// Transparent proxy: no reply needed.
    None,
    /// SOCKS4: fixed success reply.
    Socks4,
    /// SOCKS5: reply includes the upstream bind address.
    Socks5,
    /// HTTP CONNECT: fixed 200 OK reply.
    HttpConnect,
}

/// Common connect-relay-WS fallback flow used by all protocol handlers except shadowsocks.
///
/// Handles:
/// 1. WS tunnel Always mode attempt
/// 2. Delay connect (read first request before connecting)
/// 3. Route selection + upstream relay
/// 4. WS tunnel Fallback mode on desync failure
///
/// Returns the raw error on failure -- callers handle protocol-specific error policy
/// (linger for transparent, swallow for SOCKS5/HTTP).
pub(super) fn connect_and_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    reply: SuccessReply,
) -> Result<(), ConnectRelayError> {
    connect_and_relay_with(
        client,
        target,
        state,
        host_hint,
        reply,
        write_success_reply,
        run_ws_tunnel,
        run_ws_tunnel_with_seed,
        maybe_delay_connect,
        immediate_connect_relay,
        delayed_connect_relay,
        connect_after_ws_attempt,
    )
}

#[allow(clippy::too_many_arguments)]
fn connect_and_relay_with<
    WriteSuccessReply,
    RunWsTunnel,
    RunWsTunnelWithSeed,
    MaybeDelayConnect,
    ImmediateConnectRelay,
    DelayedConnectRelay,
    ConnectAfterWsAttempt,
>(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    reply: SuccessReply,
    mut write_success_reply_fn: WriteSuccessReply,
    mut run_ws_tunnel_fn: RunWsTunnel,
    mut run_ws_tunnel_with_seed_fn: RunWsTunnelWithSeed,
    mut maybe_delay_connect_fn: MaybeDelayConnect,
    mut immediate_connect_relay_fn: ImmediateConnectRelay,
    mut delayed_connect_relay_fn: DelayedConnectRelay,
    mut connect_after_ws_attempt_fn: ConnectAfterWsAttempt,
) -> Result<(), ConnectRelayError>
where
    WriteSuccessReply: FnMut(&mut TcpStream, &SuccessReply, Option<&TcpStream>) -> io::Result<()>,
    RunWsTunnel: FnMut(TcpStream, &RuntimeState) -> WsTunnelResult,
    RunWsTunnelWithSeed: FnMut(TcpStream, Vec<u8>, &RuntimeState) -> WsTunnelResult,
    MaybeDelayConnect: FnMut(
        &mut TcpStream,
        &RuntimeState,
        SocketAddr,
        Option<&str>,
        HandshakeKind,
    ) -> Result<DelayConnect, ConnectRelayError>,
    ImmediateConnectRelay: FnMut(
        &mut TcpStream,
        SocketAddr,
        &RuntimeState,
        Option<String>,
        &SuccessReply,
    ) -> Result<(), ConnectRelayError>,
    DelayedConnectRelay: FnMut(
        &mut TcpStream,
        SocketAddr,
        &RuntimeState,
        Option<String>,
        crate::runtime_policy::ConnectionRoute,
        Vec<u8>,
    ) -> Result<(), ConnectRelayError>,
    ConnectAfterWsAttempt:
        FnMut(&mut TcpStream, SocketAddr, &RuntimeState, Option<String>, Vec<u8>) -> Result<(), ConnectRelayError>,
{
    // Always mode: try WS tunnel first
    if should_ws_tunnel_first(target, state).is_some() {
        write_success_reply_fn(client, &reply, None).map_err(|err| ConnectRelayError::new(err, false))?;
        match run_ws_tunnel_fn(client.try_clone().map_err(|err| ConnectRelayError::new(err, true))?, state) {
            WsTunnelResult::ValidatedMtproto { .. } => return Ok(()),
            WsTunnelResult::NotMtproto { seed_request } => {
                tracing::debug!("WS tunnel Always mode: first request is not MTProto, falling back to desync");
                return connect_after_ws_attempt_fn(client, target, state, host_hint, seed_request);
            }
            WsTunnelResult::UnmappableDc { raw_dc, dc, seed_request } => {
                tracing::info!(
                    "WS tunnel Always mode: decoded non-tunnelable MTProto DC raw={} normalized={:?}, falling back",
                    raw_dc,
                    dc
                );
                return connect_after_ws_attempt_fn(client, target, state, host_hint, seed_request);
            }
            WsTunnelResult::ShortInit { seed_request, error } => {
                tracing::debug!("WS tunnel Always mode: short init while sniffing MTProto: {error}");
                return connect_after_ws_attempt_fn(client, target, state, host_hint, seed_request);
            }
            WsTunnelResult::BootstrapFailed { dc, seed_request, error } => {
                tracing::warn!(
                    "WS tunnel Always mode: bootstrap failed for raw DC {} (class {:?}): {error}",
                    dc.raw(),
                    dc.class()
                );
                return connect_after_ws_attempt_fn(client, target, state, host_hint, seed_request);
            }
            WsTunnelResult::WsOpenOrRelayFailed { dc, seed_request, error } => {
                tracing::warn!(
                    "WS tunnel Always mode: relay failed for raw DC {} (class {:?}): {error}",
                    dc.raw(),
                    dc.class()
                );
                return connect_after_ws_attempt_fn(client, target, state, host_hint, seed_request);
            }
        }
    }

    let handshake_kind = match reply {
        SuccessReply::Socks4 => Some(HandshakeKind::Socks4),
        SuccessReply::Socks5 => Some(HandshakeKind::Socks5),
        SuccessReply::HttpConnect => Some(HandshakeKind::HttpConnect),
        SuccessReply::None => None,
    };

    let desync_result = match handshake_kind {
        Some(kind) => match maybe_delay_connect_fn(client, state, target, host_hint.as_deref(), kind)? {
            DelayConnect::Immediate => immediate_connect_relay_fn(client, target, state, host_hint, &reply),
            DelayConnect::Delayed { route, payload } => {
                delayed_connect_relay_fn(client, target, state, host_hint, route, payload)
            }
            DelayConnect::Closed => Ok(()),
        },
        // Transparent proxy: no delay_conn, always immediate
        None => immediate_connect_relay_fn(client, target, state, host_hint, &reply),
    };

    match desync_result {
        Ok(()) => Ok(()),
        Err(mut err) => {
            // Fallback mode: try WS tunnel after desync failure
            if should_ws_tunnel_fallback(target, state).is_some() {
                let ws_result = if let Some(seed_request) = err.seed_request().map(ToOwned::to_owned) {
                    tracing::info!("WS tunnel fallback: reusing preserved first request after desync exhaustion");
                    if !err.success_reply_sent() {
                        write_success_reply_fn(client, &reply, None)
                            .map_err(|write_err| ConnectRelayError::new(write_err, false))?;
                        err.mark_success_reply_sent();
                    }
                    Some(run_ws_tunnel_with_seed_fn(
                        client.try_clone().map_err(|clone_err| ConnectRelayError::new(clone_err, true))?,
                        seed_request,
                        state,
                    ))
                } else if !err.success_reply_sent() {
                    tracing::info!("WS tunnel fallback: reading MTProto seed after desync connect failure");
                    write_success_reply_fn(client, &reply, None)
                        .map_err(|write_err| ConnectRelayError::new(write_err, false))?;
                    err.mark_success_reply_sent();
                    Some(run_ws_tunnel_fn(
                        client.try_clone().map_err(|clone_err| ConnectRelayError::new(clone_err, true))?,
                        state,
                    ))
                } else {
                    None
                };

                if let Some(result) = ws_result {
                    match result {
                        WsTunnelResult::ValidatedMtproto { dc } => {
                            if let Some(telemetry) = &state.telemetry {
                                telemetry.on_ws_tunnel_escalation(target, dc.number(), true);
                            }
                            return Ok(());
                        }
                        WsTunnelResult::NotMtproto { .. } => {
                            tracing::debug!("WS tunnel fallback: preserved request is not MTProto");
                            if let Some(telemetry) = &state.telemetry {
                                if let Some(dc) = super::ws_tunnel::detect_telegram_dc(target) {
                                    telemetry.on_ws_tunnel_escalation(target, dc, false);
                                }
                            }
                        }
                        WsTunnelResult::UnmappableDc { raw_dc, dc, .. } => {
                            tracing::info!(
                                "WS tunnel fallback: decoded non-tunnelable MTProto DC raw={} normalized={:?}",
                                raw_dc,
                                dc
                            );
                            if let Some(telemetry) = &state.telemetry {
                                if let Some(dc) = super::ws_tunnel::detect_telegram_dc(target) {
                                    telemetry.on_ws_tunnel_escalation(target, dc, false);
                                }
                            }
                        }
                        WsTunnelResult::ShortInit { error, .. } => {
                            tracing::debug!("WS tunnel fallback: short init while waiting for MTProto seed: {error}");
                            if let Some(telemetry) = &state.telemetry {
                                if let Some(dc) = super::ws_tunnel::detect_telegram_dc(target) {
                                    telemetry.on_ws_tunnel_escalation(target, dc, false);
                                }
                            }
                        }
                        WsTunnelResult::BootstrapFailed { dc, error, .. } => {
                            tracing::warn!(
                                "WS tunnel fallback: bootstrap failed for raw DC {} (class {:?}): {error}",
                                dc.raw(),
                                dc.class()
                            );
                            if let Some(telemetry) = &state.telemetry {
                                telemetry.on_ws_tunnel_escalation(target, dc.number(), false);
                            }
                        }
                        WsTunnelResult::WsOpenOrRelayFailed { dc, error, .. } => {
                            tracing::warn!(
                                "WS tunnel fallback: relay failed for raw DC {} (class {:?}): {error}",
                                dc.raw(),
                                dc.class()
                            );
                            if let Some(telemetry) = &state.telemetry {
                                telemetry.on_ws_tunnel_escalation(target, dc.number(), false);
                            }
                        }
                    }
                }
            }
            Err(err)
        }
    }
}

fn immediate_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    reply: &SuccessReply,
) -> Result<(), ConnectRelayError> {
    let (upstream, route) = super::super::routing::connect_target(target, state, None, false, host_hint)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    write_success_reply(client, reply, Some(&upstream)).map_err(|err| ConnectRelayError::new(err, false))?;
    super::super::relay::relay(
        client.try_clone().map_err(|err| ConnectRelayError::new(err, !matches!(reply, SuccessReply::None)))?,
        upstream,
        state,
        target,
        route,
        None,
    )
    .map_err(|err| ConnectRelayError::new(err, !matches!(reply, SuccessReply::None)))
}

fn delayed_connect_relay(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    route: crate::runtime_policy::ConnectionRoute,
    payload: Vec<u8>,
) -> Result<(), ConnectRelayError> {
    let host = extract_host(&state.config, &payload).or(host_hint);
    let (upstream, route) =
        super::super::routing::connect_target_with_route(target, state, route, Some(&payload), host)
            .map_err(|err| ConnectRelayError::with_seed_request(err, true, Some(payload.clone())))?;
    super::super::relay::relay(
        client.try_clone().map_err(|err| ConnectRelayError::new(err, true))?,
        upstream,
        state,
        target,
        route,
        Some(payload.clone()),
    )
    .map_err(|err| ConnectRelayError::with_seed_request(err, true, Some(payload)))
}

fn connect_after_ws_attempt(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    seed_request: Vec<u8>,
) -> Result<(), ConnectRelayError> {
    let seed_request = (!seed_request.is_empty()).then_some(seed_request);
    let (upstream, route) =
        super::super::routing::connect_target(target, state, seed_request.as_deref(), true, host_hint)
            .map_err(|err| ConnectRelayError::new(err, true))?;
    super::super::relay::relay(
        client.try_clone().map_err(|err| ConnectRelayError::new(err, true))?,
        upstream,
        state,
        target,
        route,
        seed_request,
    )
    .map_err(|err| ConnectRelayError::new(err, true))
}

/// Write the protocol-appropriate success reply to the client.
fn write_success_reply(client: &mut TcpStream, reply: &SuccessReply, upstream: Option<&TcpStream>) -> io::Result<()> {
    use std::io::Write;
    match reply {
        SuccessReply::None => Ok(()),
        SuccessReply::Socks4 => client.write_all(encode_socks4_reply(true).as_bytes()),
        SuccessReply::Socks5 => {
            let reply_addr = upstream
                .and_then(|u| u.local_addr().ok())
                .unwrap_or_else(|| SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0));
            client.write_all(encode_socks5_reply(0, reply_addr).as_bytes())
        }
        SuccessReply::HttpConnect => client.write_all(encode_http_connect_reply(true).as_bytes()),
    }
}

/// Maximum time to wait for the first request in delay_conn mode.
const DELAY_CONN_READ_TIMEOUT: Duration = Duration::from_secs(60);

fn maybe_delay_connect(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    host_hint: Option<&str>,
    handshake: HandshakeKind,
) -> Result<DelayConnect, ConnectRelayError> {
    if !state.config.network.delay_conn {
        return Ok(DelayConnect::Immediate);
    }
    let route = super::super::routing::select_route(state, target, None, None, true)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    let group = state.config.groups.get(route.group_index).ok_or_else(|| {
        ConnectRelayError::new(io::Error::new(io::ErrorKind::NotFound, "missing desync group"), false)
    })?;
    if !group_requires_payload(group) {
        return Ok(DelayConnect::Immediate);
    }

    send_success_reply(client, handshake).map_err(|err| ConnectRelayError::new(err, false))?;
    let Some(payload) = read_blocking_first_request(client, state.config.network.buffer_size)
        .map_err(|err| ConnectRelayError::new(err, true))?
    else {
        return Ok(DelayConnect::Closed);
    };

    let host = extract_host(&state.config, &payload).or_else(|| host_hint.map(ToOwned::to_owned));
    let route = if delayed_route_matches(&state.config, route.group_index, target, &payload, host.as_deref()) {
        route
    } else {
        let cache = state.cache.lock().map_err(|_| io::Error::other("cache mutex poisoned"))?;
        cache
            .select_next(
                &state.config,
                &route,
                target,
                Some(&payload),
                host.as_deref(),
                TransportProtocol::Tcp,
                DETECT_CONNECT,
                true,
                None,
            )
            .ok_or_else(|| {
                ConnectRelayError::new(
                    io::Error::new(io::ErrorKind::PermissionDenied, "no matching desync group"),
                    true,
                )
            })?
    };

    Ok(DelayConnect::Delayed { route, payload })
}

fn delayed_route_matches(
    config: &ripdpi_config::RuntimeConfig,
    group_index: usize,
    target: SocketAddr,
    payload: &[u8],
    host_hint: Option<&str>,
) -> bool {
    if route_matches_payload(config, group_index, target, payload, TransportProtocol::Tcp) {
        return true;
    }

    let Some(host) = host_hint else {
        return false;
    };
    let Some(group) = config.groups.get(group_index) else {
        return false;
    };
    if !group.matches.filters.hosts_match(host) {
        return false;
    }

    group.matches.any_protocol || group.matches.proto == 0 || (group.matches.proto & (IS_HTTP | IS_HTTPS)) == 0
}

fn read_blocking_first_request(client: &mut TcpStream, buffer_size: usize) -> io::Result<Option<Vec<u8>>> {
    let original_timeout = client.read_timeout()?;
    client.set_read_timeout(Some(DELAY_CONN_READ_TIMEOUT))?;
    let mut buffer = vec![0u8; buffer_size.max(16_384)];
    let result = match client.read(&mut buffer) {
        Ok(0) => Ok(None),
        Ok(n) => {
            buffer.truncate(n);
            Ok(Some(buffer))
        }
        Err(err) => Err(err),
    };
    client.set_read_timeout(original_timeout)?;
    result
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
    use crate::RuntimeTelemetrySink;
    use ripdpi_config::{RuntimeConfig, WsTunnelMode};
    use ripdpi_failure_classifier::ClassifiedFailure;
    use ripdpi_ws_tunnel::TelegramDc;
    use std::sync::atomic::{AtomicUsize, Ordering as StdOrdering};
    use std::sync::{Arc as StdArc, Mutex as StdMutex};

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    fn runtime_state(config: RuntimeConfig, telemetry: Option<StdArc<dyn RuntimeTelemetrySink>>) -> RuntimeState {
        RuntimeState {
            config: crate::sync::Arc::new(config.clone()),
            cache: crate::sync::Arc::new(crate::sync::Mutex::new(RuntimePolicy::load(&config))),
            adaptive_fake_ttl: crate::sync::Arc::new(crate::sync::Mutex::new(AdaptiveFakeTtlResolver::default())),
            adaptive_tuning: crate::sync::Arc::new(crate::sync::Mutex::new(AdaptivePlannerResolver::default())),
            retry_stealth: crate::sync::Arc::new(crate::sync::RwLock::new(RetryPacer::default())),
            strategy_evolver: crate::sync::Arc::new(crate::sync::RwLock::new(StrategyEvolver::new(false, 0.0))),
            active_clients: crate::sync::Arc::new(crate::sync::AtomicUsize::new(0)),
            telemetry,
            runtime_context: None,
            control: None,
            ttl_unavailable: crate::sync::Arc::new(crate::sync::AtomicBool::new(false)),
            reprobe_tracker: std::sync::Arc::new(crate::runtime::reprobe::ReprobeTracker::new()),
            #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
            io_uring: None,
        }
    }

    #[derive(Default)]
    struct TestTelemetry {
        ws_escalations: StdMutex<Vec<(SocketAddr, u8, bool)>>,
    }

    impl RuntimeTelemetrySink for TestTelemetry {
        fn on_listener_started(&self, _bind_addr: SocketAddr, _max_clients: usize, _group_count: usize) {}

        fn on_listener_stopped(&self) {}

        fn on_client_accepted(&self) {}

        fn on_client_finished(&self) {}

        fn on_client_error(&self, _error: &io::Error) {}

        fn on_route_selected(
            &self,
            _target: SocketAddr,
            _group_index: usize,
            _host: Option<&str>,
            _phase: &'static str,
        ) {
        }

        fn on_failure_classified(&self, _target: SocketAddr, _failure: &ClassifiedFailure, _host: Option<&str>) {}

        fn on_route_advanced(
            &self,
            _target: SocketAddr,
            _from_group: usize,
            _to_group: usize,
            _trigger: u32,
            _host: Option<&str>,
        ) {
        }

        fn on_host_autolearn_state(
            &self,
            _enabled: bool,
            _learned_host_count: usize,
            _penalized_host_count: usize,
            _blocked_host_count: usize,
            _last_block_signal: Option<&str>,
            _last_block_provider: Option<&str>,
        ) {
        }

        fn on_host_autolearn_event(&self, _action: &'static str, _host: Option<&str>, _group_index: Option<usize>) {}

        fn on_ws_tunnel_escalation(&self, target: SocketAddr, dc: u8, success: bool) {
            self.ws_escalations.lock().expect("ws escalations lock").push((target, dc, success));
        }
    }

    #[test]
    fn always_mode_replays_non_mtproto_seed_through_plain_connect() {
        let (_peer, mut client) = connected_pair();
        let mut config = RuntimeConfig::default();
        config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
        let state = runtime_state(config, None);
        let target = SocketAddr::from(([149, 154, 167, 91], 443));
        let seed_request = vec![1_u8, 2, 3, 4, 5];
        let sniff_seed = seed_request.clone();
        let expected_seed = seed_request.clone();
        let write_count = StdArc::new(AtomicUsize::new(0));

        let result = connect_and_relay_with(
            &mut client,
            target,
            &state,
            Some("telegram-dc2".to_string()),
            SuccessReply::Socks5,
            {
                let write_count = write_count.clone();
                move |_client, _reply, _upstream| {
                    write_count.fetch_add(1, StdOrdering::Relaxed);
                    Ok(())
                }
            },
            move |_client, _state| WsTunnelResult::NotMtproto { seed_request: sniff_seed.clone() },
            |_client, _seed_request, _state| unreachable!("fallback WS should not be used"),
            |_client, _state, _target, _host_hint, _handshake| unreachable!("desync path should not run"),
            |_client, _target, _state, _dc_host, _reply| unreachable!("plain immediate relay should not run"),
            |_client, _target, _state, _dc_host, _route, _payload| unreachable!("plain delayed relay should not run"),
            move |_client, replay_target, _state, dc_host, replay_seed| {
                assert_eq!(replay_target, target);
                assert_eq!(dc_host.as_deref(), Some("telegram-dc2"));
                assert_eq!(replay_seed, expected_seed);
                Ok(())
            },
        );

        assert!(result.is_ok());
        assert_eq!(write_count.load(StdOrdering::Relaxed), 1);
    }

    #[test]
    fn always_mode_replays_seed_through_plain_connect_after_bootstrap_failure() {
        let (_peer, mut client) = connected_pair();
        let mut config = RuntimeConfig::default();
        config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
        let state = runtime_state(config, None);
        let target = SocketAddr::from(([149, 154, 167, 91], 443));
        let seed_request = vec![7_u8; 64];
        let bootstrap_seed = seed_request.clone();
        let expected_seed = seed_request.clone();

        let result = connect_and_relay_with(
            &mut client,
            target,
            &state,
            Some("telegram-dc2".to_string()),
            SuccessReply::Socks5,
            |_client, _reply, _upstream| Ok(()),
            move |_client, _state| WsTunnelResult::BootstrapFailed {
                dc: TelegramDc::production(2),
                seed_request: bootstrap_seed.clone(),
                error: io::Error::new(io::ErrorKind::TimedOut, "bootstrap timed out"),
            },
            |_client, _seed_request, _state| unreachable!("fallback WS should not be used"),
            |_client, _state, _target, _host_hint, _handshake| unreachable!("desync path should not run"),
            |_client, _target, _state, _dc_host, _reply| unreachable!("plain immediate relay should not run"),
            |_client, _target, _state, _dc_host, _route, _payload| unreachable!("plain delayed relay should not run"),
            move |_client, replay_target, _state, dc_host, replay_seed| {
                assert_eq!(replay_target, target);
                assert_eq!(dc_host.as_deref(), Some("telegram-dc2"));
                assert_eq!(replay_seed, expected_seed);
                Ok(())
            },
        );

        assert!(result.is_ok());
    }

    #[test]
    fn fallback_mode_reuses_preserved_seed_for_validated_mtproto() {
        let (_peer, mut client) = connected_pair();
        let mut config = RuntimeConfig::default();
        config.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
        let telemetry = StdArc::new(TestTelemetry::default());
        let state = runtime_state(config, Some(telemetry.clone()));
        let target = SocketAddr::from(([149, 154, 167, 91], 443));
        let seed_request = vec![9_u8; 64];
        let fallback_seed = seed_request.clone();
        let preserved_seed = seed_request.clone();
        let write_count = StdArc::new(AtomicUsize::new(0));

        let result = connect_and_relay_with(
            &mut client,
            target,
            &state,
            Some("telegram-dc2".to_string()),
            SuccessReply::Socks5,
            {
                let write_count = write_count.clone();
                move |_client, _reply, _upstream| {
                    write_count.fetch_add(1, StdOrdering::Relaxed);
                    Ok(())
                }
            },
            |_client, _state| unreachable!("fresh WS sniff should not be used"),
            move |_client, replay_seed, _state| {
                assert_eq!(replay_seed, fallback_seed);
                WsTunnelResult::ValidatedMtproto { dc: TelegramDc::production(2) }
            },
            |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate),
            move |_client, _target, _state, _dc_host, _reply| {
                Err(ConnectRelayError::with_seed_request(
                    io::Error::other("desync exhausted"),
                    true,
                    Some(preserved_seed.clone()),
                ))
            },
            |_client, _target, _state, _dc_host, _route, _payload| unreachable!("delayed relay should not run"),
            |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
        );

        assert!(result.is_ok());
        assert_eq!(write_count.load(StdOrdering::Relaxed), 0);
        assert_eq!(telemetry.ws_escalations.lock().expect("ws escalations lock").as_slice(), &[(target, 2, true)],);
    }

    #[test]
    fn fallback_mode_returns_original_error_for_non_mtproto_preserved_seed() {
        let (_peer, mut client) = connected_pair();
        let mut config = RuntimeConfig::default();
        config.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
        let telemetry = StdArc::new(TestTelemetry::default());
        let state = runtime_state(config, Some(telemetry.clone()));
        let target = SocketAddr::from(([149, 154, 167, 91], 443));
        let seed_request = vec![7_u8; 64];
        let fallback_seed = seed_request.clone();
        let preserved_seed = seed_request.clone();
        let write_count = StdArc::new(AtomicUsize::new(0));

        let err = connect_and_relay_with(
            &mut client,
            target,
            &state,
            Some("telegram-dc2".to_string()),
            SuccessReply::Socks5,
            {
                let write_count = write_count.clone();
                move |_client, _reply, _upstream| {
                    write_count.fetch_add(1, StdOrdering::Relaxed);
                    Ok(())
                }
            },
            |_client, _state| unreachable!("fresh WS sniff should not be used"),
            move |_client, replay_seed, _state| {
                assert_eq!(replay_seed, fallback_seed);
                WsTunnelResult::NotMtproto { seed_request: replay_seed }
            },
            |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate),
            move |_client, _target, _state, _dc_host, _reply| {
                Err(ConnectRelayError::with_seed_request(
                    io::Error::new(io::ErrorKind::TimedOut, "desync timeout"),
                    true,
                    Some(preserved_seed.clone()),
                ))
            },
            |_client, _target, _state, _dc_host, _route, _payload| unreachable!("delayed relay should not run"),
            |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
        )
        .expect_err("non-MTProto fallback should keep original error");

        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
        assert_eq!(write_count.load(StdOrdering::Relaxed), 0);
        assert_eq!(telemetry.ws_escalations.lock().expect("ws escalations lock").as_slice(), &[(target, 2, false)],);
    }

    #[test]
    fn fallback_mode_returns_original_error_for_bootstrap_failure() {
        let (_peer, mut client) = connected_pair();
        let mut config = RuntimeConfig::default();
        config.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
        let telemetry = StdArc::new(TestTelemetry::default());
        let state = runtime_state(config, Some(telemetry.clone()));
        let target = SocketAddr::from(([149, 154, 167, 91], 443));
        let seed_request = vec![3_u8; 64];
        let fallback_seed = seed_request.clone();
        let preserved_seed = seed_request.clone();

        let err = connect_and_relay_with(
            &mut client,
            target,
            &state,
            Some("telegram-dc2".to_string()),
            SuccessReply::Socks5,
            |_client, _reply, _upstream| Ok(()),
            |_client, _state| unreachable!("fresh WS sniff should not be used"),
            move |_client, replay_seed, _state| {
                assert_eq!(replay_seed, fallback_seed);
                WsTunnelResult::BootstrapFailed {
                    dc: TelegramDc::production(2),
                    seed_request: replay_seed,
                    error: io::Error::new(io::ErrorKind::TimedOut, "bootstrap timed out"),
                }
            },
            |_client, _state, _target, _host_hint, _handshake| Ok(DelayConnect::Immediate),
            move |_client, _target, _state, _dc_host, _reply| {
                Err(ConnectRelayError::with_seed_request(
                    io::Error::new(io::ErrorKind::TimedOut, "desync timeout"),
                    true,
                    Some(preserved_seed.clone()),
                ))
            },
            |_client, _target, _state, _dc_host, _route, _payload| unreachable!("delayed relay should not run"),
            |_client, _target, _state, _dc_host, _seed_request| unreachable!("after-WS plain fallback should not run"),
        )
        .expect_err("bootstrap failure should keep original error");

        assert_eq!(err.kind(), io::ErrorKind::TimedOut);
        assert_eq!(telemetry.ws_escalations.lock().expect("ws escalations lock").as_slice(), &[(target, 2, false)],);
    }
}
