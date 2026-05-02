use super::types::DirectPathLearningSignal;
use super::util::now_ms;

use super::state::ProxyTelemetryState;

impl ProxyTelemetryState {
    pub(crate) fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
    ) {
        let signal = DirectPathLearningSignal {
            authority: authority.trim().to_ascii_lowercase(),
            ip_set_digest: ip_set_digest.trim().to_ascii_lowercase(),
            event: event.to_string(),
            strategy_family: strategy_family.map(ToOwned::to_owned),
            captured_at: now_ms(),
        };
        if let Ok(mut signals) = self.direct_path_learning_signals.lock() {
            signals.push(signal);
        }
    }
}
