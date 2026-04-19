// Lightweight network re-probe: when the OS network fingerprint changes
// (WiFi -> cellular, different WiFi SSID, etc.), spawn a background thread that
// tests a small set of known-blocked domains with the current desync strategy.
// If most probes fail with DPI signatures, the strategy evolver and adaptive
// tuning cache are reset so the runtime can re-learn appropriate parameters
// for the new network.
//
// Design constraints:
// - Must not block the listener accept loop.
// - Must not fire on minor network metadata changes (signal strength, traffic
//   counters) -- only when the network identity actually changes.
// - Reuses the same raw TLS ClientHello probe pattern as the connect path.

use std::io::{self, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Mutex as StdMutex;
use std::thread;
use std::time::Duration;

use ripdpi_proxy_config::NetworkSnapshot;

use super::routing::connect_socket;
use super::state::flush_autolearn_updates;
use super::state::RuntimeState;
use crate::strategy_evolver::StrategyEvolver;

/// Domains to probe. These are commonly DPI-blocked targets that exercise TLS
/// handshake classification. Using 3 domains keeps the total probe budget small.
const PROBE_DOMAINS: &[(&str, &str)] =
    &[("youtube.com", "142.250.74.206"), ("discord.com", "162.159.128.233"), ("telegram.org", "149.154.167.99")];

/// Per-domain TCP connect + TLS handshake timeout.
const PROBE_TIMEOUT: Duration = Duration::from_secs(5);

/// Total deadline for the entire reprobe batch.
const TOTAL_DEADLINE: Duration = Duration::from_secs(20);

/// Minimum number of probe failures (with DPI signatures) required to declare
/// the current strategy invalid on the new network.
const FAILURE_THRESHOLD: usize = 2;

// ---------------------------------------------------------------------------
// Network identity fingerprint
// ---------------------------------------------------------------------------

/// Derives a stable identity string from a `NetworkSnapshot` that changes only
/// when the physical network actually switches (WiFi->cellular, different SSID,
/// different carrier). Minor metadata changes (RSSI, traffic counters, MTU) are
/// intentionally excluded.
fn snapshot_identity(snapshot: &NetworkSnapshot) -> String {
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

// ---------------------------------------------------------------------------
// Tracker
// ---------------------------------------------------------------------------

/// Tracks the last observed network identity so we only reprobe when the
/// network actually changes, not on every `NetworkSnapshot` push.
pub(super) struct ReprobeTracker {
    last_identity: StdMutex<Option<String>>,
}

impl ReprobeTracker {
    pub(super) fn new() -> Self {
        Self { last_identity: StdMutex::new(None) }
    }

    /// Returns `true` if the network identity changed since the last call,
    /// indicating that a reprobe should be scheduled. Returns `false` for the
    /// initial assignment (proxy just started) and for unchanged snapshots.
    pub(super) fn check_snapshot(&self, snapshot: &NetworkSnapshot) -> bool {
        let identity = snapshot_identity(snapshot);
        let mut last = self.last_identity.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if last.as_deref() == Some(&identity) {
            return false;
        }
        let is_initial = last.is_none();
        *last = Some(identity);
        // Don't reprobe on initial snapshot (proxy just started).
        !is_initial
    }
}

// ---------------------------------------------------------------------------
// Spawn
// ---------------------------------------------------------------------------

/// Checks the current network snapshot and spawns a background reprobe thread
/// if the network identity changed. Called from the listener accept loop.
///
/// The function is intentionally fire-and-forget: the spawned thread runs
/// independently and logs results via tracing.
pub(super) fn maybe_spawn_reprobe(state: &RuntimeState) {
    let Some(control) = &state.control else {
        return;
    };
    if !state.config.host_autolearn.network_reprobe_enabled {
        return;
    }
    let Some(snapshot) = control.current_network_snapshot() else {
        return;
    };

    // Skip reprobe when the network is not usable.
    if snapshot.captive_portal || !snapshot.validated {
        tracing::debug!(
            "network_reprobe: skipping (captive_portal={}, validated={})",
            snapshot.captive_portal,
            snapshot.validated
        );
        return;
    }

    if !state.reprobe_tracker.check_snapshot(&snapshot) {
        return;
    }

    if let Ok(mut cache) = state.cache.write() {
        let cleared = cache.clear_connection_cache(&state.config);
        flush_autolearn_updates(state, &mut cache);
        if cleared > 0 {
            tracing::info!("network_reprobe: cleared {cleared} adaptive route cache entries after network handover");
        }
    }

    tracing::info!("network_reprobe: network identity changed, scheduling reprobe");

    let config = state.config.clone();
    let evolver = state.strategy_evolver.clone();
    let adaptive_tuning = state.adaptive_tuning.clone();

    thread::Builder::new()
        .name("ripdpi-reprobe".into())
        .spawn(move || {
            run_reprobe(&config, &evolver, &adaptive_tuning);
        })
        .ok();
}

// ---------------------------------------------------------------------------
// Probe logic
// ---------------------------------------------------------------------------

/// Perform the actual reprobe: connect to each probe domain on port 443 and
/// attempt a raw TLS ClientHello. A failure is classified as a DPI signature
/// if the connection is reset, times out, or receives a TLS alert before the
/// ServerHello completes.
fn run_reprobe(
    config: &ripdpi_config::RuntimeConfig,
    evolver: &crate::sync::Arc<crate::sync::RwLock<StrategyEvolver>>,
    adaptive_tuning: &crate::sync::Arc<crate::sync::RwLock<crate::adaptive_tuning::AdaptivePlannerResolver>>,
) {
    let deadline = std::time::Instant::now() + TOTAL_DEADLINE;
    let mut failures = 0usize;
    let mut successes = 0usize;

    for &(domain, ip_str) in PROBE_DOMAINS {
        if std::time::Instant::now() >= deadline {
            tracing::debug!("network_reprobe: total deadline exceeded after {successes} ok, {failures} fail");
            break;
        }

        let ip: IpAddr = match ip_str.parse() {
            Ok(addr) => addr,
            Err(_) => continue,
        };
        let target = SocketAddr::new(ip, 443);

        match probe_tls_handshake(target, domain, config.process.protect_path.as_deref()) {
            ProbeResult::Success => {
                tracing::debug!("network_reprobe: {domain} passed");
                successes += 1;
            }
            ProbeResult::DpiFailure(reason) => {
                tracing::debug!("network_reprobe: {domain} failed ({reason})");
                failures += 1;
            }
            ProbeResult::NetworkError(reason) => {
                // Network errors (DNS, routing) are not DPI -- don't count them.
                tracing::debug!("network_reprobe: {domain} network error ({reason}), skipping");
            }
        }
    }

    if failures >= FAILURE_THRESHOLD {
        tracing::info!(
            "network_reprobe: strategy_mismatch ({failures}/{} failed), resetting evolver and adaptive cache",
            PROBE_DOMAINS.len()
        );
        // Reset the strategy evolver so it re-explores on the new network.
        if let Ok(mut ev) = evolver.write() {
            *ev = StrategyEvolver::new(ev.is_enabled(), ev.epsilon());
        }
        // Flush adaptive tuning cache so per-flow hints are re-learned.
        if let Ok(mut tuning) = adaptive_tuning.write() {
            tuning.clear_all();
        }
    } else {
        tracing::info!("network_reprobe: passed ({successes}/{} ok)", PROBE_DOMAINS.len());
    }
}

enum ProbeResult {
    Success,
    DpiFailure(&'static str),
    NetworkError(&'static str),
}

/// Attempt a raw TLS ClientHello to `target` and read the first response bytes.
/// Returns `Success` if we get a ServerHello (TLS record type 0x16, handshake
/// type 0x02), `DpiFailure` if we see signs of DPI interference.
fn probe_tls_handshake(target: SocketAddr, sni: &str, protect_path: Option<&str>) -> ProbeResult {
    let stream =
        match connect_socket(target, IpAddr::V4(Ipv4Addr::UNSPECIFIED), protect_path, false, Some(PROBE_TIMEOUT)) {
            Ok(s) => s,
            Err(err) => {
                return if is_dpi_connect_error(&err) {
                    ProbeResult::DpiFailure("connect_reset")
                } else {
                    ProbeResult::NetworkError("connect_failed")
                };
            }
        };

    if stream.set_read_timeout(Some(PROBE_TIMEOUT)).is_err() {
        return ProbeResult::NetworkError("set_timeout_failed");
    }
    if stream.set_write_timeout(Some(PROBE_TIMEOUT)).is_err() {
        return ProbeResult::NetworkError("set_timeout_failed");
    }

    let client_hello = build_minimal_client_hello(sni);
    let mut stream = stream;
    if let Err(err) = stream.write_all(&client_hello) {
        return if is_dpi_write_error(&err) {
            ProbeResult::DpiFailure("write_reset")
        } else {
            ProbeResult::NetworkError("write_failed")
        };
    }

    // Read the first 5 bytes (TLS record header).
    let mut header = [0u8; 5];
    match stream.read_exact(&mut header) {
        Ok(()) => {
            if header[0] == 0x16 {
                // TLS Handshake record -- read one more byte for handshake type.
                let mut handshake_type = [0u8; 1];
                if stream.read_exact(&mut handshake_type).is_ok() && handshake_type[0] == 0x02 {
                    // ServerHello received -- handshake is proceeding normally.
                    return ProbeResult::Success;
                }
                // Got a TLS record but not ServerHello -- could be an alert.
                ProbeResult::DpiFailure("tls_unexpected_handshake")
            } else if header[0] == 0x15 {
                // TLS Alert
                ProbeResult::DpiFailure("tls_alert")
            } else if header.starts_with(b"HTTP/") {
                // HTTP response to a TLS ClientHello -- classic DPI blockpage.
                ProbeResult::DpiFailure("http_blockpage")
            } else {
                ProbeResult::DpiFailure("unexpected_response")
            }
        }
        Err(err) => {
            if err.kind() == io::ErrorKind::TimedOut {
                ProbeResult::DpiFailure("timeout")
            } else if err.kind() == io::ErrorKind::ConnectionReset {
                ProbeResult::DpiFailure("read_reset")
            } else if err.kind() == io::ErrorKind::UnexpectedEof {
                ProbeResult::DpiFailure("eof")
            } else {
                ProbeResult::NetworkError("read_failed")
            }
        }
    }
}

/// Build a minimal TLS 1.0 ClientHello with the given SNI. This is a stripped-
/// down version -- just enough to trigger DPI SNI classification.
fn build_minimal_client_hello(sni: &str) -> Vec<u8> {
    let sni_bytes = sni.as_bytes();
    let sni_list_len = (sni_bytes.len() + 3) as u16;
    let sni_ext_len = sni_list_len + 2;

    // --- Extensions block ---
    let mut extensions = Vec::new();
    // SNI extension (type 0x0000)
    extensions.extend_from_slice(&[0x00, 0x00]);
    extensions.extend_from_slice(&sni_ext_len.to_be_bytes());
    extensions.extend_from_slice(&sni_list_len.to_be_bytes());
    extensions.push(0x00); // host_name type
    extensions.extend_from_slice(&(sni_bytes.len() as u16).to_be_bytes());
    extensions.extend_from_slice(sni_bytes);

    // --- ClientHello body ---
    let mut body = Vec::new();
    body.extend_from_slice(&[0x03, 0x01]); // client_version: TLS 1.0
    body.extend_from_slice(&[0u8; 32]); // Random (32 bytes)
    body.push(0); // session_id length = 0
                  // Cipher suites: TLS_RSA_WITH_AES_128_GCM_SHA256
    body.extend_from_slice(&[0x00, 0x02, 0x00, 0x9c]);
    // Compression methods: null
    body.extend_from_slice(&[0x01, 0x00]);
    // Extensions length + extensions
    body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
    body.extend_from_slice(&extensions);

    // --- Handshake header ---
    let handshake_len = body.len() as u32;
    let mut handshake = Vec::with_capacity(4 + body.len());
    handshake.push(0x01); // ClientHello
    handshake.push((handshake_len >> 16) as u8);
    handshake.push((handshake_len >> 8) as u8);
    handshake.push(handshake_len as u8);
    handshake.extend_from_slice(&body);

    // --- TLS record header ---
    let record_len = handshake.len() as u16;
    let mut record = Vec::with_capacity(5 + handshake.len());
    record.push(0x16); // ContentType: Handshake
    record.extend_from_slice(&[0x03, 0x01]); // TLS 1.0
    record.extend_from_slice(&record_len.to_be_bytes());
    record.extend_from_slice(&handshake);

    record
}

fn is_dpi_connect_error(err: &io::Error) -> bool {
    matches!(err.kind(), io::ErrorKind::ConnectionReset | io::ErrorKind::ConnectionRefused | io::ErrorKind::TimedOut)
}

fn is_dpi_write_error(err: &io::Error) -> bool {
    matches!(err.kind(), io::ErrorKind::ConnectionReset | io::ErrorKind::BrokenPipe)
}

#[cfg(test)]
mod tests {
    use super::*;

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
        use ripdpi_proxy_config::WifiSnapshot;
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
        use ripdpi_proxy_config::WifiSnapshot;
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
        use ripdpi_proxy_config::WifiSnapshot;
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
        use ripdpi_proxy_config::CellularSnapshot;
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
}
