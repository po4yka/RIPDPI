use super::*;
use std::net::{Ipv4Addr, TcpListener};
fn config() -> IpFamilyProbeConfig {
    IpFamilyProbeConfig {
        version: 1,
        ipv4_address: "127.0.0.1".into(),
        ipv6_address: "::1".into(),
        port: 443,
        timeout_ms: 100,
    }
}
fn evidence(row: &ProbeResult) -> String {
    row.details[0].value.clone()
}
#[test]
fn numeric_ipv4_success_is_independent_from_ipv6_and_missing_dns64() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let mut config = config();
    config.port = listener.local_addr().unwrap().port();
    let rows = run_ip_family_probes(
        &config,
        &[],
        &TransportConfig::Direct { route_experiment: None },
        true,
        &AtomicBool::new(false),
    );
    assert_eq!(rows.len(), 3);
    assert_eq!(rows[0].outcome, "ip_family_reachable");
    assert_eq!(rows[1].outcome, "ip_family_unavailable");
    assert_eq!(rows[2].outcome, "ip_family_inconclusive");
    assert!(evidence(&rows[0]).contains("127.0.0.1"));
    assert!(evidence(&rows[1]).contains("::1"));
    assert!(evidence(&rows[2]).contains("no_network_dns"));
}
#[test]
fn proxy_cancellation_and_deadline_cannot_report_reachability() {
    let rows = run_ip_family_probes(
        &config(),
        &[],
        &TransportConfig::Socks5 { host: "127.0.0.1".into(), port: 9, credentials: None },
        false,
        &AtomicBool::new(false),
    );
    assert!(
        rows.iter()
            .all(|row| evidence(row).contains("UNSUPPORTED_PROXY") && evidence(row).contains("\"attemptCount\":0"))
    );
    let rows = run_ip_family_probes(
        &config(),
        &[],
        &TransportConfig::Direct { route_experiment: None },
        true,
        &AtomicBool::new(true),
    );
    assert!(rows.iter().all(|row| row.outcome == "ip_family_cancelled"));
    with_scan_io_deadline(Some(Instant::now() - Duration::from_millis(1)), || {
        let rows = run_ip_family_probes(
            &config(),
            &[],
            &TransportConfig::Direct { route_experiment: None },
            true,
            &AtomicBool::new(false),
        );
        assert!(
            rows.iter()
                .all(|row| evidence(row).contains("deadline_exceeded") && evidence(row).contains("\"attemptCount\":0"))
        );
    });
}

fn nat64_evidence() -> IpFamilyEvidence {
    IpFamilyEvidence {
        version: 1,
        family: "NAT64".into(),
        stage: "DNS64_DISCOVERY".into(),
        status: "NOT_OBSERVED".into(),
        reason: None,
        duration_ms: None,
        destination_address: None,
        destination_port: 443,
        attempt_count: 0,
        path_scope: "RAW_PATH".into(),
        prefix: None,
        prefix_length: None,
        discovery_status: Some("NOT_RUN".into()),
        resolver_source: Some("NETWORK_SNAPSHOT".into()),
    }
}
#[test]
fn discovered_prefixes_require_real_connect_and_try_next_in_response_order() {
    use ripdpi_diagnostics_dns::dns::Nat64Prefix;
    let prefixes = vec![
        Nat64Prefix { address: "2001:db8::".parse().unwrap(), length: 96 },
        Nat64Prefix { address: "64:ff9b::".parse().unwrap(), length: 96 },
    ];
    let mut facts = nat64_evidence();
    let mut peers = vec![];
    execute_with(
        &config(),
        &[],
        &AtomicBool::new(false),
        &mut facts,
        |_, _| Nat64Discovery { prefixes: prefixes.clone(), status: "DISCOVERED", reason: None, attempts: 1 },
        |peer| {
            peers.push(peer);
            if peers.len() == 1 { Err(std::io::Error::from(std::io::ErrorKind::ConnectionRefused)) } else { Ok(()) }
        },
    );
    assert_eq!(facts.status, "REACHABLE");
    assert_eq!(facts.attempt_count, 3);
    assert_eq!(facts.prefix, Some("64:ff9b::".into()));
    assert_eq!(peers[0].ip().to_string(), "2001:db8::7f00:1");
    assert_eq!(peers[1].ip().to_string(), "64:ff9b::7f00:1");
    let mut facts = nat64_evidence();
    execute_with(
        &config(),
        &[],
        &AtomicBool::new(false),
        &mut facts,
        |_, _| Nat64Discovery { prefixes, status: "DISCOVERED", reason: None, attempts: 1 },
        |_| Err(std::io::Error::from(std::io::ErrorKind::TimedOut)),
    );
    assert_eq!(facts.discovery_status, Some("DISCOVERED".into()));
    assert_eq!(facts.status, "TIMEOUT");
    assert_eq!(facts.stage, "TCP_CONNECT");
    assert_eq!(facts.reason, Some("timeout".into()));
}

#[test]
fn cancellation_after_discovery_prevents_tcp_and_dns_timeout_is_inconclusive() {
    let cancel = AtomicBool::new(false);
    let mut facts = nat64_evidence();
    execute_with(
        &config(),
        &[],
        &cancel,
        &mut facts,
        |_, cancel| {
            cancel.store(true, Ordering::Release);
            Nat64Discovery {
                prefixes: vec![ripdpi_diagnostics_dns::dns::Nat64Prefix {
                    address: "64:ff9b::".parse().unwrap(),
                    length: 96,
                }],
                status: "DISCOVERED",
                reason: None,
                attempts: 1,
            }
        },
        |_| panic!("TCP must not start after cancellation"),
    );
    assert_eq!(facts.status, "CANCELLED");
    assert_eq!(facts.attempt_count, 1);
    facts.status = "TIMEOUT".into();
    facts.reason = Some("dns_timeout".into());
    assert_eq!(result(facts).outcome, "ip_family_inconclusive");
}

#[test]
fn numeric_ipv6_success_does_not_fallback_to_ipv4() {
    let listener = TcpListener::bind((std::net::Ipv6Addr::LOCALHOST, 0)).unwrap();
    let mut config = config();
    config.port = listener.local_addr().unwrap().port();
    let rows = run_ip_family_probes(
        &config,
        &[],
        &TransportConfig::Direct { route_experiment: None },
        true,
        &AtomicBool::new(false),
    );
    assert_eq!(rows[0].outcome, "ip_family_unavailable");
    assert_eq!(rows[1].outcome, "ip_family_reachable");
    assert!(evidence(&rows[1]).contains("::1"));
}

#[test]
fn expired_budget_after_discovery_never_counts_a_tcp_attempt() {
    let mut facts = nat64_evidence();
    facts.attempt_count = 1;
    facts.discovery_status = Some("DISCOVERED".into());
    with_scan_io_deadline(Some(Instant::now() - Duration::from_millis(1)), || {
        connect(Some("64:ff9b::101:101".parse().unwrap()), &config(), &AtomicBool::new(false), &mut facts, &mut |_| {
            panic!("expired TCP")
        });
    });
    assert_eq!(facts.stage, "DNS64_DISCOVERY");
    assert_eq!(facts.attempt_count, 1);
    assert_eq!(result(facts).outcome, "ip_family_inconclusive");
}

#[test]
fn interrupted_first_tcp_attempt_retains_its_matching_prefix() {
    use ripdpi_diagnostics_dns::dns::Nat64Prefix;
    let prefixes = vec![
        Nat64Prefix { address: "2001:db8::".parse().unwrap(), length: 96 },
        Nat64Prefix { address: "64:ff9b::".parse().unwrap(), length: 96 },
    ];
    let mut facts = nat64_evidence();
    let mut count = 0;
    let cancel = AtomicBool::new(false);
    execute_with(
        &config(),
        &[],
        &cancel,
        &mut facts,
        |_, _| Nat64Discovery { prefixes, status: "DISCOVERED", reason: None, attempts: 1 },
        |_| {
            count += 1;
            cancel.store(true, Ordering::Release);
            Err(std::io::Error::from(std::io::ErrorKind::TimedOut))
        },
    );
    assert_eq!(count, 1);
    assert_eq!(facts.prefix, Some("2001:db8::".into()));
    assert_eq!(facts.destination_address, Some("2001:db8::7f00:1".into()));
}
