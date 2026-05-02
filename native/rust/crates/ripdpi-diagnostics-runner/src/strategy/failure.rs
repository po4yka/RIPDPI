use std::net::{IpAddr, SocketAddr};

use ripdpi_failure_classifier::{confirm_dns_tampering, ClassifiedFailure, FailureAction, FailureClass, FailureStage};

use crate::types::DomainTarget;

pub(super) fn build_strategy_dns_failure(
    target: &DomainTarget,
    system_targets: &[SocketAddr],
    system_resolution_failed: bool,
    encrypted_ips: &[IpAddr],
    resolver_label: &str,
) -> Option<ClassifiedFailure> {
    if system_resolution_failed {
        return Some(
            ClassifiedFailure::new(
                FailureClass::DnsTampering,
                FailureStage::Dns,
                FailureAction::ResolverOverrideRecommended,
                format!(
                    "System DNS returned NXDOMAIN for {} but encrypted resolver returned valid addresses",
                    target.host
                ),
            )
            .with_tag("host", target.host.clone())
            .with_tag("targetIp", "nxdomain".to_string())
            .with_tag("encryptedAnswers", encrypted_ips.iter().map(ToString::to_string).collect::<Vec<_>>().join("|"))
            .with_tag("resolver", resolver_label.to_string()),
        );
    }

    system_targets
        .iter()
        .map(SocketAddr::ip)
        .find_map(|system_ip| confirm_dns_tampering(&target.host, system_ip, encrypted_ips, resolver_label))
}
