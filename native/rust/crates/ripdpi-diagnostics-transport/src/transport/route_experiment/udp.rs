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
    let timeout = bounded_scan_io_timeout(IO_TIMEOUT).map_err(str::to_string)?;
    let mut last_error = None;
    for destination in destinations.iter().copied() {
        match relay_udp_direct_with_bucket(destination, payload, config, route_identity, bucket, timeout) {
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
    timeout: std::time::Duration,
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
    udp.set_read_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    udp.set_write_timeout(Some(timeout)).map_err(|err| err.to_string())?;
    udp.send_to(payload, server).map_err(|err| err.to_string())?;
    let mut buf = [0u8; 2048];
    let (size, _) = udp.recv_from(&mut buf).map_err(|err| err.to_string())?;
    let local_addr = udp.local_addr().map_err(|err| err.to_string())?;
    Ok((buf[..size].to_vec(), server, local_addr))
}
