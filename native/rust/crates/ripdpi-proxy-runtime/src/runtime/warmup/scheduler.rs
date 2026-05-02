use std::thread;

use super::autolearn::flush_updates;
use super::execution::probe_domain;
use super::target_catalog::{PROBE_DOMAINS, WARMUP_DEADLINE};
use crate::runtime::state::RuntimeState;

/// Spawn the warmup probe as a background thread.
///
/// The thread probes each domain sequentially, stopping early if the
/// runtime shutdown is requested or the total deadline expires.
pub(in crate::runtime) fn spawn_warmup_thread(state: RuntimeState) {
    if !state.config.host_autolearn.enabled || !state.config.host_autolearn.warmup_probe_enabled {
        return;
    }
    if state.config.groups.len() < 2 {
        // Warmup is only useful when there are fallback groups to escalate to.
        return;
    }
    thread::Builder::new().name("ripdpi-warmup".into()).spawn(move || run_warmup(&state)).ok();
}

fn run_warmup(state: &RuntimeState) {
    let deadline = std::time::Instant::now() + WARMUP_DEADLINE;
    tracing::info!(domain_count = PROBE_DOMAINS.len(), "warmup probe started");

    let mut probed = 0u32;
    let mut learned = 0u32;

    for &domain in PROBE_DOMAINS {
        if is_shutdown(state) {
            tracing::debug!("warmup probe aborted: shutdown requested");
            break;
        }
        if std::time::Instant::now() >= deadline {
            tracing::debug!("warmup probe stopped: deadline reached");
            break;
        }

        match probe_domain(state, domain) {
            Ok(escalated) => {
                probed += 1;
                if escalated {
                    learned += 1;
                }
            }
            Err(err) => {
                tracing::debug!(domain, error = %err, "warmup probe skipped");
                probed += 1;
            }
        }
    }

    flush_updates(state);

    tracing::info!(probed, learned, "warmup probe finished");
}

fn is_shutdown(state: &RuntimeState) -> bool {
    state.control.as_ref().map_or_else(crate::process::shutdown_requested, |c| c.shutdown_requested())
}
