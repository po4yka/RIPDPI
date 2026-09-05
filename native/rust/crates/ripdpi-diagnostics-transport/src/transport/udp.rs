use std::net::{Ipv4Addr, SocketAddr};

use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout};

use super::route_experiment::{relay_udp_direct_with_route_experiment, route_identity};
use super::socks5::relay_udp_via_socks5;
use super::tcp::resolve_candidate_addresses;
use super::types::{TargetAddress, TransportConfig, TransportError, UdpRelayResult};

pub fn relay_udp_payload_observed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
    payload: &[u8],
) -> Result<UdpRelayResult, TransportError> {
    match transport {
        TransportConfig::Direct { route_experiment } => {
            let destinations = resolve_candidate_addresses(targets, port)?;
            if let Some(config) = route_experiment.as_ref() {
                let route_identity = route_identity(&destinations);
                return relay_udp_direct_with_route_experiment(&destinations, payload, config, &route_identity)
                    .map_err(TransportError::RouteExperiment);
            }
            let mut last_error = None;
            for destination in destinations {
                match relay_udp_direct(destination, payload) {
                    Ok((bytes, local_addr)) => {
                        return Ok(UdpRelayResult {
                            payload: bytes,
                            connected_addr: Some(destination),
                            local_addr: Some(local_addr),
                            route_report: None,
                        });
                    }
                    Err(err) => last_error = Some(err),
                }
            }
            Err(last_error.unwrap_or(TransportError::NoSocketAddrs))
        }
        TransportConfig::Socks5 { host, port: proxy_port, credentials } => {
            let mut last_error = None;
            for destination in resolve_candidate_addresses(targets, port)? {
                match relay_udp_via_socks5(host, *proxy_port, destination, payload, credentials.as_ref()) {
                    Ok((bytes, local_addr)) => {
                        return Ok(UdpRelayResult {
                            payload: bytes,
                            connected_addr: Some(destination),
                            local_addr: Some(local_addr),
                            route_report: None,
                        });
                    }
                    Err(err) => last_error = Some(err),
                }
            }
            Err(last_error.unwrap_or(TransportError::NoTargetCandidates))
        }
    }
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

    use super::{relay_udp_direct, relay_udp_payload_observed};
    use crate::transport::{Socks5Credentials, TargetAddress, TransportConfig};

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
    ) -> Result<crate::transport::UdpRelayResult, crate::transport::TransportError> {
        let relay = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind UDP relay");
        let relay_addr = relay.local_addr().expect("UDP relay address");
        let control = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind SOCKS control");
        let control_addr = control.local_addr().expect("SOCKS control address");
        let server = thread::spawn(move || {
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
            if wrong_source {
                frame[4..8].copy_from_slice(&[8, 8, 8, 8]);
            }
            relay.send_to(&frame[..size], peer).expect("echo UDP frame");
        });

        let credentials = Socks5Credentials::new("attempt", "test").expect("valid credentials");
        let result = relay_udp_payload_observed(
            &[TargetAddress::Ip(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)))],
            443,
            &TransportConfig::Socks5 {
                host: Ipv4Addr::LOCALHOST.to_string(),
                port: control_addr.port(),
                credentials: Some(credentials),
            },
            b"quic probe",
        );
        server.join().expect("SOCKS fixture thread");
        result
    }

    #[test]
    fn authenticated_socks_transport_relays_udp_payload() {
        assert_eq!(socks_udp_fixture(false).unwrap().payload, b"quic probe");
    }

    #[test]
    fn socks_udp_rejects_unrelated_encapsulated_source() {
        assert!(socks_udp_fixture(true).is_err());
    }
}
