use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureClass {
    Unknown,
    DnsTampering,
    DnsResolutionFailure,
    TcpReset,
    SilentDrop,
    TlsAlert,
    HttpBlockpage,
    QuicBreakage,
    Redirect,
    TlsHandshakeFailure,
    ConnectFailure,
    StrategyExecutionFailure,
    ConnectionFreeze,
    /// The strategy candidate was skipped because a required platform
    /// capability was unavailable at runtime (e.g. TTL write, raw TCP).
    /// Distinct from `Failed` (which implies the probe ran and lost) and
    /// from `NetworkBlocked` (which implies external censorship).
    CapabilitySkipped,
    /// The TUIC server speaks a wire version this client does not
    /// support (RIPDPI is v5-only per
    /// `docs/architecture/tuic-v4-policy.md`). Distinct from
    /// `ConnectFailure` so the user-visible diagnostic can recommend
    /// upgrading the server rather than retrying.
    TuicVersionUnsupported,
    /// The ShadowTLS server speaks v2 wire framing while this client
    /// is v3-only per `docs/architecture/shadowtls-version-policy.md`.
    /// Distinct from `TlsHandshakeFailure` so the user-visible
    /// diagnostic can recommend a server upgrade.
    ShadowTlsVersionMismatch,
    /// All DoH-provided IPs failed at SYN and the alternate IP family also
    /// failed at SYN, with no CDN variant succeeding within the attempt
    /// budget. The engine must jump straight to owned-stack arms (A10/A9);
    /// TLS-family arms must not be attempted in this state.
    IpBlockSuspect,
    /// A classic VLESS Reality flow completed its catalog-validated TLS/VLESS
    /// handshake but repeatedly established no application response bytes,
    /// while a same-network QUIC control succeeded.
    ConfirmGoodDpiSuspected,
}

impl FailureClass {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Unknown => "unknown",
            Self::DnsTampering => "dns_tampering",
            Self::DnsResolutionFailure => "dns_resolution_failure",
            Self::TcpReset => "tcp_reset",
            Self::SilentDrop => "silent_drop",
            Self::TlsAlert => "tls_alert",
            Self::HttpBlockpage => "http_blockpage",
            Self::QuicBreakage => "quic_breakage",
            Self::Redirect => "redirect",
            Self::TlsHandshakeFailure => "tls_handshake_failure",
            Self::ConnectFailure => "connect_failure",
            Self::StrategyExecutionFailure => "strategy_execution_failure",
            Self::ConnectionFreeze => "connection_freeze",
            Self::CapabilitySkipped => "capability_skipped",
            Self::TuicVersionUnsupported => "tuic_version_unsupported",
            Self::ShadowTlsVersionMismatch => "shadowtls_version_mismatch",
            Self::IpBlockSuspect => "ip_block_suspect",
            Self::ConfirmGoodDpiSuspected => "confirm_good_dpi_suspected",
        }
    }
}

/// Controls which circumvention arms the engine is allowed to attempt after
/// classification. `OwnedStackOnly` skips all TLS-family arms and goes
/// straight to owned-stack arms (A10/A9).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ArmGate {
    /// No restriction — engine may attempt any arm.
    #[default]
    Unrestricted,
    /// Skip TLS-family arms; jump directly to owned-stack arms (A10/A9).
    OwnedStackOnly,
}

/// Verdict returned by [`classify_ip_block_suspect`]. When `verdict` is
/// `IpBlockSuspect` the caller must honour `arm_gate = OwnedStackOnly`.
/// When `verdict` is `PendingSecondFlow` the engine must wait for one more
/// flow before persisting any classification.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum IpBlockVerdict {
    /// Both IP families failed at SYN, no CDN success, guard passed —
    /// classify as IP_BLOCK_SUSPECT.
    IpBlockSuspect,
    /// Guard has not yet been satisfied; defer classification until the next
    /// flow completes.
    PendingSecondFlow,
    /// At least one SYN or CDN attempt succeeded — not an IP block.
    NotIpBlock,
}

/// Output of the pure-function IP-block classifier.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct IpBlockSuspectVerdict {
    pub verdict: IpBlockVerdict,
    #[serde(default)]
    pub arm_gate: ArmGate,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureStage {
    Dns,
    Connect,
    FirstWrite,
    FirstResponse,
    TlsHandshake,
    HttpResponse,
    QuicProbe,
    Diagnostic,
    Relay,
    PostHandshake,
}

impl FailureStage {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Dns => "dns",
            Self::Connect => "connect",
            Self::FirstWrite => "first_write",
            Self::FirstResponse => "first_response",
            Self::TlsHandshake => "tls_handshake",
            Self::HttpResponse => "http_response",
            Self::QuicProbe => "quic_probe",
            Self::Diagnostic => "diagnostic",
            Self::Relay => "relay",
            Self::PostHandshake => "post_handshake",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FailureAction {
    None,
    RetryWithMatchingGroup,
    ResolverOverrideRecommended,
    DiagnosticsOnly,
    SurfaceOnly,
    PivotTransportFamily,
}

impl FailureAction {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::RetryWithMatchingGroup => "retry_with_matching_group",
            Self::ResolverOverrideRecommended => "resolver_override_recommended",
            Self::DiagnosticsOnly => "diagnostics_only",
            Self::SurfaceOnly => "surface_only",
            Self::PivotTransportFamily => "pivot_transport_family",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FailureEvidence {
    pub summary: String,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub tags: Vec<String>,
}

impl FailureEvidence {
    pub fn new(summary: impl Into<String>) -> Self {
        Self { summary: summary.into(), tags: Vec::new() }
    }

    pub fn with_tag(mut self, key: &str, value: impl Into<String>) -> Self {
        self.tags.push(format!("{key}={}", value.into()));
        self
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ClassifiedFailure {
    pub class: FailureClass,
    pub stage: FailureStage,
    pub action: FailureAction,
    #[serde(default)]
    pub evidence: FailureEvidence,
}

impl ClassifiedFailure {
    pub fn new(class: FailureClass, stage: FailureStage, action: FailureAction, summary: impl Into<String>) -> Self {
        Self { class, stage, action, evidence: FailureEvidence::new(summary) }
    }

    pub fn with_tag(self, key: &str, value: impl Into<String>) -> Self {
        Self { evidence: self.evidence.with_tag(key, value), ..self }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn evidence_tags_format_key_value_pairs() {
        let e = FailureEvidence::new("test").with_tag("foo", "bar").with_tag("baz", "42");
        assert_eq!(e.tags, vec!["foo=bar", "baz=42"]);
    }

    #[test]
    fn failure_class_as_str_covers_all_variants() {
        let cases = [
            (FailureClass::Unknown, "unknown"),
            (FailureClass::DnsTampering, "dns_tampering"),
            (FailureClass::DnsResolutionFailure, "dns_resolution_failure"),
            (FailureClass::TcpReset, "tcp_reset"),
            (FailureClass::SilentDrop, "silent_drop"),
            (FailureClass::TlsAlert, "tls_alert"),
            (FailureClass::HttpBlockpage, "http_blockpage"),
            (FailureClass::QuicBreakage, "quic_breakage"),
            (FailureClass::Redirect, "redirect"),
            (FailureClass::TlsHandshakeFailure, "tls_handshake_failure"),
            (FailureClass::ConnectFailure, "connect_failure"),
            (FailureClass::StrategyExecutionFailure, "strategy_execution_failure"),
            (FailureClass::ConnectionFreeze, "connection_freeze"),
            (FailureClass::CapabilitySkipped, "capability_skipped"),
            (FailureClass::TuicVersionUnsupported, "tuic_version_unsupported"),
            (FailureClass::ShadowTlsVersionMismatch, "shadowtls_version_mismatch"),
            (FailureClass::IpBlockSuspect, "ip_block_suspect"),
            (FailureClass::ConfirmGoodDpiSuspected, "confirm_good_dpi_suspected"),
        ];
        for (variant, expected) in cases {
            assert_eq!(variant.as_str(), expected, "{variant:?} should map to {expected:?}");
        }
    }

    #[test]
    fn tuic_version_unsupported_distinct_from_connect_failure() {
        // TUIC v4-server response must classify as a distinct variant so
        // the user-visible diagnostic can recommend upgrading the server
        // rather than a generic protocol failure. See
        // docs/architecture/tuic-v4-policy.md.
        assert_ne!(FailureClass::TuicVersionUnsupported, FailureClass::ConnectFailure);
        assert_ne!(FailureClass::TuicVersionUnsupported, FailureClass::Unknown);
        assert_eq!(FailureClass::TuicVersionUnsupported.as_str(), "tuic_version_unsupported");
    }

    #[test]
    fn shadowtls_version_mismatch_distinct_from_tls_handshake_failure() {
        // ShadowTLS v2-server response must classify as a distinct
        // variant so the user-visible diagnostic can recommend
        // upgrading the server rather than a generic TLS handshake
        // failure. See docs/architecture/shadowtls-version-policy.md.
        assert_ne!(FailureClass::ShadowTlsVersionMismatch, FailureClass::TlsHandshakeFailure);
        assert_ne!(FailureClass::ShadowTlsVersionMismatch, FailureClass::Unknown);
        assert_eq!(FailureClass::ShadowTlsVersionMismatch.as_str(), "shadowtls_version_mismatch");
    }

    #[test]
    fn failure_stage_as_str_covers_all_variants() {
        let cases = [
            (FailureStage::Dns, "dns"),
            (FailureStage::Connect, "connect"),
            (FailureStage::FirstWrite, "first_write"),
            (FailureStage::FirstResponse, "first_response"),
            (FailureStage::TlsHandshake, "tls_handshake"),
            (FailureStage::HttpResponse, "http_response"),
            (FailureStage::QuicProbe, "quic_probe"),
            (FailureStage::Diagnostic, "diagnostic"),
            (FailureStage::Relay, "relay"),
            (FailureStage::PostHandshake, "post_handshake"),
        ];
        for (variant, expected) in cases {
            assert_eq!(variant.as_str(), expected, "{variant:?} should map to {expected:?}");
        }
    }

    #[test]
    fn failure_action_as_str_covers_all_variants() {
        let cases = [
            (FailureAction::None, "none"),
            (FailureAction::RetryWithMatchingGroup, "retry_with_matching_group"),
            (FailureAction::ResolverOverrideRecommended, "resolver_override_recommended"),
            (FailureAction::DiagnosticsOnly, "diagnostics_only"),
            (FailureAction::SurfaceOnly, "surface_only"),
            (FailureAction::PivotTransportFamily, "pivot_transport_family"),
        ];
        for (variant, expected) in cases {
            assert_eq!(variant.as_str(), expected, "{variant:?} should map to {expected:?}");
        }
    }

    #[test]
    fn failure_class_serde_round_trip() {
        for class in [
            FailureClass::Unknown,
            FailureClass::DnsTampering,
            FailureClass::DnsResolutionFailure,
            FailureClass::TcpReset,
            FailureClass::SilentDrop,
            FailureClass::TlsAlert,
            FailureClass::HttpBlockpage,
            FailureClass::QuicBreakage,
            FailureClass::Redirect,
            FailureClass::TlsHandshakeFailure,
            FailureClass::ConnectFailure,
            FailureClass::StrategyExecutionFailure,
            FailureClass::ConnectionFreeze,
            FailureClass::CapabilitySkipped,
            FailureClass::IpBlockSuspect,
            FailureClass::ConfirmGoodDpiSuspected,
        ] {
            let json = serde_json::to_string(&class).unwrap();
            let deserialized: FailureClass = serde_json::from_str(&json).unwrap();
            assert_eq!(class, deserialized, "round-trip failed for {class:?}");
        }
    }

    #[test]
    fn failure_class_serde_uses_snake_case() {
        let json = serde_json::to_string(&FailureClass::TcpReset).unwrap();
        assert_eq!(json, r#""tcp_reset""#);

        let json = serde_json::to_string(&FailureClass::TlsHandshakeFailure).unwrap();
        assert_eq!(json, r#""tls_handshake_failure""#);
    }

    #[test]
    fn failure_stage_serde_round_trip() {
        for stage in [
            FailureStage::Dns,
            FailureStage::Connect,
            FailureStage::FirstWrite,
            FailureStage::FirstResponse,
            FailureStage::TlsHandshake,
            FailureStage::HttpResponse,
            FailureStage::QuicProbe,
            FailureStage::Diagnostic,
            FailureStage::Relay,
            FailureStage::PostHandshake,
        ] {
            let json = serde_json::to_string(&stage).unwrap();
            let deserialized: FailureStage = serde_json::from_str(&json).unwrap();
            assert_eq!(stage, deserialized, "round-trip failed for {stage:?}");
        }
    }

    #[test]
    fn failure_action_serde_round_trip() {
        for action in [
            FailureAction::None,
            FailureAction::RetryWithMatchingGroup,
            FailureAction::ResolverOverrideRecommended,
            FailureAction::DiagnosticsOnly,
            FailureAction::SurfaceOnly,
            FailureAction::PivotTransportFamily,
        ] {
            let json = serde_json::to_string(&action).unwrap();
            let deserialized: FailureAction = serde_json::from_str(&json).unwrap();
            assert_eq!(action, deserialized, "round-trip failed for {action:?}");
        }
    }

    #[test]
    fn classified_failure_serde_round_trip() {
        let original = ClassifiedFailure::new(
            FailureClass::TcpReset,
            FailureStage::Connect,
            FailureAction::RetryWithMatchingGroup,
            "connection reset",
        )
        .with_tag("kind", "ConnectionReset");

        let json = serde_json::to_string(&original).unwrap();
        let deserialized: ClassifiedFailure = serde_json::from_str(&json).unwrap();
        assert_eq!(original, deserialized);
    }

    #[test]
    fn classified_failure_json_uses_camel_case_fields() {
        let f = ClassifiedFailure::new(FailureClass::Unknown, FailureStage::Dns, FailureAction::None, "test");
        let json = serde_json::to_string(&f).unwrap();
        assert!(!json.contains("\"statusCode\""), "should not contain statusCode");
        let value: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(value.get("class").is_some());
        assert!(value.get("stage").is_some());
        assert!(value.get("action").is_some());
        assert!(value.get("evidence").is_some());
    }

    #[test]
    fn failure_evidence_default_has_empty_summary_and_no_tags() {
        let e = FailureEvidence::default();
        assert!(e.summary.is_empty());
        assert!(e.tags.is_empty());
    }

    #[test]
    fn failure_evidence_serde_omits_empty_tags() {
        let e = FailureEvidence::new("summary only");
        let json = serde_json::to_string(&e).unwrap();
        assert!(!json.contains("tags"), "empty tags should be omitted via skip_serializing_if");
    }

    #[test]
    fn failure_evidence_serde_includes_nonempty_tags() {
        let e = FailureEvidence::new("test").with_tag("k", "v");
        let json = serde_json::to_string(&e).unwrap();
        assert!(json.contains("tags"));
    }

    #[test]
    fn classified_failure_is_cloneable() {
        let original = ClassifiedFailure::new(
            FailureClass::TcpReset,
            FailureStage::Connect,
            FailureAction::RetryWithMatchingGroup,
            "test",
        )
        .with_tag("key", "value");
        let cloned = original.clone();
        assert_eq!(original, cloned);
    }

    #[test]
    fn enums_are_copy() {
        let class = FailureClass::TcpReset;
        let class2 = class;
        assert_eq!(class, class2);

        let stage = FailureStage::Connect;
        let stage2 = stage;
        assert_eq!(stage, stage2);

        let action = FailureAction::None;
        let action2 = action;
        assert_eq!(action, action2);
    }
}
