//! `ProbeDescriptor` — static metadata for the scheduled connectivity probes.
//!
//! With the Probe-trait migration complete (every row of
//! [`crate::scheduled_inventory::SCHEDULED_PROBE_INVENTORY`] is
//! [`crate::scheduled_inventory::ProbeTraitBacking::Backed`]), this module
//! adds the unified descriptor table the migration was building toward — one
//! [`ProbeDescriptor`] row per scheduled connectivity stage, analogous to the
//! `RELAY_TRANSPORT_REGISTRATIONS` table in `ripdpi-relay-core`.
//!
//! ## Scope
//!
//! This is **metadata, not a dispatcher**. The table carries only fields that
//! are already stable and non-behavioral — the probe id, family, the
//! monitor-engine `probe_type` and runner name, the path-mode requirement,
//! and a short label. Nothing here changes probe scheduling or execution; the
//! `ripdpi-monitor-engine` stage loop is untouched.
//!
//! The 4 *strategy* stage runners are intentionally excluded, exactly as in
//! [`crate::scheduled_inventory`] — they consume `StrategyCandidateSpec`
//! inputs, not the [`crate::Probe`] trait. Drift tests pin this table to the
//! scheduled inventory one-to-one.

use ripdpi_diagnostics_contracts::ProbeTaskFamily;

use crate::probes::circumvention_reachability::CIRCUMVENTION_REACHABILITY_PROBE_ID;
use crate::probes::dns_integrity::DNS_INTEGRITY_PROBE_ID;
use crate::probes::domain_reachability::DOMAIN_REACHABILITY_PROBE_ID;
use crate::probes::mtproto_reachability::MTPROTO_REACHABILITY_PROBE_ID;
use crate::probes::network_environment::NETWORK_ENVIRONMENT_PROBE_ID;
use crate::probes::quic_probe::QUIC_PROBE_OFFLINE_PROBE_ID;
use crate::probes::service_reachability::SERVICE_REACHABILITY_PROBE_ID;
use crate::probes::tcp_fat_header::TCP_FAT_HEADER_PROBE_ID;
use crate::probes::throughput::THROUGHPUT_PROBE_ID;

/// The scan path mode a probe stage requires.
///
/// Path mode (`RAW_PATH` vs `IN_PATH`) is a scan-level choice — see
/// `docs/architecture/DIAGNOSTICS_ARCHITECTURE.md`, "Raw-path vs in-path
/// requirements". Every scheduled connectivity stage today runs in whichever
/// mode the scan selects, so all rows are [`ProbePathRequirement::Either`];
/// the other variants exist for descriptors that may later pin a mode.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProbePathRequirement {
    /// The stage requires a raw-path scan (VPN stopped, direct connection).
    RawPath,
    /// The stage requires an in-path scan (through the active proxy / VPN).
    InPath,
    /// The stage runs in either path mode; the scan selects.
    Either,
    /// The path requirement has not been determined.
    Unknown,
}

/// Static metadata for one scheduled connectivity probe stage.
///
/// Purely descriptive: it pairs a [`crate::Probe`] id with the monitor-engine
/// scheduling identifiers (`scheduled_probe_type`, `runner`) and stable
/// presentation metadata. It drives no scheduling or execution.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProbeDescriptor {
    /// The backing probe's [`crate::Probe::id`] — a stable telemetry / golden
    /// contract. May differ from [`Self::scheduled_probe_type`].
    pub id: &'static str,
    /// The probe family the backing [`crate::Probe`] reports.
    pub family: ProbeTaskFamily,
    /// The `ProbeResult.probe_type` the monitor-engine stage emits — the key
    /// used by [`crate::scheduled_inventory::SCHEDULED_PROBE_INVENTORY`].
    pub scheduled_probe_type: &'static str,
    /// The monitor-engine stage-runner type name.
    pub runner: &'static str,
    /// The scan path mode this stage requires.
    pub path_requirement: ProbePathRequirement,
    /// A short human-readable label for inventory and audit surfaces.
    pub label: &'static str,
}

/// Static descriptor table — one row per scheduled connectivity stage, in the
/// same order as [`crate::scheduled_inventory::SCHEDULED_PROBE_INVENTORY`].
///
/// The 4 strategy stage runners are intentionally excluded. Drift tests pin
/// this table to the scheduled inventory.
pub const PROBE_DESCRIPTORS: &[ProbeDescriptor] = &[
    ProbeDescriptor {
        id: NETWORK_ENVIRONMENT_PROBE_ID,
        family: ProbeTaskFamily::Service,
        scheduled_probe_type: "network_environment",
        runner: "EnvironmentRunner",
        path_requirement: ProbePathRequirement::Either,
        label: "Network environment",
    },
    ProbeDescriptor {
        id: DNS_INTEGRITY_PROBE_ID,
        family: ProbeTaskFamily::Dns,
        scheduled_probe_type: "dns_integrity",
        runner: "DnsRunner",
        path_requirement: ProbePathRequirement::Either,
        label: "DNS integrity",
    },
    ProbeDescriptor {
        id: DOMAIN_REACHABILITY_PROBE_ID,
        family: ProbeTaskFamily::Web,
        scheduled_probe_type: "domain_reachability",
        runner: "WebRunner",
        path_requirement: ProbePathRequirement::Either,
        label: "Domain reachability",
    },
    ProbeDescriptor {
        id: QUIC_PROBE_OFFLINE_PROBE_ID,
        family: ProbeTaskFamily::Quic,
        scheduled_probe_type: "quic_reachability",
        runner: "QuicRunner",
        path_requirement: ProbePathRequirement::Either,
        label: "QUIC reachability",
    },
    ProbeDescriptor {
        id: TCP_FAT_HEADER_PROBE_ID,
        family: ProbeTaskFamily::Tcp,
        scheduled_probe_type: "tcp_fat_header",
        runner: "TcpRunner",
        path_requirement: ProbePathRequirement::Either,
        label: "TCP fat header",
    },
    ProbeDescriptor {
        id: SERVICE_REACHABILITY_PROBE_ID,
        family: ProbeTaskFamily::Service,
        scheduled_probe_type: "service_reachability",
        runner: "ServiceRunner",
        path_requirement: ProbePathRequirement::Either,
        label: "Service reachability",
    },
    ProbeDescriptor {
        id: CIRCUMVENTION_REACHABILITY_PROBE_ID,
        family: ProbeTaskFamily::Circumvention,
        scheduled_probe_type: "circumvention_reachability",
        runner: "CircumventionRunner",
        path_requirement: ProbePathRequirement::Either,
        label: "Circumvention reachability",
    },
    ProbeDescriptor {
        id: MTPROTO_REACHABILITY_PROBE_ID,
        family: ProbeTaskFamily::Telegram,
        scheduled_probe_type: "telegram",
        runner: "TelegramRunner",
        path_requirement: ProbePathRequirement::Either,
        label: "Telegram reachability",
    },
    ProbeDescriptor {
        id: THROUGHPUT_PROBE_ID,
        family: ProbeTaskFamily::Throughput,
        scheduled_probe_type: "throughput_window",
        runner: "ThroughputRunner",
        path_requirement: ProbePathRequirement::Either,
        label: "Throughput window",
    },
];

/// Look up a descriptor by its [`crate::Probe::id`].
pub fn descriptor_by_id(id: &str) -> Option<&'static ProbeDescriptor> {
    PROBE_DESCRIPTORS.iter().find(|descriptor| descriptor.id == id)
}

/// Look up a descriptor by its scheduled `ProbeResult.probe_type`.
pub fn descriptor_by_probe_type(probe_type: &str) -> Option<&'static ProbeDescriptor> {
    PROBE_DESCRIPTORS.iter().find(|descriptor| descriptor.scheduled_probe_type == probe_type)
}

/// Every descriptor whose backing probe reports `family`.
pub fn descriptors_for_family(family: ProbeTaskFamily) -> Vec<&'static ProbeDescriptor> {
    PROBE_DESCRIPTORS.iter().filter(|descriptor| descriptor.family == family).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scheduled_inventory::{ProbeTraitBacking, SCHEDULED_PROBE_INVENTORY};

    /// The 4 strategy stage runners — intentionally excluded from both the
    /// descriptor table and the scheduled inventory.
    const STRATEGY_RUNNERS: &[&str] =
        &["StrategyDnsBaselineRunner", "StrategyTcpRunner", "StrategyQuicRunner", "StrategyRecommendationRunner"];

    #[test]
    fn every_scheduled_inventory_row_has_one_descriptor() {
        for stage in SCHEDULED_PROBE_INVENTORY {
            let matches: Vec<_> = PROBE_DESCRIPTORS
                .iter()
                .filter(|descriptor| descriptor.scheduled_probe_type == stage.probe_type)
                .collect();
            assert_eq!(matches.len(), 1, "expected exactly one descriptor for {}", stage.probe_type);
            assert!(descriptor_by_probe_type(stage.probe_type).is_some());
        }
    }

    #[test]
    fn every_descriptor_maps_to_one_scheduled_inventory_row() {
        for descriptor in PROBE_DESCRIPTORS {
            let rows: Vec<_> = SCHEDULED_PROBE_INVENTORY
                .iter()
                .filter(|stage| stage.probe_type == descriptor.scheduled_probe_type)
                .collect();
            assert_eq!(rows.len(), 1, "expected one inventory row for descriptor {}", descriptor.id);
            let row = rows[0];
            assert_eq!(row.runner, descriptor.runner, "runner drift for {}", descriptor.scheduled_probe_type);
            assert_eq!(
                row.backing,
                ProbeTraitBacking::Backed(descriptor.id),
                "backing-id drift for {}",
                descriptor.scheduled_probe_type,
            );
        }
    }

    #[test]
    fn every_descriptor_id_is_non_empty_and_unique() {
        let mut seen: Vec<&str> = Vec::new();
        for descriptor in PROBE_DESCRIPTORS {
            assert!(!descriptor.id.is_empty(), "empty descriptor id");
            assert!(!seen.contains(&descriptor.id), "duplicate descriptor id: {}", descriptor.id);
            seen.push(descriptor.id);
        }
    }

    #[test]
    fn no_duplicate_scheduled_probe_type() {
        let mut seen: Vec<&str> = Vec::new();
        for descriptor in PROBE_DESCRIPTORS {
            assert!(
                !seen.contains(&descriptor.scheduled_probe_type),
                "duplicate scheduled_probe_type: {}",
                descriptor.scheduled_probe_type,
            );
            seen.push(descriptor.scheduled_probe_type);
        }
    }

    #[test]
    fn strategy_runners_remain_out_of_scope() {
        assert_eq!(PROBE_DESCRIPTORS.len(), 9, "descriptor table must cover only the 9 connectivity stages");
        for descriptor in PROBE_DESCRIPTORS {
            assert!(
                !STRATEGY_RUNNERS.contains(&descriptor.runner),
                "strategy runner {} must not have a descriptor",
                descriptor.runner,
            );
        }
    }

    #[test]
    fn lookup_helpers_resolve_descriptors() {
        // The probe id and the scheduled probe_type are distinct lookup keys:
        // quic's probe id differs from its scheduled probe_type.
        let quic = descriptor_by_id(QUIC_PROBE_OFFLINE_PROBE_ID).expect("quic by id");
        assert_eq!(quic.scheduled_probe_type, "quic_reachability");
        assert_eq!(descriptor_by_probe_type("quic_reachability"), Some(quic));
        assert!(descriptor_by_id("quic_reachability").is_none());
        assert!(descriptor_by_probe_type("not_a_stage").is_none());

        // Service is the one family with two scheduled stages.
        assert_eq!(descriptors_for_family(ProbeTaskFamily::Service).len(), 2);
        assert_eq!(descriptors_for_family(ProbeTaskFamily::Dns).len(), 1);
    }
}
