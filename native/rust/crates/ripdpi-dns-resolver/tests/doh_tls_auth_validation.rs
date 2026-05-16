//! AC4 — Wrong auth name → rejected; correct name → accepted;
//! pinned bootstrap-IP mismatch → rejected.

use std::net::{IpAddr, Ipv4Addr};

use ripdpi_dns_resolver::doh::{DohAuthPolicy, DohClientError, TrustPolicy};

const EXPECTED_NAME: &str = "dns.example.com";
const WRONG_NAME: &str = "evil.attacker.net";
const PINNED_IP: IpAddr = IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1));
const OTHER_IP: IpAddr = IpAddr::V4(Ipv4Addr::new(9, 9, 9, 9));

fn check_auth(policy: &DohAuthPolicy, presented_name: &str, used_ip: IpAddr) -> Result<(), DohClientError> {
    if !policy.validates_name(presented_name) {
        return Err(DohClientError::AuthNameMismatch {
            expected: policy.auth_name.clone(),
            presented: presented_name.to_string(),
        });
    }
    if !policy.validates_bootstrap_ip(used_ip) {
        let pinned = match &policy.trust {
            TrustPolicy::PinnedBootstrapIp(ip) => *ip,
            TrustPolicy::SystemRoots => unreachable!("validates_bootstrap_ip returned false for SystemRoots"),
        };
        return Err(DohClientError::BootstrapIpMismatch { pinned, used: used_ip });
    }
    Ok(())
}

// --- auth name ---

#[test]
fn correct_auth_name_is_accepted_with_system_roots() {
    let policy = DohAuthPolicy::with_system_roots(EXPECTED_NAME);
    check_auth(&policy, EXPECTED_NAME, PINNED_IP).expect("correct name must be accepted");
}

#[test]
fn wrong_auth_name_is_rejected() {
    let policy = DohAuthPolicy::with_system_roots(EXPECTED_NAME);
    let err = check_auth(&policy, WRONG_NAME, PINNED_IP).expect_err("wrong name must be rejected");
    assert!(matches!(err, DohClientError::AuthNameMismatch { .. }), "expected AuthNameMismatch, got: {err}",);
}

#[test]
fn auth_name_comparison_is_case_insensitive() {
    let policy = DohAuthPolicy::with_system_roots("DNS.EXAMPLE.COM");
    check_auth(&policy, "dns.example.com", PINNED_IP).expect("case-insensitive match must pass");
}

// --- bootstrap IP pin ---

#[test]
fn correct_bootstrap_ip_is_accepted() {
    let policy = DohAuthPolicy::with_pinned_ip(EXPECTED_NAME, PINNED_IP);
    check_auth(&policy, EXPECTED_NAME, PINNED_IP).expect("correct IP must be accepted");
}

#[test]
fn wrong_bootstrap_ip_is_rejected() {
    let policy = DohAuthPolicy::with_pinned_ip(EXPECTED_NAME, PINNED_IP);
    let err = check_auth(&policy, EXPECTED_NAME, OTHER_IP).expect_err("mismatched IP must be rejected");
    assert!(matches!(err, DohClientError::BootstrapIpMismatch { .. }), "expected BootstrapIpMismatch, got: {err}",);
}

#[test]
fn system_roots_policy_accepts_any_ip() {
    let policy = DohAuthPolicy::with_system_roots(EXPECTED_NAME);
    // Any IP is acceptable when using system roots (no pin).
    check_auth(&policy, EXPECTED_NAME, OTHER_IP).expect("system-roots policy must accept any IP");
    check_auth(&policy, EXPECTED_NAME, PINNED_IP).expect("system-roots policy must accept any IP");
}

#[test]
fn wrong_name_takes_priority_over_wrong_ip() {
    // Name check fires first; the error must be AuthNameMismatch.
    let policy = DohAuthPolicy::with_pinned_ip(EXPECTED_NAME, PINNED_IP);
    let err = check_auth(&policy, WRONG_NAME, OTHER_IP).expect_err("both wrong → must still error");
    assert!(matches!(err, DohClientError::AuthNameMismatch { .. }), "name check must fire before IP check; got: {err}",);
}
