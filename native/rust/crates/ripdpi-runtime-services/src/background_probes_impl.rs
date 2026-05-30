use std::sync::Arc;

use ripdpi_proxy_config::{set_active_network_scope, NetworkSnapshot};
use ripdpi_runtime_api::BackgroundProbes;

use crate::services_state::WarmupRequest;
use crate::ServicesStateHandle;

impl BackgroundProbes for ServicesStateHandle {
    /// Called when the OS reports a network state change.
    ///
    /// Stores the snapshot for lock-free reading by proxy-runtime and enqueues
    /// a reprobe warmup pass when the network *identity* actually changes
    /// (transport, SSID, operator, DNS servers). Minor metadata updates
    /// (RSSI, traffic counters, MTU, captive portal flag) do not trigger a
    /// reprobe.
    fn notify_network_change(&self, snapshot: NetworkSnapshot) {
        // Persist the raw snapshot for lock-free consumption by proxy-runtime.
        self.network.snapshot.store(Arc::new(Some(snapshot.clone())));

        // Skip reprobe when the network is not usable. Also clear the ambient
        // network scope so `(host, NetProfile)`-keyed resolver-selection caching
        // is disabled until a usable network is observed again.
        if snapshot.captive_portal || !snapshot.validated {
            set_active_network_scope(None);
            tracing::debug!(
                captive_portal = snapshot.captive_portal,
                validated = snapshot.validated,
                "background_probes: skipping reprobe (network not usable)",
            );
            return;
        }

        let identity = network_identity(&snapshot);

        // Publish the current network identity as the ambient scope for the
        // resolver-selection cache. `network_identity` uses only non-raw,
        // stable identifiers (hashed SSID, operator code, DNS set) — see
        // `.claude/rules/network-fingerprint-privacy.md`.
        set_active_network_scope(Some(identity.clone()));
        let should_reprobe = {
            let mut last = self.network.last_identity.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
            if last.as_deref() == Some(&identity) {
                false
            } else {
                let is_initial = last.is_none();
                *last = Some(identity);
                // Do not reprobe on the very first snapshot (proxy just started).
                !is_initial
            }
        };

        if should_reprobe {
            tracing::info!("background_probes: network identity changed, scheduling reprobe warmup");
            // Enqueue a synthetic warmup request for the reprobe domains so that
            // proxy-runtime's warmup consumer can re-probe after a network change.
            // Individual domain requests let proxy-runtime decide the execution order.
            for domain in REPROBE_DOMAINS {
                let req = WarmupRequest { authority: (*domain).to_owned(), is_udp: false };
                if self.warmup.tx.try_send(req).is_err() {
                    tracing::debug!("background_probes: warmup channel full, dropping reprobe for {domain}");
                }
            }
        }
    }

    /// Enqueue a fire-and-forget warmup request for a given authority.
    ///
    /// Proxy-runtime drains these via the receiver obtained from
    /// [`ServicesState::take_warmup_receiver`] and executes them through the
    /// normal routing pipeline.
    fn request_warmup(&self, authority: &str, is_udp: bool) {
        let req = WarmupRequest { authority: authority.to_owned(), is_udp };
        if self.warmup.tx.try_send(req).is_err() {
            tracing::debug!("background_probes: warmup channel full, dropping request for {authority}");
        }
    }
}

/// Derives a stable identity string from a `NetworkSnapshot` that changes only
/// when the physical network actually switches. Minor metadata changes (RSSI,
/// traffic counters, MTU, captive portal) are intentionally excluded.
///
/// Mirrors the logic in `ripdpi-proxy-runtime`'s `reprobe::identity` module so
/// that `ServicesStateHandle` can track network changes without depending on
/// proxy-runtime internals.
fn network_identity(snapshot: &NetworkSnapshot) -> String {
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

/// Domains sent as warmup requests when a network identity change is detected.
/// These mirror `reprobe::target_catalog::PROBE_TARGETS` in proxy-runtime.
const REPROBE_DOMAINS: &[&str] = &["youtube.com", "discord.com", "telegram.org"];
