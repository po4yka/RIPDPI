//! Typed scaffold for platform ECH end-to-end spike findings.
//!
//! This module records the shape of findings that a real device run would
//! populate. All fields are `Option` because the spike was conducted without
//! device access; a future integration test can supply real values and assert
//! round-trip fidelity.

use serde::{Deserialize, Serialize};

/// Outcome of a single ECH probe attempt on a target device.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct EchSpikeFinding {
    /// Android API level on which the probe ran.
    pub api_level: u32,

    /// Whether the HTTPS-RR query via `DnsResolver` returned a parseable
    /// `ECHConfigList` for the target host.
    pub https_rr_ech_config_available: bool,

    /// Raw ECHConfigList bytes returned by the resolver, base64-encoded for
    /// JSON transport. `None` if the record was absent or malformed.
    pub ech_config_list_b64: Option<String>,

    /// Resolver path used for the HTTPS-RR bootstrap.
    pub bootstrap_resolver: BootstrapResolver,

    /// Outcome of the TLS handshake attempt after ECH was configured.
    pub handshake_outcome: HandshakeOutcome,

    /// Whether the server confirmed ECH acceptance (extension 0xfe0d present
    /// in EncryptedExtensions and status == Accepted).
    pub ech_accepted: bool,

    /// Practical bypass verdict change observed, if any.
    pub bypass_verdict_delta: Option<BypassVerdictDelta>,

    /// Free-form notes from the device run (flaky paths, pre-stable API
    /// caveats, deltas from documented surface).
    pub notes: Vec<String>,
}

/// DNS resolver path used for the HTTPS-RR / ECHConfig bootstrap.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum BootstrapResolver {
    /// AdGuard DoH (`dns.adguard.com`) — current default in Rust path.
    AdguardDoh,
    /// Android system resolver via `DnsResolver.query(TYPE_HTTPS)` with
    /// Private DNS (DoT/DoH) configured in OS network settings.
    AndroidPlatformPrivateDns,
    /// Android system resolver via `DnsResolver.query(TYPE_HTTPS)` with
    /// plaintext DNS — unreliable for SvcParam preservation.
    AndroidPlatformPlaintext,
    /// Explicit DoH endpoint bound to a specific `Network` object.
    ExplicitDohNetwork { host: String },
}

/// Result of the TLS handshake attempt after ECH configuration.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HandshakeOutcome {
    /// Handshake completed successfully.
    Ok,
    /// Server rejected ECH and provided retry_configs.
    EchRejectedWithRetryConfigs,
    /// Server rejected ECH with no retry_configs.
    EchRejectedNoRetry,
    /// Handshake failed for a reason unrelated to ECH.
    Failed { error: String },
    /// Probe was not run (e.g. ECHConfigList was unavailable).
    NotRun { reason: String },
}

/// Change in practical bypass verdict attributable to ECH.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct BypassVerdictDelta {
    /// Verdict without ECH.
    pub before: String,
    /// Verdict with ECH applied.
    pub after: String,
    /// Human-readable explanation.
    pub explanation: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    fn placeholder_finding() -> EchSpikeFinding {
        EchSpikeFinding {
            api_level: 36,
            https_rr_ech_config_available: false,
            ech_config_list_b64: None,
            bootstrap_resolver: BootstrapResolver::AdguardDoh,
            handshake_outcome: HandshakeOutcome::NotRun { reason: "no device access during spike session".to_string() },
            ech_accepted: false,
            bypass_verdict_delta: None,
            notes: vec![
                "Spike conducted without device access.".to_string(),
                "Validate platform ECH behavior on a physical device before using these findings.".to_string(),
            ],
        }
    }

    #[test]
    fn ech_spike_finding_round_trips_via_json() {
        let finding = placeholder_finding();
        let json = serde_json::to_string(&finding).expect("serialize");
        let recovered: EchSpikeFinding = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(finding, recovered);
    }

    #[test]
    fn ech_spike_finding_with_handshake_ok_round_trips() {
        let finding = EchSpikeFinding {
            api_level: 36,
            https_rr_ech_config_available: true,
            ech_config_list_b64: Some("AAECBA==".to_string()),
            bootstrap_resolver: BootstrapResolver::AndroidPlatformPrivateDns,
            handshake_outcome: HandshakeOutcome::Ok,
            ech_accepted: true,
            bypass_verdict_delta: Some(BypassVerdictDelta {
                before: "sni_based_block".to_string(),
                after: "ech_bypassed".to_string(),
                explanation: "SNI hidden in ClientHelloInner; outer carries cover domain".to_string(),
            }),
            notes: vec!["Verified on a physical device against cloudflare.com".to_string()],
        };
        let json = serde_json::to_string_pretty(&finding).expect("serialize");
        let recovered: EchSpikeFinding = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(finding, recovered);
    }
}
