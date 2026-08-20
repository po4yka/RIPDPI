use crate::types::{ScanPathMode, ScanRequest};
use crate::util::probe_session_seed;

use super::types::{RouteExperimentConfig, Socks5Credentials, TransportConfig};

pub fn direct_transport() -> TransportConfig {
    TransportConfig::Direct { route_experiment: None }
}

pub fn transport_for_request_with_session(request: &ScanRequest, session_id: &str) -> TransportConfig {
    if let (ScanPathMode::InPath, Some(route)) = (&request.path_mode, request.in_path_route.as_ref()) {
        let credentials =
            Socks5Credentials::new(route.credentials.username.clone(), route.credentials.password.clone());
        return TransportConfig::Socks5 { host: route.host.clone(), port: route.port, credentials };
    }
    match (&request.path_mode, request.proxy_host.as_ref(), request.proxy_port) {
        (ScanPathMode::InPath, Some(host), Some(port)) => {
            TransportConfig::Socks5 { host: host.clone(), port, credentials: None }
        }
        _ => TransportConfig::Direct {
            route_experiment: request.route_probe.as_ref().map(|config| RouteExperimentConfig {
                stable_flow_attempts: config.stable_flow_attempts.max(1),
                diversity_buckets: config.diversity_buckets.max(1),
                diversity_on_failure_only: config.diversity_on_failure_only,
                session_seed: probe_session_seed(None, session_id),
            }),
        },
    }
}

pub fn describe_transport(transport: &TransportConfig) -> String {
    match transport {
        TransportConfig::Direct { route_experiment: Some(config) } => {
            format!("DIRECT(routeProbe stable={} buckets={})", config.stable_flow_attempts, config.diversity_buckets,)
        }
        TransportConfig::Direct { route_experiment: None } => "DIRECT".to_string(),
        TransportConfig::Socks5 { host, port, .. } => format!("SOCKS5({host}:{port})"),
    }
}

#[cfg(test)]
mod tests {
    use crate::types::{DiagnosticProfileFamily, ScanKind};

    use super::*;

    fn request(path_mode: ScanPathMode) -> ScanRequest {
        ScanRequest {
            profile_id: "test".to_string(),
            display_name: "test".to_string(),
            path_mode,
            kind: ScanKind::Connectivity,
            family: DiagnosticProfileFamily::General,
            region_tag: None,
            manual_only: false,
            pack_refs: vec![],
            proxy_host: Some("proxy".to_string()),
            proxy_port: Some(1080),
            in_path_route: None,
            probe_tasks: vec![],
            domain_targets: vec![],
            dns_targets: vec![],
            tcp_targets: vec![],
            quic_targets: vec![],
            service_targets: vec![],
            circumvention_targets: vec![],
            throughput_targets: vec![],
            whitelist_sni: vec![],
            telegram_target: None,
            strategy_probe: None,
            network_snapshot: None,
            route_probe: None,
            scan_deadline_ms: None,
            diagnostic_tls_keylog_path: None,
            confirm_good_dpi_evidence: None,
        }
    }

    #[test]
    fn transport_for_request_direct_on_raw_path() {
        match transport_for_request_with_session(&request(ScanPathMode::RawPath), "default") {
            TransportConfig::Direct { .. } => {}
            TransportConfig::Socks5 { .. } => panic!("expected Direct for RawPath"),
        }
    }

    #[test]
    fn transport_for_request_socks5_on_in_path() {
        match transport_for_request_with_session(&request(ScanPathMode::InPath), "default") {
            TransportConfig::Socks5 { host, port, .. } => {
                assert_eq!(host, "proxy");
                assert_eq!(port, 1080);
            }
            TransportConfig::Direct { .. } => panic!("expected Socks5 for InPath"),
        }
    }

    #[test]
    fn describe_transport_direct() {
        assert_eq!(describe_transport(&direct_transport()), "DIRECT");
    }

    #[test]
    fn describe_transport_socks5() {
        let t = TransportConfig::Socks5 { host: "1.2.3.4".to_string(), port: 1080, credentials: None };
        assert_eq!(describe_transport(&t), "SOCKS5(1.2.3.4:1080)");
    }
}
