use std::sync::atomic::Ordering;

use ripdpi_failure_classifier::ClassifiedFailure;

use super::state::ProxyTelemetryState;

impl ProxyTelemetryState {
    pub fn on_route_selected(&self, target: String, group_index: usize, host: Option<String>, phase: &str) {
        // Ordering: Relaxed -- display-only field; readers tolerate stale group index.
        self.last_route_group.store(group_index.try_into().unwrap_or(i64::MAX), Ordering::Relaxed);
        let message = format!(
            "route selected phase={} group={} target={} host={}",
            phase,
            group_index,
            target,
            host.as_deref().unwrap_or("<none>")
        );
        self.emit_event("proxy", "info", &message, None);
        self.update_strings(|s| {
            s.last_target = Some(target.clone());
            s.last_host = host.clone();
        });
    }

    pub fn on_route_advanced(
        &self,
        target: String,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<String>,
    ) {
        // Ordering: Relaxed -- counter read for display only, no happens-before needed.
        self.route_changes.fetch_add(1, Ordering::Relaxed);
        // Ordering: Relaxed -- display-only field; readers tolerate stale group index.
        self.last_route_group.store(to_group.try_into().unwrap_or(i64::MAX), Ordering::Relaxed);
        let message = format!(
            "route advanced target={} from={} to={} trigger={} host={}",
            target,
            from_group,
            to_group,
            trigger,
            host.as_deref().unwrap_or("<none>")
        );
        self.emit_event("proxy", "warn", &message, None);
        self.update_strings(|s| {
            s.last_target = Some(target.clone());
            s.last_host = host.clone();
        });
    }

    pub fn on_failure_classified(&self, target: String, failure: &ClassifiedFailure, host: Option<String>) {
        let level = if failure.action.as_str() == "retry_with_matching_group" { "warn" } else { "info" };
        let message = format!(
            "failure classified target={} class={} stage={} action={} host={} evidence={}",
            target,
            failure.class.as_str(),
            failure.stage.as_str(),
            failure.action.as_str(),
            host.as_deref().unwrap_or("<none>"),
            failure.evidence.summary
        );
        self.emit_event("proxy", level, &message, None);
        {
            let evidence = failure.evidence.summary.clone();
            let class = failure.class.as_str().to_string();
            let action = failure.action.as_str().to_string();
            self.update_strings(|s| {
                s.last_target = Some(target.clone());
                s.last_host = host.clone();
                s.last_error = Some(evidence.clone());
                s.last_failure_class = Some(class.clone());
                s.last_fallback_action = Some(action.clone());
            });
        }
    }

    pub fn on_retry_paced(&self, target: String, group_index: usize, reason: &'static str, backoff_ms: u64) {
        if backoff_ms > 0 {
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            self.retry_paced_count.fetch_add(1, Ordering::Relaxed);
        }
        // Ordering: Relaxed -- display-only field; staleness of a few ms is acceptable.
        self.last_retry_backoff_ms.store(backoff_ms, Ordering::Relaxed);
        if reason == "candidate_order_diversified" {
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            self.candidate_diversification_count.fetch_add(1, Ordering::Relaxed);
        }
        let message =
            format!("retry pacing target={target} group={group_index} reason={reason} backoffMs={backoff_ms}");
        self.emit_event("proxy", "info", &message, None);
        {
            let reason_str = reason.to_string();
            self.update_strings(|s| {
                s.last_target = Some(target.clone());
                s.last_retry_reason = Some(reason_str.clone());
            });
        }
    }
}
