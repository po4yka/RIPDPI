use serde::{Deserialize, Serialize};

// --- Android OS network state snapshot ---

/// A compact snapshot of Android OS network state, captured from ConnectivityManager,
/// NetworkCapabilities, LinkProperties, TelephonyManager, and TrafficStats.
/// All fields use `#[serde(default)]` for forward-compatible deserialization.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct NetworkSnapshot {
    /// Physical transport: "wifi", "cellular", "ethernet", "vpn", "none", "unknown"
    #[serde(default)]
    pub transport: String,
    /// NET_CAPABILITY_VALIDATED
    #[serde(default)]
    pub validated: bool,
    /// NET_CAPABILITY_CAPTIVE_PORTAL
    #[serde(default)]
    pub captive_portal: bool,
    /// !NET_CAPABILITY_NOT_METERED
    #[serde(default)]
    pub metered: bool,
    /// "system" (default/opportunistic) or strict hostname from Private DNS settings
    #[serde(default)]
    pub private_dns_mode: String,
    /// DNS servers from LinkProperties.getDnsServers()
    #[serde(default)]
    pub dns_servers: Vec<String>,
    /// Present when transport is "cellular"
    #[serde(default)]
    pub cellular: Option<CellularSnapshot>,
    /// Present when transport is "wifi"
    #[serde(default)]
    pub wifi: Option<WifiSnapshot>,
    /// LinkProperties.getMtu() when the platform reports a positive value
    #[serde(default)]
    pub mtu: Option<u32>,
    /// TrafficStats.getUidTxBytes(uid) at capture time
    #[serde(default)]
    pub traffic_tx_bytes: u64,
    /// TrafficStats.getUidRxBytes(uid) at capture time
    #[serde(default)]
    pub traffic_rx_bytes: u64,
    /// System.currentTimeMillis() at capture time
    #[serde(default)]
    pub captured_at_ms: u64,
    /// True when the VPN service was configured in VPN mode but halted at snapshot capture
    /// time, meaning transport == "none" because the VPN tunnel went down rather than because
    /// the physical network is absent.
    #[serde(default)]
    pub vpn_service_was_active: bool,
}

/// Cellular network details, populated when transport is "cellular".
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct CellularSnapshot {
    /// Radio generation: "2g", "3g", "4g", "5g", "unknown"
    #[serde(default)]
    pub generation: String,
    /// Whether the device is roaming
    #[serde(default)]
    pub roaming: bool,
    /// MCC+MNC of the serving network operator
    #[serde(default)]
    pub operator_code: String,
    /// Diagnostics-style mobile network type: "LTE", "NR", "IWLAN", "unknown", etc.
    #[serde(default)]
    pub data_network_type: String,
    /// ServiceState.state normalized to "in_service", "out_of_service", etc.
    #[serde(default)]
    pub service_state: String,
    /// Carrier ID when the platform reports a non-negative value
    #[serde(default)]
    pub carrier_id: Option<i32>,
    /// SignalStrength.level
    #[serde(default)]
    pub signal_level: Option<i32>,
    /// First reported cell signal strength dBm
    #[serde(default)]
    pub signal_dbm: Option<i32>,
}

/// Wi-Fi network details, populated when transport is "wifi".
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "camelCase")]
pub struct WifiSnapshot {
    /// Frequency band: "2.4ghz", "5ghz", "6ghz", "unknown"
    #[serde(default)]
    pub frequency_band: String,
    /// SHA-256 hex of the sanitized SSID (privacy-preserving; never raw SSID)
    #[serde(default)]
    pub ssid_hash: String,
    /// Wi-Fi frequency in MHz when the platform reports a positive value
    #[serde(default)]
    pub frequency_mhz: Option<i32>,
    /// RSSI in dBm when the platform reports a sane value
    #[serde(default)]
    pub rssi_dbm: Option<i32>,
    /// Wi-Fi link speed in Mbps when the platform reports a positive value
    #[serde(default)]
    pub link_speed_mbps: Option<i32>,
    /// Wi-Fi RX link speed in Mbps when available
    #[serde(default)]
    pub rx_link_speed_mbps: Option<i32>,
    /// Wi-Fi TX link speed in Mbps when available
    #[serde(default)]
    pub tx_link_speed_mbps: Option<i32>,
    /// Diagnostics-style channel width label: "20 MHz", "80 MHz", "unknown", etc.
    #[serde(default)]
    pub channel_width: String,
    /// Diagnostics-style standard label: "802.11ax", "legacy", "unknown", etc.
    #[serde(default)]
    pub wifi_standard: String,
}
