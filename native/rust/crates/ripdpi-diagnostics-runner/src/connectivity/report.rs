use ripdpi_diagnostics_tls::tls::{EchConfigSource, TlsObservation, ech_status_label, preferred_tls_observation};
use ripdpi_diagnostics_transport::transport::TransportConfig;

use crate::types::*;

/// Project measurements from exactly one connection under the given attempt prefix.
pub fn append_tls_attempt_details(
    details: &mut Vec<ProbeDetail>,
    prefix: &str,
    attempt: &TlsObservation,
    transport: &TransportConfig,
) {
    let peer_role = match (transport, attempt.connected_addr, attempt.transport_open_ms) {
        (TransportConfig::Direct { .. }, Some(_), _) => Some("direct_peer"),
        (TransportConfig::Socks5 { .. }, Some(_), _) => Some("socks_requested_destination"),
        (TransportConfig::Socks5 { .. }, None, Some(_)) => Some("socks_destination_unknown"),
        _ => None,
    };
    let socket_leg = match transport {
        TransportConfig::Direct { .. } => "direct",
        TransportConfig::Socks5 { .. } => "client_to_proxy",
    };
    let values = [
        ("Status", Some(attempt.status.clone())),
        ("Version", Some(attempt.version.clone().unwrap_or_else(|| "unknown".to_string()))),
        ("Error", Some(attempt.error.clone().unwrap_or_else(|| "none".to_string()))),
        ("FailureStage", Some(attempt.failure_stage.map_or("none", |stage| stage.as_str()).to_string())),
        (
            "FailureDurationMs",
            Some(attempt.failure_duration_ms.map_or_else(|| "none".to_string(), |ms| ms.to_string())),
        ),
        ("TransportOpenMs", attempt.transport_open_ms.map(|ms| ms.to_string())),
        ("HandshakeMs", attempt.tls_handshake_ms.map(|ms| ms.to_string())),
        ("CertChainLength", attempt.cert_chain_length.map(|length| length.to_string())),
        ("CertificateVerification", Some(attempt.certificate_verification.as_str().to_string())),
        ("CertIssuer", attempt.cert_issuer.clone()),
        ("LocalSocketTtl", attempt.local_socket_ttl.map(|ttl| ttl.to_string())),
        ("LocalSocketTtlLeg", attempt.local_socket_ttl.map(|_| socket_leg.to_string())),
        ("Ja3Fingerprint", attempt.ja3_fingerprint.clone()),
        ("ClientHelloCapture", Some(attempt.client_hello_capture.as_str().to_string())),
        ("AlertCode", attempt.tls_alert_code.map(|code| code.to_string())),
        ("AlertSource", attempt.tls_alert_code.map(|_| "unknown".to_string())),
        ("AlertDescription", attempt.tls_alert_description.clone()),
        ("ServerHelloReceived", attempt.tls_server_hello_received.map(|answer| answer.to_string())),
        ("PeerResponseReceived", attempt.tls_peer_response_after_client_hello.map(|answer| answer.to_string())),
        ("ConnectedIp", attempt.connected_addr.map(|addr| addr.ip().to_string())),
        ("ConnectedPort", attempt.connected_addr.map(|addr| addr.port().to_string())),
        ("PeerRole", peer_role.map(str::to_string)),
        ("CdnProvider", attempt.cdn_provider.clone()),
        ("ResolutionDetail", attempt.ech_resolution_detail.clone()),
        ("Acceptance", attempt.ech_status.map(ech_status_label)),
        ("ConfigSource", attempt.ech_config_source.map(|source| source.as_str().to_string())),
        ("RetrySkippedReason", attempt.ech_retry_skipped_reason.map(str::to_string)),
        ("ConfigCdn", attempt.ech_config_source.and_then(EchConfigSource::cdn_provider).map(str::to_string)),
    ];
    details.extend(
        values
            .into_iter()
            .filter_map(|(suffix, value)| value.map(|value| ProbeDetail { key: format!("{prefix}{suffix}"), value })),
    );
    if let Some(initial) = &attempt.ech_initial_attempt {
        let initial_prefix = format!("{prefix}Initial");
        let values = [
            ("Status", Some("tls_handshake_failed".to_string())),
            ("Acceptance", Some("rejected".to_string())),
            ("ConnectedIp", Some(initial.connected_addr.ip().to_string())),
            ("ConnectedPort", Some(initial.connected_addr.port().to_string())),
            (
                "PeerRole",
                Some(
                    match transport {
                        TransportConfig::Direct { .. } => "direct_peer",
                        TransportConfig::Socks5 { .. } => "socks_requested_destination",
                    }
                    .to_string(),
                ),
            ),
            ("TransportOpenMs", Some(initial.transport_open_ms.to_string())),
            ("HandshakeMs", initial.tls_handshake_ms.map(|ms| ms.to_string())),
            ("Ja3Fingerprint", initial.ja3_fingerprint.clone()),
            ("ClientHelloCapture", Some(initial.client_hello_capture.as_str().to_string())),
            ("ConfigSource", Some(initial.ech_config_source.as_str().to_string())),
            ("ConfigCdn", initial.ech_config_source.cdn_provider().map(str::to_string)),
            ("Error", Some(initial.error.clone())),
            ("CertificateVerification", Some(initial.certificate_verification.as_str().to_string())),
        ];
        details.extend(values.into_iter().filter_map(|(suffix, value)| {
            value.map(|value| ProbeDetail { key: format!("{initial_prefix}{suffix}"), value })
        }));
    }
}

/// Select the observed attempt that owns the aggregate peer and TLS summary.
pub fn select_tls_report_attempt<'a>(
    outcome: &str,
    tls13: &'a TlsObservation,
    tls12: &'a TlsObservation,
    tls_ech: &'a TlsObservation,
) -> (&'static str, &'a TlsObservation) {
    if outcome == "tls_ech_only"
        || (outcome == "tls_cert_invalid"
            && tls_ech.certificate_anomaly
            && !tls13.certificate_anomaly
            && !tls12.certificate_anomaly)
    {
        return ("tlsEch", tls_ech);
    }
    if std::ptr::eq(preferred_tls_observation(tls13, tls12), tls12) {
        return ("tls12", tls12);
    }
    if tls13.status != "tls_ok" && !tls13.certificate_anomaly {
        if (tls13.tls_peer_response_after_client_hello != Some(true)
            && tls12.tls_peer_response_after_client_hello == Some(true))
            || (tls13.transport_open_ms.is_none() && tls12.transport_open_ms.is_some())
        {
            return ("tls12", tls12);
        }
        if tls13.transport_open_ms.is_none() && tls_ech.transport_open_ms.is_some() {
            return ("tlsEch", tls_ech);
        }
    }
    ("tls13", tls13)
}

/// Describe which destination the TLS version comparison covers.
pub fn append_tls_comparison_details(
    details: &mut Vec<ProbeDetail>,
    outcome: &str,
    tls13: &TlsObservation,
    tls12: &TlsObservation,
    transport: &TransportConfig,
) {
    if outcome != "tls_version_split" {
        return;
    }
    let scope = match (tls13.connected_addr, tls12.connected_addr, transport) {
        (Some(a), Some(b), TransportConfig::Direct { .. }) if a == b => "same_peer",
        (Some(_), Some(_), TransportConfig::Direct { .. }) => "mixed_peers",
        (Some(a), Some(b), TransportConfig::Socks5 { .. }) if a == b => "same_requested_destination",
        (Some(_), Some(_), TransportConfig::Socks5 { .. }) => "mixed_requested_destinations",
        _ => "unknown_peer",
    };
    details.push(ProbeDetail { key: "tlsVersionComparisonScope".to_string(), value: scope.to_string() });
}

pub fn summarize_probe_event(probe: &ProbeResult) -> String {
    match probe.probe_type.as_str() {
        "dns_integrity" => format!(
            "{} -> {} (udp={}, encrypted={}, resolver={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "udpAddresses"),
            probe_detail_value(probe, "encryptedAddresses"),
            probe_detail_value(probe, "encryptedResolverId"),
        ),
        "domain_reachability" => format!(
            "{} -> {} (tls13={}, tls12={}, ech={}, http={})",
            probe.target,
            probe.outcome,
            probe_detail_value(probe, "tls13Status"),
            probe_detail_value(probe, "tls12Status"),
            probe_detail_value(probe, "tlsEchStatus"),
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

pub fn probe_detail_value<'a>(probe: &'a ProbeResult, key: &str) -> &'a str {
    probe.details.iter().find(|detail| detail.key == key).map_or("unknown", |detail| detail.value.as_str())
}

/// Build a synthetic `network_environment` ProbeResult from the OS-provided NetworkSnapshot.
/// Returns `None` when no snapshot is present (backward compat: no snapshot = no probe).
pub fn build_network_environment_probe(snapshot: Option<&ripdpi_proxy_config::NetworkSnapshot>) -> Option<ProbeResult> {
    let snap = snapshot?;
    let outcome = if snap.transport == "none" {
        if snap.vpn_service_was_active { "vpn_tunnel_down" } else { "network_unavailable" }
    } else {
        "network_available"
    };
    let mut details = vec![
        ProbeDetail { key: "transport".to_string(), value: snap.transport.clone() },
        ProbeDetail { key: "validated".to_string(), value: snap.validated.to_string() },
        ProbeDetail { key: "captivePortal".to_string(), value: snap.captive_portal.to_string() },
        ProbeDetail { key: "metered".to_string(), value: snap.metered.to_string() },
        ProbeDetail { key: "privateDnsMode".to_string(), value: snap.private_dns_mode.clone() },
        ProbeDetail { key: "dnsServerCount".to_string(), value: snap.dns_servers.len().to_string() },
        ProbeDetail { key: "capturedAtMs".to_string(), value: snap.captured_at_ms.to_string() },
        ProbeDetail { key: "vpnServiceWasActive".to_string(), value: snap.vpn_service_was_active.to_string() },
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
            vpn_service_was_active: false,
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

    #[test]
    fn network_environment_probe_emits_vpn_tunnel_down_when_vpn_service_was_active() {
        let snapshot = NetworkSnapshot {
            transport: "none".to_string(),
            vpn_service_was_active: true,
            ..NetworkSnapshot::default()
        };

        let probe = build_network_environment_probe(Some(&snapshot)).expect("probe");

        assert_eq!(probe.outcome, "vpn_tunnel_down");
        assert_eq!(probe_detail_value(&probe, "vpnServiceWasActive"), "true");
    }

    #[test]
    fn network_environment_probe_emits_network_unavailable_when_vpn_was_not_active() {
        let snapshot = NetworkSnapshot { transport: "none".to_string(), ..NetworkSnapshot::default() };

        let probe = build_network_environment_probe(Some(&snapshot)).expect("probe");

        assert_eq!(probe.outcome, "network_unavailable");
        assert_eq!(probe_detail_value(&probe, "vpnServiceWasActive"), "false");
    }

    /// The `network_environment` Probe-trait adapter
    /// (`ripdpi-diagnostics-probes`) re-presents `build_network_environment_probe`'s
    /// `ProbeResult` without changing its status or fields.
    #[test]
    fn network_environment_probe_feeds_the_probe_trait_adapter() {
        use ripdpi_diagnostics_probes::{NetworkEnvironmentProbe, Probe};

        let snapshots = [
            NetworkSnapshot { transport: "wifi".to_string(), validated: true, ..NetworkSnapshot::default() },
            NetworkSnapshot { transport: "none".to_string(), ..NetworkSnapshot::default() },
            NetworkSnapshot {
                transport: "none".to_string(),
                vpn_service_was_active: true,
                ..NetworkSnapshot::default()
            },
        ];

        for snapshot in snapshots {
            let expected = build_network_environment_probe(Some(&snapshot)).expect("probe");
            let adapter = NetworkEnvironmentProbe::new(expected.clone());
            let carried = adapter.report();

            assert_eq!(carried.probe_type, "network_environment");
            assert_eq!(carried.probe_type, expected.probe_type);
            assert_eq!(carried.target, expected.target);
            assert_eq!(carried.outcome, expected.outcome);
            let carried_details: Vec<(&str, &str)> =
                carried.details.iter().map(|detail| (detail.key.as_str(), detail.value.as_str())).collect();
            let expected_details: Vec<(&str, &str)> =
                expected.details.iter().map(|detail| (detail.key.as_str(), detail.value.as_str())).collect();
            assert_eq!(carried_details, expected_details);
            assert_eq!(adapter.id(), "network_environment");
        }
    }
}
