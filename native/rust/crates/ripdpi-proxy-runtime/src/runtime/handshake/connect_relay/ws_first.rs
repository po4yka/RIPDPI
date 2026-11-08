use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};

use super::super::super::state::RuntimeState;
use super::super::ws_tunnel::{
    WsTunnelResult, run_ws_tunnel_prepared, should_ws_tunnel_first, with_dialed_dc_fallback,
};
use super::ConnectRelayError;
use super::reply::SuccessReply;

pub(super) enum AlwaysWsOutcome {
    Handled,
    FallbackToDesync { seed_request: Vec<u8> },
    Failed(ConnectRelayError),
    CarrierFailed(ConnectRelayError),
}

pub(super) fn run_ws_always_first<WriteSuccessReply, RunWsTunnel>(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    reply: &SuccessReply,
    write_success_reply_fn: &mut WriteSuccessReply,
    run_ws_tunnel_fn: &mut RunWsTunnel,
) -> Result<Option<AlwaysWsOutcome>, ConnectRelayError>
where
    WriteSuccessReply: FnMut(&mut TcpStream, &SuccessReply, Option<&TcpStream>) -> io::Result<()>,
    RunWsTunnel: FnMut(TcpStream, &RuntimeState) -> WsTunnelResult,
{
    let Some(dc) = should_ws_tunnel_first(target, state) else {
        return Ok(None);
    };
    if matches!(reply, SuccessReply::Socks4) {
        return Ok(Some(match run_socks4_ws_before_reply(client, target, state, dc, None, write_success_reply_fn) {
            Ok(result) => always_ws_outcome(result),
            Err(WsBeforeReplyError::Carrier(err))
                if !state.shutdown_requested()
                    && !matches!(err.kind(), io::ErrorKind::Interrupted | io::ErrorKind::TimedOut) =>
            {
                AlwaysWsOutcome::CarrierFailed(ConnectRelayError::new(err, false))
            }
            Err(err) => AlwaysWsOutcome::Failed(err.into_relay_error()),
        }));
    }

    write_success_reply_fn(client, reply, None).map_err(|err| ConnectRelayError::new(err, false))?;
    let result = run_ws_tunnel_fn(client.try_clone().map_err(|err| ConnectRelayError::new(err, true))?, state);
    let result = with_dialed_dc_fallback(result, client, target, state);
    Ok(Some(always_ws_outcome(result)))
}

pub(super) fn normal_after_carrier_failure(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    host_hint: Option<String>,
    carrier_error: ConnectRelayError,
) -> Result<(), ConnectRelayError> {
    if state.shutdown_requested() {
        return Err(ConnectRelayError::new(io::Error::new(io::ErrorKind::Interrupted, "runtime stopped"), false));
    }
    let route = crate::runtime::routing::select_route(state, target, None, host_hint.as_deref(), true)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    let connected = super::socks_delay::connect_with(
        client,
        state,
        target,
        route,
        host_hint.as_deref(),
        &SuccessReply::Socks4,
        |client, upstream, buffer_size| {
            use crate::runtime::relay::{FirstOutboundRead, read_first_client_payload};
            use crate::runtime::ws::{WsSeedClassification, runtime_exact_telegram_dc};
            let mut watched_upstream = Some(upstream);
            let mut seed = loop {
                match read_first_client_payload(client, watched_upstream, buffer_size)? {
                    FirstOutboundRead::Payload(payload) => break payload,
                    FirstOutboundRead::ClientClosed => return Ok(FirstOutboundRead::ClientClosed),
                    FirstOutboundRead::ServerFirst => {
                        let mut banner = vec![0; buffer_size];
                        let mut reader = upstream;
                        let count = reader.read(&mut banner)?;
                        if count == 0 {
                            client.shutdown(std::net::Shutdown::Write)?;
                            watched_upstream = None;
                        } else {
                            client.write_all(&banner[..count])?;
                        }
                    }
                }
            };
            let mut remaining = [0; 64];
            while seed.len() < 64 {
                let needed = 64 - seed.len();
                match client.read(&mut remaining[..needed]) {
                    Ok(0) => break,
                    Ok(n) => seed.extend_from_slice(&remaining[..n]),
                    Err(err) if matches!(err.kind(), io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock) => break,
                    Err(err) => return Err(err),
                }
            }
            let validated_mtproto = match state.classify_mtproto_seed(&seed) {
                WsSeedClassification::ValidatedMtproto { .. } => true,
                WsSeedClassification::UnmappableDc { dc: None, .. } => runtime_exact_telegram_dc(target).is_some(),
                WsSeedClassification::NotMtproto | WsSeedClassification::UnmappableDc { dc: Some(_), .. } => false,
            };
            if validated_mtproto {
                return Err(carrier_error.into_io_error());
            }
            Ok(FirstOutboundRead::Payload(seed))
        },
    )?;
    match connected {
        super::delay::DelayConnect::Connected(upstream) => {
            super::relay::connected_relay(client, target, state, *upstream, None)
        }
        super::delay::DelayConnect::Closed => Ok(()),
        super::delay::DelayConnect::Immediate { .. } | super::delay::DelayConnect::Delayed { .. } => {
            unreachable!("connect_with returns an established connection or a closed client")
        }
    }
}

#[derive(Debug)]
pub(super) enum WsBeforeReplyError {
    Carrier(io::Error),
    Client(io::Error),
}

impl WsBeforeReplyError {
    pub(super) fn into_relay_error(self) -> ConnectRelayError {
        match self {
            Self::Carrier(err) | Self::Client(err) => ConnectRelayError::new(err, false),
        }
    }
}

pub(super) fn run_socks4_ws_before_reply<Write>(
    client: &mut TcpStream,
    target: SocketAddr,
    state: &RuntimeState,
    dc: crate::runtime::ws::RuntimeTelegramDc,
    seed_request: Option<Vec<u8>>,
    write: &mut Write,
) -> Result<WsTunnelResult, WsBeforeReplyError>
where
    Write: FnMut(&mut TcpStream, &SuccessReply, Option<&TcpStream>) -> io::Result<()>,
{
    use std::time::Instant;
    let timeout = state.ws_tunnel_config(None).connect_timeout().unwrap_or(crate::runtime::state::HANDSHAKE_TIMEOUT);
    let deadline = Instant::now() + timeout;
    let should_cancel = || state.shutdown_requested() || Instant::now() >= deadline;
    let check_stage = || {
        if state.shutdown_requested() {
            Err(io::Error::new(io::ErrorKind::Interrupted, "runtime stopped during WS bootstrap"))
        } else if Instant::now() >= deadline {
            Err(io::Error::new(io::ErrorKind::TimedOut, "WS bootstrap deadline expired"))
        } else {
            Ok(())
        }
    };
    let mut guarded_write = |client: &mut TcpStream, reply: &SuccessReply, upstream: Option<&TcpStream>| {
        check_stage()?;
        write(client, reply, upstream)
    };
    run_prepared_ws_first(
        client,
        &SuccessReply::Socks4,
        &mut guarded_write,
        || {
            check_stage()?;
            let resolved = state.resolve_ws_tunnel_addr_with_cancel(dc, &should_cancel);
            check_stage()?;
            let addr = resolved?;
            let remaining = deadline.saturating_duration_since(Instant::now());
            let config = state.ws_tunnel_config(Some(addr)).with_connect_timeout(remaining);
            let prepared = state.open_ws_tunnel(dc, &config, &should_cancel);
            check_stage()?;
            prepared
        },
        |client, prepared| run_ws_tunnel_prepared(client, target, dc, prepared, seed_request, state),
    )
}

fn run_prepared_ws_first<Open, Run, Write, Prepared>(
    client: &mut TcpStream,
    reply: &SuccessReply,
    write: &mut Write,
    open: Open,
    run: Run,
) -> Result<WsTunnelResult, WsBeforeReplyError>
where
    Open: FnOnce() -> io::Result<Prepared>,
    Run: FnOnce(TcpStream, Prepared) -> WsTunnelResult,
    Write: FnMut(&mut TcpStream, &SuccessReply, Option<&TcpStream>) -> io::Result<()>,
{
    let prepared = open().map_err(WsBeforeReplyError::Carrier)?;
    let relay_client = client.try_clone().map_err(WsBeforeReplyError::Client)?;
    write(client, reply, None).map_err(WsBeforeReplyError::Client)?;
    Ok(run(relay_client, prepared))
}

fn always_ws_outcome(result: WsTunnelResult) -> AlwaysWsOutcome {
    match result {
        WsTunnelResult::ValidatedMtproto { .. } => AlwaysWsOutcome::Handled,
        WsTunnelResult::NotMtproto { seed_request } => {
            tracing::debug!("WS tunnel Always mode: first request is not MTProto, falling back to desync");
            AlwaysWsOutcome::FallbackToDesync { seed_request }
        }
        WsTunnelResult::UnmappableDc { raw_dc, dc, seed_request } => {
            tracing::info!(
                "WS tunnel Always mode: decoded non-tunnelable MTProto DC raw={} normalized={:?}, falling back",
                raw_dc,
                dc
            );
            AlwaysWsOutcome::FallbackToDesync { seed_request }
        }
        WsTunnelResult::ShortInit { seed_request, error } => {
            tracing::debug!("WS tunnel Always mode: short init while sniffing MTProto: {error}");
            AlwaysWsOutcome::FallbackToDesync { seed_request }
        }
        WsTunnelResult::BootstrapFailed { dc, seed_request, error } => {
            tracing::warn!(
                "WS tunnel Always mode: bootstrap failed for validated MTProto raw DC {} (class {:?}), failing closed: {error}",
                dc.raw(),
                dc.class()
            );
            AlwaysWsOutcome::Failed(ConnectRelayError::with_seed_request(error, true, Some(seed_request)))
        }
        WsTunnelResult::WsOpenOrRelayFailed { dc, seed_request, error } => {
            tracing::warn!(
                "WS tunnel Always mode: relay failed for validated MTProto raw DC {} (class {:?}), failing closed: {error}",
                dc.raw(),
                dc.class()
            );
            AlwaysWsOutcome::Failed(ConnectRelayError::with_seed_request(error, true, Some(seed_request)))
        }
    }
}

#[cfg(all(test, not(feature = "loom")))]
mod tests {
    use super::*;
    use crate::runtime::handshake::connect_relay::reply::write_success_reply;
    use crate::runtime::ws::RuntimeTelegramDc;
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener};
    use std::time::Duration;

    fn pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let peer = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        (peer, listener.accept().unwrap().0)
    }

    #[test]
    fn failed_carrier_open_leaves_socks4_connect_unacknowledged() {
        let (mut peer, mut client) = pair();
        let err = run_prepared_ws_first(
            &mut client,
            &SuccessReply::Socks4,
            &mut write_success_reply,
            || Err::<(), _>(io::Error::new(io::ErrorKind::ConnectionRefused, "owned carrier refused")),
            |_, _| unreachable!("a refused carrier cannot relay"),
        )
        .err()
        .expect("carrier failure must fail CONNECT")
        .into_relay_error();
        assert!(!err.success_reply_sent());
        assert_eq!(err.kind(), io::ErrorKind::ConnectionRefused);
        drop(client);
        assert_eq!(peer.read(&mut [0; 8]).unwrap(), 0);
    }

    #[test]
    fn socks4_client_can_wait_for_ack_until_carrier_is_ready() {
        let (mut peer, mut client) = pair();
        let carrier = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let carrier_addr = carrier.local_addr().unwrap();
        let app = std::thread::spawn(move || {
            let mut reply = [0; 8];
            peer.read_exact(&mut reply).unwrap();
            assert_eq!(reply, [0, 0x5a, 0, 0, 0, 0, 0, 0]);
            // The real carrier handshake exists before the client supplies its seed.
            let (mut upstream, _) = carrier.accept().unwrap();
            upstream.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            peer.write_all(&[0x42; 64]).unwrap();
            let mut seed = [0; 64];
            upstream.read_exact(&mut seed).unwrap();
            assert_eq!(seed, [0x42; 64]);
        });
        let result = run_prepared_ws_first(
            &mut client,
            &SuccessReply::Socks4,
            &mut write_success_reply,
            || TcpStream::connect(carrier_addr),
            |mut client, mut upstream| {
                let mut seed = [0; 64];
                client.read_exact(&mut seed).unwrap();
                upstream.write_all(&seed).unwrap();
                WsTunnelResult::ValidatedMtproto { dc: RuntimeTelegramDc::production(2) }
            },
        )
        .unwrap();
        assert!(matches!(result, WsTunnelResult::ValidatedMtproto { .. }));
        app.join().unwrap();
    }
    #[test]
    fn failed_ws_carrier_keeps_normal_traffic_but_never_leaks_validated_mtproto() {
        use crate::runtime::config::RuntimeConfig;
        let http = b"GET / HTTP/1.0\r\nX-Fixture: abcdefghijklmnopqrstuvwxyz0123456789abcdef\r\n\r\n".to_vec();
        let mtproto = super::super::super::ws_tunnel::tests::build_test_init_packet(2);
        for (payload, blocked, server_first) in
            [(http.clone(), false, false), (mtproto.clone(), true, false), (http, false, true), (mtproto, true, true)]
        {
            let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            let target = origin.local_addr().unwrap();
            let sent = payload.clone();
            let server = std::thread::spawn(move || {
                let (mut server, _) = origin.accept().unwrap();
                server.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
                if server_first {
                    server.write_all(b"BANNER").unwrap();
                }
                let mut received = Vec::new();
                server.read_to_end(&mut received).unwrap();
                if !blocked {
                    server.write_all(b"normal response").unwrap();
                }
                assert_eq!(received, if blocked { vec![] } else { sent });
                origin.set_nonblocking(true).unwrap();
                assert_eq!(origin.accept().unwrap_err().kind(), io::ErrorKind::WouldBlock);
            });
            let (mut peer, mut client) = pair();
            let app = std::thread::spawn(move || {
                let mut reply = [0; 8];
                peer.read_exact(&mut reply).unwrap();
                assert_eq!(reply, [0, 0x5a, 0, 0, 0, 0, 0, 0]);
                if server_first {
                    let mut banner = [0; 6];
                    peer.read_exact(&mut banner).unwrap();
                    assert_eq!(&banner, b"BANNER");
                }
                peer.write_all(&payload).unwrap();
                peer.shutdown(std::net::Shutdown::Write).unwrap();
                let mut response = Vec::new();
                peer.read_to_end(&mut response).unwrap();
                assert_eq!(response, if blocked { b"".as_slice() } else { b"normal response".as_slice() });
            });
            let result = normal_after_carrier_failure(
                &mut client,
                target,
                &RuntimeState::test(RuntimeConfig::default()),
                None,
                ConnectRelayError::new(io::Error::new(io::ErrorKind::ConnectionRefused, "carrier refused"), false),
            );
            drop(client);
            assert_eq!(result.is_err(), blocked, "normal fallback result: {result:?}");
            if let Err(err) = result {
                assert!(err.success_reply_sent());
                assert_eq!(err.kind(), io::ErrorKind::ConnectionRefused);
            }
            app.join().unwrap();
            server.join().unwrap();
        }
    }
    #[test]
    fn ws_dns_preparation_stops_on_owner_cancel_and_shared_deadline_before_ack() {
        use crate::runtime::config::RuntimeConfig;
        use ripdpi_proxy_runtime_adapter::model::proxy_config::{ProxyEncryptedDnsContext, ProxyRuntimeContext};
        use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
        use std::sync::{Arc, mpsc};
        use std::time::Instant;
        for cancel_owner in [false, true] {
            let dns = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            let port = dns.local_addr().unwrap().port();
            let context = ProxyRuntimeContext {
                encrypted_dns: Some(ProxyEncryptedDnsContext {
                    resolver_id: Some("owned-stalled-doh".into()),
                    protocol: "doh".into(),
                    host: "127.0.0.1".into(),
                    port,
                    tls_server_name: None,
                    bootstrap_ips: vec!["127.0.0.1".into()],
                    doh_url: Some(format!("http://127.0.0.1:{port}/dns-query")),
                    dnscrypt_provider_name: None,
                    dnscrypt_public_key: None,
                }),
                ..Default::default()
            };
            let mut config = RuntimeConfig::default();
            config.timeouts.connect_timeout_ms = if cancel_owner { 5_000 } else { 100 };
            let control = Arc::new(EmbeddedProxyControl::new_with_context(None, Some(context)));
            let state = RuntimeState::test_with_control(config, control.clone());
            let (entered_tx, entered_rx) = mpsc::sync_channel(1);
            let resolver = std::thread::spawn(move || {
                dns.set_nonblocking(true).unwrap();
                let accept_deadline = Instant::now() + Duration::from_secs(3);
                let mut stream = loop {
                    match dns.accept() {
                        Ok((stream, _)) => break stream,
                        Err(err) if err.kind() == io::ErrorKind::WouldBlock && Instant::now() < accept_deadline => {
                            std::thread::sleep(Duration::from_millis(5));
                        }
                        Err(err) => panic!("owned DNS server did not receive the request: {err}"),
                    }
                };
                stream.set_nonblocking(false).unwrap();
                stream.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
                entered_tx.send(()).unwrap();
                let mut request = Vec::new();
                match stream.read_to_end(&mut request) {
                    Ok(_) => {}
                    Err(err) => assert_eq!(err.kind(), io::ErrorKind::ConnectionReset),
                }
            });
            let stopper = std::thread::spawn(move || {
                entered_rx.recv_timeout(Duration::from_secs(3)).unwrap();
                if cancel_owner {
                    control.request_shutdown();
                }
            });
            let (mut peer, mut client) = pair();
            let started = Instant::now();
            let error = run_socks4_ws_before_reply(
                &mut client,
                SocketAddr::from(([149, 154, 167, 51], 443)),
                &state,
                RuntimeTelegramDc::production(2),
                None,
                &mut write_success_reply,
            )
            .err()
            .expect("a stalled WS resolver must stop before CONNECT success")
            .into_relay_error();
            assert_eq!(error.kind(), if cancel_owner { io::ErrorKind::Interrupted } else { io::ErrorKind::TimedOut });
            assert!(!error.success_reply_sent());
            assert!(started.elapsed() < Duration::from_secs(2));
            drop(client);
            assert_eq!(peer.read(&mut [0; 8]).unwrap(), 0);
            stopper.join().unwrap();
            resolver.join().unwrap();
        }
    }
    #[test]
    fn normal_fallback_preserves_origin_fin_before_client_payload() {
        use crate::runtime::config::RuntimeConfig;
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let target = origin.local_addr().unwrap();
        let payload = b"GET / HTTP/1.0\r\nX-Fixture: abcdefghijklmnopqrstuvwxyz0123456789abcdef\r\n\r\n";
        let server = std::thread::spawn(move || {
            let (mut server, _) = origin.accept().unwrap();
            server.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
            server.shutdown(std::net::Shutdown::Write).unwrap();
            let mut received = Vec::new();
            server.read_to_end(&mut received).unwrap();
            assert_eq!(received, payload);
        });
        let (mut peer, mut client) = pair();
        let worker = std::thread::spawn(move || {
            normal_after_carrier_failure(
                &mut client,
                target,
                &RuntimeState::test(RuntimeConfig::default()),
                None,
                ConnectRelayError::new(io::Error::new(io::ErrorKind::ConnectionRefused, "carrier refused"), false),
            )
        });
        let mut reply = [0; 8];
        peer.read_exact(&mut reply).unwrap();
        assert_eq!(reply, [0, 0x5a, 0, 0, 0, 0, 0, 0]);
        assert_eq!(peer.read(&mut [0; 1]).expect("origin FIN must reach the client before it sends payload"), 0);
        peer.write_all(payload).unwrap();
        peer.shutdown(std::net::Shutdown::Write).unwrap();
        assert!(worker.join().unwrap().is_ok());
        server.join().unwrap();
    }
}
