/// Static descriptor for one probe-lane adapter module.
///
/// A `LaneAdapter` is read-only inventory metadata: it names an adapter
/// module in this crate and records which `ripdpi-diagnostics-*` crate the
/// adapter wraps. It is not a dispatch handle — the engine calls the adapter
/// functions directly; the table exists for documentation and architecture
/// audit. See `docs/architecture/DIAGNOSTICS_ARCHITECTURE.md`, "Probe &
/// candidate registration flow".
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LaneAdapter {
    /// Short, stable adapter name (matches the module name under `adapters`).
    pub name: &'static str,
    /// Fully qualified path of the adapter module within this crate.
    pub module_path: &'static str,
    /// The `ripdpi-diagnostics-*` crate whose probe logic the adapter wraps.
    pub source_crate: &'static str,
}

/// Inventory of every probe-lane adapter, one [`LaneAdapter`] row per module.
///
/// This is the wiring seam between the `ripdpi-diagnostics-*` probe crates
/// and `ripdpi-monitor-engine`. Adding a probe crate that the engine consumes
/// means adding an `adapters` module and a row here.
pub const LANE_ADAPTERS: &[LaneAdapter] = &[
    LaneAdapter {
        name: "blockpage_fingerprints",
        module_path: "ripdpi_monitor_lane_adapter::adapters::blockpage_fingerprints",
        source_crate: "ripdpi-diagnostics-http",
    },
    LaneAdapter {
        name: "candidates",
        module_path: "ripdpi_monitor_lane_adapter::adapters::candidates",
        source_crate: "ripdpi-diagnostics-candidates",
    },
    LaneAdapter {
        name: "cdn_ech",
        module_path: "ripdpi_monitor_lane_adapter::adapters::cdn_ech",
        source_crate: "ripdpi-diagnostics-dns",
    },
    LaneAdapter {
        name: "classification",
        module_path: "ripdpi_monitor_lane_adapter::adapters::classification",
        source_crate: "ripdpi-diagnostics-classification",
    },
    LaneAdapter {
        name: "connectivity",
        module_path: "ripdpi_monitor_lane_adapter::adapters::connectivity",
        source_crate: "ripdpi-diagnostics-runner",
    },
    LaneAdapter {
        name: "http",
        module_path: "ripdpi_monitor_lane_adapter::adapters::http",
        source_crate: "ripdpi-diagnostics-http",
    },
    LaneAdapter {
        name: "observations",
        module_path: "ripdpi_monitor_lane_adapter::adapters::observations",
        source_crate: "ripdpi-diagnostics-classification",
    },
    LaneAdapter {
        name: "strategy",
        module_path: "ripdpi_monitor_lane_adapter::adapters::strategy",
        source_crate: "ripdpi-diagnostics-runner",
    },
    LaneAdapter {
        name: "telegram",
        module_path: "ripdpi_monitor_lane_adapter::adapters::telegram",
        source_crate: "ripdpi-diagnostics-telegram",
    },
    LaneAdapter {
        name: "tls",
        module_path: "ripdpi_monitor_lane_adapter::adapters::tls",
        source_crate: "ripdpi-diagnostics-tls",
    },
    LaneAdapter {
        name: "transport",
        module_path: "ripdpi_monitor_lane_adapter::adapters::transport",
        source_crate: "ripdpi-diagnostics-transport",
    },
];
