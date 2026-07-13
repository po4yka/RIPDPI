use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::sync::{Arc, LazyLock, Mutex, mpsc};
use std::thread;
use std::time::Duration;

use crate::types::{DomainTarget, QuicTarget};

use super::types::TargetAddress;

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

type ResolveFn = dyn Fn(&str, u16) -> Result<Vec<SocketAddr>, String> + Send + Sync + 'static;

static DNS_RESOLVER: LazyLock<Result<ResolverExecutor, String>> = LazyLock::new(|| {
    ResolverExecutor::new(
        DNS_RESOLVER_WORKERS,
        DNS_RESOLVER_QUEUE_CAPACITY,
        Arc::new(|host, port| (host, port).to_socket_addrs().map(Iterator::collect).map_err(|err| err.to_string())),
    )
});

struct ResolveJob {
    host: String,
    port: u16,
    response: mpsc::Sender<Result<Vec<SocketAddr>, String>>,
}

struct ResolverExecutor {
    jobs: Option<mpsc::SyncSender<ResolveJob>>,
    workers: Vec<thread::JoinHandle<()>>,
}

impl ResolverExecutor {
    fn new(worker_count: usize, queue_capacity: usize, resolver: Arc<ResolveFn>) -> Result<Self, String> {
        if worker_count == 0 || queue_capacity == 0 {
            return Err("dns resolver executor requires non-zero workers and queue capacity".to_string());
        }
        let (jobs, receiver) = mpsc::sync_channel::<ResolveJob>(queue_capacity);
        let receiver = Arc::new(Mutex::new(receiver));
        let mut workers = Vec::with_capacity(worker_count);
        for index in 0..worker_count {
            let receiver = Arc::clone(&receiver);
            let resolver = Arc::clone(&resolver);
            let worker = thread::Builder::new()
                .name(format!("ripdpi-dns-resolver-{index}"))
                .spawn(move || resolver_worker(receiver, resolver))
                .map_err(|error| format!("failed to spawn diagnostics DNS resolver worker: {error}"))?;
            workers.push(worker);
        }
        Ok(Self { jobs: Some(jobs), workers })
    }

    fn submit(&self, host: String, port: u16) -> Result<mpsc::Receiver<Result<Vec<SocketAddr>, String>>, String> {
        let (response, result) = mpsc::channel();
        let jobs = self.jobs.as_ref().ok_or_else(|| "dns_resolver_unavailable".to_string())?;
        jobs.try_send(ResolveJob { host, port, response }).map_err(|error| match error {
            mpsc::TrySendError::Full(_) => "dns_resolver_busy".to_string(),
            mpsc::TrySendError::Disconnected(_) => "dns_resolver_unavailable".to_string(),
        })?;
        Ok(result)
    }

    fn resolve(&self, host: String, port: u16, timeout: Duration) -> Result<Vec<SocketAddr>, String> {
        match self.submit(host, port)?.recv_timeout(timeout) {
            Ok(result) => result,
            Err(mpsc::RecvTimeoutError::Timeout) => Err("dns_resolve_timeout".to_string()),
            Err(mpsc::RecvTimeoutError::Disconnected) => Err("dns_resolver_unavailable".to_string()),
        }
    }
}

impl Drop for ResolverExecutor {
    fn drop(&mut self) {
        self.jobs.take();
        for worker in self.workers.drain(..) {
            let _ = worker.join();
        }
    }
}

fn resolver_worker(receiver: Arc<Mutex<mpsc::Receiver<ResolveJob>>>, resolver: Arc<ResolveFn>) {
    loop {
        let job = {
            let receiver = receiver.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            receiver.recv()
        };
        let Ok(job) = job else {
            break;
        };
        let _ = job.response.send(resolver(&job.host, job.port));
    }
}

pub fn resolve_addresses(target: &TargetAddress, port: u16) -> Result<Vec<SocketAddr>, String> {
    match target {
        TargetAddress::Ip(ip) => Ok(vec![SocketAddr::new(*ip, port)]),
        TargetAddress::Host(host) => {
            DNS_RESOLVER.as_ref().map_err(Clone::clone)?.resolve(host.clone(), port, DNS_RESOLVE_TIMEOUT)
        }
    }
}

pub fn resolve_first_socket_addr(value: &str) -> Result<SocketAddr, String> {
    value.to_socket_addrs().map_err(|err| err.to_string())?.next().ok_or_else(|| "no_socket_addrs".to_string())
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Condvar, Mutex};

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
    fn timed_out_dns_requests_do_not_create_unbounded_resolver_threads() {
        const REQUESTS: usize = 8;
        const MAX_WORKERS: usize = 4;
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
            assert_eq!(result, Err("dns_resolve_timeout".to_string()));
        }
        let peak = active.load(Ordering::SeqCst);
        let (released, wake) = &*release;
        *released.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = true;
        wake.notify_all();
        drop(executor);

        assert!(peak <= MAX_WORKERS, "resolver threads must be bounded to {MAX_WORKERS}, observed {peak}");
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

        let first = executor.submit("first.example".to_string(), 443).expect("submit running lookup");
        while active.load(Ordering::Acquire) == 0 {
            thread::yield_now();
        }
        let second = executor.submit("second.example".to_string(), 443).expect("fill resolver queue");
        let third = executor.submit("third.example".to_string(), 443);

        let (released, wake) = &*release;
        *released.lock().unwrap_or_else(std::sync::PoisonError::into_inner) = true;
        wake.notify_all();
        drop(first);
        drop(second);
        drop(executor);
        assert_eq!(third.expect_err("full resolver queue must reject work"), "dns_resolver_busy");
    }
}
