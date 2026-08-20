use std::net::{IpAddr, SocketAddr};

use ripdpi_failure_classifier::{ClassifiedFailure, FailureAction, FailureClass, FailureStage, confirm_dns_tampering};

use crate::strategy::adapters::transport::DnsResolveError;
use crate::types::DomainTarget;

pub(super) fn build_strategy_dns_failure(
    target: &DomainTarget,
    system_targets: &[SocketAddr],
    system_resolution_failed: bool,
    system_error_kind: Option<DnsResolveError>,
    encrypted_ips: &[IpAddr],
    resolver_label: &str,
) -> Option<ClassifiedFailure> {
    if system_resolution_failed {
        let error_kind = system_error_kind.unwrap_or(DnsResolveError::Failure);
        let summary = if error_kind == DnsResolveError::Nxdomain {
            format!("System DNS returned NXDOMAIN for {} but encrypted resolver returned valid addresses", target.host)
        } else {
            format!(
                "System DNS failed for {} with {} but encrypted resolver returned valid addresses",
                target.host,
                error_kind.code()
            )
        };
        let failure_class = if error_kind == DnsResolveError::Nxdomain {
            FailureClass::DnsTampering
        } else {
            FailureClass::DnsResolutionFailure
        };
        let mut failure = ClassifiedFailure::new(
            failure_class,
            FailureStage::Dns,
            FailureAction::ResolverOverrideRecommended,
            summary,
        )
        .with_tag("host", target.host.clone())
        .with_tag("systemDnsErrorKind", error_kind.code().to_string())
        .with_tag("encryptedAnswers", encrypted_ips.iter().map(ToString::to_string).collect::<Vec<_>>().join("|"))
        .with_tag("resolver", resolver_label.to_string());
        if error_kind == DnsResolveError::Nxdomain {
            failure = failure.with_tag("targetIp", "nxdomain".to_string());
        }
        return Some(failure);
    }

    system_targets
        .iter()
        .map(SocketAddr::ip)
        .find_map(|system_ip| confirm_dns_tampering(&target.host, system_ip, encrypted_ips, resolver_label))
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr};

    use ripdpi_failure_classifier::{FailureAction, FailureClass, FailureStage};

    use super::build_strategy_dns_failure;
    use crate::strategy::adapters::transport::DnsResolveError;
    use crate::types::DomainTarget;

    fn target() -> DomainTarget {
        DomainTarget {
            host: "blocked.example".to_string(),
            connect_ip: None,
            connect_ips: Vec::new(),
            https_port: Some(443),
            http_port: Some(80),
            http_path: "/".to_string(),
            is_control: false,
            concurrency_probe: None,
        }
    }

    #[test]
    fn system_dns_timeout_recommends_override_without_claiming_tampering_failure_class() {
        let failure = build_strategy_dns_failure(
            &target(),
            &[],
            true,
            Some(DnsResolveError::Timeout),
            &[IpAddr::V4(Ipv4Addr::new(198, 51, 100, 77))],
            "primary",
        )
        .expect("classified resolver failure");

        assert_eq!(failure.class, FailureClass::DnsResolutionFailure);
        assert_eq!(failure.stage, FailureStage::Dns);
        assert_eq!(failure.action, FailureAction::ResolverOverrideRecommended);
        assert!(failure.evidence.summary.contains("dns_resolve_timeout"));
    }
}
