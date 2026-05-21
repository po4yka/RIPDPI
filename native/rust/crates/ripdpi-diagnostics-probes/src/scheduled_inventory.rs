//! Scheduled-probe inventory and Probe-trait migration drift artifact.
//!
//! This module is the task-5 drift artifact for the diagnostics
//! "Probe-trait migration". It is the compile-time list of every scheduled
//! *connectivity* stage the `ripdpi-monitor-engine` runs, paired with
//! whether that stage has a backing [`crate::Probe`] implementation in this
//! crate.
//!
//! ## What is in scope
//!
//! [`SCHEDULED_PROBE_INVENTORY`] enumerates the 9 connectivity stage
//! runners. Each [`ScheduledProbeStage::backing`] either names the backing
//! probe's [`crate::Probe::id`] (via [`ProbeTraitBacking::Backed`]) or
//! marks the stage as not yet migrated ([`ProbeTraitBacking::NotBacked`]).
//! The `Backed(...)` rows reference the real `*_PROBE_ID` consts from the
//! probe modules, so this list is compile-time-coupled to the probe
//! implementations — deleting or renaming a probe const breaks this file.
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
//! ## Migration completion gate
//!
//! The unified `ProbeDescriptor` table must NOT be built until every row
//! in this inventory is [`ProbeTraitBacking::Backed`]. As of this artifact
//! the `network_environment` stage is the last remaining un-backed stage;
//! [`unbacked_stages`] returns exactly that one `probe_type`.

use crate::probes::circumvention_reachability::CIRCUMVENTION_REACHABILITY_PROBE_ID;
use crate::probes::dns_integrity::DNS_INTEGRITY_PROBE_ID;
use crate::probes::domain_reachability::DOMAIN_REACHABILITY_PROBE_ID;
use crate::probes::mtproto_reachability::MTPROTO_REACHABILITY_PROBE_ID;
use crate::probes::quic_probe::QUIC_PROBE_OFFLINE_PROBE_ID;
use crate::probes::service_reachability::SERVICE_REACHABILITY_PROBE_ID;
use crate::probes::tcp_fat_header::TCP_FAT_HEADER_PROBE_ID;
use crate::probes::throughput::THROUGHPUT_PROBE_ID;

/// Whether a scheduled stage has a backing [`crate::Probe`] implementation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbeTraitBacking {
    /// The stage is backed by a [`crate::Probe`]. Carries the backing
    /// probe's [`crate::Probe::id`].
    Backed(&'static str),
    /// The stage has not yet been migrated to the [`crate::Probe`] trait.
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

/// Every scheduled connectivity stage runner in the `ripdpi-monitor-engine`,
/// paired with its Probe-trait backing status.
///
/// The 4 strategy stage runners are intentionally absent — see the module
/// documentation.
pub const SCHEDULED_PROBE_INVENTORY: &[ScheduledProbeStage] = &[
    ScheduledProbeStage {
        probe_type: "network_environment",
        runner: "EnvironmentRunner",
        backing: ProbeTraitBacking::NotBacked,
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
    ScheduledProbeStage {
        probe_type: "telegram",
        runner: "TelegramRunner",
        backing: ProbeTraitBacking::Backed(MTPROTO_REACHABILITY_PROBE_ID),
    },
    ScheduledProbeStage {
        probe_type: "throughput_window",
        runner: "ThroughputRunner",
        backing: ProbeTraitBacking::Backed(THROUGHPUT_PROBE_ID),
    },
];

/// The `probe_type`s of every scheduled stage that does not yet have a
/// backing [`crate::Probe`] implementation.
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
    fn unbacked_stages_is_frozen_expectation() {
        // This list changes ONLY when a scheduled stage is migrated to the
        // Probe trait (a row flips to `Backed`) or a new un-backed stage is
        // added. Any other diff here is a regression.
        assert_eq!(unbacked_stages(), vec!["network_environment"]);
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
    fn inventory_has_exactly_nine_rows() {
        assert_eq!(SCHEDULED_PROBE_INVENTORY.len(), 9);
    }
}
