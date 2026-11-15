// Baseline TLS reachability probe after an OS network identity change.
// Repeated transport failures reset learned strategy state for the new network.
// The probe does not apply the current desync strategy; origin TLS rejection
// is inconclusive and does not count toward a reset.
//
// Design constraints:
// - Must not block the listener accept loop.
// - Must not fire on minor network metadata changes (signal strength, traffic
//   counters) -- only when the network identity actually changes.
// - Reuses the same raw TLS ClientHello probe pattern as the connect path.

mod cache_flush;
mod reset_policy;
mod scheduler;
mod target_catalog;
mod tls_probe;

pub(crate) use scheduler::maybe_spawn_reprobe;

#[cfg(test)]
mod tests;
