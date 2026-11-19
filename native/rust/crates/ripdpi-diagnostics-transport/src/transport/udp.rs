use std::borrow::Cow;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use crate::util::{IO_TIMEOUT, active_scan_io_deadline};

use super::abort::ABORT_POLL_INTERVAL;
use super::address::resolve_addresses_with_timeout_and_abort;
use super::route_experiment::{relay_udp_direct_with_route_experiment, route_identity};
use super::socks5::relay_udp_via_socks5_matching;
use super::tcp::candidate_address_groups;
use super::types::{TargetAddress, TransportConfig, TransportError, UdpRelayResult};

#[path = "udp/quic_version_matrix.rs"]
pub(super) mod quic_version_matrix;

pub fn relay_udp_payload_observed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    payload: &[u8],
) -> Result<UdpRelayResult, TransportError> {
    relay_udp_payload_matching_observed(targets, port, transport, payload, &|_| true)
}

/// Ignore rejected peer payloads within each exchange's original receive deadline.
pub fn relay_udp_payload_matching_observed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    payload: &[u8],
    accept_response: &dyn Fn(&[u8]) -> bool,
) -> Result<UdpRelayResult, TransportError> {
    relay_udp_with_cancel(targets, port, transport, payload, accept_response, None)
}

/// Run a cancellable UDP probe. Prepare a new context for every socket or association.
/// Plain Direct can send the second datagram in the same context before the receive deadline.
/// The matcher and returned request use the first datagram as the context for both sends.
pub fn relay_quic_probe_observed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    cancel: &AtomicBool,
    prepare: &mut dyn FnMut() -> Option<(Vec<u8>, Vec<u8>)>,
    matcher: &dyn Fn(&[u8], &[u8]) -> bool,
) -> Result<(UdpRelayResult, Vec<u8>), TransportError> {
    let mut probe = UdpProbe {
        cancel: Some(cancel),
        prepare: &mut || {
            prepare().map(|(initial, repeat)| UdpDatagrams { initial: initial.into(), repeat: repeat.into() })
        },
        matcher,
    };
    relay_udp_probe(resolve_udp_candidates(targets, port, probe.cancel), transport, &mut probe)
        .map(|(result, request)| (result, request.into_owned()))
}

pub(super) struct UdpDatagrams<'a> {
    pub(super) initial: Cow<'a, [u8]>,
    repeat: Cow<'a, [u8]>,
}

impl<'a> UdpDatagrams<'a> {
    pub(super) fn fixed(payload: &'a [u8]) -> Self {
        Self { initial: payload.into(), repeat: payload.into() }
    }
}

pub(super) struct UdpProbe<'a> {
    pub(super) cancel: Option<&'a AtomicBool>,
    pub(super) prepare: &'a mut dyn FnMut() -> Option<UdpDatagrams<'a>>,
    pub(super) matcher: &'a dyn Fn(&[u8], &[u8]) -> bool,
}

impl<'a> UdpProbe<'a> {
    pub(super) fn datagrams(&mut self) -> Result<UdpDatagrams<'a>, TransportError> {
        check_udp_attempt(self.cancel)?;
        (self.prepare)().ok_or(TransportError::InvalidProbePayload)
    }
}

pub(super) type ProbeExchange<'a> = (UdpRelayResult, Cow<'a, [u8]>);

fn resolve_udp_candidates<'a>(
    targets: &'a [TargetAddress],
    port: u16,
    cancel: Option<&'a AtomicBool>,
) -> impl Iterator<Item = Result<Vec<SocketAddr>, TransportError>> + 'a {
    candidate_address_groups(targets, port, move |target, port, timeout| {
        let check_abort = || cancellation_reason(cancel);
        resolve_addresses_with_timeout_and_abort(target, port, timeout, Some(&check_abort))
    })
}

fn relay_udp_with_cancel(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    payload: &[u8],
    accept_response: &dyn Fn(&[u8]) -> bool,
    cancel: Option<&AtomicBool>,
) -> Result<UdpRelayResult, TransportError> {
    check_udp_attempt(cancel)?;
    relay_udp_candidates(resolve_udp_candidates(targets, port, cancel), transport, payload, accept_response, cancel)
}

fn relay_udp_candidates(
    groups: impl Iterator<Item = Result<Vec<SocketAddr>, TransportError>>,
    transport: &TransportConfig,
    payload: &[u8],
    accept_response: &dyn Fn(&[u8]) -> bool,
    cancel: Option<&AtomicBool>,
) -> Result<UdpRelayResult, TransportError> {
    let mut probe = UdpProbe {
        cancel,
        prepare: &mut || Some(UdpDatagrams::fixed(payload)),
        matcher: &|_, response| accept_response(response),
    };
    relay_udp_probe(groups, transport, &mut probe).map(|(result, _)| result)
}

fn relay_udp_probe<'a>(
    groups: impl Iterator<Item = Result<Vec<SocketAddr>, TransportError>>,
    transport: &TransportConfig,
    probe: &mut UdpProbe<'a>,
) -> Result<ProbeExchange<'a>, TransportError> {
    let cancel = probe.cancel;
    check_udp_attempt(cancel)?;
    let mut last_error = TransportError::NoSocketAddrs;
    let mut attempted = false;
    for group in groups {
        check_udp_attempt(cancel)?;
        let destinations = match group {
            Ok(destinations) => destinations,
            Err(error) => {
                if !attempted {
                    last_error = error;
                }
                continue;
            }
        };
        attempted = true;
        if let TransportConfig::Direct { route_experiment: Some(config) } = transport {
            let identity = route_identity(&destinations);
            match relay_udp_direct_with_route_experiment(&destinations, config, &identity, probe) {
                Ok(result) => return Ok(result),
                Err(TransportError::InvalidProbePayload) => return Err(TransportError::InvalidProbePayload),
                Err(error) => {
                    check_udp_attempt(cancel)?;
                    remember_udp_failure(&mut last_error, error);
                    continue;
                }
            }
        }
        for destination in destinations {
            let packets = probe.datagrams()?;
            let accept_response = |response: &[u8]| (probe.matcher)(&packets.initial, response);
            let result = match transport {
                TransportConfig::Direct { .. } => relay_udp_direct_exchange(
                    destination,
                    &packets.initial,
                    Some(&packets.repeat),
                    &accept_response,
                    cancel,
                ),
                TransportConfig::Socks5 { host, port, credentials } => relay_udp_via_socks5_matching(
                    host,
                    *port,
                    destination,
                    &packets.initial,
                    credentials.as_ref(),
                    &accept_response,
                    cancel,
                ),
            };
            match result {
                Ok((bytes, local_addr)) => {
                    let reply = UdpRelayResult {
                        payload: bytes,
                        connected_addr: Some(destination),
                        local_addr: Some(local_addr),
                        route_report: None,
                    };
                    return Ok((reply, packets.initial));
                }
                Err(error) => remember_udp_failure(&mut last_error, error),
            }
        }
    }
    check_udp_attempt(cancel)?;
    Err(last_error)
}

pub fn relay_udp_direct(server: SocketAddr, payload: &[u8]) -> Result<(Vec<u8>, SocketAddr), TransportError> {
    relay_udp_direct_exchange(server, payload, None, &|_| true, None)
}

fn relay_udp_direct_exchange(
    server: SocketAddr,
    payload: &[u8],
    retry: Option<&[u8]>,
    accept_response: &dyn Fn(&[u8]) -> bool,
    cancel: Option<&AtomicBool>,
) -> Result<(Vec<u8>, SocketAddr), TransportError> {
    let started = Instant::now();
    let deadline = udp_exchange_deadline(cancel)?;
    let bind_addr: SocketAddr =
        if server.is_ipv4() { (Ipv4Addr::UNSPECIFIED, 0).into() } else { (std::net::Ipv6Addr::UNSPECIFIED, 0).into() };
    let socket = super::protect::protected_udp_bind(bind_addr, server)?;
    socket.connect(server)?;
    let retry_at = if retry.is_some() { started + deadline.saturating_duration_since(started) / 2 } else { deadline };
    send_udp_before(&socket, payload, deadline, cancel)?;
    let mut saw_unmatched = false;
    let first = recv_udp_matching(&socket, retry_at, accept_response, cancel, &mut saw_unmatched);
    let result = match (first, retry) {
        (Err(TransportError::UdpRecvTimeout), Some(retry)) => send_udp_before(&socket, retry, deadline, cancel)
            .map_err(as_udp_receive_error)
            .and_then(|()| recv_udp_matching(&socket, deadline, accept_response, cancel, &mut saw_unmatched)),
        (other, _) => other,
    };
    let bytes = matched_udp_result(result, saw_unmatched)?;
    udp_remaining(deadline, cancel)?;
    Ok((bytes, socket.local_addr()?))
}

pub(super) fn cancellation_reason(cancel: Option<&AtomicBool>) -> Option<&'static str> {
    // Acquire observes the flag published by the scan owner.
    cancel.is_some_and(|flag| flag.load(Ordering::Acquire)).then_some("cancelled")
}

pub(super) fn check_udp_attempt(cancel: Option<&AtomicBool>) -> Result<(), TransportError> {
    if let Some(reason) = cancellation_reason(cancel) {
        return Err(TransportError::ProbeAborted(reason));
    }
    if active_scan_io_deadline().is_some_and(|end| Instant::now() >= end) {
        return Err(TransportError::ScanDeadlineExceeded);
    }
    Ok(())
}

pub(super) fn udp_exchange_deadline(cancel: Option<&AtomicBool>) -> Result<Instant, TransportError> {
    check_udp_attempt(cancel)?;
    let limit = Instant::now() + IO_TIMEOUT;
    Ok(active_scan_io_deadline().map_or(limit, |scan| scan.min(limit)))
}

pub(super) fn udp_remaining(deadline: Instant, cancel: Option<&AtomicBool>) -> Result<Duration, TransportError> {
    check_udp_attempt(cancel)?;
    deadline.checked_duration_since(Instant::now()).filter(|wait| !wait.is_zero()).ok_or(TransportError::UdpRecvTimeout)
}

pub(super) fn send_udp_before(
    socket: &UdpSocket,
    payload: &[u8],
    deadline: Instant,
    cancel: Option<&AtomicBool>,
) -> Result<(), TransportError> {
    let remaining = || {
        udp_remaining(deadline, cancel).map_err(|error| match error {
            TransportError::UdpRecvTimeout => {
                TransportError::Io(io::Error::new(io::ErrorKind::TimedOut, "UDP send deadline exceeded"))
            }
            other => other,
        })
    };
    socket.set_write_timeout(Some(poll_wait(remaining()?, cancel.is_some())))?;
    remaining()?;
    socket.send(payload)?;
    Ok(())
}

pub(super) fn as_udp_receive_error(error: TransportError) -> TransportError {
    match error {
        TransportError::Io(error) => TransportError::udp_recv_error(error),
        other => other,
    }
}

pub(super) fn remember_udp_failure(previous: &mut TransportError, failure: TransportError) {
    fn evidence(error: &TransportError) -> u8 {
        match error {
            TransportError::UdpRecvUnmatched => 3,
            TransportError::UdpRecvTimeout | TransportError::UdpRecvWouldBlock => 2,
            TransportError::UdpRecvFailed { .. } => 1,
            _ => 0,
        }
    }
    if evidence(&failure) >= evidence(previous) {
        *previous = failure;
    }
}

pub(super) fn poll_wait(remaining: Duration, poll: bool) -> Duration {
    let wait = if poll { remaining.min(ABORT_POLL_INTERVAL) } else { remaining };
    wait.max(Duration::from_millis(1))
}

pub(super) fn matched_udp_result<T>(
    result: Result<T, TransportError>,
    saw_unmatched: bool,
) -> Result<T, TransportError> {
    match result {
        Err(TransportError::UdpRecvTimeout | TransportError::UdpRecvWouldBlock) if saw_unmatched => {
            Err(TransportError::UdpRecvUnmatched)
        }
        result => result,
    }
}

pub(super) fn recv_udp_matching(
    socket: &UdpSocket,
    deadline: Instant,
    accept_response: &dyn Fn(&[u8]) -> bool,
    cancel: Option<&AtomicBool>,
    saw_unmatched: &mut bool,
) -> Result<Vec<u8>, TransportError> {
    // A valid UDP datagram can exceed the path MTU after IP reassembly.
    let mut bytes = [0; u16::MAX as usize];
    loop {
        let remaining = udp_remaining(deadline, cancel)?;
        socket.set_read_timeout(Some(poll_wait(remaining, cancel.is_some())))?;
        match socket.recv(&mut bytes) {
            Ok(size) => {
                udp_remaining(deadline, cancel)?;
                if accept_response(&bytes[..size]) {
                    return Ok(bytes[..size].to_vec());
                }
                *saw_unmatched = true;
            }
            Err(error)
                if matches!(
                    error.kind(),
                    io::ErrorKind::Interrupted | io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
                ) => {}
            Err(error) => return Err(TransportError::udp_recv_error(error)),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::{IpAddr, Ipv4Addr, TcpListener, UdpSocket};
    use std::thread;
    use std::time::{Duration, Instant};

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

    use super::{candidate_address_groups, relay_udp_candidates, relay_udp_direct, relay_udp_payload_observed};
    use crate::transport::{Socks5Credentials, TargetAddress, TransportConfig};
    use std::cell::Cell;

    fn assert_udp_ignores_unrelated_sender(route_experiment: bool) {
        let server = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let address = server.local_addr().unwrap();
        let worker = thread::spawn(move || {
            let mut buf = [0; 16];
            let (_, peer) = server.recv_from(&mut buf).unwrap();
            let other = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
            other.send_to(b"unrelated", peer).unwrap();
            thread::sleep(Duration::from_millis(30));
            server.send_to(b"valid", peer).unwrap();
        });
        let route_experiment = route_experiment.then_some(crate::transport::RouteExperimentConfig {
            stable_flow_attempts: 1,
            diversity_buckets: 1,
            diversity_on_failure_only: false,
            session_seed: 44,
        });
        let result = relay_udp_payload_observed(
            &[TargetAddress::Ip(address.ip())],
            address.port(),
            &TransportConfig::Direct { route_experiment },
            b"probe",
        )
        .unwrap();
        worker.join().unwrap();
        assert_eq!(result.payload, b"valid");
    }

    #[test]
    fn direct_udp_ignores_unrelated_sender() {
        assert_udp_ignores_unrelated_sender(false);
    }

    #[test]
    fn route_udp_ignores_unrelated_sender() {
        assert_udp_ignores_unrelated_sender(true);
    }

    #[test]
    fn direct_udp_rejects_io_after_scan_deadline() {
        let server = "127.0.0.1:9".parse().expect("socket address");
        let result = with_scan_io_deadline(Some(Instant::now() - Duration::from_millis(1)), || {
            relay_udp_direct(server, b"probe")
        });

        assert_eq!(result.unwrap_err().to_string(), "scan_deadline_exceeded");
    }

    fn socks_udp_fixture(
        wrong_source: bool,
        fallback: bool,
    ) -> Result<crate::transport::UdpRelayResult, crate::transport::TransportError> {
        let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind UDP relay");
        let relay_addr = relay.local_addr().expect("UDP relay address");
        let control = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind SOCKS control");
        let control_addr = control.local_addr().expect("SOCKS control address");
        let server = thread::spawn(move || {
            for attempt in 0..if fallback { 2 } else { 1 } {
                let (mut stream, _) = control.accept().expect("accept SOCKS control");
                let mut greeting = [0u8; 3];
                stream.read_exact(&mut greeting).expect("read greeting");
                assert_eq!(greeting, [0x05, 0x01, 0x02]);
                stream.write_all(&[0x05, 0x02]).expect("select USERPASS");
                let mut auth = [0u8; 14];
                stream.read_exact(&mut auth).expect("read credentials");
                assert_eq!(&auth, b"\x01\x07attempt\x04test");
                stream.write_all(&[0x01, 0x00]).expect("accept credentials");
                let mut associate = [0u8; 10];
                stream.read_exact(&mut associate).expect("read UDP ASSOCIATE");
                assert_eq!(associate[1], 0x80);
                stream.write_all(&[5, 7, 0, 1, 0, 0, 0, 0, 0, 0]).expect("reject private association");
                let mut closed = [0];
                assert_eq!(stream.read(&mut closed).expect("close rejected control"), 0);
                let (mut stream, _) = control.accept().expect("accept standard fallback");
                stream.read_exact(&mut greeting).expect("read fallback greeting");
                assert_eq!(greeting, [5, 1, 2]);
                stream.write_all(&[5, 2]).expect("select fallback auth");
                stream.read_exact(&mut auth).expect("read fallback credentials");
                assert_eq!(&auth, b"\x01\x07attempt\x04test");
                stream.write_all(&[1, 0]).expect("accept fallback auth");
                stream.read_exact(&mut associate).expect("read standard association");
                assert_eq!(associate, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
                let port = relay_addr.port().to_be_bytes();
                stream
                    .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, port[0], port[1]])
                    .expect("write UDP relay address");
                let mut frame = [0u8; 256];
                let (size, peer) = relay.recv_from(&mut frame).expect("receive UDP frame");
                if wrong_source || (fallback && attempt == 0) {
                    frame[4..8].copy_from_slice(&[8, 8, 8, 8]);
                }
                relay.send_to(&frame[..size], peer).expect("echo UDP frame");
                if !(wrong_source || fallback && attempt == 0) {
                    stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
                    assert_eq!(stream.read(&mut closed).expect("close completed control"), 0);
                }
            }
        });

        let credentials = Socks5Credentials::new("attempt", "test").expect("valid credentials");
        let targets =
            [TargetAddress::Host("fallback.invalid".into()), TargetAddress::Ip(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)))];
        let calls = Cell::new(0);
        let groups = candidate_address_groups(&targets, 443, |_, _, timeout| {
            calls.set(calls.get() + 1);
            if fallback {
                Ok(vec!["2.2.2.2:443".parse().unwrap()])
            } else {
                thread::sleep(timeout + Duration::from_millis(1));
                Err(crate::transport::DnsResolveError::Timeout)
            }
        });
        let result = with_scan_io_deadline(Some(Instant::now() + Duration::from_secs(1)), || {
            relay_udp_candidates(
                groups,
                &TransportConfig::Socks5 {
                    host: Ipv4Addr::LOCALHOST.to_string(),
                    port: control_addr.port(),
                    credentials: Some(credentials),
                },
                b"quic probe",
                &|_| true,
                None,
            )
        });
        server.join().expect("SOCKS fixture thread");
        if !wrong_source {
            assert_eq!(calls.get(), usize::from(fallback));
        }
        result
    }

    #[test]
    fn authenticated_socks_transport_relays_udp_payload() {
        assert_eq!(socks_udp_fixture(false, false).unwrap().payload, b"quic probe");
    }

    #[test]
    fn socks_udp_resolves_fallback_after_failed_pinned_peer() {
        let result = socks_udp_fixture(false, true).unwrap();
        assert_eq!(result.payload, b"quic probe");
        assert_eq!(result.connected_addr, Some("2.2.2.2:443".parse().unwrap()));
    }

    #[test]
    fn socks_udp_rejects_unrelated_encapsulated_source() {
        assert!(socks_udp_fixture(true, false).is_err());
    }
    #[test]
    fn pinned_udp_succeeds_without_stalled_fallback_resolution() {
        for experiment in [
            None,
            Some(crate::transport::RouteExperimentConfig {
                stable_flow_attempts: 1,
                diversity_buckets: 1,
                diversity_on_failure_only: false,
                session_seed: 44,
            }),
        ] {
            let server = UdpSocket::bind("127.0.0.1:0").unwrap();
            server.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let address = server.local_addr().unwrap();
            let worker = thread::spawn(move || {
                let mut buf = [0; 16];
                if let Ok((size, peer)) = server.recv_from(&mut buf) {
                    server.send_to(&buf[..size], peer).unwrap();
                }
            });
            let targets = [TargetAddress::Host("fallback.invalid".into()), TargetAddress::Ip(address.ip())];
            let calls = Cell::new(0);
            let result = with_scan_io_deadline(Some(Instant::now() + Duration::from_secs(1)), || {
                let groups = candidate_address_groups(&targets, address.port(), |_, _, timeout| {
                    calls.set(calls.get() + 1);
                    thread::sleep(timeout + Duration::from_millis(1));
                    Err(crate::transport::DnsResolveError::Timeout)
                });
                relay_udp_candidates(
                    groups,
                    &TransportConfig::Direct { route_experiment: experiment },
                    b"probe",
                    &|_| true,
                    None,
                )
            });
            worker.join().unwrap();
            assert_eq!(result.unwrap().payload, b"probe");
            assert_eq!(calls.get(), 0);
        }
    }

    #[test]
    fn udp_resolves_fallback_after_failed_pinned_peer() {
        for experiment in [
            None,
            Some(crate::transport::RouteExperimentConfig {
                stable_flow_attempts: 1,
                diversity_buckets: 1,
                diversity_on_failure_only: false,
                session_seed: 44,
            }),
        ] {
            let server = UdpSocket::bind("127.0.0.1:0").unwrap();
            server.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
            let address = server.local_addr().unwrap();
            let worker = thread::spawn(move || {
                let mut buf = [0; 16];
                if let Ok((size, peer)) = server.recv_from(&mut buf) {
                    server.send_to(&buf[..size], peer).unwrap();
                }
            });
            let targets =
                [TargetAddress::Ip("127.0.0.2".parse().unwrap()), TargetAddress::Host("fallback.invalid".into())];
            let calls = Cell::new(0);
            let groups = candidate_address_groups(&targets, address.port(), |_, _, _| {
                calls.set(calls.get() + 1);
                Ok(vec![address])
            });
            let result = relay_udp_candidates(
                groups,
                &TransportConfig::Direct { route_experiment: experiment },
                b"probe",
                &|_| true,
                None,
            );
            worker.join().unwrap();
            assert_eq!(result.unwrap().payload, b"probe");
            assert_eq!(calls.get(), 1);
        }
    }
    #[test]
    fn failed_fallback_dns_preserves_udp_failure() {
        let server = UdpSocket::bind("127.0.0.1:0").unwrap();
        let port = server.local_addr().unwrap().port();
        let targets = [TargetAddress::Ip("127.0.0.2".parse().unwrap()), TargetAddress::Host("invalid\0host".into())];
        let error =
            relay_udp_payload_observed(&targets, port, &TransportConfig::Direct { route_experiment: None }, b"probe")
                .unwrap_err();
        assert!(
            matches!(
                error,
                crate::transport::TransportError::UdpRecvFailed { .. }
                    | crate::transport::TransportError::UdpRecvTimeout
                    | crate::transport::TransportError::UdpRecvWouldBlock
            ),
            "{error:?}"
        );
    }
}

#[cfg(test)]
#[path = "udp/correlation_tests.rs"]
mod correlation_tests;
