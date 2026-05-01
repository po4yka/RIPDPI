use ripdpi_proxy_config::NetworkSnapshot;

/// Derives a stable identity string from a `NetworkSnapshot` that changes only
/// when the physical network actually switches (WiFi->cellular, different SSID,
/// different carrier). Minor metadata changes (RSSI, traffic counters, MTU) are
/// intentionally excluded.
pub(crate) fn snapshot_identity(snapshot: &NetworkSnapshot) -> String {
    let mut id = snapshot.transport.clone();
    if let Some(ref wifi) = snapshot.wifi {
        id.push(':');
        id.push_str(&wifi.ssid_hash);
    }
    if let Some(ref cellular) = snapshot.cellular {
        id.push(':');
        id.push_str(&cellular.operator_code);
        id.push(':');
        id.push_str(&cellular.generation);
    }
    for dns in &snapshot.dns_servers {
        id.push(',');
        id.push_str(dns);
    }
    id
}
