use std::net::{SocketAddr, UdpSocket};

use socket2::{Protocol, SockAddr, Socket, Type};

use crate::util::stable_probe_hash;

use super::common::{route_bind_addr, route_bucket_port, socket_domain_for};
use super::{MAX_ROUTE_EXPERIMENT_ATTEMPTS, RouteAttemptTracker};
use crate::transport::types::{RouteExperimentConfig, TransportError, UdpRelayResult};
use crate::transport::udp::{
    ProbeExchange, UdpProbe, check_udp_attempt, matched_udp_result, recv_udp_matching, remember_udp_failure,
    send_udp_before, udp_exchange_deadline, udp_remaining,
};

pub(crate) fn relay_udp_direct_with_route_experiment<'a>(
    destinations: &[SocketAddr],
    config: &RouteExperimentConfig,
    route_identity: &str,
    probe: &mut UdpProbe<'a>,
) -> Result<ProbeExchange<'a>, TransportError> {
    let mut tracker = RouteAttemptTracker::new(config, "no_socket_addrs");
    let mut last_error = TransportError::NoSocketAddrs;
    let cancel = probe.cancel;

    for attempt_index in 0..tracker.stable_attempts() {
        check_udp_attempt(cancel)?;
        match relay_udp_bucket(destinations, config, route_identity, 0, probe) {
            Ok((mut reply, request)) => {
                reply.route_report = Some(tracker.stable_success(attempt_index, 0));
                return Ok((reply, request));
            }
            Err(TransportError::InvalidProbePayload) => return Err(TransportError::InvalidProbePayload),
            Err(error) => {
                check_udp_attempt(cancel)?;
                tracker.stable_failure(attempt_index, error.to_string());
                remember_udp_failure(&mut last_error, error);
            }
        }
    }

    if config.diversity_on_failure_only {
        for bucket in 1..config.diversity_buckets.min(MAX_ROUTE_EXPERIMENT_ATTEMPTS) {
            check_udp_attempt(cancel)?;
            match relay_udp_bucket(destinations, config, route_identity, bucket, probe) {
                Ok((mut reply, request)) => {
                    reply.route_report = Some(tracker.diversity_success(bucket));
                    return Ok((reply, request));
                }
                Err(TransportError::InvalidProbePayload) => return Err(TransportError::InvalidProbePayload),
                Err(error) => {
                    check_udp_attempt(cancel)?;
                    tracker.diversity_failure(bucket, error.to_string());
                    remember_udp_failure(&mut last_error, error);
                }
            }
        }
    }

    Err(if cancel.is_some() { last_error } else { TransportError::RouteExperiment(tracker.failure_summary()) })
}

fn relay_udp_bucket<'a>(
    destinations: &[SocketAddr],
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
    probe: &mut UdpProbe<'a>,
) -> Result<ProbeExchange<'a>, TransportError> {
    let mut last_error = TransportError::NoSocketAddrs;
    for destination in destinations.iter().copied() {
        check_udp_attempt(probe.cancel)?;
        match relay_udp_direct_with_bucket(destination, config, route_identity, bucket, probe) {
            Ok(result) => return Ok(result),
            Err(TransportError::InvalidProbePayload) => return Err(TransportError::InvalidProbePayload),
            Err(error) => remember_udp_failure(&mut last_error, error),
        }
    }
    Err(last_error)
}

fn relay_udp_direct_with_bucket<'a>(
    server: SocketAddr,
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
    probe: &mut UdpProbe<'a>,
) -> Result<ProbeExchange<'a>, TransportError> {
    let cancel = probe.cancel;
    let deadline = udp_exchange_deadline(cancel)?;
    let packets = probe.datagrams()?;
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
    send_udp_before(&udp, &packets.initial, deadline, cancel)?;
    let mut saw_unmatched = false;
    let accept_response = |response: &[u8]| (probe.matcher)(&packets.initial, response);
    let result = recv_udp_matching(&udp, deadline, &accept_response, cancel, &mut saw_unmatched);
    let payload = matched_udp_result(result, saw_unmatched)?;
    udp_remaining(deadline, cancel)?;
    let reply = UdpRelayResult {
        payload,
        connected_addr: Some(server),
        local_addr: Some(udp.local_addr()?),
        route_report: None,
    };
    Ok((reply, packets.initial))
}

#[cfg(test)]
mod tests {
    use std::net::UdpSocket;
    use std::time::{Duration, Instant};

    use crate::transport::types::RouteExperimentConfig;
    use crate::transport::udp::{UdpDatagrams, UdpProbe};
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
            let mut probe =
                UdpProbe { cancel: None, prepare: &mut || Some(UdpDatagrams::fixed(b"ping")), matcher: &|_, _| true };
            relay_udp_bucket(&addresses, &config, "deadline-test", 0, &mut probe).map(|(reply, _)| reply)
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
            let mut probe =
                UdpProbe { cancel: None, prepare: &mut || Some(UdpDatagrams::fixed(b"ping")), matcher: &|_, _| true };
            relay_udp_direct_with_route_experiment(&[address], &config, "deadline-test", &mut probe)
                .map(|(reply, _)| reply)
        });
        assert!(matches!(result, Err(super::TransportError::ScanDeadlineExceeded)));
    }
}
