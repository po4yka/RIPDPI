use std::net::{SocketAddr, TcpStream};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::Duration;

use super::route_experiment::{connect_addresses_with_route_experiment, route_identity};
use super::socks5::connect_via_socks5_observed;
use super::types::{
    RouteExperimentConfig, TargetAddress, TransportConfig, TransportConnectError, TransportConnectResult,
    TransportError, TransportFailureStage,
};
use crate::util::{CONNECT_TIMEOUT, active_scan_io_deadline, bounded_scan_io_timeout, with_scan_io_deadline};

const HAPPY_EYEBALLS_DELAY: Duration = Duration::from_millis(250);

pub fn connect_transport_observed(
    targets: &[TargetAddress],
    port: u16,
    transport: &TransportConfig,
) -> Result<TransportConnectResult, TransportConnectError> {
    match transport {
        TransportConfig::Direct { route_experiment } => {
            connect_direct_observed(targets, port, route_experiment.as_ref())
        }
        TransportConfig::Socks5 { host, port: proxy_port, credentials } => {
            connect_via_socks5_observed(targets, port, host, *proxy_port, credentials.as_ref())
        }
    }
}

pub fn connect_direct(target: &TargetAddress, port: u16) -> Result<TcpStream, TransportError> {
    connect_direct_observed(std::slice::from_ref(target), port, None)
        .map(|result| result.stream)
        .map_err(|err| TransportError::ConnectFailed { stage: err.stage, message: err.message })
}

pub(super) fn connect_direct_observed(
    targets: &[TargetAddress],
    port: u16,
    route_experiment: Option<&RouteExperimentConfig>,
) -> Result<TransportConnectResult, TransportConnectError> {
    connect_direct_candidates(
        candidate_address_groups(targets, port, super::resolve_addresses_with_timeout),
        route_experiment,
    )
}

fn connect_direct_candidates(
    groups: impl Iterator<Item = Result<Vec<SocketAddr>, TransportError>>,
    route_experiment: Option<&RouteExperimentConfig>,
) -> Result<TransportConnectResult, TransportConnectError> {
    let mut last_error = TransportConnectError::new(TransportFailureStage::DnsResolution, "no_socket_addrs");
    let mut attempted = false;
    for group in groups {
        let addresses = match group {
            Ok(addresses) => addresses,
            Err(error) => {
                if !attempted || matches!(error, TransportError::ScanDeadlineExceeded) {
                    last_error = TransportConnectError::new(TransportFailureStage::DnsResolution, error.to_string());
                }
                continue;
            }
        };
        attempted = true;
        let result = if let Some(config) = route_experiment {
            let identity = route_identity(&addresses);
            connect_addresses_with_route_experiment(&addresses, config, &identity).map(
                |((stream, connected_addr, local_addr), route_report)| TransportConnectResult {
                    stream,
                    connected_addr: Some(connected_addr),
                    local_addr: Some(local_addr),
                    route_report: Some(route_report),
                },
            )
        } else {
            connect_addresses_with_race(&addresses)
                .map(|(stream, connected_addr)| {
                    let local_addr = stream.local_addr().ok();
                    TransportConnectResult {
                        stream,
                        connected_addr: Some(connected_addr),
                        local_addr,
                        route_report: None,
                    }
                })
                .map_err(|error| error.to_string())
        };
        match result {
            Ok(result) => return Ok(result),
            Err(error) => last_error = TransportConnectError::new(TransportFailureStage::TcpConnect, error),
        }
    }
    Err(last_error)
}

pub(super) fn candidate_address_groups<'a>(
    targets: &'a [TargetAddress],
    port: u16,
    mut resolve: impl FnMut(&TargetAddress, u16, Duration) -> Result<Vec<SocketAddr>, super::DnsResolveError> + 'a,
) -> impl Iterator<Item = Result<Vec<SocketAddr>, TransportError>> + 'a {
    // Keep literal peers together for Happy Eyeballs, before any fallback DNS work.
    let pinned = targets
        .iter()
        .filter_map(|target| match target {
            TargetAddress::Ip(ip) => Some(SocketAddr::new(*ip, port)),
            TargetAddress::Host(_) => None,
        })
        .collect::<Vec<_>>();
    let literal_group = std::iter::once_with(move || {
        bounded_scan_io_timeout(CONNECT_TIMEOUT).map(|_| pinned).map_err(|_| TransportError::ScanDeadlineExceeded)
    });
    let names = targets.iter().filter(|target| matches!(target, TargetAddress::Host(_))).map(move |target| {
        let timeout = bounded_scan_io_timeout(CONNECT_TIMEOUT).map_err(|_| TransportError::ScanDeadlineExceeded)?;
        resolve(target, port, timeout).map_err(|error| {
            if bounded_scan_io_timeout(CONNECT_TIMEOUT).is_err() {
                TransportError::ScanDeadlineExceeded
            } else {
                TransportError::from(error)
            }
        })
    });
    let mut seen = Vec::new();
    literal_group.chain(names).filter_map(move |group| match group {
        Ok(addresses) => {
            let fresh = addresses
                .into_iter()
                .filter(|address| {
                    if seen.contains(address) {
                        false
                    } else {
                        seen.push(*address);
                        true
                    }
                })
                .collect::<Vec<_>>();
            (!fresh.is_empty()).then_some(Ok(fresh))
        }
        Err(error) => Some(Err(error)),
    })
}

fn connect_addresses_with_race(addresses: &[SocketAddr]) -> Result<(TcpStream, SocketAddr), TransportError> {
    let initial_batch = addresses.iter().take(2).copied().collect::<Vec<_>>();
    let initial_batch_len = initial_batch.len();
    let mut last_error = None;
    if !initial_batch.is_empty() {
        let raced = race_initial_addresses(
            initial_batch,
            Arc::new(move |address| {
                let timeout =
                    bounded_scan_io_timeout(CONNECT_TIMEOUT).map_err(|_| TransportError::ScanDeadlineExceeded)?;
                Ok(super::protect::protected_tcp_connect(address, timeout)?)
            }),
        );
        if let Ok((stream, address)) = raced {
            return Ok((stream, address));
        }
        last_error = raced.err();
    }
    for address in addresses.iter().skip(initial_batch_len).copied() {
        let timeout = bounded_scan_io_timeout(CONNECT_TIMEOUT).map_err(|_| TransportError::ScanDeadlineExceeded)?;
        match super::protect::protected_tcp_connect(address, timeout) {
            Ok(stream) => return Ok((stream, address)),
            Err(err) => last_error = Some(TransportError::Io(err)),
        }
    }
    Err(last_error.unwrap_or(TransportError::NoAddresses))
}

fn race_initial_addresses<T>(
    addresses: Vec<SocketAddr>,
    connect: Arc<dyn Fn(SocketAddr) -> Result<T, TransportError> + Send + Sync + 'static>,
) -> Result<(T, SocketAddr), TransportError>
where
    T: Send + 'static,
{
    let scan_deadline = active_scan_io_deadline();
    let attempt_count = addresses.len();
    let (result_tx, result_rx) = mpsc::channel();
    for (index, address) in addresses.into_iter().enumerate() {
        let connect = Arc::clone(&connect);
        let result_tx = result_tx.clone();
        thread::Builder::new()
            .name(format!("ripdpi-connect-race-{index}"))
            .spawn(move || {
                if index > 0 {
                    thread::sleep(HAPPY_EYEBALLS_DELAY);
                }
                let result = with_scan_io_deadline(scan_deadline, || connect(address));
                let _ = result_tx.send((address, result));
            })
            .map_err(TransportError::ConnectRaceSpawnFailed)?;
    }
    drop(result_tx);

    let mut last_error = None;
    for _ in 0..attempt_count {
        match result_rx.recv() {
            Ok((address, Ok(stream))) => return Ok((stream, address)),
            Ok((_, Err(error))) => last_error = Some(error),
            Err(_) => return Err(TransportError::ConnectRacePanicked),
        }
    }
    Err(last_error.unwrap_or(TransportError::NoAddresses))
}

pub fn wait_for_listener(addr: SocketAddr) -> Result<(), TransportError> {
    for _ in 0..40 {
        if super::protect::protected_tcp_connect(addr, Duration::from_millis(50)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(25));
    }
    Err(TransportError::ListenerNotReady(addr))
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

    use super::*;

    #[test]
    fn address_race_returns_fast_second_success_before_slow_first_attempt() {
        let first = "192.0.2.1:443".parse().expect("first address");
        let second = "192.0.2.2:443".parse().expect("second address");
        let started = Instant::now();

        let (winner, address) = race_initial_addresses(
            vec![first, second],
            Arc::new(move |address| {
                if address == first {
                    thread::sleep(Duration::from_secs(1));
                    Err(TransportError::Io(std::io::Error::other("slow failure")))
                } else {
                    Ok("fast success")
                }
            }),
        )
        .expect("second attempt should win");

        assert_eq!(winner, "fast success");
        assert_eq!(address, second);
        assert!(started.elapsed() < Duration::from_millis(600));
    }

    #[test]
    fn direct_tcp_rejects_connect_after_scan_deadline() {
        let loopback = "127.0.0.1:9".parse().expect("loopback socket address");
        let result = with_scan_io_deadline(Some(Instant::now() - Duration::from_millis(1)), || {
            connect_addresses_with_race(&[loopback])
        });

        assert!(matches!(result, Err(error) if error.to_string() == "scan_deadline_exceeded"));
    }

    #[test]
    fn empty_direct_target_list_reports_dns_resolution_stage() {
        let error = connect_transport_observed(&[], 443, &TransportConfig::Direct { route_experiment: None })
            .expect_err("empty target list must fail");

        assert_eq!(error.stage, TransportFailureStage::DnsResolution);
        assert_eq!(error.message, "no_socket_addrs");
    }
}

#[cfg(test)]
mod candidate_resolution_regression {
    use super::*;

    #[test]
    fn failed_name_does_not_discard_valid_pinned_candidate() {
        let valid = TargetAddress::Ip("127.0.0.1".parse().unwrap());
        let invalid = TargetAddress::Host("invalid\0host".to_string());
        for candidates in [[valid.clone(), invalid.clone()], [invalid.clone(), valid]] {
            assert_eq!(
                candidate_address_groups(&candidates, 443, super::super::resolve_addresses_with_timeout)
                    .next()
                    .unwrap()
                    .unwrap(),
                ["127.0.0.1:443".parse::<SocketAddr>().unwrap()]
            );
        }
        assert!(
            candidate_address_groups(&[invalid], 443, super::super::resolve_addresses_with_timeout)
                .next()
                .unwrap()
                .is_err()
        );
    }
}

#[cfg(test)]
mod pinned_first_regression {
    use super::*;
    use std::cell::Cell;
    use std::net::TcpListener;
    use std::time::Instant;

    #[test]
    fn pinned_tcp_succeeds_without_stalled_fallback_resolution() {
        for experiment in [
            None,
            Some(RouteExperimentConfig {
                stable_flow_attempts: 1,
                diversity_buckets: 1,
                diversity_on_failure_only: false,
                session_seed: 44,
            }),
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let targets = [TargetAddress::Host("fallback.invalid".into()), TargetAddress::Ip(address.ip())];
            let calls = Cell::new(0);
            let result = with_scan_io_deadline(Some(Instant::now() + Duration::from_secs(1)), || {
                let groups = candidate_address_groups(&targets, address.port(), |target, port, timeout| match target {
                    TargetAddress::Ip(ip) => Ok(vec![SocketAddr::new(*ip, port)]),
                    TargetAddress::Host(_) => {
                        calls.set(calls.get() + 1);
                        thread::sleep(timeout + Duration::from_millis(1));
                        Err(super::super::DnsResolveError::Timeout)
                    }
                });
                connect_direct_candidates(groups, experiment.as_ref())
            });
            assert!(result.is_ok(), "{result:?}");
            assert_eq!(calls.get(), 0);
        }
    }
    #[test]
    fn tcp_resolves_fallback_after_failed_pinned_peer() {
        for experiment in [
            None,
            Some(RouteExperimentConfig {
                stable_flow_attempts: 1,
                diversity_buckets: 1,
                diversity_on_failure_only: false,
                session_seed: 44,
            }),
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let targets =
                [TargetAddress::Ip("127.0.0.2".parse().unwrap()), TargetAddress::Host("fallback.invalid".into())];
            let calls = Cell::new(0);
            let groups = candidate_address_groups(&targets, address.port(), |_, _, timeout| {
                assert!(timeout <= CONNECT_TIMEOUT);
                calls.set(calls.get() + 1);
                Ok(vec![address])
            });
            let result = connect_direct_candidates(groups, experiment.as_ref()).unwrap();
            assert_eq!(result.connected_addr, Some(address));
            assert_eq!(calls.get(), 1);
        }
    }

    #[test]
    fn hostname_only_candidates_connect_and_duplicate_peers_are_skipped() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let targets = [TargetAddress::Host("fallback.invalid".into())];
        let groups = candidate_address_groups(&targets, address.port(), |_, _, _| Ok(vec![address, address]));
        assert_eq!(connect_direct_candidates(groups, None).unwrap().connected_addr, Some(address));
        let targets = [
            TargetAddress::Ip(address.ip()),
            TargetAddress::Ip(address.ip()),
            TargetAddress::Host("fallback.invalid".into()),
        ];
        let groups = candidate_address_groups(&targets, address.port(), |_, _, _| Ok(vec![address]));
        assert_eq!(groups.collect::<Result<Vec<_>, _>>().unwrap(), vec![vec![address]]);
    }

    #[test]
    fn expired_deadline_prevents_fallback_resolution() {
        let targets = [TargetAddress::Host("fallback.invalid".into())];
        let result = with_scan_io_deadline(Some(Instant::now() - Duration::from_millis(1)), || {
            let groups = candidate_address_groups(&targets, 443, |_, _, _| panic!("expired scan must not resolve"));
            connect_direct_candidates(groups, None)
        });
        assert_eq!(result.unwrap_err().message, "scan_deadline_exceeded");
    }

    #[test]
    fn fallback_dns_exhaustion_overrides_earlier_connect_failure() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let targets = [TargetAddress::Ip("127.0.0.2".parse().unwrap()), TargetAddress::Host("fallback.invalid".into())];
        let result = with_scan_io_deadline(Some(Instant::now() + Duration::from_millis(100)), || {
            let groups = candidate_address_groups(&targets, port, |_, _, timeout| {
                thread::sleep(timeout + Duration::from_millis(1));
                Err(super::super::DnsResolveError::Timeout)
            });
            connect_direct_candidates(groups, None)
        });
        assert_eq!(result.unwrap_err().message, "scan_deadline_exceeded");
    }
    #[test]
    fn failed_fallback_dns_preserves_tcp_failure_stage() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let targets = [TargetAddress::Ip("127.0.0.2".parse().unwrap()), TargetAddress::Host("invalid\0host".into())];
        let error = connect_direct_observed(&targets, port, None).unwrap_err();
        assert_eq!(error.stage, TransportFailureStage::TcpConnect);
    }
}
