/// Schema version for `NetProfile`, `HostProfile`, and `ArmStats`.
///
/// Bump this constant whenever a field is added, removed, or its serialized
/// representation changes. Consumers should reject records whose
/// `schema_version` differs from this value.
pub const SCHEMA_VERSION: u32 = 1;

// ── Network-level context ────────────────────────────────────────────────────

/// Coarse access-technology classification.
///
/// Intentionally coarse: no carrier name, no cell-tower ID. Only the
/// radio/link type that a network stack can observe without user consent.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum AccessType {
    Unknown,
    Wifi,
    CellularNr,
    CellularLte,
    CellularOther,
    Ethernet,
    Vpn,
}

/// IP-family in use for this flow.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum IpFamily {
    Unknown,
    V4,
    V6,
    DualStack,
}

/// Coarse DNS-resolver classification.
///
/// No IP address, no resolver hostname — only the class of resolver
/// (system default, DoH, DoT, DoQ).
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum DnsClass {
    Unknown,
    SystemDefault,
    Doh,
    Dot,
    Doq,
}

/// The connection phase at which an observed failure occurred, or `None` when
/// there was no failure.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum FailPhase {
    Unknown,
    DnsResolution,
    TcpConnect,
    TlsHandshake,
    HttpRequest,
    DataTransfer,
}

/// Network-level context snapshot.
///
/// Privacy invariants:
/// - No SSID, BSSID, or cell-tower identifier.
/// - `asn` is a raw `u32`; no carrier/ISP name is stored.
/// - No precise location.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct NetProfile {
    /// Autonomous System Number of the network path (0 = unknown).
    pub asn: u32,
    pub access_type: AccessType,
    pub ip_family: IpFamily,
    pub dns_class: DnsClass,
    /// Whether UDP port 443 (QUIC) appeared reachable during the last probe.
    pub udp443_ok: bool,
    /// Whether TCP port 443 appeared reachable during the last probe.
    pub tcp443_ok: bool,
    /// Phase at which the last observed failure occurred, or `None` for
    /// no-failure / not-yet-observed.
    pub observed_fail_phase: Option<FailPhase>,
}

// ── Host-level context ───────────────────────────────────────────────────────

/// Coarse application-protocol family for the target host.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
#[non_exhaustive]
pub enum AppFamily {
    Unknown,
    Browser,
    Streaming,
    SocialMedia,
    Messaging,
    Gaming,
    FileTransfer,
    Voip,
}

/// Per-host context.
///
/// Privacy invariants:
/// - Only the eTLD+1 is stored (e.g., `"example.com"`), never a full URL,
///   full hostname, path, query string, or fragment.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct HostProfile {
    /// Effective TLD plus one label (e.g., `"youtube.com"`).
    /// Never a full hostname like `video.youtube.com`.
    pub etld_plus_1: String,
    /// Whether the host advertised HTTP/3 (Alt-Svc or HTTPS RR with `h3`).
    pub h3_advertised: bool,
    /// Whether an HTTPS SVCB/HTTPS resource record was observed for this host.
    pub https_rr_present: bool,
    /// Whether ECH (Encrypted Client Hello) capability was detected.
    pub ech_capable: bool,
    pub app_family: AppFamily,
}

// ── Bandit arm statistics ────────────────────────────────────────────────────

/// Per-arm statistics used by the privacy-preserving strategy learner.
///
/// All timing values use an opaque monotonic epoch (milliseconds since the
/// evolver was started), never wall-clock time. `last_success_at` is stored
/// as an opaque `u64` epoch-relative millisecond offset.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct ArmStats {
    /// Stable identifier for this desync strategy arm.
    pub arm_id: String,
    /// Beta-distribution shape parameter α (successes + prior).
    pub alpha: f64,
    /// Beta-distribution shape parameter β (failures + prior).
    pub beta: f64,
    /// Estimated p50 time-to-first-byte in milliseconds (0 = no data).
    pub p50_ttfb_ms: u32,
    /// Mean bytes of overhead introduced by this arm per connection.
    pub bytes_overhead: u32,
    /// Number of consecutive failures since the last success.
    pub repeated_failures: u32,
    /// Evolver-monotonic millisecond offset of the most recent success,
    /// or `None` if the arm has never succeeded.
    pub last_success_at: Option<u64>,
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    // ── SCHEMA_VERSION ───────────────────────────────────────────────────────

    #[test]
    fn schema_version_constant_exists_and_is_nonzero() {
        const { assert!(SCHEMA_VERSION > 0, "SCHEMA_VERSION must be a positive integer") };
    }

    #[test]
    fn schema_version_is_serialized_in_wrapper() {
        // Verify the constant can be embedded in JSON (used by callers that
        // stamp it alongside a serialized profile).
        let json = serde_json::json!({ "schema_version": SCHEMA_VERSION });
        assert_eq!(json["schema_version"], SCHEMA_VERSION);
    }

    // ── NetProfile round-trips ───────────────────────────────────────────────

    fn sample_net_profile() -> NetProfile {
        NetProfile {
            asn: 12345,
            access_type: AccessType::Wifi,
            ip_family: IpFamily::V4,
            dns_class: DnsClass::Doh,
            udp443_ok: true,
            tcp443_ok: true,
            observed_fail_phase: Some(FailPhase::TlsHandshake),
        }
    }

    #[test]
    fn net_profile_serde_round_trip() {
        let original = sample_net_profile();
        let json = serde_json::to_string(&original).expect("serialize NetProfile");
        let decoded: NetProfile = serde_json::from_str(&json).expect("deserialize NetProfile");
        assert_eq!(original, decoded);
    }

    #[test]
    fn net_profile_no_fail_phase_round_trip() {
        let profile = NetProfile { observed_fail_phase: None, ..sample_net_profile() };
        let json = serde_json::to_string(&profile).expect("serialize");
        let decoded: NetProfile = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(decoded.observed_fail_phase, None);
    }

    #[test]
    fn net_profile_asn_is_u32() {
        // Privacy invariant: ASN stored as raw number, no string/carrier name.
        let profile = sample_net_profile();
        let json = serde_json::to_value(&profile).expect("to_value");
        assert!(json["asn"].is_number(), "asn must serialize as a JSON number");
    }

    // ── HostProfile round-trips ──────────────────────────────────────────────

    fn sample_host_profile() -> HostProfile {
        HostProfile {
            etld_plus_1: "youtube.com".to_owned(),
            h3_advertised: true,
            https_rr_present: true,
            ech_capable: false,
            app_family: AppFamily::Streaming,
        }
    }

    #[test]
    fn host_profile_serde_round_trip() {
        let original = sample_host_profile();
        let json = serde_json::to_string(&original).expect("serialize HostProfile");
        let decoded: HostProfile = serde_json::from_str(&json).expect("deserialize HostProfile");
        assert_eq!(original, decoded);
    }

    #[test]
    fn host_profile_etld_plus_1_is_string() {
        let profile = sample_host_profile();
        let json = serde_json::to_value(&profile).expect("to_value");
        assert!(json["etld_plus_1"].is_string(), "etld_plus_1 must serialize as a JSON string");
    }

    // ── ArmStats round-trips ─────────────────────────────────────────────────

    fn sample_arm_stats() -> ArmStats {
        ArmStats {
            arm_id: "split".to_owned(),
            alpha: 3.0,
            beta: 1.5,
            p50_ttfb_ms: 120,
            bytes_overhead: 48,
            repeated_failures: 0,
            last_success_at: Some(90_000),
        }
    }

    #[test]
    fn arm_stats_serde_round_trip() {
        let original = sample_arm_stats();
        let json = serde_json::to_string(&original).expect("serialize ArmStats");
        let decoded: ArmStats = serde_json::from_str(&json).expect("deserialize ArmStats");
        assert_eq!(original, decoded);
    }

    #[test]
    fn arm_stats_no_last_success_round_trip() {
        let stats = ArmStats { last_success_at: None, ..sample_arm_stats() };
        let json = serde_json::to_string(&stats).expect("serialize");
        let decoded: ArmStats = serde_json::from_str(&json).expect("deserialize");
        assert_eq!(decoded.last_success_at, None);
    }

    // ── Enum exhaustiveness snapshots ────────────────────────────────────────
    //
    // These tests force a compile error if a new variant is added without
    // updating the match. They also verify every variant survives a serde
    // round-trip.

    #[test]
    fn access_type_all_variants_round_trip() {
        let variants = [
            AccessType::Unknown,
            AccessType::Wifi,
            AccessType::CellularNr,
            AccessType::CellularLte,
            AccessType::CellularOther,
            AccessType::Ethernet,
            AccessType::Vpn,
        ];
        for v in variants {
            // Exhaustiveness compile-check via match.
            let _label = match v {
                AccessType::Unknown => "unknown",
                AccessType::Wifi => "wifi",
                AccessType::CellularNr => "cellular_nr",
                AccessType::CellularLte => "cellular_lte",
                AccessType::CellularOther => "cellular_other",
                AccessType::Ethernet => "ethernet",
                AccessType::Vpn => "vpn",
                // #[non_exhaustive] means we need a wildcard in external
                // crates, but within the defining module the match IS
                // exhaustive — no wildcard needed here.
            };
            let json = serde_json::to_string(&v).unwrap();
            let decoded: AccessType = serde_json::from_str(&json).unwrap();
            assert_eq!(v, decoded);
        }
    }

    #[test]
    fn ip_family_all_variants_round_trip() {
        let variants = [IpFamily::Unknown, IpFamily::V4, IpFamily::V6, IpFamily::DualStack];
        for v in variants {
            let _label = match v {
                IpFamily::Unknown => "unknown",
                IpFamily::V4 => "v4",
                IpFamily::V6 => "v6",
                IpFamily::DualStack => "dual_stack",
            };
            let json = serde_json::to_string(&v).unwrap();
            let decoded: IpFamily = serde_json::from_str(&json).unwrap();
            assert_eq!(v, decoded);
        }
    }

    #[test]
    fn dns_class_all_variants_round_trip() {
        let variants = [DnsClass::Unknown, DnsClass::SystemDefault, DnsClass::Doh, DnsClass::Dot, DnsClass::Doq];
        for v in variants {
            let _label = match v {
                DnsClass::Unknown => "unknown",
                DnsClass::SystemDefault => "system_default",
                DnsClass::Doh => "doh",
                DnsClass::Dot => "dot",
                DnsClass::Doq => "doq",
            };
            let json = serde_json::to_string(&v).unwrap();
            let decoded: DnsClass = serde_json::from_str(&json).unwrap();
            assert_eq!(v, decoded);
        }
    }

    #[test]
    fn fail_phase_all_variants_round_trip() {
        let variants = [
            FailPhase::Unknown,
            FailPhase::DnsResolution,
            FailPhase::TcpConnect,
            FailPhase::TlsHandshake,
            FailPhase::HttpRequest,
            FailPhase::DataTransfer,
        ];
        for v in variants {
            let _label = match v {
                FailPhase::Unknown => "unknown",
                FailPhase::DnsResolution => "dns_resolution",
                FailPhase::TcpConnect => "tcp_connect",
                FailPhase::TlsHandshake => "tls_handshake",
                FailPhase::HttpRequest => "http_request",
                FailPhase::DataTransfer => "data_transfer",
            };
            let json = serde_json::to_string(&v).unwrap();
            let decoded: FailPhase = serde_json::from_str(&json).unwrap();
            assert_eq!(v, decoded);
        }
    }

    #[test]
    fn app_family_all_variants_round_trip() {
        let variants = [
            AppFamily::Unknown,
            AppFamily::Browser,
            AppFamily::Streaming,
            AppFamily::SocialMedia,
            AppFamily::Messaging,
            AppFamily::Gaming,
            AppFamily::FileTransfer,
            AppFamily::Voip,
        ];
        for v in variants {
            let _label = match v {
                AppFamily::Unknown => "unknown",
                AppFamily::Browser => "browser",
                AppFamily::Streaming => "streaming",
                AppFamily::SocialMedia => "social_media",
                AppFamily::Messaging => "messaging",
                AppFamily::Gaming => "gaming",
                AppFamily::FileTransfer => "file_transfer",
                AppFamily::Voip => "voip",
            };
            let json = serde_json::to_string(&v).unwrap();
            let decoded: AppFamily = serde_json::from_str(&json).unwrap();
            assert_eq!(v, decoded);
        }
    }
}
