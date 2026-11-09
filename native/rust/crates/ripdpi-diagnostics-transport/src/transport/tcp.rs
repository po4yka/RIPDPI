use std::net::{SocketAddr, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::{Duration, Instant};

use super::route_experiment::{connect_addresses_with_route_experiment, route_identity};
use super::socks5::connect_via_socks5_observed;
use super::types::{
    RouteExperimentConfig, TargetAddress, TransportConfig, TransportConnectError, TransportConnectResult,
    TransportError, TransportFailureStage,
};
use crate::util::{CONNECT_TIMEOUT, active_scan_io_deadline, bounded_scan_io_timeout, with_scan_io_deadline};

const HAPPY_EYEBALLS_DELAY: Duration = Duration::from_millis(250);
/// Bounds the threads one race uses; a failed attempt frees a slot for the next address.
const MAX_IN_FLIGHT_ATTEMPTS: usize = 4;

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

/// Connect exactly one numeric peer through the protected direct path.
/// Preserves the OS error kind for diagnostics and never performs DNS or family fallback.
pub fn connect_direct_address(address: SocketAddr) -> std::io::Result<TcpStream> {
    let timeout = bounded_scan_io_timeout(CONNECT_TIMEOUT)
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::TimedOut, "scan deadline exceeded"))?;
    super::protect::protected_tcp_connect(address, timeout)
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

/// Orders addresses by alternating families (RFC 8305 section 4, First Address Family Count 1).
/// The family of the first address leads; order inside each family is kept.
pub(super) fn interleave_address_families(addresses: &[SocketAddr]) -> Vec<SocketAddr> {
    let Some(first) = addresses.first() else {
        return Vec::new();
    };
    let (mut lead, mut other): (Vec<SocketAddr>, Vec<SocketAddr>) =
        addresses.iter().copied().partition(|address| address.is_ipv4() == first.is_ipv4());
    let mut result = Vec::with_capacity(addresses.len());
    lead.reverse();
    other.reverse();
    while !lead.is_empty() || !other.is_empty() {
        result.extend(lead.pop());
        result.extend(other.pop());
    }
    result
}

fn connect_addresses_with_race(addresses: &[SocketAddr]) -> Result<(TcpStream, SocketAddr), TransportError> {
    race_addresses(
        interleave_address_families(addresses),
        Arc::new(move |address, stop| {
            let timeout = bounded_scan_io_timeout(CONNECT_TIMEOUT).map_err(|_| TransportError::ScanDeadlineExceeded)?;
            Ok(super::protect::protected_tcp_connect_until(address, timeout, stop)?)
        }),
    )
}

/// One connect attempt; the flag is set once the race has a result.
type RaceConnect<T> = dyn Fn(SocketAddr, &AtomicBool) -> Result<T, TransportError> + Send + Sync + 'static;

/// Sets the race's `decided` flag when dropped, so every exit of the race (also a failed spawn)
/// stops the attempts that already started.
struct DecideOnDrop(Arc<AtomicBool>);

impl Drop for DecideOnDrop {
    fn drop(&mut self) {
        self.0.store(true, Ordering::Release);
    }
}

/// Sends a `ConnectRacePanicked` failure for its attempt when dropped before a result was sent
/// (a panicking attempt), so the caller gets exactly one message per started attempt.
struct AttemptReport<T> {
    tx: mpsc::Sender<(SocketAddr, Result<T, TransportError>)>,
    address: SocketAddr,
    sent: bool,
}

impl<T> Drop for AttemptReport<T> {
    fn drop(&mut self) {
        if !self.sent {
            let _ = self.tx.send((self.address, Err(TransportError::ConnectRacePanicked)));
        }
    }
}

/// Races `addresses` in order (RFC 8305 section 5): attempt `i` starts `HAPPY_EYEBALLS_DELAY` after
/// attempt `i - 1` started, or at once when the previous attempt failed. Started attempts keep
/// running; the first success wins. At most `MAX_IN_FLIGHT_ATTEMPTS` attempts run at the same time.
///
/// # Cancel safety: NOT cancel-safe: blocking call; dropping the race flags `decided` so started attempts stop.
fn race_addresses<T>(
    addresses: Vec<SocketAddr>,
    connect: Arc<RaceConnect<T>>,
) -> Result<(T, SocketAddr), TransportError>
where
    T: Send + 'static,
{
    let scan_deadline = active_scan_io_deadline();
    let (result_tx, result_rx) = mpsc::channel();
    // Set when the caller returns, so a pending attempt does not complete a connection that nobody
    // reads (RFC 8305 section 5). `connect` receives it to stop a started attempt.
    let decided = Arc::new(AtomicBool::new(false));
    let _decide_on_exit = DecideOnDrop(Arc::clone(&decided));
    let mut pending = addresses.into_iter().enumerate();
    let mut next = pending.next();
    let mut in_flight = 0usize;
    let mut next_start = Instant::now();
    let mut last_error = None;
    loop {
        if scan_deadline.is_some_and(|deadline| Instant::now() >= deadline) {
            return Err(TransportError::ScanDeadlineExceeded);
        }
        if in_flight < MAX_IN_FLIGHT_ATTEMPTS
            && Instant::now() >= next_start
            && let Some((index, address)) = next.take()
        {
            let connect = Arc::clone(&connect);
            let result_tx = result_tx.clone();
            let decided = Arc::clone(&decided);
            thread::Builder::new()
                .name(format!("ripdpi-connect-race-{index}"))
                .spawn(move || {
                    let mut report = AttemptReport { tx: result_tx, address, sent: false };
                    let outcome = with_scan_io_deadline(scan_deadline, || connect(address, &decided));
                    report.sent = true;
                    let _ = report.tx.send((address, outcome));
                })
                .map_err(TransportError::ConnectRaceSpawnFailed)?;
            in_flight += 1;
            next = pending.next();
            next_start = Instant::now() + HAPPY_EYEBALLS_DELAY;
            continue;
        }
        if in_flight == 0 && next.is_none() {
            return Err(last_error.unwrap_or(TransportError::NoAddresses));
        }
        // Wake for the next start only when one is allowed; otherwise wait for a result.
        let wake = (next.is_some() && in_flight < MAX_IN_FLIGHT_ATTEMPTS).then_some(next_start);
        let wait_until = match (wake, scan_deadline) {
            (Some(wake), Some(deadline)) => Some(wake.min(deadline)),
            (wake, deadline) => wake.or(deadline),
        };
        let received = match wait_until {
            Some(until) => result_rx.recv_timeout(until.saturating_duration_since(Instant::now())),
            None => result_rx.recv().map_err(|_| mpsc::RecvTimeoutError::Disconnected),
        };
        match received {
            Ok((address, Ok(value))) => return Ok((value, address)),
            Ok((_, Err(error))) => {
                in_flight -= 1;
                last_error = Some(error);
                // A failed attempt starts the next one at once.
                next_start = Instant::now();
            }
            Err(mpsc::RecvTimeoutError::Timeout) => {
                if scan_deadline.is_some_and(|deadline| Instant::now() >= deadline) {
                    return Err(TransportError::ScanDeadlineExceeded);
                }
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => return Err(TransportError::ConnectRacePanicked),
        }
    }
}

pub fn wait_for_listener(addr: SocketAddr) -> Result<(), TransportError> {
    for _ in 0..40 {
        let timeout =
            bounded_scan_io_timeout(Duration::from_millis(50)).map_err(|_| TransportError::ScanDeadlineExceeded)?;
        if super::protect::protected_tcp_connect(addr, timeout).is_ok() {
            return Ok(());
        }
        let delay =
            bounded_scan_io_timeout(Duration::from_millis(25)).map_err(|_| TransportError::ScanDeadlineExceeded)?;
        thread::sleep(delay);
    }
    bounded_scan_io_timeout(Duration::from_millis(1)).map_err(|_| TransportError::ScanDeadlineExceeded)?;
    Err(TransportError::ListenerNotReady(addr))
}

#[cfg(test)]
mod tests {
    use ripdpi_diagnostics_contracts::util::with_scan_io_deadline;

    use super::*;

    #[test]
    fn address_race_returns_fast_second_success_before_slow_first_attempt() {
        let first = "192.0.2.1:443".parse().expect("first address");
        let second = "192.0.2.2:443".parse().expect("second address");
        let started = Instant::now();

        let (winner, address) = race_addresses(
            vec![first, second],
            Arc::new(move |address, _| {
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
    fn delayed_attempt_is_skipped_after_the_first_address_wins() {
        let first = "192.0.2.1:443".parse().expect("first address");
        let second = "192.0.2.2:443".parse().expect("second address");
        let attempted = Arc::new(std::sync::Mutex::new(Vec::new()));

        let (_, winner) = race_addresses(vec![first, second], {
            let attempted = Arc::clone(&attempted);
            Arc::new(move |address, _| {
                attempted.lock().unwrap().push(address);
                Ok(())
            })
        })
        .expect("first attempt wins");
        thread::sleep(HAPPY_EYEBALLS_DELAY * 2);

        assert_eq!(winner, first);
        assert_eq!(*attempted.lock().unwrap(), vec![first]);
    }

    #[test]
    fn race_decision_is_set_when_the_race_returns_early() {
        let decided = Arc::new(AtomicBool::new(false));
        let spawn_result: Result<(), TransportError> = Err(TransportError::NoAddresses);
        let race = || -> Result<(), TransportError> {
            let _decide_on_exit = DecideOnDrop(Arc::clone(&decided));
            spawn_result?;
            Ok(())
        };

        assert!(race().is_err());
        assert!(decided.load(Ordering::Acquire), "a started attempt would run on");
    }

    #[test]
    fn started_delayed_attempt_stops_once_the_first_address_wins() {
        let first = "192.0.2.1:443".parse().expect("first address");
        let second = "192.0.2.2:443".parse().expect("second address");
        let (observed_tx, observed_rx) = mpsc::channel();

        let (_, winner) = race_addresses(vec![first, second], {
            let observed_tx = std::sync::Mutex::new(observed_tx);
            Arc::new(move |address, stop: &AtomicBool| {
                if address == first {
                    // Win after the delayed attempt has started.
                    thread::sleep(HAPPY_EYEBALLS_DELAY + Duration::from_millis(150));
                    return Ok(());
                }
                let started = Instant::now();
                while !stop.load(Ordering::Acquire) && started.elapsed() < Duration::from_secs(5) {
                    thread::sleep(Duration::from_millis(5));
                }
                let _ = observed_tx.lock().unwrap().send(Instant::now());
                Err(TransportError::NoAddresses)
            })
        })
        .expect("first attempt wins");
        let returned = Instant::now();

        let stopped = observed_rx.recv_timeout(Duration::from_secs(6)).expect("second attempt ends");
        assert_eq!(winner, first);
        assert!(stopped.saturating_duration_since(returned) < Duration::from_secs(1), "second attempt ran on");
    }

    #[test]
    fn interleaving_alternates_families_and_keeps_the_first_family_first() {
        let sa = |text: &str| -> SocketAddr { text.parse().expect("socket address") };
        let (v6a, v6b) = (sa("[2001:db8::1]:443"), sa("[2001:db8::2]:443"));
        let (v4a, v4b) = (sa("192.0.2.1:443"), sa("192.0.2.2:443"));

        assert_eq!(interleave_address_families(&[v6a, v6b, v4a, v4b]), vec![v6a, v4a, v6b, v4b]);
        assert_eq!(interleave_address_families(&[v4a, v4b, v6a, v6b]), vec![v4a, v6a, v4b, v6b]);
        assert_eq!(interleave_address_families(&[v4a, v4b]), vec![v4a, v4b]);
        assert!(interleave_address_families(&[]).is_empty());
    }

    #[test]
    fn blackholed_leading_addresses_do_not_delay_a_later_success() {
        let blackholes: [SocketAddr; 3] =
            ["192.0.2.1:443", "192.0.2.2:443", "192.0.2.3:443"].map(|a| a.parse().unwrap());
        let good: SocketAddr = "192.0.2.4:443".parse().unwrap();
        let mut addresses = blackholes.to_vec();
        addresses.push(good);
        let started = Instant::now();

        let (_, winner) = race_addresses(
            addresses,
            Arc::new(move |address, stop: &AtomicBool| {
                if address == good {
                    return Ok(());
                }
                while !stop.load(Ordering::Acquire) {
                    thread::sleep(Duration::from_millis(5));
                }
                Err(TransportError::NoAddresses)
            }),
        )
        .expect("the reachable address wins");

        // Attempts start at 0, 250, 500 and 750 ms; sequential tails would take 3 x 2.5 s.
        assert_eq!(winner, good);
        assert!(started.elapsed() < Duration::from_millis(1500), "tail was tried sequentially");
    }

    #[test]
    fn failed_attempt_starts_the_next_address_at_once() {
        let first: SocketAddr = "192.0.2.1:443".parse().unwrap();
        let second: SocketAddr = "192.0.2.2:443".parse().unwrap();
        let starts = Arc::new(std::sync::Mutex::new(Vec::new()));
        let origin = Instant::now();

        let (_, winner) = race_addresses(vec![first, second], {
            let starts = Arc::clone(&starts);
            Arc::new(move |address, _| {
                starts.lock().unwrap().push((address, origin.elapsed()));
                if address == first { Err(TransportError::NoAddresses) } else { Ok(()) }
            })
        })
        .expect("second attempt wins");

        assert_eq!(winner, second);
        let starts = starts.lock().unwrap();
        let second_start = starts.iter().find(|(address, _)| *address == second).expect("second started").1;
        assert!(second_start < Duration::from_millis(100), "second started after {second_start:?}");
    }

    #[test]
    fn freed_slots_let_later_addresses_run_past_the_in_flight_cap() {
        let addresses: Vec<SocketAddr> = (1..=6).map(|n| format!("192.0.2.{n}:443").parse().unwrap()).collect();
        let good = addresses[5];
        let started = Instant::now();

        let (_, winner) = race_addresses(
            addresses,
            Arc::new(move |address, _| {
                if address == good {
                    Ok(())
                } else {
                    // Fail quickly so each failure frees a slot for the next address.
                    thread::sleep(Duration::from_millis(20));
                    Err(TransportError::NoAddresses)
                }
            }),
        )
        .expect("the sixth address wins");

        assert_eq!(winner, good);
        assert!(started.elapsed() < Duration::from_millis(500), "slots were not reused");
    }

    #[test]
    fn blocked_attempts_at_the_cap_hold_back_later_addresses_until_one_fails() {
        let addresses: Vec<SocketAddr> = (1..=6).map(|n| format!("192.0.2.{n}:443").parse().unwrap()).collect();
        let good = addresses[5];
        let started_at = Arc::new(std::sync::Mutex::new(None));

        let (_, winner) = race_addresses(addresses.clone(), {
            let started_at = Arc::clone(&started_at);
            let origin = Instant::now();
            Arc::new(move |address, stop: &AtomicBool| {
                if address == good {
                    *started_at.lock().unwrap() = Some(origin.elapsed());
                    return Ok(());
                }
                if address == addresses[0] {
                    thread::sleep(Duration::from_millis(1200));
                    return Err(TransportError::NoAddresses);
                }
                if address == addresses[4] {
                    return Err(TransportError::NoAddresses);
                }
                while !stop.load(Ordering::Acquire) {
                    thread::sleep(Duration::from_millis(5));
                }
                Err(TransportError::NoAddresses)
            })
        })
        .expect("the sixth address wins once the first frees a slot");

        // Four attempts run at 0, 250, 500 and 750 ms; the fifth waits for the failure at 1200 ms, fails at once, and frees the slot for the sixth.
        assert_eq!(winner, good);
        let started = started_at.lock().unwrap().expect("sixth started");
        assert!(started >= Duration::from_millis(1200), "cap exceeded: sixth started at {started:?}");
    }

    #[test]
    fn all_failures_report_the_last_error() {
        let addresses: Vec<SocketAddr> = vec!["192.0.2.1:443".parse().unwrap(), "192.0.2.2:443".parse().unwrap()];
        let result = race_addresses(
            addresses,
            Arc::new(|address, _| -> Result<(), TransportError> {
                Err(TransportError::Io(std::io::Error::other(format!("failed {address}"))))
            }),
        );
        let Err(TransportError::Io(error)) = result else { panic!("expected the last attempt's error") };
        assert_eq!(error.to_string(), "failed 192.0.2.2:443");
    }

    #[test]
    fn panicking_attempt_reports_race_panic_only_when_all_attempts_fail() {
        let first: SocketAddr = "192.0.2.1:443".parse().unwrap();
        let second: SocketAddr = "192.0.2.2:443".parse().unwrap();

        let all_fail =
            race_addresses(vec![first], Arc::new(|_, _| -> Result<(), TransportError> { panic!("attempt panic") }));
        assert!(matches!(all_fail, Err(TransportError::ConnectRacePanicked)));

        let (_, winner) = race_addresses(
            vec![first, second],
            Arc::new(move |address, _| -> Result<(), TransportError> {
                if address == first { panic!("attempt panic") } else { Ok(()) }
            }),
        )
        .expect("a panic in one attempt does not stop the race");
        assert_eq!(winner, second);
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
    fn address_race_stops_waiting_at_scan_deadline() {
        let addresses = vec!["192.0.2.1:443".parse().unwrap(), "192.0.2.2:443".parse().unwrap()];
        let started = Instant::now();
        let result = with_scan_io_deadline(Some(started + Duration::from_millis(30)), || {
            race_addresses(
                addresses,
                Arc::new(|_, _| {
                    thread::sleep(Duration::from_millis(300));
                    Err::<(), _>(TransportError::NoAddresses)
                }),
            )
        });
        assert!(matches!(result, Err(TransportError::ScanDeadlineExceeded)));
    }

    #[test]
    fn listener_wait_stops_at_scan_deadline() {
        let address = "127.0.0.1:0".parse().unwrap();
        let started = Instant::now();
        let result = with_scan_io_deadline(Some(started + Duration::from_millis(30)), || wait_for_listener(address));
        assert!(matches!(result, Err(TransportError::ScanDeadlineExceeded)));
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
