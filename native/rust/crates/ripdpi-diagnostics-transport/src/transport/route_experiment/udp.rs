use std::net::{SocketAddr, UdpSocket};

use socket2::{Protocol, SockAddr, Socket, Type};

use crate::util::{IO_TIMEOUT, bounded_scan_io_timeout, stable_probe_hash};

use super::RouteAttemptTracker;
use super::common::{route_bind_addr, route_bucket_port, socket_domain_for};
use crate::transport::types::{RouteExperimentConfig, UdpRelayResult};

pub(crate) fn relay_udp_direct_with_route_experiment(
    destinations: &[SocketAddr],
    payload: &[u8],
    config: &RouteExperimentConfig,
    route_identity: &str,
) -> Result<UdpRelayResult, String> {
    let mut tracker = RouteAttemptTracker::new(config, "no_socket_addrs");

    for attempt_index in 0..tracker.stable_attempts() {
        match relay_udp_bucket(destinations, payload, config, route_identity, 0) {
            Ok((bytes, destination, local_addr)) => {
                return Ok(UdpRelayResult {
                    payload: bytes,
                    connected_addr: Some(destination),
                    local_addr: Some(local_addr),
                    route_report: Some(tracker.stable_success(attempt_index, 0)),
                });
            }
            Err(err) => tracker.stable_failure(attempt_index, err),
        }
    }

    if config.diversity_on_failure_only {
        for bucket in 1..config.diversity_buckets.max(1) {
            match relay_udp_bucket(destinations, payload, config, route_identity, bucket) {
                Ok((bytes, destination, local_addr)) => {
                    return Ok(UdpRelayResult {
                        payload: bytes,
                        connected_addr: Some(destination),
                        local_addr: Some(local_addr),
                        route_report: Some(tracker.diversity_success(bucket)),
                    });
                }
                Err(err) => tracker.diversity_failure(bucket, err),
            }
        }
    }

    Err(tracker.failure_summary())
}

fn relay_udp_bucket(
    destinations: &[SocketAddr],
    payload: &[u8],
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
) -> Result<(Vec<u8>, SocketAddr, SocketAddr), String> {
    let mut last_error = None;
    for destination in destinations.iter().copied() {
        bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
        match relay_udp_direct_with_bucket(destination, payload, config, route_identity, bucket) {
            Ok(result) => return Ok(result),
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_socket_addrs".to_string()))
}

fn relay_udp_direct_with_bucket(
    server: SocketAddr,
    payload: &[u8],
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
) -> Result<(Vec<u8>, SocketAddr, SocketAddr), String> {
    let domain = socket_domain_for(server);
    let kind_seed = stable_probe_hash(config.session_seed, route_identity);
    let port = route_bucket_port(kind_seed, bucket);
    let bind_addr = route_bind_addr(server, port);
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP)).map_err(|err| err.to_string())?;
    crate::transport::protect::protect_for_target(&socket, server).map_err(|err| err.to_string())?;
    let _ = socket.set_reuse_address(true);
    socket.bind(&SockAddr::from(bind_addr)).map_err(|err| err.to_string())?;
    let udp: UdpSocket = socket.into();
    let write_timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
    udp.set_write_timeout(Some(write_timeout)).map_err(|err| err.to_string())?;
    udp.connect(server).map_err(|err| err.to_string())?;
    udp.send(payload).map_err(|err| err.to_string())?;
    let read_timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
    udp.set_read_timeout(Some(read_timeout)).map_err(|err| err.to_string())?;
    let mut buf = [0u8; 2048];
    let size = udp.recv(&mut buf).map_err(|err| err.to_string())?;
    let local_addr = udp.local_addr().map_err(|err| err.to_string())?;
    Ok((buf[..size].to_vec(), server, local_addr))
}

#[cfg(test)]
mod tests {
    use std::net::UdpSocket;
    use std::time::{Duration, Instant};

    use crate::transport::types::RouteExperimentConfig;
    use crate::util::{stable_probe_hash, with_scan_io_deadline};

    use super::super::common::route_bucket_port;
    use super::relay_udp_bucket;

    #[test]
    fn route_attempt_does_not_try_next_destination_after_deadline() {
        let silent = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addresses = [silent.local_addr().unwrap(); 2];
        let session_seed = (0..256)
            .find(|seed| {
                let port = route_bucket_port(stable_probe_hash(*seed, "deadline-test"), 0);
                UdpSocket::bind(("0.0.0.0", port)).is_ok()
            })
            .expect("available route bucket port");
        let config = RouteExperimentConfig {
            stable_flow_attempts: 1,
            diversity_buckets: 1,
            diversity_on_failure_only: false,
            session_seed,
        };
        let result = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(40)), || {
            relay_udp_bucket(&addresses, b"ping", &config, "deadline-test", 0)
        });
        assert_eq!(result.unwrap_err(), "scan_deadline_exceeded");
    }
}
