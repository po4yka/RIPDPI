use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::Mutex;

use android_support::clear_proxy_events;
use arc_swap::ArcSwap;
use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::ProxyLogContext;
use ripdpi_telemetry::LatencyHistogram;

use super::types::DirectPathLearningSignal;
use super::util::now_ms;

static NEXT_PROXY_SESSION_ID: AtomicU64 = AtomicU64::new(1);

fn is_transient_network_error(error: &std::io::Error) -> bool {
    matches!(
        error.raw_os_error(),
        Some(
            libc::ENETUNREACH
                | libc::EHOSTUNREACH
                | libc::ETIMEDOUT
                | libc::ECONNREFUSED
                | libc::ECONNRESET
                | libc::ECONNABORTED
                | libc::ENETDOWN
                | libc::EPIPE
        )
    )
}

#[derive(Clone)]
pub(super) struct TelemetryStrings {
    pub(super) listener_address: Option<String>,
    pub(super) upstream_address: Option<String>,
    pub(super) upstream_rtt_ms: Option<u64>,
    pub(super) last_target: Option<String>,
    pub(super) last_host: Option<String>,
    pub(super) last_error: Option<String>,
    pub(super) last_failure_class: Option<String>,
    pub(super) last_fallback_action: Option<String>,
    pub(super) adaptive_trigger_mask: Option<u64>,
    pub(super) adaptive_last_trigger: Option<String>,
    pub(super) adaptive_override_reason: Option<String>,
    pub(super) morph_hint_family: Option<String>,
    pub(super) morph_rollback_reason: Option<String>,
    pub(super) quic_migration_status: Option<String>,
    pub(super) quic_migration_reason: Option<String>,
    pub(super) last_retry_reason: Option<String>,
    pub(super) last_autolearn_host: Option<String>,
    pub(super) last_autolearn_action: Option<String>,
    pub(super) last_block_signal: Option<String>,
    pub(super) last_block_provider: Option<String>,
}

pub(crate) struct ProxyTelemetryState {
    pub(super) session_id: String,
    pub(super) log_scope: String,
    pub(super) log_context: Option<ProxyLogContext>,
    pub(super) running: AtomicBool,
    pub(super) active_sessions: AtomicU64,
    pub(super) total_sessions: AtomicU64,
    pub(super) total_errors: AtomicU64,
    pub(super) network_errors: AtomicU64,
    pub(super) route_changes: AtomicU64,
    pub(super) retry_paced_count: AtomicU64,
    pub(super) last_retry_backoff_ms: AtomicU64,
    pub(super) candidate_diversification_count: AtomicU64,
    pub(super) last_route_group: AtomicI64,
    pub(super) adaptive_override_active: AtomicBool,
    pub(super) autolearn_enabled: AtomicBool,
    pub(super) learned_host_count: AtomicU64,
    pub(super) penalized_host_count: AtomicU64,
    pub(super) blocked_host_count: AtomicU64,
    pub(super) last_autolearn_group: AtomicI64,
    pub(super) slot_exhaustions: AtomicU64,
    pub(super) strings: ArcSwap<TelemetryStrings>,
    pub(super) direct_path_learning_signals: Mutex<Vec<DirectPathLearningSignal>>,
    pub(super) tcp_connect_histogram: LatencyHistogram,
    pub(super) tls_handshake_histogram: LatencyHistogram,
}

impl ProxyTelemetryState {
    pub(crate) fn new(log_context: Option<ProxyLogContext>) -> Self {
        // Ordering: Relaxed -- session ID is a monotonic counter used only for logging; no
        // synchronisation with other threads is needed beyond uniqueness.
        let ordinal = NEXT_PROXY_SESSION_ID.fetch_add(1, Ordering::Relaxed);
        let session_id = format!("proxy-{ordinal}");
        clear_proxy_events();
        Self {
            log_scope: format!("proxy:{session_id}"),
            session_id,
            log_context,
            running: AtomicBool::new(false),
            active_sessions: AtomicU64::new(0),
            total_sessions: AtomicU64::new(0),
            total_errors: AtomicU64::new(0),
            network_errors: AtomicU64::new(0),
            route_changes: AtomicU64::new(0),
            retry_paced_count: AtomicU64::new(0),
            last_retry_backoff_ms: AtomicU64::new(0),
            candidate_diversification_count: AtomicU64::new(0),
            last_route_group: AtomicI64::new(-1),
            adaptive_override_active: AtomicBool::new(false),
            autolearn_enabled: AtomicBool::new(false),
            learned_host_count: AtomicU64::new(0),
            penalized_host_count: AtomicU64::new(0),
            blocked_host_count: AtomicU64::new(0),
            last_autolearn_group: AtomicI64::new(-1),
            slot_exhaustions: AtomicU64::new(0),
            direct_path_learning_signals: Mutex::new(Vec::new()),
            strings: ArcSwap::from_pointee(TelemetryStrings {
                listener_address: None,
                upstream_address: None,
                upstream_rtt_ms: None,
                last_target: None,
                last_host: None,
                last_error: None,
                last_failure_class: None,
                last_fallback_action: None,
                adaptive_trigger_mask: None,
                adaptive_last_trigger: None,
                adaptive_override_reason: None,
                morph_hint_family: None,
                morph_rollback_reason: None,
                quic_migration_status: None,
                quic_migration_reason: None,
                last_retry_reason: None,
                last_autolearn_host: None,
                last_autolearn_action: None,
                last_block_signal: None,
                last_block_provider: None,
            }),
            tcp_connect_histogram: LatencyHistogram::new(),
            tls_handshake_histogram: LatencyHistogram::new(),
        }
    }

    pub(crate) fn log_scope(&self) -> &str {
        &self.log_scope
    }

    fn emit_event(&self, source: &str, level: &str, message: &str, kind: Option<&str>) {
        let log_context = self.log_context.as_ref();
        let runtime_id = log_context.and_then(|context| context.runtime_id.as_deref()).unwrap_or("");
        let mode = log_context.and_then(|context| context.mode.as_deref()).unwrap_or("");
        let policy_signature = log_context.and_then(|context| context.policy_signature.as_deref()).unwrap_or("");
        let fingerprint_hash = log_context.and_then(|context| context.fingerprint_hash.as_deref()).unwrap_or("");
        let diagnostics_session_id =
            log_context.and_then(|context| context.diagnostics_session_id.as_deref()).unwrap_or("");
        let kind = kind.unwrap_or("");
        match level.trim().to_ascii_lowercase().as_str() {
            "trace" => tracing::trace!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            "debug" => tracing::debug!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            "warn" | "warning" => tracing::warn!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            "error" => tracing::error!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
            _ => tracing::info!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
                kind,
                runtime_id,
                mode,
                policy_signature,
                fingerprint_hash,
                diagnostics_session_id,
                "{message}"
            ),
        }
    }

    /// Atomically update string fields using compare-and-swap.
    /// Retries on concurrent modification (rare at observed write frequencies).
    fn update_strings<F: Fn(&mut TelemetryStrings)>(&self, f: F) {
        self.strings.rcu(|current| {
            let mut next = (**current).clone();
            f(&mut next);
            next
        });
    }

    pub(crate) fn mark_running(&self, bind_addr: String, max_clients: usize, group_count: usize) {
        // Ordering: Release -- pairs with Acquire loads in snapshot() to publish the running
        // state transition; readers on other threads must see all preceding writes.
        self.running.store(true, Ordering::Release);
        // Ordering: Release -- pairs with Acquire load in snapshot(); signals override cleared.
        self.adaptive_override_active.store(false, Ordering::Release);
        let message = format!("listener started addr={bind_addr} maxClients={max_clients} groups={group_count}");
        self.emit_event("proxy", "info", &message, Some("runtime_ready"));
        self.update_strings(|s| {
            s.listener_address = Some(bind_addr.clone());
            s.adaptive_trigger_mask = None;
            s.adaptive_last_trigger = None;
            s.adaptive_override_reason = None;
            s.morph_hint_family = None;
            s.morph_rollback_reason = None;
        });
    }

    pub(crate) fn mark_stopped(&self) {
        // Ordering: Release -- pairs with Acquire load in snapshot(); publishes stopped state.
        self.running.store(false, Ordering::Release);
        // Ordering: Release -- active_sessions gates UI display of "N active"; Release ensures
        // readers that Acquire-load running=false also see active_sessions=0.
        self.active_sessions.store(0, Ordering::Release);
        // Ordering: Release -- pairs with Acquire load in snapshot(); signals override cleared.
        self.adaptive_override_active.store(false, Ordering::Release);
        let message = "listener stopped".to_string();
        self.emit_event("proxy", "info", &message, Some("runtime_stopped"));
    }

    pub(crate) fn on_client_accepted(&self) {
        // Ordering: AcqRel -- active_sessions gates display logic ("if active_sessions > 0");
        // AcqRel on fetch_add ensures the increment is globally visible on all cores.
        self.active_sessions.fetch_add(1, Ordering::AcqRel);
        // Ordering: Relaxed -- monotonic counter read for display only; no happens-before needed.
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn on_client_finished(&self) {
        // Ordering: AcqRel/Acquire -- active_sessions gates display logic; use AcqRel on success
        // and Acquire on load so the decrement is visible to concurrent snapshot readers.
        self.active_sessions
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |value| Some(value.saturating_sub(1)))
            .ok();
    }

    pub(crate) fn on_client_error(&self, error: String) {
        // Ordering: Relaxed -- counter read for display only, no happens-before needed.
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        let message = format!("client error: {error}");
        self.emit_event("proxy", "warn", &message, None);
        self.update_strings(|s| s.last_error = Some(error.clone()));
    }

    pub(crate) fn on_client_io_error(&self, error: &std::io::Error) {
        // Ordering: Relaxed -- counter read for display only, no happens-before needed.
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        if is_transient_network_error(error) {
            // Ordering: Relaxed -- counter read for display only, no happens-before needed.
            self.network_errors.fetch_add(1, Ordering::Relaxed);
        }
        let error_str = error.to_string();
        let message = format!("client error: {error_str}");
        self.emit_event("proxy", "warn", &message, None);
        self.update_strings(|s| s.last_error = Some(error_str.clone()));
    }

    pub(crate) fn on_route_selected(&self, target: String, group_index: usize, host: Option<String>, phase: &str) {
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

    pub(crate) fn on_route_advanced(
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

    pub(crate) fn on_failure_classified(&self, target: String, failure: &ClassifiedFailure, host: Option<String>) {
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

    pub(crate) fn on_retry_paced(&self, target: String, group_index: usize, reason: &'static str, backoff_ms: u64) {
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

    pub(crate) fn on_upstream_connected(&self, upstream_address: String, upstream_rtt_ms: Option<u64>) {
        if let Some(rtt_ms) = upstream_rtt_ms {
            self.tcp_connect_histogram.record(rtt_ms);
        }
        self.update_strings(|s| {
            s.upstream_address = Some(upstream_address.clone());
            s.upstream_rtt_ms = upstream_rtt_ms;
        });
    }

    pub(crate) fn on_tls_handshake_completed(&self, latency_ms: u64) {
        self.tls_handshake_histogram.record(latency_ms);
    }

    pub(crate) fn set_autolearn_state(
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

    pub(crate) fn on_autolearn_event(&self, action: &'static str, host: Option<String>, group_index: Option<usize>) {
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

    pub(crate) fn clear_last_error(&self) {
        self.update_strings(|s| s.last_error = None);
    }

    pub(crate) fn push_event(&self, source: &str, level: &str, message: String) {
        self.emit_event(source, level, &message, None);
    }

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
