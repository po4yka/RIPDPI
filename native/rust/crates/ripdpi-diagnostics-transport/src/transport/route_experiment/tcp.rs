use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use socket2::{Protocol, SockAddr, Socket, Type};

use crate::util::{CONNECT_TIMEOUT, bounded_scan_io_timeout, stable_probe_hash};

use super::RouteAttemptTracker;
use super::common::{route_bind_addr, route_bucket_port, socket_domain_for};
use crate::transport::types::{RouteExperimentConfig, RouteExperimentReport};

pub(crate) fn connect_addresses_with_route_experiment(
    addresses: &[SocketAddr],
    config: &RouteExperimentConfig,
    route_identity: &str,
) -> Result<((TcpStream, SocketAddr, SocketAddr), RouteExperimentReport), String> {
    let mut tracker = RouteAttemptTracker::new(config, "no_addresses");

    for attempt_index in 0..tracker.stable_attempts() {
        match connect_addresses_with_bucket(addresses, config, route_identity, 0) {
            Ok(result) => return Ok((result, tracker.stable_success(attempt_index, 0))),
            Err(err) => tracker.stable_failure(attempt_index, err),
        }
    }

    if config.diversity_on_failure_only {
        for bucket in 1..config.diversity_buckets.max(1) {
            match connect_addresses_with_bucket(addresses, config, route_identity, bucket) {
                Ok(result) => return Ok((result, tracker.diversity_success(bucket))),
                Err(err) => tracker.diversity_failure(bucket, err),
            }
        }
    }

    Err(tracker.failure_summary())
}

fn connect_addresses_with_bucket(
    addresses: &[SocketAddr],
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
) -> Result<(TcpStream, SocketAddr, SocketAddr), String> {
    let timeout = bounded_scan_io_timeout(CONNECT_TIMEOUT).map_err(str::to_string)?;
    let mut last_error = None;
    for address in addresses.iter().copied() {
        match connect_bound_tcp(address, config, route_identity, bucket, timeout) {
            Ok(result) => return Ok(result),
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| "no_addresses".to_string()))
}

fn connect_bound_tcp(
    address: SocketAddr,
    config: &RouteExperimentConfig,
    route_identity: &str,
    bucket: usize,
    timeout: Duration,
) -> Result<(TcpStream, SocketAddr, SocketAddr), String> {
    let domain = socket_domain_for(address);
    let seed = stable_probe_hash(config.session_seed, route_identity);
    let port = route_bucket_port(seed, bucket);
    let remote = SockAddr::from(address);
    let bind_addr = route_bind_addr(address, port);
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP)).map_err(|err| err.to_string())?;
    crate::transport::protect::protect_for_target(&socket, address).map_err(|err| err.to_string())?;
    let _ = socket.set_reuse_address(true);
    socket.bind(&SockAddr::from(bind_addr)).map_err(|err| err.to_string())?;
    socket.connect_timeout(&remote, timeout).map_err(|err| err.to_string())?;
    let stream: TcpStream = socket.into();
    let local_addr = stream.local_addr().map_err(|err| err.to_string())?;
    Ok((stream, address, local_addr))
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, Shutdown, TcpListener};
    use std::thread;

    use crate::transport::{TargetAddress, TransportConfig, connect_transport_observed};

    use super::super::common::{route_bind_addr, route_bucket_port, route_identity};
    use super::*;

    #[test]
    fn direct_route_experiment_binds_deterministic_stable_bucket_port() {
        for session_seed in 7..256 {
            let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tcp listener");
            let server_addr = listener.local_addr().expect("listener addr");
            let config = RouteExperimentConfig {
                stable_flow_attempts: 1,
                diversity_buckets: 1,
                diversity_on_failure_only: true,
                session_seed,
            };
            let identity = route_identity(&[server_addr]);
            let expected_port = route_bucket_port(crate::util::stable_probe_hash(config.session_seed, &identity), 0);

            let result = connect_transport_observed(
                &[TargetAddress::Ip(server_addr.ip())],
                server_addr.port(),
                &TransportConfig::Direct { route_experiment: Some(config.clone()) },
            );

            match result {
                Ok(result) => {
                    assert_eq!(result.local_addr.expect("local addr").port(), expected_port);
                    let route_report = result.route_report.expect("route report");
                    assert_eq!(route_report.selected_bucket, 0);
                    assert_eq!(route_report.selected_bucket_kind, "stable");
                    let (stream, _) = listener.accept().expect("accept");
                    let _ = stream.shutdown(Shutdown::Both);
                    return;
                }
                Err(err) if err.contains("Address already in use") || err.contains("os error 48") => {
                    continue;
                }
                Err(err) => panic!("route-stable connect: {err}"),
            }
        }

        panic!("route-stable connect could not find an available deterministic source port");
    }

    #[test]
    #[ignore = "local route-bucket socket timing is flaky under full-workspace test load"]
    fn direct_route_experiment_uses_diversity_bucket_when_stable_port_is_busy() {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind tcp listener");
        let server_addr = listener.local_addr().expect("listener addr");
        let accept_handle = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept");
            let _ = stream.shutdown(Shutdown::Both);
        });
        let config = RouteExperimentConfig {
            stable_flow_attempts: 1,
            diversity_buckets: 2,
            diversity_on_failure_only: true,
            session_seed: 9,
        };
        let identity = route_identity(&[server_addr]);
        let stable_port = route_bucket_port(crate::util::stable_probe_hash(config.session_seed, &identity), 0);
        let occupied =
            TcpListener::bind(route_bind_addr(server_addr, stable_port)).expect("occupy stable route bucket");

        let result = connect_transport_observed(
            &[TargetAddress::Ip(server_addr.ip())],
            server_addr.port(),
            &TransportConfig::Direct { route_experiment: Some(config) },
        )
        .expect("route-diversity connect");

        let route_report = result.route_report.expect("route report");
        assert_eq!(route_report.selected_bucket, 1);
        assert_eq!(route_report.selected_bucket_kind, "diversity");
        assert!(route_report.summary.contains("stable#0"));
        assert!(route_report.summary.contains("bucket#1:ok"));
        assert_ne!(result.local_addr.expect("local addr").port(), stable_port);

        drop(occupied);
        accept_handle.join().expect("join accept");
    }
}
