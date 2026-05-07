#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct LaneAdapter {
    pub name: &'static str,
    pub module_path: &'static str,
    pub source_crate: &'static str,
}

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
