use std::net::{IpAddr, SocketAddr};
use std::thread;

use ripdpi_proxy_runtime_adapter::failure::ProbeResult;
use ripdpi_proxy_runtime_adapter::model::services::ReprobeResetHandle;

use super::super::state::RuntimeState;
use super::cache_flush::flush_runtime_cache_after_handover;
use super::reset_policy::reset_if_strategy_mismatch;
use super::target_catalog::{PROBE_TARGETS, TOTAL_DEADLINE};
use super::tls_probe::probe_tls_handshake;

/// Checks the current network snapshot and spawns a background reprobe thread
/// if the network identity changed. Called from the listener accept loop.
///
/// The function is intentionally fire-and-forget: the spawned thread runs
/// independently and logs results via tracing.
pub(crate) fn maybe_spawn_reprobe(state: &RuntimeState) {
    if !state.network_reprobe_enabled() {
        return;
    }
    let Some(snapshot) = state.current_network_snapshot() else {
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

    if !state.should_reprobe_network(&snapshot) {
        return;
    }

    flush_runtime_cache_after_handover(state);

    tracing::info!("network_reprobe: network identity changed, scheduling reprobe");

    let reset_handle = state.reprobe_reset_handle();
    let protect_path = state.network_reprobe_protect_path();

    thread::Builder::new()
        .name("ripdpi-reprobe".into())
        .spawn(move || {
            run_reprobe(protect_path, &reset_handle);
        })
        .ok();
}

/// Perform the actual reprobe: connect to each probe domain on port 443 and
/// attempt a raw TLS ClientHello. A failure is classified as a DPI signature
/// if the connection is reset, times out, or receives a TLS alert before the
/// ServerHello completes.
fn run_reprobe(protect_path: Option<String>, reset_handle: &ReprobeResetHandle) {
    let deadline = std::time::Instant::now() + TOTAL_DEADLINE;
    let mut failures = 0usize;
    let mut successes = 0usize;

    for target_def in PROBE_TARGETS {
        if std::time::Instant::now() >= deadline {
            tracing::debug!("network_reprobe: total deadline exceeded after {successes} ok, {failures} fail");
            break;
        }

        let ip: IpAddr = match target_def.ip.parse() {
            Ok(addr) => addr,
            Err(_) => continue,
        };
        let target = SocketAddr::new(ip, 443);

        match probe_tls_handshake(target, target_def.domain, protect_path.as_deref()) {
            ProbeResult::Success => {
                tracing::debug!("network_reprobe: {} passed", target_def.domain);
                successes += 1;
            }
            ProbeResult::DpiFailure(reason) => {
                tracing::debug!("network_reprobe: {} failed ({reason})", target_def.domain);
                failures += 1;
            }
            ProbeResult::NetworkError(reason) => {
                // Network errors (DNS, routing) are not DPI -- don't count them.
                tracing::debug!("network_reprobe: {} network error ({reason}), skipping", target_def.domain);
            }
        }
    }

    reset_if_strategy_mismatch(failures, successes, PROBE_TARGETS.len(), reset_handle);
}
