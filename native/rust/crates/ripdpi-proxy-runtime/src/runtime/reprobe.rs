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

mod cache_flush;
mod classification;
mod reset_policy;
mod scheduler;
mod target_catalog;
mod tls_probe;

pub(crate) use scheduler::maybe_spawn_reprobe;

#[cfg(test)]
mod tests;
