use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::AtomicBool;

use socket2::{Protocol, SockAddr, Socket, Type};

use crate::util::stable_probe_hash;

use super::common::{route_bind_addr, route_bucket_port, socket_domain_for};
use super::{MAX_ROUTE_EXPERIMENT_ATTEMPTS, RouteAttemptTracker};
use crate::transport::types::{RouteExperimentConfig, TransportError, UdpRelayResult};
use crate::transport::udp::{
    check_udp_attempt, matched_udp_result, recv_udp_matching, send_udp_before, udp_exchange_deadline,
};

pub(crate) fn relay_udp_direct_with_route_experiment(
    destinations: &[SocketAddr],
    payload: &[u8],
    config: &RouteExperimentConfig,
    route_identity: &str,
    accept_response: &dyn Fn(&[u8]) -> bool,
    cancel: Option<&AtomicBool>,
) -> Result<UdpRelayResult, TransportError> {
    let mut tracker = RouteAttemptTracker::new(config, "no_socket_addrs");
    let mut last_error = TransportError::NoSocketAddrs;

    for attempt_index in 0..tracker.stable_attempts() {
        check_udp_attempt(cancel)?;
        match relay_udp_bucket(destinations, payload, config, route_identity, 0, accept_response, cancel) {
            Ok((bytes, destination, local_addr)) => {
                return Ok(UdpRelayResult {
                    payload: bytes,
                    connected_addr: Some(destination),
                    local_addr: Some(local_addr),
                    route_report: Some(tracker.stable_success(attempt_index, 0)),
                });
            }
            Err(error) => {
                check_udp_attempt(cancel)?;
                tracker.stable_failure(attempt_index, error.to_string());
                last_error = error;
            }
        }
    }

    if config.diversity_on_failure_only {
        for bucket in 1..config.diversity_buckets.min(MAX_ROUTE_EXPERIMENT_ATTEMPTS) {
            check_udp_attempt(cancel)?;
            match relay_udp_bucket(destinations, payload, config, route_identity, bucket, accept_response, cancel) {
                Ok((bytes, destination, local_addr)) => {
                    return Ok(UdpRelayResult {
                        payload: bytes,
                        connected_addr: Some(destination),
                        local_addr: Some(local_addr),
                        route_report: Some(tracker.diversity_success(bucket)),
                    });
                }
                Err(error) => {
                    check_udp_attempt(cancel)?;
                    tracker.diversity_failure(bucket, error.to_string());
                    last_error = error;
                }
            }
        }
    }

    Err(if cancel.is_some() { last_error } else { TransportError::RouteExperiment(tracker.failure_summary()) })
}

fn relay_udp_bucket(
    destinations: &[SocketAddr],
    payload: &[u8],
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
    accept_response: &dyn Fn(&[u8]) -> bool,
    cancel: Option<&AtomicBool>,
) -> Result<(Vec<u8>, SocketAddr, SocketAddr), TransportError> {
    let mut last_error = TransportError::NoSocketAddrs;
    for destination in destinations.iter().copied() {
        check_udp_attempt(cancel)?;
        match relay_udp_direct_with_bucket(
            destination,
            payload,
            config,
            route_identity,
            bucket,
            accept_response,
            cancel,
        ) {
            Ok(result) => return Ok(result),
            Err(error) => last_error = error,
        }
    }
    Err(last_error)
}

fn relay_udp_direct_with_bucket(
    server: SocketAddr,
    payload: &[u8],
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
    accept_response: &dyn Fn(&[u8]) -> bool,
    cancel: Option<&AtomicBool>,
) -> Result<(Vec<u8>, SocketAddr, SocketAddr), TransportError> {
    let deadline = udp_exchange_deadline(cancel)?;
    let domain = socket_domain_for(server);
    let kind_seed = stable_probe_hash(config.session_seed, route_identity);
    let port = route_bucket_port(kind_seed, bucket);
    let bind_addr = route_bind_addr(server, port);
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    crate::transport::protect::protect_for_target(&socket, server)?;
    let _ = socket.set_reuse_address(true);
    socket.bind(&SockAddr::from(bind_addr))?;
    let udp: UdpSocket = socket.into();
    udp.connect(server)?;
    send_udp_before(&udp, payload, deadline, cancel)?;
    let mut saw_unmatched = false;
    let result = recv_udp_matching(&udp, deadline, accept_response, cancel, &mut saw_unmatched);
    let bytes = matched_udp_result(result, saw_unmatched)?;
    let local_addr = udp.local_addr()?;
    Ok((bytes, server, local_addr))
}

#[cfg(test)]
mod tests {
    use std::net::UdpSocket;
    use std::time::{Duration, Instant};

    use crate::transport::types::RouteExperimentConfig;
    use crate::util::{stable_probe_hash, with_scan_io_deadline};

    use super::super::common::route_bucket_port;
    use super::{relay_udp_bucket, relay_udp_direct_with_route_experiment};

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
            relay_udp_bucket(&addresses, b"ping", &config, "deadline-test", 0, &|_| true, None)
        });
        assert_eq!(result.unwrap_err().to_string(), "scan_deadline_exceeded");
    }

    #[test]
    fn route_retries_stop_at_the_scan_deadline() {
        let silent = UdpSocket::bind("127.0.0.1:0").unwrap();
        let address = silent.local_addr().unwrap();
        let config = RouteExperimentConfig {
            stable_flow_attempts: usize::MAX,
            diversity_buckets: usize::MAX,
            diversity_on_failure_only: true,
            session_seed: u64::from(address.port()),
        };
        let result = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(70)), || {
            relay_udp_direct_with_route_experiment(&[address], b"ping", &config, "deadline-test", &|_| true, None)
        });
        assert!(matches!(result, Err(super::TransportError::ScanDeadlineExceeded)));
    }
}
