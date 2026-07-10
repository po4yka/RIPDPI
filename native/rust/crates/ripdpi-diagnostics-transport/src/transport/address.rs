use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::sync::mpsc;
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

pub fn resolve_addresses(target: &TargetAddress, port: u16) -> Result<Vec<SocketAddr>, String> {
    match target {
        TargetAddress::Ip(ip) => Ok(vec![SocketAddr::new(*ip, port)]),
        TargetAddress::Host(host) => {
            let host = host.clone();
            let (tx, rx) = mpsc::channel();
            thread::spawn(move || {
                let _ = tx.send(
                    (host.as_str(), port).to_socket_addrs().map(Iterator::collect).map_err(|err| err.to_string()),
                );
            });
            rx.recv_timeout(DNS_RESOLVE_TIMEOUT).map_err(|_| "dns_resolve_timeout".to_string())?
        }
    }
}

pub fn resolve_first_socket_addr(value: &str) -> Result<SocketAddr, String> {
    value.to_socket_addrs().map_err(|err| err.to_string())?.next().ok_or_else(|| "no_socket_addrs".to_string())
}

#[cfg(test)]
mod tests {
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
}
