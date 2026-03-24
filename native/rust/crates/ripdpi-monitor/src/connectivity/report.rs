use crate::types::*;

pub(crate) fn summarize_probe_event(probe: &ProbeResult) -> String {
    match probe.probe_type.as_str() {
        "dns_integrity" => format!(
            "{} -> {} (udp={}, doh={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "udpAddresses"),
            probe_detail_value(probe, "dohAddresses"),
        ),
        "domain_reachability" => format!(
            "{} -> {} (tls13={}, tls12={}, http={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "tls13Status"),
            probe_detail_value(probe, "tls12Status"),
            probe_detail_value(probe, "httpStatus"),
        ),
        "tcp_fat_header" => format!(
            "{} -> {} (sni={}, bytes={}, responses={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "selectedSni"),
            probe_detail_value(probe, "bytesSent"),
            probe_detail_value(probe, "responsesSeen"),
        ),
        "quic_reachability" => format!(
            "{} -> {} (status={}, latency={}ms)",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "status"),
            probe_detail_value(probe, "latencyMs"),
        ),
        "service_reachability" => format!(
            "{} -> {} (bootstrap={}, media={}, gateway={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "bootstrapStatus"),
            probe_detail_value(probe, "mediaStatus"),
            probe_detail_value(probe, "gatewayStatus"),
        ),
        "circumvention_reachability" => format!(
            "{} -> {} (bootstrap={}, handshake={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "bootstrapStatus"),
            probe_detail_value(probe, "handshakeStatus"),
        ),
        "throughput_window" => {
            format!("{} -> {} (median={}bps)", probe.target, probe.outcome, probe_detail_value(probe, "medianBps"),)
        }
        _ => format!("{} -> {}", probe.target, probe.outcome),
    }
}

pub(crate) fn probe_detail_value<'a>(probe: &'a ProbeResult, key: &str) -> &'a str {
    probe.details.iter().find(|detail| detail.key == key).map_or("unknown", |detail| detail.value.as_str())
}

/// Build a synthetic `network_environment` ProbeResult from the OS-provided NetworkSnapshot.
/// Returns `None` when no snapshot is present (backward compat: no snapshot = no probe).
pub(crate) fn build_network_environment_probe(
    snapshot: Option<&ripdpi_proxy_config::NetworkSnapshot>,
) -> Option<ProbeResult> {
    let snap = snapshot?;
    let outcome = if snap.transport == "none" { "network_unavailable" } else { "network_available" };
    let mut details = vec![
        ProbeDetail { key: "transport".to_string(), value: snap.transport.clone() },
        ProbeDetail { key: "validated".to_string(), value: snap.validated.to_string() },
        ProbeDetail { key: "captivePortal".to_string(), value: snap.captive_portal.to_string() },
        ProbeDetail { key: "metered".to_string(), value: snap.metered.to_string() },
        ProbeDetail { key: "privateDnsMode".to_string(), value: snap.private_dns_mode.clone() },
        ProbeDetail { key: "dnsServerCount".to_string(), value: snap.dns_servers.len().to_string() },
        ProbeDetail { key: "capturedAtMs".to_string(), value: snap.captured_at_ms.to_string() },
    ];
    if let Some(mtu) = snap.mtu {
        details.push(ProbeDetail { key: "mtu".to_string(), value: mtu.to_string() });
    }
    if let Some(ref cell) = snap.cellular {
        details.push(ProbeDetail { key: "cellularGeneration".to_string(), value: cell.generation.clone() });
        details.push(ProbeDetail { key: "cellularRoaming".to_string(), value: cell.roaming.to_string() });
        push_network_detail(&mut details, "cellularDataNetworkType", &cell.data_network_type);
        push_network_detail(&mut details, "cellularServiceState", &cell.service_state);
        if let Some(carrier_id) = cell.carrier_id {
            details.push(ProbeDetail { key: "cellularCarrierId".to_string(), value: carrier_id.to_string() });
        }
        if let Some(signal_level) = cell.signal_level {
            details.push(ProbeDetail { key: "cellularSignalLevel".to_string(), value: signal_level.to_string() });
        }
        if let Some(signal_dbm) = cell.signal_dbm {
            details.push(ProbeDetail { key: "cellularSignalDbm".to_string(), value: signal_dbm.to_string() });
        }
    }
    if let Some(ref wifi) = snap.wifi {
        details.push(ProbeDetail { key: "wifiFrequencyBand".to_string(), value: wifi.frequency_band.clone() });
        if let Some(frequency_mhz) = wifi.frequency_mhz {
            details.push(ProbeDetail { key: "wifiFrequencyMhz".to_string(), value: frequency_mhz.to_string() });
        }
        if let Some(rssi_dbm) = wifi.rssi_dbm {
            details.push(ProbeDetail { key: "wifiRssiDbm".to_string(), value: rssi_dbm.to_string() });
        }
        if let Some(link_speed_mbps) = wifi.link_speed_mbps {
            details.push(ProbeDetail { key: "wifiLinkSpeedMbps".to_string(), value: link_speed_mbps.to_string() });
        }
        if let Some(rx_link_speed_mbps) = wifi.rx_link_speed_mbps {
            details.push(ProbeDetail { key: "wifiRxLinkSpeedMbps".to_string(), value: rx_link_speed_mbps.to_string() });
        }
        if let Some(tx_link_speed_mbps) = wifi.tx_link_speed_mbps {
            details.push(ProbeDetail { key: "wifiTxLinkSpeedMbps".to_string(), value: tx_link_speed_mbps.to_string() });
        }
        push_network_detail(&mut details, "wifiChannelWidth", &wifi.channel_width);
        push_network_detail(&mut details, "wifiStandard", &wifi.wifi_standard);
    }
    Some(ProbeResult {
        probe_type: "network_environment".to_string(),
        target: snap.transport.clone(),
        outcome: outcome.to_string(),
        details,
    })
}

fn push_network_detail(details: &mut Vec<ProbeDetail>, key: &str, value: &str) {
    if !value.is_empty() && value != "unknown" {
        details.push(ProbeDetail { key: key.to_string(), value: value.to_string() });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_proxy_config::{CellularSnapshot, NetworkSnapshot, WifiSnapshot};

    #[test]
    fn network_environment_probe_includes_extended_wifi_and_cellular_fields() {
        let snapshot = NetworkSnapshot {
            transport: "wifi".to_string(),
            validated: true,
            captive_portal: false,
            metered: false,
            private_dns_mode: "system".to_string(),
            dns_servers: vec!["1.1.1.1".to_string()],
            cellular: Some(CellularSnapshot {
                generation: "5g".to_string(),
                roaming: true,
                operator_code: "25001".to_string(),
                data_network_type: "NR".to_string(),
                service_state: "in_service".to_string(),
                carrier_id: Some(42),
                signal_level: Some(4),
                signal_dbm: Some(-95),
            }),
            wifi: Some(WifiSnapshot {
                frequency_band: "5ghz".to_string(),
                ssid_hash: "cafebabe".to_string(),
                frequency_mhz: Some(5180),
                rssi_dbm: Some(-58),
                link_speed_mbps: Some(866),
                rx_link_speed_mbps: Some(780),
                tx_link_speed_mbps: Some(720),
                channel_width: "80 MHz".to_string(),
                wifi_standard: "802.11ax".to_string(),
            }),
            mtu: Some(1500),
            traffic_tx_bytes: 10,
            traffic_rx_bytes: 20,
            captured_at_ms: 1_700_000_000_000,
        };

        let probe = build_network_environment_probe(Some(&snapshot)).expect("probe");

        assert_eq!(probe_detail_value(&probe, "wifiFrequencyMhz"), "5180");
        assert_eq!(probe_detail_value(&probe, "wifiRssiDbm"), "-58");
        assert_eq!(probe_detail_value(&probe, "wifiLinkSpeedMbps"), "866");
        assert_eq!(probe_detail_value(&probe, "wifiRxLinkSpeedMbps"), "780");
        assert_eq!(probe_detail_value(&probe, "wifiTxLinkSpeedMbps"), "720");
        assert_eq!(probe_detail_value(&probe, "wifiChannelWidth"), "80 MHz");
        assert_eq!(probe_detail_value(&probe, "wifiStandard"), "802.11ax");
        assert_eq!(probe_detail_value(&probe, "cellularDataNetworkType"), "NR");
        assert_eq!(probe_detail_value(&probe, "cellularServiceState"), "in_service");
        assert_eq!(probe_detail_value(&probe, "cellularCarrierId"), "42");
        assert_eq!(probe_detail_value(&probe, "cellularSignalLevel"), "4");
        assert_eq!(probe_detail_value(&probe, "cellularSignalDbm"), "-95");
    }

    #[test]
    fn network_environment_probe_keeps_old_snapshots_valid() {
        let snapshot = NetworkSnapshot {
            transport: "wifi".to_string(),
            wifi: Some(WifiSnapshot { frequency_band: "5ghz".to_string(), ..WifiSnapshot::default() }),
            ..NetworkSnapshot::default()
        };

        let probe = build_network_environment_probe(Some(&snapshot)).expect("probe");

        assert_eq!(probe_detail_value(&probe, "wifiFrequencyBand"), "5ghz");
        assert_eq!(probe_detail_value(&probe, "wifiChannelWidth"), "unknown");
        assert_eq!(probe_detail_value(&probe, "cellularSignalDbm"), "unknown");
    }
}
