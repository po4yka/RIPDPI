//! Scheduled-probe inventory and Probe-trait parity table.
//!
//! This module is the compile-time list of every scheduled *connectivity*
//! stage the `ripdpi-monitor-engine` runs, paired with whether that stage has
//! a backing [`crate::Probe`] implementation in this crate.
//!
//! Every connectivity runner has an inventory row. The scheduled Telegram
//! stage uses its native HTTP, TCP and WebSocket probes; it does not dispatch
//! the standalone offline TCP probe. Backed rows reference the actual
//! production probe identifier.
//!
//! ## What is intentionally out of scope
//!
//! The 4 *strategy* stage runners — `StrategyDnsBaselineRunner`,
//! `StrategyTcpRunner`, `StrategyQuicRunner`, and
//! `StrategyRecommendationRunner` — are intentionally NOT listed here.
//! They consume `StrategyCandidateSpec` inputs rather than the
//! [`crate::Probe`] trait, so they are not part of this migration and must
//! not be added to the inventory.
//!
use crate::probes::circumvention_reachability::CIRCUMVENTION_REACHABILITY_PROBE_ID;
use crate::probes::dns_integrity::DNS_INTEGRITY_PROBE_ID;
use crate::probes::doh_json_survey::DOH_JSON_SURVEY_PROBE_ID;
use crate::probes::domain_reachability::DOMAIN_REACHABILITY_PROBE_ID;
use crate::probes::http3::HTTP3_PROBE_ID;
use crate::probes::ip_family::IP_FAMILY_PROBE_ID;
use crate::probes::network_environment::NETWORK_ENVIRONMENT_PROBE_ID;
use crate::probes::pmtu::PMTU_PROBE_ID;
use crate::probes::quic_probe::QUIC_PROBE_OFFLINE_PROBE_ID;
use crate::probes::quic_version_matrix::QUIC_VERSION_MATRIX_PROBE_ID;
use crate::probes::selective_availability::SELECTIVE_AVAILABILITY_PROBE_ID;
use crate::probes::service_reachability::SERVICE_REACHABILITY_PROBE_ID;
use crate::probes::tcp_fat_header::TCP_FAT_HEADER_PROBE_ID;
use crate::probes::throughput::THROUGHPUT_PROBE_ID;
use crate::probes::tls_hop_comparison::TLS_HOP_COMPARISON_PROBE_ID;

/// Whether a scheduled stage has a backing [`crate::Probe`] implementation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbeTraitBacking {
    /// The stage is backed by a [`crate::Probe`]. Carries the backing
    /// probe's [`crate::Probe::id`].
    Backed(&'static str),
    /// The native stage runs without the [`crate::Probe`] trait.
    NotBacked,
}

/// One scheduled connectivity stage in the `ripdpi-monitor-engine`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ScheduledProbeStage {
    /// The `ProbeResult.probe_type` / artifact identifier the
    /// monitor-engine stage emits.
    pub probe_type: &'static str,
    /// The monitor-engine stage-runner name.
    pub runner: &'static str,
    /// Probe-trait backing status for this stage.
    pub backing: ProbeTraitBacking,
}

impl ScheduledProbeStage {
    /// The [`crate::probe_descriptor::ProbeDescriptor`] metadata for this
    /// scheduled stage, resolved by `probe_type`.
    ///
    /// Always `Some` for a stage drawn from [`SCHEDULED_PROBE_INVENTORY`]; a
    /// `None` means the descriptor table has drifted from this inventory,
    /// which the drift tests in [`crate::probe_descriptor`] reject.
    pub fn descriptor(&self) -> Option<&'static crate::probe_descriptor::ProbeDescriptor> {
        crate::probe_descriptor::descriptor_by_probe_type(self.probe_type)
    }
}

/// Every scheduled connectivity stage runner in the `ripdpi-monitor-engine`,
/// paired with its Probe-trait backing status.
///
/// The 4 strategy stage runners are intentionally absent — see the module
/// documentation.
pub const SCHEDULED_PROBE_INVENTORY: &[ScheduledProbeStage] = &[
    ScheduledProbeStage {
        probe_type: "network_environment",
        runner: "EnvironmentRunner",
        backing: ProbeTraitBacking::Backed(NETWORK_ENVIRONMENT_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "dns_integrity",
        runner: "DnsRunner",
        backing: ProbeTraitBacking::Backed(DNS_INTEGRITY_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "domain_reachability",
        runner: "WebRunner",
        backing: ProbeTraitBacking::Backed(DOMAIN_REACHABILITY_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "quic_reachability",
        runner: "QuicRunner",
        backing: ProbeTraitBacking::Backed(QUIC_PROBE_OFFLINE_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "tcp_fat_header",
        runner: "TcpRunner",
        backing: ProbeTraitBacking::Backed(TCP_FAT_HEADER_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "service_reachability",
        runner: "ServiceRunner",
        backing: ProbeTraitBacking::Backed(SERVICE_REACHABILITY_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "circumvention_reachability",
        runner: "CircumventionRunner",
        backing: ProbeTraitBacking::Backed(CIRCUMVENTION_REACHABILITY_PROBE_ID),
    },
    ScheduledProbeStage { probe_type: "telegram", runner: "TelegramRunner", backing: ProbeTraitBacking::NotBacked },
    ScheduledProbeStage {
        probe_type: "throughput_window",
        runner: "ThroughputRunner",
        backing: ProbeTraitBacking::Backed(THROUGHPUT_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "selective_availability",
        runner: "SelectiveMatrixRunner",
        backing: ProbeTraitBacking::Backed(SELECTIVE_AVAILABILITY_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "ip_family",
        runner: "IpFamilyRunner",
        backing: ProbeTraitBacking::Backed(IP_FAMILY_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "http3",
        runner: "Http3Runner",
        backing: ProbeTraitBacking::Backed(HTTP3_PROBE_ID),
    },
    ScheduledProbeStage { probe_type: "pmtu", runner: "PmtuRunner", backing: ProbeTraitBacking::Backed(PMTU_PROBE_ID) },
    ScheduledProbeStage {
        probe_type: "tls_hop_comparison",
        runner: "TlsHopComparisonRunner",
        backing: ProbeTraitBacking::Backed(TLS_HOP_COMPARISON_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "quic_version_matrix",
        runner: "QuicVersionMatrixRunner",
        backing: ProbeTraitBacking::Backed(QUIC_VERSION_MATRIX_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "doh_json_survey",
        runner: "DohJsonSurveyRunner",
        backing: ProbeTraitBacking::Backed(DOH_JSON_SURVEY_PROBE_ID),
    },
];

/// The `probe_type`s of every scheduled stage without a backing
/// [`crate::Probe`] implementation.
pub fn unbacked_stages() -> Vec<&'static str> {
    SCHEDULED_PROBE_INVENTORY
        .iter()
        .filter(|stage| matches!(stage.backing, ProbeTraitBacking::NotBacked))
        .map(|stage| stage.probe_type)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_duplicate_probe_types() {
        let mut seen: Vec<&str> = Vec::new();
        for stage in SCHEDULED_PROBE_INVENTORY {
            assert!(!seen.contains(&stage.probe_type), "duplicate probe_type: {}", stage.probe_type);
            seen.push(stage.probe_type);
        }
    }

    #[test]
    fn scheduled_telegram_has_no_offline_probe_backing() {
        assert_eq!(unbacked_stages(), ["telegram"]);
    }

    #[test]
    fn every_backed_stage_has_non_empty_id() {
        for stage in SCHEDULED_PROBE_INVENTORY {
            if let ProbeTraitBacking::Backed(id) = stage.backing {
                assert!(!id.is_empty(), "empty backing id for {}", stage.probe_type);
            }
        }
    }

    #[test]
    fn inventory_has_exactly_sixteen_rows() {
        assert_eq!(SCHEDULED_PROBE_INVENTORY.len(), 16);
    }

    #[test]
    fn every_stage_resolves_its_descriptor() {
        for stage in SCHEDULED_PROBE_INVENTORY {
            let descriptor = stage.descriptor().unwrap_or_else(|| panic!("no descriptor for {}", stage.probe_type));
            assert_eq!(descriptor.scheduled_probe_type, stage.probe_type);
            assert_eq!(descriptor.runner, stage.runner);
        }
    }
}
