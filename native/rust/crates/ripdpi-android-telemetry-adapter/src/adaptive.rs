use std::sync::atomic::Ordering;

use super::state::ProxyTelemetryState;

impl ProxyTelemetryState {
    pub(crate) fn on_adaptive_override(
        &self,
        target: String,
        group_index: usize,
        trigger_mask: u32,
        failure_class: &'static str,
        host: Option<String>,
        reason: &'static str,
    ) {
        // Ordering: Release -- flag-like signal; pairs with Acquire load in snapshot() so readers
        // see adaptive_override_active=true before reading associated trigger/reason strings.
        self.adaptive_override_active.store(true, Ordering::Release);
        // Ordering: Relaxed -- display-only field; readers tolerate stale group index.
        self.last_route_group.store(group_index.try_into().unwrap_or(i64::MAX), Ordering::Relaxed);
        let message = format!(
            "adaptive override active target={} group={} triggerMask={} failureClass={} reason={} host={}",
            target,
            group_index,
            trigger_mask,
            failure_class,
            reason,
            host.as_deref().unwrap_or("<none>")
        );
        self.emit_event("proxy", "warn", &message, None);
        self.update_strings(|s| {
            s.last_target = Some(target.clone());
            s.last_host = host.clone();
            s.adaptive_trigger_mask = Some(u64::from(trigger_mask));
            s.adaptive_last_trigger = Some(failure_class.to_string());
            s.adaptive_override_reason = Some(reason.to_string());
        });
    }

    pub(crate) fn on_telegram_dc_detected(&self, target: String, dc: u8) {
        let message = format!("telegram dc detected target={target} dc={dc}");
        self.emit_event("proxy", "info", &message, None);
        self.update_strings(|s| s.last_target = Some(target.clone()));
    }

    pub(crate) fn on_ws_tunnel_escalation(&self, target: String, dc: u8, success: bool) {
        let level = if success { "info" } else { "warn" };
        let result = if success { "success" } else { "failed" };
        let message = format!("ws tunnel escalation target={target} dc={dc} result={result}");
        self.emit_event("proxy", level, &message, None);
        self.update_strings(|s| s.last_target = Some(target.clone()));
    }

    pub(crate) fn on_quic_migration_status(&self, target: String, status: &'static str, reason: &'static str) {
        let message = format!("quic migration target={target} status={status} reason={reason}");
        self.emit_event("proxy", "info", &message, None);
        self.update_strings(|s| {
            s.last_target = Some(target.clone());
            s.quic_migration_status = Some(status.to_string());
            s.quic_migration_reason = Some(reason.to_string());
        });
    }

    pub(crate) fn on_morph_hint_applied(&self, target: String, policy_id: &str, family: &str) {
        let message = format!("morph hint applied target={target} policyId={policy_id} family={family}");
        self.emit_event("proxy", "info", &message, None);
        self.update_strings(|s| {
            s.last_target = Some(target.clone());
            s.morph_hint_family = Some(family.to_string());
        });
    }

    pub(crate) fn on_morph_rollback(&self, target: String, policy_id: &str, reason: &str) {
        let message = format!("morph rollback target={target} policyId={policy_id} reason={reason}");
        self.emit_event("proxy", "warn", &message, None);
        self.update_strings(|s| {
            s.last_target = Some(target.clone());
            s.morph_rollback_reason = Some(reason.to_string());
        });
    }
}
