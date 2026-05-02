use std::sync::atomic::Ordering;

use super::state::ProxyTelemetryState;

impl ProxyTelemetryState {
    pub fn set_autolearn_state(
        &self,
        enabled: bool,
        learned_host_count: usize,
        penalized_host_count: usize,
        blocked_host_count: usize,
        last_block_signal: Option<&str>,
        last_block_provider: Option<&str>,
    ) {
        // Ordering: Release -- autolearn_enabled is a flag that gates UI behaviour; Release pairs
        // with Acquire load in snapshot() so readers see the correct host counts alongside it.
        self.autolearn_enabled.store(enabled, Ordering::Release);
        // Ordering: Relaxed -- counters read for display only, no happens-before needed.
        self.learned_host_count.store(learned_host_count as u64, Ordering::Relaxed);
        // Ordering: Relaxed -- counter read for display only, no happens-before needed.
        self.penalized_host_count.store(penalized_host_count as u64, Ordering::Relaxed);
        // Ordering: Relaxed -- counter read for display only, no happens-before needed.
        self.blocked_host_count.store(blocked_host_count as u64, Ordering::Relaxed);
        self.update_strings(|s| {
            s.last_block_signal = last_block_signal.map(ToOwned::to_owned);
            s.last_block_provider = last_block_provider.map(ToOwned::to_owned);
        });
    }

    pub fn on_autolearn_event(&self, action: &'static str, host: Option<String>, group_index: Option<usize>) {
        // Ordering: Relaxed -- display-only field; readers tolerate stale group index.
        self.last_autolearn_group
            .store(group_index.and_then(|value| i64::try_from(value).ok()).unwrap_or(-1), Ordering::Relaxed);
        let level = if matches!(action, "group_penalized" | "host_blocked") { "warn" } else { "info" };
        let message = format!(
            "autolearn action={} host={} group={}",
            action,
            host.as_deref().unwrap_or("<none>"),
            group_index.map_or_else(|| "<none>".to_string(), |value| value.to_string())
        );
        self.emit_event("autolearn", level, &message, None);
        {
            let action_str = action.to_string();
            self.update_strings(|s| {
                s.last_autolearn_host = host.clone();
                s.last_autolearn_action = Some(action_str.clone());
            });
        }
    }
}
