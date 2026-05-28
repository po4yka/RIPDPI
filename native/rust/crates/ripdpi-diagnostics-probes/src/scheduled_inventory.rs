//! Scheduled-probe inventory and Probe-trait parity table.
//!
//! This module is the compile-time list of every scheduled *connectivity*
//! stage the `ripdpi-monitor-engine` runs, paired with whether that stage has
//! a backing [`crate::Probe`] implementation in this crate.
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
//! ## Probe-trait migration: complete
//!
//! Every connectivity stage is [`ProbeTraitBacking::Backed`], so
//! [`unbacked_stages`] returns an empty list. The unified descriptor table the
//! migration was building toward now exists in [`crate::probe_descriptor`];
//! [`ScheduledProbeStage::descriptor`] resolves a row to its
//! [`crate::probe_descriptor::ProbeDescriptor`], and drift tests pin the two
//! tables together.

use crate::probes::circumvention_reachability::CIRCUMVENTION_REACHABILITY_PROBE_ID;
use crate::probes::dns_integrity::DNS_INTEGRITY_PROBE_ID;
use crate::probes::domain_reachability::DOMAIN_REACHABILITY_PROBE_ID;
use crate::probes::mtproto_reachability::MTPROTO_REACHABILITY_PROBE_ID;
use crate::probes::network_environment::NETWORK_ENVIRONMENT_PROBE_ID;
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
        // Every scheduled connectivity stage is now Probe-backed, so this is
        // empty. It changes ONLY when a new un-backed stage is added; any
        // other diff here is a regression.
        assert_eq!(unbacked_stages(), Vec::<&'static str>::new());
    }

    #[test]
    fn every_scheduled_stage_is_backed() {
        for stage in SCHEDULED_PROBE_INVENTORY {
            assert!(
                matches!(stage.backing, ProbeTraitBacking::Backed(_)),
                "scheduled stage {} is not Probe-backed",
                stage.probe_type,
            );
        }
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

    #[test]
    fn every_stage_resolves_its_descriptor() {
        for stage in SCHEDULED_PROBE_INVENTORY {
            let descriptor = stage.descriptor().unwrap_or_else(|| panic!("no descriptor for {}", stage.probe_type));
            assert_eq!(descriptor.scheduled_probe_type, stage.probe_type);
            assert_eq!(descriptor.runner, stage.runner);
        }
    }
}
