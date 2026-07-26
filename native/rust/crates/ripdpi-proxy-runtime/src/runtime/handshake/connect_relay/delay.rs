use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

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

/// Maximum time to wait for the first request in delay_conn mode.
const DELAY_CONN_READ_TIMEOUT: Duration = Duration::from_secs(60);
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
    let first_request = if requires_delay {
        read_blocking_first_request(client, state.delayed_connect_buffer_size())
    } else {
        read_available_first_request(client, state.delayed_connect_buffer_size())
    }
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

fn read_blocking_first_request(client: &mut TcpStream, buffer_size: usize) -> io::Result<FirstRequestRead> {
    let original_timeout = client.read_timeout()?;
    client.set_read_timeout(Some(DELAY_CONN_READ_TIMEOUT))?;
    let mut buffer = vec![0u8; buffer_size];
    let result = match client.read(&mut buffer) {
        Ok(0) => Ok(FirstRequestRead::Closed),
        Ok(n) => {
            buffer.truncate(n);
            Ok(FirstRequestRead::Payload(buffer))
        }
        Err(err) => Err(err),
    };
    client.set_read_timeout(original_timeout)?;
    result
}

fn read_available_first_request(client: &mut TcpStream, buffer_size: usize) -> io::Result<FirstRequestRead> {
    let original_timeout = client.read_timeout()?;
    client.set_read_timeout(Some(DESTINATION_HOST_PROBE_TIMEOUT))?;
    let mut buffer = vec![0u8; buffer_size];
    let result = match client.read(&mut buffer) {
        Ok(0) => Ok(FirstRequestRead::Closed),
        Ok(n) => {
            buffer.truncate(n);
            Ok(FirstRequestRead::Payload(buffer))
        }
        Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
            Ok(FirstRequestRead::Pending)
        }
        Err(err) => Err(err),
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
}
