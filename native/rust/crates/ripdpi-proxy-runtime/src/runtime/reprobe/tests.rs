use ripdpi_proxy_runtime_adapter::model::proxy_config::NetworkSnapshot;

use super::identity::snapshot_identity;
use super::tls_probe::build_minimal_client_hello;
use super::tracker::ReprobeTracker;

#[test]
fn reprobe_tracker_skips_initial_snapshot() {
    let tracker = ReprobeTracker::new();
    let snap = NetworkSnapshot { transport: "wifi".to_string(), validated: true, ..Default::default() };
    assert!(!tracker.check_snapshot(&snap));
}

#[test]
fn reprobe_tracker_detects_transport_change() {
    let tracker = ReprobeTracker::new();
    let wifi = NetworkSnapshot { transport: "wifi".to_string(), validated: true, ..Default::default() };
    let cellular = NetworkSnapshot { transport: "cellular".to_string(), validated: true, ..Default::default() };
    assert!(!tracker.check_snapshot(&wifi));
    assert!(tracker.check_snapshot(&cellular));
}

#[test]
fn reprobe_tracker_ignores_same_identity() {
    let tracker = ReprobeTracker::new();
    let snap = NetworkSnapshot { transport: "wifi".to_string(), validated: true, ..Default::default() };
    assert!(!tracker.check_snapshot(&snap));
    assert!(!tracker.check_snapshot(&snap));
}

#[test]
fn reprobe_tracker_detects_ssid_change() {
    use ripdpi_proxy_runtime_adapter::model::proxy_config::WifiSnapshot;

    let tracker = ReprobeTracker::new();
    let snap1 = NetworkSnapshot {
        transport: "wifi".to_string(),
        wifi: Some(WifiSnapshot { ssid_hash: "aaa".to_string(), ..Default::default() }),
        ..Default::default()
    };
    let snap2 = NetworkSnapshot {
        transport: "wifi".to_string(),
        wifi: Some(WifiSnapshot { ssid_hash: "bbb".to_string(), ..Default::default() }),
        ..Default::default()
    };
    assert!(!tracker.check_snapshot(&snap1));
    assert!(tracker.check_snapshot(&snap2));
}

#[test]
fn reprobe_tracker_ignores_rssi_change() {
    use ripdpi_proxy_runtime_adapter::model::proxy_config::WifiSnapshot;

    let tracker = ReprobeTracker::new();
    let snap1 = NetworkSnapshot {
        transport: "wifi".to_string(),
        wifi: Some(WifiSnapshot { ssid_hash: "aaa".to_string(), rssi_dbm: Some(-70), ..Default::default() }),
        ..Default::default()
    };
    let snap2 = NetworkSnapshot {
        transport: "wifi".to_string(),
        wifi: Some(WifiSnapshot { ssid_hash: "aaa".to_string(), rssi_dbm: Some(-50), ..Default::default() }),
        ..Default::default()
    };
    assert!(!tracker.check_snapshot(&snap1));
    assert!(!tracker.check_snapshot(&snap2), "RSSI change should not trigger reprobe");
}

#[test]
fn snapshot_identity_includes_transport_and_wifi() {
    use ripdpi_proxy_runtime_adapter::model::proxy_config::WifiSnapshot;

    let snap = NetworkSnapshot {
        transport: "wifi".to_string(),
        wifi: Some(WifiSnapshot { ssid_hash: "abc123".to_string(), ..Default::default() }),
        ..Default::default()
    };
    let id = snapshot_identity(&snap);
    assert!(id.contains("wifi"));
    assert!(id.contains("abc123"));
}

#[test]
fn snapshot_identity_includes_cellular_operator() {
    use ripdpi_proxy_runtime_adapter::model::proxy_config::CellularSnapshot;

    let snap = NetworkSnapshot {
        transport: "cellular".to_string(),
        cellular: Some(CellularSnapshot {
            operator_code: "25001".to_string(),
            generation: "4g".to_string(),
            ..Default::default()
        }),
        ..Default::default()
    };
    let id = snapshot_identity(&snap);
    assert!(id.contains("cellular"));
    assert!(id.contains("25001"));
}

#[test]
fn build_minimal_client_hello_starts_with_tls_record_header() {
    let hello = build_minimal_client_hello("example.com");
    assert_eq!(hello[0], 0x16, "content type must be Handshake");
    assert_eq!(hello[1], 0x03, "major version");
    assert_eq!(hello[2], 0x01, "minor version (TLS 1.0)");
    // The 4th handshake byte (after the 5-byte record header) should be
    // ClientHello type = 0x01.
    assert_eq!(hello[5], 0x01, "handshake type must be ClientHello");
}

#[test]
fn build_minimal_client_hello_contains_sni() {
    let hello = build_minimal_client_hello("test.example.org");
    assert!(hello.windows(b"test.example.org".len()).any(|w| w == b"test.example.org"));
}
