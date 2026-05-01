use std::net::IpAddr;

use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

pub fn confirm_dns_tampering(
    host: &str,
    target_ip: IpAddr,
    encrypted_answers: &[IpAddr],
    source_label: &str,
) -> Option<ClassifiedFailure> {
    if encrypted_answers.is_empty() || encrypted_answers.contains(&target_ip) {
        return None;
    }
    let expected = encrypted_answers.iter().map(ToString::to_string).collect::<Vec<_>>().join("|");
    Some(
        ClassifiedFailure::new(
            FailureClass::DnsTampering,
            FailureStage::Dns,
            FailureAction::ResolverOverrideRecommended,
            format!("Encrypted DNS answers for {host} do not include {target_ip}"),
        )
        .with_tag("host", host.to_string())
        .with_tag("targetIp", target_ip.to_string())
        .with_tag("encryptedAnswers", expected)
        .with_tag("resolver", source_label.to_string()),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn confirms_dns_tampering_when_target_ip_is_outside_answer_set() {
        let classified = confirm_dns_tampering(
            "example.org",
            "203.0.113.9".parse().expect("target"),
            &["198.51.100.10".parse().expect("answer"), "198.51.100.11".parse().expect("answer")],
            "cloudflare",
        )
        .expect("dns tampering");

        assert_eq!(classified.class, FailureClass::DnsTampering);
        assert_eq!(classified.action, FailureAction::ResolverOverrideRecommended);
    }

    #[test]
    fn dns_tampering_returns_none_for_empty_answers() {
        assert!(confirm_dns_tampering("example.org", "1.2.3.4".parse().unwrap(), &[], "cloudflare").is_none());
    }

    #[test]
    fn dns_tampering_returns_none_when_target_in_answers() {
        let target: IpAddr = "198.51.100.10".parse().unwrap();
        assert!(confirm_dns_tampering("example.org", target, &[target], "cloudflare").is_none());
    }

    #[test]
    fn dns_tampering_works_with_ipv6_addresses() {
        let target: IpAddr = "2001:db8::1".parse().unwrap();
        let answers: Vec<IpAddr> = vec!["2001:db8::2".parse().unwrap()];
        let f = confirm_dns_tampering("example.org", target, &answers, "doh").expect("tampering");
        assert_eq!(f.class, FailureClass::DnsTampering);
        assert!(f.evidence.tags.iter().any(|t| t == "targetIp=2001:db8::1"));
    }
}
