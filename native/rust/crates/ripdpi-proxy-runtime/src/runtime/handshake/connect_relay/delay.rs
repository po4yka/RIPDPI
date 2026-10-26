use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};

use super::super::super::state::RuntimeState;
use super::super::protocol_io::{HandshakeKind, send_success_reply};
use super::ConnectRelayError;
use crate::runtime::types::{RuntimeConnectionRoute, RuntimeTransportProtocol};

pub(super) enum DelayConnect {
    Immediate { success_reply_sent: bool },
    Delayed { route: RuntimeConnectionRoute, payload: Vec<u8> },
    Closed,
}

enum FirstRequestRead {
    Payload(Vec<u8>),
    Closed,
    Pending,
}

/// Time to wait for the first request of a group that needs a payload before the
/// flow is treated as server-first and connected without one.
const DELAY_CONN_FIRST_REQUEST_PROBE: Duration = Duration::from_secs(1);
const DESTINATION_HOST_PROBE_TIMEOUT: Duration = Duration::from_millis(50);

pub(super) fn maybe_delay_connect(
    client: &mut TcpStream,
    state: &RuntimeState,
    target: SocketAddr,
    host_hint: Option<&str>,
    handshake: Option<HandshakeKind>,
) -> Result<DelayConnect, ConnectRelayError> {
    let destination_policy_requires_host = host_hint.is_none() && state.destination_policy_may_need_host();
    if !state.delayed_connect_enabled() && !destination_policy_requires_host {
        return Ok(DelayConnect::Immediate { success_reply_sent: false });
    }
    let route = super::super::super::routing::select_route(state, target, None, host_hint, true)
        .map_err(|err| ConnectRelayError::new(err, false))?;
    let requires_delay =
        state.route_requires_delay_payload(route.group_index).map_err(|err| ConnectRelayError::new(err, false))?;
    if !requires_delay && !destination_policy_requires_host {
        return Ok(DelayConnect::Immediate { success_reply_sent: false });
    }

    if let Some(handshake) = handshake {
        send_success_reply(client, handshake).map_err(|err| ConnectRelayError::new(err, false))?;
    }
    // A server-first protocol (SMTP, IMAP, FTP) sends nothing until the server speaks, so a
    // silent client falls back to the immediate path and its server-first handling.
    let probe = if requires_delay { DELAY_CONN_FIRST_REQUEST_PROBE } else { DESTINATION_HOST_PROBE_TIMEOUT };
    let first_request = read_first_request(client, state.delayed_connect_buffer_size(), probe)
        .map_err(|err| ConnectRelayError::new(err, true))?;
    let payload = match first_request {
        FirstRequestRead::Payload(payload) => payload,
        FirstRequestRead::Closed => return Ok(DelayConnect::Closed),
        FirstRequestRead::Pending => return Ok(DelayConnect::Immediate { success_reply_sent: handshake.is_some() }),
    };

    let host = state.extract_relay_payload_host(&payload).or_else(|| host_hint.map(ToOwned::to_owned));
    let route = if state.delayed_route_matches_payload(route.group_index, target, &payload, host.as_deref()) {
        route
    } else {
        state
            .select_next_route(
                &route,
                target,
                Some(&payload),
                host.as_deref(),
                RuntimeTransportProtocol::Tcp,
                RuntimeState::connect_failure_trigger(),
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

/// Reads the client's first request, waiting up to `idle_timeout` for its first byte.
/// A TLS ClientHello split over several reads is reassembled first, as on the
/// immediate path, so host matching and desync never see a truncated record.
fn read_first_request(
    client: &mut TcpStream,
    buffer_size: usize,
    idle_timeout: Duration,
) -> io::Result<FirstRequestRead> {
    let original_timeout = client.read_timeout()?;
    let mut buffer = vec![0u8; buffer_size];
    let mut assembler = RuntimeState::first_outbound_tls_client_hello_assembler();
    let result = loop {
        let now = Instant::now();
        if let Err(err) = client.set_read_timeout(Some(assembler.timeout(now).unwrap_or(idle_timeout))) {
            break Err(err);
        }
        match client.read(&mut buffer) {
            Ok(0) => break Ok(assembler.finish().map_or(FirstRequestRead::Closed, FirstRequestRead::Payload)),
            Ok(n) => {
                if let Some(payload) = assembler.push(&buffer[..n], now) {
                    break Ok(FirstRequestRead::Payload(payload));
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if let Some(payload) = assembler.flush_on_timeout(Instant::now()) {
                    break Ok(FirstRequestRead::Payload(payload));
                }
                if !assembler.is_buffering() {
                    break Ok(FirstRequestRead::Pending);
                }
            }
            Err(err) => break Err(err),
        }
    };
    let restore = client.set_read_timeout(original_timeout);
    match (result, restore) {
        (Err(err), _) | (_, Err(err)) => Err(err),
        (Ok(result), Ok(())) => Ok(result),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::config::RuntimeConfig;
    use ripdpi_proxy_runtime_adapter::model::config::{
        DestinationDomainMatcher, DestinationDomainMatcherKind, DestinationRoutingAction, DestinationRoutingNetwork,
        DestinationRoutingPolicy, DestinationRoutingRule, DesyncGroup,
    };
    use std::io::Write;
    use std::net::{Ipv4Addr, TcpListener};

    #[test]
    fn domain_policy_forces_first_payload_read_before_any_egress_connect() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind client listener");
        let client_addr = listener.local_addr().expect("client listener addr");
        let writer = std::thread::spawn(move || {
            let mut client = TcpStream::connect(client_addr).expect("connect client");
            let mut reply = [0_u8; 10];
            client.read_exact(&mut reply).expect("read SOCKS5 success");
            assert_eq!(&reply[..2], &[5, 0]);
            client.write_all(b"GET / HTTP/1.1\r\nHost: direct.example\r\n\r\n").expect("write first payload");
        });
        let (mut accepted, _) = listener.accept().expect("accept client");
        let target = SocketAddr::from(([192, 0, 2, 10], 443));
        let config = RuntimeConfig {
            groups: vec![DesyncGroup::new(0)],
            destination_routing: DestinationRoutingPolicy {
                rules: vec![DestinationRoutingRule {
                    action: DestinationRoutingAction::Direct,
                    network: DestinationRoutingNetwork::Tcp,
                    domains: vec![DestinationDomainMatcher {
                        kind: DestinationDomainMatcherKind::Exact,
                        value: "direct.example".to_string(),
                    }],
                    ip_ranges: vec![],
                    destination_ports: vec![],
                }],
                default_action: DestinationRoutingAction::Tunneled,
                canonical_digest: "digest".to_string(),
            },
            ..Default::default()
        };

        let delayed =
            maybe_delay_connect(&mut accepted, &RuntimeState::test(config), target, None, Some(HandshakeKind::Socks5))
                .expect("policy delay");

        assert!(matches!(delayed, DelayConnect::Delayed { .. }));
        writer.join().expect("writer");
    }

    #[test]
    fn hostless_server_first_flow_uses_tunneled_default_without_waiting_for_client_payload() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind client listener");
        let client_addr = listener.local_addr().expect("client listener addr");
        let client = std::thread::spawn(move || {
            let mut client = TcpStream::connect(client_addr).expect("connect client");
            let mut reply = [0_u8; 10];
            client.read_exact(&mut reply).expect("read SOCKS5 success");
            assert_eq!(&reply[..2], &[5, 0]);
            std::thread::sleep(Duration::from_millis(250));
        });
        let (mut accepted, _) = listener.accept().expect("accept client");
        let target = SocketAddr::from(([192, 0, 2, 22], 22));
        let config = RuntimeConfig {
            groups: vec![DesyncGroup::new(0)],
            destination_routing: DestinationRoutingPolicy {
                rules: vec![DestinationRoutingRule {
                    action: DestinationRoutingAction::Direct,
                    network: DestinationRoutingNetwork::Tcp,
                    domains: vec![DestinationDomainMatcher {
                        kind: DestinationDomainMatcherKind::Suffix,
                        value: "example".to_string(),
                    }],
                    ip_ranges: vec![],
                    destination_ports: vec![],
                }],
                default_action: DestinationRoutingAction::Tunneled,
                canonical_digest: "digest".to_string(),
            },
            ..Default::default()
        };

        let started = std::time::Instant::now();
        let delayed =
            maybe_delay_connect(&mut accepted, &RuntimeState::test(config), target, None, Some(HandshakeKind::Socks5))
                .expect("policy probe");

        assert!(matches!(delayed, DelayConnect::Immediate { success_reply_sent: true }));
        assert!(started.elapsed() < Duration::from_millis(200));
        client.join().expect("client");
    }
    #[test]
    fn hostless_flow_reassembles_a_client_hello_split_across_reads() {
        let hello = RuntimeState::build_probe_client_hello("direct.example");
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind client listener");
        let client_addr = listener.local_addr().expect("client listener addr");
        let sent = hello.clone();
        let writer = std::thread::spawn(move || {
            let mut client = TcpStream::connect(client_addr).expect("connect client");
            client.set_nodelay(true).expect("nodelay");
            let mut reply = [0_u8; 10];
            client.read_exact(&mut reply).expect("read SOCKS5 success");
            client.write_all(&sent[..20]).expect("write first ClientHello segment");
            std::thread::sleep(Duration::from_millis(20));
            client.write_all(&sent[20..]).expect("write rest of ClientHello");
        });
        let (mut accepted, _) = listener.accept().expect("accept client");
        let config = RuntimeConfig {
            groups: vec![DesyncGroup::new(0)],
            destination_routing: DestinationRoutingPolicy {
                rules: vec![DestinationRoutingRule {
                    action: DestinationRoutingAction::Direct,
                    network: DestinationRoutingNetwork::Tcp,
                    domains: vec![DestinationDomainMatcher {
                        kind: DestinationDomainMatcherKind::Exact,
                        value: "direct.example".to_string(),
                    }],
                    ip_ranges: vec![],
                    destination_ports: vec![],
                }],
                default_action: DestinationRoutingAction::Tunneled,
                canonical_digest: "digest".to_string(),
            },
            ..Default::default()
        };
        let target = SocketAddr::from(([192, 0, 2, 10], 443));

        let delayed =
            maybe_delay_connect(&mut accepted, &RuntimeState::test(config), target, None, Some(HandshakeKind::Socks5))
                .expect("policy delay");

        let DelayConnect::Delayed { payload, .. } = delayed else {
            panic!("a ClientHello must be read before the egress connect");
        };
        assert_eq!(payload, hello, "the delayed path must keep the whole ClientHello, not its first segment");
        writer.join().expect("writer");
    }
    #[test]
    fn payload_group_falls_back_to_immediate_connect_for_a_server_first_client() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind client listener");
        let client_addr = listener.local_addr().expect("client listener addr");
        let (done_tx, done_rx) = std::sync::mpsc::channel::<()>();
        let client = std::thread::spawn(move || {
            let mut client = TcpStream::connect(client_addr).expect("connect client");
            let mut reply = [0_u8; 10];
            client.read_exact(&mut reply).expect("read SOCKS5 success");
            // A server-first client waits for the banner and sends nothing.
            let _ = done_rx.recv_timeout(Duration::from_secs(10));
        });
        let (mut accepted, _) = listener.accept().expect("accept client");
        let mut config = RuntimeConfig::default();
        config.network.delay_conn = true;
        config.groups[0].matches.proto = ripdpi_packets::IS_HTTPS;
        let target = SocketAddr::from(([192, 0, 2, 25], 25));

        let started = std::time::Instant::now();
        let delayed =
            maybe_delay_connect(&mut accepted, &RuntimeState::test(config), target, None, Some(HandshakeKind::Socks5))
                .expect("server-first flow must not fail");

        assert!(matches!(delayed, DelayConnect::Immediate { success_reply_sent: true }));
        assert!(started.elapsed() < Duration::from_secs(3), "waited {:?} for a server-first client", started.elapsed());
        done_tx.send(()).expect("release client");
        client.join().expect("client");
    }
}
