use std::net::{Ipv4Addr, SocketAddr};

use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};

use super::route_experiment::{relay_udp_direct_with_route_experiment, route_identity};
use super::socks5::relay_udp_via_socks5;
use super::tcp::candidate_address_groups;
use super::types::{TargetAddress, TransportConfig, TransportError, UdpRelayResult};

pub fn relay_udp_payload_observed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    payload: &[u8],
) -> Result<UdpRelayResult, TransportError> {
    relay_udp_candidates(
        candidate_address_groups(targets, port, super::resolve_addresses_with_timeout),
        transport,
        payload,
    )
}

fn relay_udp_candidates(
    groups: impl Iterator<Item = Result<Vec<SocketAddr>, TransportError>>,
    transport: &TransportConfig,
    payload: &[u8],
) -> Result<UdpRelayResult, TransportError> {
    let mut last_error = TransportError::NoSocketAddrs;
    let mut attempted = false;
    for group in groups {
        let destinations = match group {
            Ok(destinations) => destinations,
            Err(error) => {
                if !attempted || matches!(error, TransportError::ScanDeadlineExceeded) {
                    last_error = error;
                }
                continue;
            }
        };
        attempted = true;
        if let TransportConfig::Direct { route_experiment: Some(config) } = transport {
            let identity = route_identity(&destinations);
            match relay_udp_direct_with_route_experiment(&destinations, payload, config, &identity) {
                Ok(result) => return Ok(result),
                Err(error) => {
                    last_error = TransportError::RouteExperiment(error);
                    continue;
                }
            }
        }
        for destination in destinations {
            let result = match transport {
                TransportConfig::Direct { .. } => relay_udp_direct(destination, payload),
                TransportConfig::Socks5 { host, port, credentials } => {
                    relay_udp_via_socks5(host, *port, destination, payload, credentials.as_ref())
                }
            };
            match result {
                Ok((bytes, local_addr)) => {
                    return Ok(UdpRelayResult {
                        payload: bytes,
                        connected_addr: Some(destination),
                        local_addr: Some(local_addr),
                        route_report: None,
                    });
                }
                Err(error) => last_error = error,
            }
        }
    }
    Err(last_error)
}

pub fn relay_udp_direct(server: SocketAddr, payload: &[u8]) -> Result<(Vec<u8>, SocketAddr), TransportError> {
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(|_| TransportError::ScanDeadlineExceeded)?;
    let bind_addr: SocketAddr =
        if server.is_ipv4() { (Ipv4Addr::UNSPECIFIED, 0).into() } else { (std::net::Ipv6Addr::UNSPECIFIED, 0).into() };
    let socket = super::protect::protected_udp_bind(bind_addr, server)?;
    socket.set_read_timeout(Some(timeout))?;
    socket.set_write_timeout(Some(timeout))?;
    socket.connect(server)?;
    socket.send(payload)?;
    let mut buf = [0u8; 2048];
    let size = socket.recv(&mut buf).map_err(TransportError::udp_recv_error)?;
    let local_addr = socket.local_addr()?;
    Ok((buf[..size].to_vec(), local_addr))
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
                relay_udp_candidates(groups, &TransportConfig::Direct { route_experiment: experiment }, b"probe")
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
            let result =
                relay_udp_candidates(groups, &TransportConfig::Direct { route_experiment: experiment }, b"probe");
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
