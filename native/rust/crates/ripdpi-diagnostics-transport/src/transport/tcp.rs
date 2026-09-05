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
    let addresses = resolve_candidate_addresses(targets, port)
        .map_err(|err| TransportConnectError::new(TransportFailureStage::DnsResolution, err.to_string()))?;
    if let Some(config) = route_experiment {
        let route_identity = route_identity(&addresses);
        let ((stream, connected_addr, local_addr), route_report) =
            connect_addresses_with_route_experiment(&addresses, config, &route_identity)
                .map_err(|err| TransportConnectError::new(TransportFailureStage::TcpConnect, err))?;
        return Ok(TransportConnectResult {
            stream,
            connected_addr: Some(connected_addr),
            local_addr: Some(local_addr),
            route_report: Some(route_report),
        });
    }
    let (stream, connected_addr) = connect_addresses_with_race(&addresses)
        .map_err(|err| TransportConnectError::new(TransportFailureStage::TcpConnect, err.to_string()))?;
    let local_addr = stream.local_addr().ok();
    Ok(TransportConnectResult { stream, connected_addr: Some(connected_addr), local_addr, route_report: None })
}

pub(super) fn resolve_candidate_addresses(
    targets: &[TargetAddress],
    port: u16,
) -> Result<Vec<SocketAddr>, TransportError> {
    let mut resolved = Vec::new();
    let mut last_error = None;
    for target in targets {
        let timeout = bounded_scan_io_timeout(CONNECT_TIMEOUT).map_err(|_| TransportError::ScanDeadlineExceeded)?;
        match super::resolve_addresses_with_timeout(target, port, timeout) {
            Ok(addresses) => {
                for address in addresses {
                    if !resolved.contains(&address) {
                        resolved.push(address);
                    }
                }
            }
            Err(error) => last_error = Some(TransportError::from(error)),
        }
    }
    if resolved.is_empty() {
        return Err(last_error.unwrap_or(TransportError::NoSocketAddrs));
    }
    Ok(resolved)
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
                resolve_candidate_addresses(&candidates, 443).unwrap(),
                ["127.0.0.1:443".parse::<SocketAddr>().unwrap()]
            );
        }
        assert!(resolve_candidate_addresses(&[invalid], 443).is_err());
    }
}
