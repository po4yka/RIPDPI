use std::fmt;
use std::io;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, LazyLock, Mutex, mpsc};
use std::thread;
use std::time::{Duration, Instant};

use crate::types::{DomainTarget, QuicTarget};

use super::types::{TargetAddress, TransportError};

pub fn domain_connect_target(target: &DomainTarget) -> TargetAddress {
    domain_connect_targets(target).into_iter().next().unwrap_or_else(|| TargetAddress::Host(target.host.clone()))
}

pub fn quic_connect_target(target: &QuicTarget) -> TargetAddress {
    quic_connect_targets(target).into_iter().next().unwrap_or_else(|| TargetAddress::Host(target.host.clone()))
}

pub fn domain_connect_targets(target: &DomainTarget) -> Vec<TargetAddress> {
    ordered_connect_targets(Some(target.host.as_str()), target.connect_ip.as_deref(), &target.connect_ips)
}

pub fn quic_connect_targets(target: &QuicTarget) -> Vec<TargetAddress> {
    ordered_connect_targets(Some(target.host.as_str()), target.connect_ip.as_deref(), &target.connect_ips)
}

pub fn throughput_connect_targets(
    host: Option<&str>,
    connect_ip: Option<&str>,
    connect_ips: &[String],
) -> Vec<TargetAddress> {
    ordered_connect_targets(host, connect_ip, connect_ips)
}

fn ordered_connect_targets(host: Option<&str>, connect_ip: Option<&str>, connect_ips: &[String]) -> Vec<TargetAddress> {
    let mut ordered = Vec::new();
    for value in connect_ip.into_iter().chain(connect_ips.iter().map(String::as_str)) {
        let trimmed = value.trim();
        if trimmed.is_empty() {
            continue;
        }
        if let Ok(ip) = trimmed.parse::<IpAddr>() {
            let target = TargetAddress::Ip(ip);
            if !ordered.contains(&target) {
                ordered.push(target);
            }
        }
    }
    if let Some(host) = host.filter(|value| !value.trim().is_empty()) {
        let fallback = TargetAddress::Host(host.to_string());
        if !ordered.contains(&fallback) {
            ordered.push(fallback);
        }
    }
    ordered
}

const DNS_RESOLVE_TIMEOUT: Duration = Duration::from_secs(5);
const DNS_RESOLVER_WORKERS: usize = 4;
const DNS_RESOLVER_QUEUE_CAPACITY: usize = 64;
const DNS_RESOLVER_REPLACEMENT_FACTOR: usize = 2;
const DNS_SUPERVISOR_POLL_INTERVAL: Duration = Duration::from_millis(25);

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DnsResolveError {
    Nxdomain,
    Timeout,
    Busy,
    Unavailable,
    Failure,
}

impl DnsResolveError {
    pub fn code(self) -> &'static str {
        match self {
            Self::Nxdomain => "dns_nxdomain",
            Self::Timeout => "dns_resolve_timeout",
            Self::Busy => "dns_resolver_busy",
            Self::Unavailable => "dns_resolver_unavailable",
            Self::Failure => "dns_resolution_error",
        }
    }
}

impl fmt::Display for DnsResolveError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(self.code())
    }
}

impl std::error::Error for DnsResolveError {}

fn classify_system_resolver_error(error: &io::Error) -> DnsResolveError {
    if error.kind() == io::ErrorKind::NotFound || error.raw_os_error() == Some(libc::EAI_NONAME) {
        DnsResolveError::Nxdomain
    } else {
        DnsResolveError::Failure
    }
}

type ResolveFn = dyn Fn(&str, u16) -> Result<Vec<SocketAddr>, DnsResolveError> + Send + Sync + 'static;

static DNS_RESOLVER: LazyLock<Result<ResolverExecutor, DnsResolveError>> = LazyLock::new(|| {
    ResolverExecutor::new(
        DNS_RESOLVER_WORKERS,
        DNS_RESOLVER_QUEUE_CAPACITY,
        Arc::new(|host, port| {
            (host, port)
                .to_socket_addrs()
                .map(Iterator::collect)
                .map_err(|error| classify_system_resolver_error(&error))
        }),
    )
});

struct ResolveJob {
    host: String,
    port: u16,
    deadline: Instant,
    response: mpsc::Sender<Result<Vec<SocketAddr>, DnsResolveError>>,
}

struct ResolverExecutor {
    jobs: Option<mpsc::SyncSender<ResolveJob>>,
    shutdown: Arc<AtomicBool>,
    workers: Vec<thread::JoinHandle<()>>,
}

struct LookupBudget {
    active: AtomicUsize,
    limit: usize,
}

struct LookupPermit {
    budget: Arc<LookupBudget>,
}

impl LookupBudget {
    fn try_acquire(self: &Arc<Self>) -> Option<LookupPermit> {
        // Ordering: this atomic is only a capacity counter; it does not publish lookup data.
        self.active
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |active| (active < self.limit).then_some(active + 1))
            .ok()
            .map(|_| LookupPermit { budget: Arc::clone(self) })
    }
}

impl Drop for LookupPermit {
    fn drop(&mut self) {
        // Ordering: this atomic is only a capacity counter; the result channel provides synchronization.
        let previous = self.budget.active.fetch_sub(1, Ordering::Relaxed);
        debug_assert!(previous > 0, "DNS lookup permit counter underflow");
    }
}

impl ResolverExecutor {
    fn new(worker_count: usize, queue_capacity: usize, resolver: Arc<ResolveFn>) -> Result<Self, DnsResolveError> {
        let lookup_limit =
            worker_count.checked_mul(DNS_RESOLVER_REPLACEMENT_FACTOR).ok_or(DnsResolveError::Unavailable)?;
        Self::new_with_lookup_limit(worker_count, queue_capacity, lookup_limit, resolver)
    }

    fn new_with_lookup_limit(
        worker_count: usize,
        queue_capacity: usize,
        lookup_limit: usize,
        resolver: Arc<ResolveFn>,
    ) -> Result<Self, DnsResolveError> {
        if worker_count == 0 || queue_capacity == 0 || lookup_limit < worker_count {
            return Err(DnsResolveError::Unavailable);
        }
        let (jobs, receiver) = mpsc::sync_channel::<ResolveJob>(queue_capacity);
        let receiver = Arc::new(Mutex::new(receiver));
        let shutdown = Arc::new(AtomicBool::new(false));
        let lookup_budget = Arc::new(LookupBudget { active: AtomicUsize::new(0), limit: lookup_limit });
        let mut workers: Vec<thread::JoinHandle<()>> = Vec::with_capacity(worker_count);
        for index in 0..worker_count {
            let receiver = Arc::clone(&receiver);
            let resolver = Arc::clone(&resolver);
            let worker_shutdown = Arc::clone(&shutdown);
            let lookup_budget = Arc::clone(&lookup_budget);
            let worker = match thread::Builder::new()
                .name(format!("ripdpi-dns-resolver-{index}"))
                .spawn(move || resolver_worker(receiver, resolver, lookup_budget, worker_shutdown))
            {
                Ok(worker) => worker,
                Err(error) => {
                    // Ordering: publish shutdown before disconnecting the queue and joining supervisors.
                    shutdown.store(true, Ordering::Release);
                    drop(jobs);
                    for worker in workers {
                        let _ = worker.join();
                    }
                    let _ = error;
                    return Err(DnsResolveError::Unavailable);
                }
            };
            workers.push(worker);
        }
        Ok(Self { jobs: Some(jobs), shutdown, workers })
    }

    fn submit(
        &self,
        host: String,
        port: u16,
        timeout: Duration,
    ) -> Result<mpsc::Receiver<Result<Vec<SocketAddr>, DnsResolveError>>, DnsResolveError> {
        let (response, result) = mpsc::channel();
        let jobs = self.jobs.as_ref().ok_or(DnsResolveError::Unavailable)?;
        let deadline = Instant::now().checked_add(timeout).ok_or(DnsResolveError::Timeout)?;
        jobs.try_send(ResolveJob { host, port, deadline, response }).map_err(|error| match error {
            mpsc::TrySendError::Full(_) => DnsResolveError::Busy,
            mpsc::TrySendError::Disconnected(_) => DnsResolveError::Unavailable,
        })?;
        Ok(result)
    }

    fn resolve(&self, host: String, port: u16, timeout: Duration) -> Result<Vec<SocketAddr>, DnsResolveError> {
        let started = Instant::now();
        let result = self.submit(host, port, timeout)?;
        let remaining = timeout.saturating_sub(started.elapsed());
        match result.recv_timeout(remaining) {
            Ok(result) => result,
            Err(mpsc::RecvTimeoutError::Timeout) => Err(DnsResolveError::Timeout),
            Err(mpsc::RecvTimeoutError::Disconnected) => Err(DnsResolveError::Unavailable),
        }
    }
}

impl Drop for ResolverExecutor {
    fn drop(&mut self) {
        // Ordering: publish shutdown before disconnecting the queue; Acquire polls make active supervisors exit.
        self.shutdown.store(true, Ordering::Release);
        self.jobs.take();
        for worker in self.workers.drain(..) {
            let _ = worker.join();
        }
    }
}

fn resolver_worker(
    receiver: Arc<Mutex<mpsc::Receiver<ResolveJob>>>,
    resolver: Arc<ResolveFn>,
    lookup_budget: Arc<LookupBudget>,
    shutdown: Arc<AtomicBool>,
) {
    loop {
        // Ordering: pairs with the Release store during executor shutdown.
        if shutdown.load(Ordering::Acquire) {
            break;
        }
        let job = {
            let receiver = receiver.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            receiver.recv()
        };
        let Ok(job) = job else {
            break;
        };
        resolve_job(job, &resolver, &lookup_budget, &shutdown);
    }
}

fn resolve_job(
    job: ResolveJob,
    resolver: &Arc<ResolveFn>,
    lookup_budget: &Arc<LookupBudget>,
    shutdown: &Arc<AtomicBool>,
) {
    let ResolveJob { host, port, deadline, response } = job;
    if deadline <= Instant::now() {
        let _ = response.send(Err(DnsResolveError::Timeout));
        return;
    }
    // Ordering: pairs with the Release store during executor shutdown.
    if shutdown.load(Ordering::Acquire) {
        let _ = response.send(Err(DnsResolveError::Unavailable));
        return;
    }
    let Some(permit) = lookup_budget.try_acquire() else {
        let _ = response.send(Err(DnsResolveError::Busy));
        return;
    };
    let (lookup_response, lookup_result) = mpsc::channel();
    let resolver = Arc::clone(resolver);
    let lookup = thread::Builder::new().name("ripdpi-dns-lookup".to_string()).spawn(move || {
        let _permit = permit;
        let result = if deadline <= Instant::now() {
            Err(DnsResolveError::Timeout)
        } else {
            catch_unwind(AssertUnwindSafe(|| resolver(&host, port))).unwrap_or(Err(DnsResolveError::Unavailable))
        };
        let _ = lookup_response.send(result);
    });
    let Ok(lookup) = lookup else {
        let _ = response.send(Err(DnsResolveError::Unavailable));
        return;
    };
    drop(lookup);
    let _ = response.send(wait_for_lookup(lookup_result, deadline, shutdown));
}

fn wait_for_lookup(
    result: mpsc::Receiver<Result<Vec<SocketAddr>, DnsResolveError>>,
    deadline: Instant,
    shutdown: &AtomicBool,
) -> Result<Vec<SocketAddr>, DnsResolveError> {
    loop {
        // Ordering: pairs with the Release store during executor shutdown.
        if shutdown.load(Ordering::Acquire) {
            return Err(DnsResolveError::Unavailable);
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return match result.try_recv() {
                Ok(result) => result,
                Err(mpsc::TryRecvError::Empty) => Err(DnsResolveError::Timeout),
                Err(mpsc::TryRecvError::Disconnected) => Err(DnsResolveError::Unavailable),
            };
        }
        match result.recv_timeout(remaining.min(DNS_SUPERVISOR_POLL_INTERVAL)) {
            Ok(result) => return result,
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => return Err(DnsResolveError::Unavailable),
        }
    }
}

pub fn resolve_addresses(target: &TargetAddress, port: u16) -> Result<Vec<SocketAddr>, DnsResolveError> {
    resolve_addresses_with_timeout(target, port, DNS_RESOLVE_TIMEOUT)
}

pub(crate) fn resolve_addresses_with_timeout(
    target: &TargetAddress,
    port: u16,
    timeout: Duration,
) -> Result<Vec<SocketAddr>, DnsResolveError> {
    match target {
        TargetAddress::Ip(ip) => Ok(vec![SocketAddr::new(*ip, port)]),
        TargetAddress::Host(host) => DNS_RESOLVER.as_ref().map_err(Clone::clone)?.resolve(host.clone(), port, timeout),
    }
}

pub fn resolve_first_socket_addr(value: &str) -> Result<SocketAddr, TransportError> {
    resolve_first_socket_addr_with(value, DNS_RESOLVER.as_ref().map_err(Clone::clone)?)
}

fn resolve_first_socket_addr_with(value: &str, resolver: &ResolverExecutor) -> Result<SocketAddr, TransportError> {
    if let Ok(address) = value.parse::<SocketAddr>() {
        return Ok(address);
    }
    let (host, port) = value.rsplit_once(':').ok_or(TransportError::MissingSocketPort)?;
    let host = host.trim().trim_start_matches('[').trim_end_matches(']');
    if host.is_empty() {
        return Err(TransportError::MissingSocketHost);
    }
    let port = port.parse::<u16>().map_err(TransportError::InvalidSocketPort)?;
    resolver
        .resolve(host.to_string(), port, DNS_RESOLVE_TIMEOUT)?
        .into_iter()
        .next()
        .ok_or(TransportError::NoSocketAddrs)
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Condvar, Mutex};
    use std::time::Instant;

    use super::*;

    #[test]
    fn domain_connect_target_uses_ip_override() {
        let target = DomainTarget {
            host: "example.com".to_string(),
            connect_ip: Some("1.2.3.4".to_string()),
            connect_ips: vec![],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        };
        match domain_connect_target(&target) {
            TargetAddress::Ip(ip) => assert_eq!(ip, "1.2.3.4".parse::<IpAddr>().unwrap()),
            TargetAddress::Host(_) => panic!("expected IP"),
        }
    }

    #[test]
    fn domain_connect_target_falls_back_to_host() {
        let target = DomainTarget {
            host: "example.com".to_string(),
            connect_ip: None,
            connect_ips: vec![],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        };
        match domain_connect_target(&target) {
            TargetAddress::Host(host) => assert_eq!(host, "example.com"),
            TargetAddress::Ip(_) => panic!("expected Host"),
        }
    }

    #[test]
    fn domain_connect_targets_keep_legacy_connect_ip_ahead_of_edge_list_and_host_fallback() {
        let target = DomainTarget {
            host: "example.com".to_string(),
            connect_ip: Some("203.0.113.10".to_string()),
            connect_ips: vec!["203.0.113.20".to_string(), "203.0.113.10".to_string()],
            https_port: None,
            http_port: None,
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        };

        let targets = domain_connect_targets(&target);

        assert_eq!(
            targets,
            vec![
                TargetAddress::Ip("203.0.113.10".parse::<IpAddr>().unwrap()),
                TargetAddress::Ip("203.0.113.20".parse::<IpAddr>().unwrap()),
                TargetAddress::Host("example.com".to_string()),
            ]
        );
    }

    #[test]
    fn resolve_addresses_with_ip_target() {
        let target = TargetAddress::Ip("127.0.0.1".parse().unwrap());
        let addrs = resolve_addresses(&target, 80).unwrap();
        assert_eq!(addrs, vec!["127.0.0.1:80".parse::<SocketAddr>().unwrap()]);
    }

    #[test]
    fn resolve_addresses_with_localhost_uses_bounded_executor() {
        let target = TargetAddress::Host("localhost".to_string());
        let addrs = resolve_addresses(&target, 443).expect("resolve localhost");

        assert!(addrs.iter().any(|address| address.ip().is_loopback() && address.port() == 443));
    }

    #[test]
    fn system_resolver_classifies_nxdomain_from_typed_os_error() {
        let error = io::Error::from_raw_os_error(libc::EAI_NONAME);

        assert_eq!(classify_system_resolver_error(&error), DnsResolveError::Nxdomain);
        assert_eq!(DnsResolveError::Nxdomain.code(), "dns_nxdomain");
    }

    #[test]
    fn system_resolver_does_not_parse_generic_error_text_as_nxdomain() {
        let error = io::Error::other("localized nxdomain-looking message");

        assert_eq!(classify_system_resolver_error(&error), DnsResolveError::Failure);
    }

    #[test]
    fn socket_address_helper_uses_bounded_resolver_for_hostnames() {
        let calls = Arc::new(AtomicUsize::new(0));
        let resolver: Arc<ResolveFn> = Arc::new({
            let calls = Arc::clone(&calls);
            move |host, port| {
                calls.fetch_add(1, Ordering::SeqCst);
                assert_eq!(host, "resolver.example");
                Ok(vec![SocketAddr::new("192.0.2.53".parse().expect("fixture IP"), port)])
            }
        });
        let executor = ResolverExecutor::new(1, 1, resolver).expect("create resolver executor");

        let address = resolve_first_socket_addr_with("resolver.example:5353", &executor).expect("resolve endpoint");

        assert_eq!(address, "192.0.2.53:5353".parse().expect("expected endpoint"));
        assert_eq!(calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn timed_out_dns_requests_do_not_create_unbounded_resolver_threads() {
        const REQUESTS: usize = 8;
        const MAX_WORKERS: usize = 4;
        const MAX_LOOKUPS: usize = MAX_WORKERS * DNS_RESOLVER_REPLACEMENT_FACTOR;
        let active = Arc::new(AtomicUsize::new(0));
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let resolver: Arc<ResolveFn> = Arc::new({
            let active = Arc::clone(&active);
            let release = Arc::clone(&release);
            move |_host, _port| {
                active.fetch_add(1, Ordering::SeqCst);
                let (released, wake) = &*release;
                let guard = released.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                drop(wake.wait_while(guard, |released| !*released));
                active.fetch_sub(1, Ordering::SeqCst);
                Ok(Vec::new())
            }
        });
        let executor = ResolverExecutor::new(MAX_WORKERS, REQUESTS, resolver).expect("create resolver executor");

        for index in 0..REQUESTS {
            let result = executor.resolve(format!("blocked-{index}.example"), 443, Duration::from_millis(10));
            assert_eq!(result, Err(DnsResolveError::Timeout));
        }
        let observed = active.load(Ordering::SeqCst);
        let (released, wake) = &*release;
        *released.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = true;
        wake.notify_all();
        drop(executor);

        assert!(observed <= MAX_LOOKUPS, "resolver lookups must be bounded to {MAX_LOOKUPS}, observed {observed}");
    }

    #[test]
    fn exhausted_primary_workers_use_bounded_replacement_capacity() {
        const WORKERS: usize = 2;
        const LOOKUP_LIMIT: usize = 4;
        let active = Arc::new(AtomicUsize::new(0));
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let resolver: Arc<ResolveFn> = Arc::new({
            let active = Arc::clone(&active);
            let release = Arc::clone(&release);
            move |host, port| {
                if host.starts_with("blocked-") {
                    active.fetch_add(1, Ordering::SeqCst);
                    let (released, wake) = &*release;
                    let guard = released.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                    drop(wake.wait_while(guard, |released| !*released));
                    active.fetch_sub(1, Ordering::SeqCst);
                }
                Ok(vec![SocketAddr::new("192.0.2.53".parse().expect("fixture IP"), port)])
            }
        });
        let executor = ResolverExecutor::new_with_lookup_limit(WORKERS, 8, LOOKUP_LIMIT, resolver)
            .expect("create resolver executor");

        for index in 0..WORKERS {
            let result = executor.resolve(format!("blocked-{index}.example"), 443, Duration::from_millis(10));
            assert_eq!(result, Err(DnsResolveError::Timeout));
        }
        wait_for_count(&active, WORKERS);

        let recovered = executor
            .resolve("recovery.example".to_string(), 443, Duration::from_millis(100))
            .expect("replacement lookup must run while primary lookups remain blocked");

        assert_eq!(recovered, vec!["192.0.2.53:443".parse().expect("fixture socket")]);
        assert_eq!(active.load(Ordering::SeqCst), WORKERS);
        release_all(&release);
        wait_for_count(&active, 0);
    }

    #[test]
    fn lookup_thread_limit_recovers_after_exhaustion() {
        const WORKERS: usize = 2;
        const LOOKUP_LIMIT: usize = 4;
        let active = Arc::new(AtomicUsize::new(0));
        let peak = Arc::new(AtomicUsize::new(0));
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let resolver: Arc<ResolveFn> = Arc::new({
            let active = Arc::clone(&active);
            let peak = Arc::clone(&peak);
            let release = Arc::clone(&release);
            move |host, port| {
                if host.starts_with("blocked-") {
                    let current = active.fetch_add(1, Ordering::SeqCst) + 1;
                    peak.fetch_max(current, Ordering::SeqCst);
                    let (released, wake) = &*release;
                    let guard = released.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                    drop(wake.wait_while(guard, |released| !*released));
                    active.fetch_sub(1, Ordering::SeqCst);
                }
                Ok(vec![SocketAddr::new("192.0.2.54".parse().expect("fixture IP"), port)])
            }
        });
        let executor = ResolverExecutor::new_with_lookup_limit(WORKERS, 8, LOOKUP_LIMIT, resolver)
            .expect("create resolver executor");

        for index in 0..LOOKUP_LIMIT {
            let result = executor.resolve(format!("blocked-{index}.example"), 443, Duration::from_millis(10));
            assert_eq!(result, Err(DnsResolveError::Timeout));
            wait_for_count(&active, index + 1);
        }
        let overflow = executor.resolve("overflow.example".to_string(), 443, Duration::from_millis(100));

        assert_eq!(overflow, Err(DnsResolveError::Busy));
        assert_eq!(peak.load(Ordering::SeqCst), LOOKUP_LIMIT);

        release_all(&release);
        wait_for_count(&active, 0);
        let recovered = executor
            .resolve("recovered.example".to_string(), 443, Duration::from_millis(100))
            .expect("lookup capacity must return after blocked calls exit");
        assert_eq!(recovered, vec!["192.0.2.54:443".parse().expect("fixture socket")]);
    }

    #[test]
    fn panicking_lookup_is_replaced_without_losing_capacity() {
        let calls = Arc::new(AtomicUsize::new(0));
        let resolver: Arc<ResolveFn> = Arc::new({
            let calls = Arc::clone(&calls);
            move |host, port| {
                calls.fetch_add(1, Ordering::SeqCst);
                if host == "panic.example" {
                    panic!("injected resolver panic");
                }
                Ok(vec![SocketAddr::new("192.0.2.55".parse().expect("fixture IP"), port)])
            }
        });
        let executor = ResolverExecutor::new_with_lookup_limit(1, 2, 1, resolver).expect("create resolver executor");

        let panicked = executor.resolve("panic.example".to_string(), 443, Duration::from_millis(100));
        let recovered = executor
            .resolve("recovered.example".to_string(), 443, Duration::from_millis(100))
            .expect("replacement lookup must succeed after panic");

        assert_eq!(panicked, Err(DnsResolveError::Unavailable));
        assert_eq!(recovered, vec!["192.0.2.55:443".parse().expect("fixture socket")]);
        assert_eq!(calls.load(Ordering::SeqCst), 2);
    }

    #[test]
    fn expired_queued_lookup_is_skipped_without_network_work() {
        let active = Arc::new(AtomicUsize::new(0));
        let calls = Arc::new(Mutex::new(Vec::new()));
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let resolver: Arc<ResolveFn> = Arc::new({
            let active = Arc::clone(&active);
            let calls = Arc::clone(&calls);
            let release = Arc::clone(&release);
            move |host, port| {
                calls.lock().unwrap_or_else(std::sync::PoisonError::into_inner).push(host.to_string());
                if host == "blocked.example" {
                    active.store(1, Ordering::SeqCst);
                    let (released, wake) = &*release;
                    let guard = released.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                    drop(wake.wait_while(guard, |released| !*released));
                    active.store(0, Ordering::SeqCst);
                }
                Ok(vec![SocketAddr::new("192.0.2.56".parse().expect("fixture IP"), port)])
            }
        });
        let executor = ResolverExecutor::new_with_lookup_limit(1, 4, 2, resolver).expect("create resolver executor");

        let blocked =
            executor.submit("blocked.example".to_string(), 443, Duration::from_secs(1)).expect("submit blocked lookup");
        wait_for_count(&active, 1);
        let expired = executor
            .submit("expired.example".to_string(), 443, Duration::from_millis(10))
            .expect("queue expiring lookup");
        thread::sleep(Duration::from_millis(20));
        release_all(&release);

        blocked.recv_timeout(Duration::from_millis(100)).expect("blocked response").expect("blocked result");
        assert_eq!(
            expired.recv_timeout(Duration::from_millis(100)).expect("expired response"),
            Err(DnsResolveError::Timeout)
        );
        assert_eq!(
            *calls.lock().unwrap_or_else(std::sync::PoisonError::into_inner),
            vec!["blocked.example".to_string()]
        );
    }

    #[test]
    fn dropping_executor_does_not_wait_for_hung_lookup_thread() {
        let active = Arc::new(AtomicUsize::new(0));
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let resolver: Arc<ResolveFn> = Arc::new({
            let active = Arc::clone(&active);
            let release = Arc::clone(&release);
            move |_host, _port| {
                active.store(1, Ordering::SeqCst);
                let (released, wake) = &*release;
                let guard = released.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                drop(wake.wait_while(guard, |released| !*released));
                active.store(0, Ordering::SeqCst);
                Ok(Vec::new())
            }
        });
        let executor = ResolverExecutor::new_with_lookup_limit(1, 2, 2, resolver).expect("create resolver executor");
        let response = executor
            .submit("blocked.example".to_string(), 443, Duration::from_secs(60))
            .expect("submit blocked lookup");
        wait_for_count(&active, 1);

        let (dropped, drop_result) = mpsc::channel();
        let drop_thread = thread::spawn(move || {
            let started = Instant::now();
            drop(executor);
            let _ = dropped.send(started.elapsed());
        });
        let elapsed = drop_result.recv_timeout(Duration::from_millis(500));
        drop(response);
        release_all(&release);
        drop_thread.join().expect("join executor drop thread");
        wait_for_count(&active, 0);
        assert!(
            elapsed.expect("executor drop must complete before hung lookup exits") < Duration::from_millis(500),
            "executor drop exceeded bounded shutdown"
        );
    }

    #[test]
    fn resolver_executor_rejects_work_when_queue_is_full() {
        let active = Arc::new(AtomicUsize::new(0));
        let release = Arc::new((Mutex::new(false), Condvar::new()));
        let resolver: Arc<ResolveFn> = Arc::new({
            let active = Arc::clone(&active);
            let release = Arc::clone(&release);
            move |_host, _port| {
                active.store(1, Ordering::Release);
                let (released, wake) = &*release;
                let guard = released.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
                drop(wake.wait_while(guard, |released| !*released));
                Ok(Vec::new())
            }
        });
        let executor = ResolverExecutor::new(1, 1, resolver).expect("create resolver executor");

        let first =
            executor.submit("first.example".to_string(), 443, Duration::from_secs(1)).expect("submit running lookup");
        while active.load(Ordering::Acquire) == 0 {
            thread::yield_now();
        }
        let second =
            executor.submit("second.example".to_string(), 443, Duration::from_secs(1)).expect("fill resolver queue");
        let third = executor.submit("third.example".to_string(), 443, Duration::from_secs(1));

        let (released, wake) = &*release;
        *released.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = true;
        wake.notify_all();
        drop(first);
        drop(second);
        drop(executor);
        assert_eq!(third.expect_err("full resolver queue must reject work"), DnsResolveError::Busy);
    }

    fn wait_for_count(counter: &AtomicUsize, expected: usize) {
        let deadline = Instant::now() + Duration::from_secs(1);
        while counter.load(Ordering::SeqCst) != expected {
            assert!(Instant::now() < deadline, "counter did not reach {expected}");
            thread::yield_now();
        }
    }

    fn release_all(release: &(Mutex<bool>, Condvar)) {
        let (released, wake) = release;
        *released.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = true;
        wake.notify_all();
    }
}
