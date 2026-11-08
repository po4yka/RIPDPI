use super::types::DirectPathLearningSignal;
use super::util::now_ms;

use super::state::ProxyTelemetryState;

impl ProxyTelemetryState {
    pub fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
        app_scope: Option<&str>,
    ) {
        let signal = DirectPathLearningSignal {
            authority: authority.trim().to_ascii_lowercase(),
            ip_set_digest: ip_set_digest.trim().to_ascii_lowercase(),
            event: event.to_string(),
            strategy_family: strategy_family.map(ToOwned::to_owned),
            captured_at: now_ms(),
            app_scope: app_scope.map(ToOwned::to_owned),
        };
        if let Ok(mut signals) = self.direct_path_learning_signals.lock() {
            signals.push(signal);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn app_scope_is_additive_and_absent_for_unattributed_signals() {
        let state = ProxyTelemetryState::new(None);
        state.on_direct_path_learning_signal("shared.example:443", "digest", "QUIC_SUCCESS", None, None);
        state.on_direct_path_learning_signal(
            "shared.example:443",
            "digest",
            "QUIC_SUCCESS",
            None,
            Some("e0d09c2c-56cb-4e60-a168-0e069aa6be15"),
        );
        let signals = state.direct_path_learning_signals.lock().expect("signals");
        let unscoped = serde_json::to_value(&signals[0]).expect("unscoped signal");
        let scoped = serde_json::to_value(&signals[1]).expect("scoped signal");
        assert!(unscoped.get("appScope").is_none());
        assert_eq!(scoped["appScope"], "e0d09c2c-56cb-4e60-a168-0e069aa6be15");
        assert_eq!(unscoped["authority"], scoped["authority"]);
    }
}
