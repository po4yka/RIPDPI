use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU64, Ordering};
use std::sync::Arc;

use arc_swap::ArcSwap;

use ripdpi_failure_classifier::ClassifiedFailure;
use ripdpi_proxy_config::ProxyLogContext;
use ripdpi_runtime::RuntimeTelemetrySink;
use ripdpi_telemetry::{LatencyDistributions, LatencyHistogram};
use serde::Serialize;

use android_support::{clear_proxy_events, drain_proxy_events, NativeEventRecord};

static NEXT_PROXY_SESSION_ID: AtomicU64 = AtomicU64::new(1);

/// Returns true for I/O errors caused by transient network conditions
/// (e.g., Doze mode, network handover, airplane mode toggle).
/// These are separated from permanent failures in telemetry so the UI
/// can distinguish recoverable connectivity blips from real bugs.
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

#[allow(clippy::trivially_copy_pass_by_ref)]
fn is_false(value: &bool) -> bool {
    !*value
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeRuntimeEvent {
    pub(crate) source: String,
    pub(crate) level: String,
    pub(crate) message: String,
    pub(crate) created_at: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) runtime_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) mode: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) policy_signature: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) fingerprint_hash: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) subsystem: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TunnelStatsSnapshot {
    pub(crate) tx_packets: u64,
    pub(crate) tx_bytes: u64,
    pub(crate) rx_packets: u64,
    pub(crate) rx_bytes: u64,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct NativeRuntimeSnapshot {
    pub(crate) source: String,
    pub(crate) state: String,
    pub(crate) health: String,
    pub(crate) active_sessions: u64,
    pub(crate) total_sessions: u64,
    pub(crate) total_errors: u64,
    pub(crate) network_errors: u64,
    pub(crate) route_changes: u64,
    pub(crate) retry_paced_count: u64,
    pub(crate) last_retry_backoff_ms: Option<u64>,
    pub(crate) last_retry_reason: Option<String>,
    pub(crate) candidate_diversification_count: u64,
    pub(crate) last_route_group: Option<i32>,
    pub(crate) last_failure_class: Option<String>,
    pub(crate) last_fallback_action: Option<String>,
    #[serde(skip_serializing_if = "is_false")]
    pub(crate) adaptive_override_active: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) adaptive_trigger_mask: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) adaptive_last_trigger: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) adaptive_override_reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) strategy_pack_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) strategy_pack_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) tls_profile_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) tls_profile_catalog_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) morph_policy_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) morph_hint_family: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) morph_rollback_reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) quic_migration_status: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) quic_migration_reason: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) pt_runtime_kind: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) pt_runtime_state: Option<String>,
    pub(crate) listener_address: Option<String>,
    pub(crate) upstream_address: Option<String>,
    pub(crate) upstream_rtt_ms: Option<u64>,
    pub(crate) last_target: Option<String>,
    pub(crate) last_host: Option<String>,
    pub(crate) last_error: Option<String>,
    pub(crate) autolearn_enabled: bool,
    pub(crate) learned_host_count: i32,
    pub(crate) penalized_host_count: i32,
    pub(crate) blocked_host_count: i32,
    pub(crate) last_block_signal: Option<String>,
    pub(crate) last_block_provider: Option<String>,
    pub(crate) last_autolearn_host: Option<String>,
    pub(crate) last_autolearn_group: Option<i32>,
    pub(crate) last_autolearn_action: Option<String>,
    pub(crate) slot_exhaustions: u64,
    pub(crate) profile_id: Option<String>,
    pub(crate) protocol_kind: Option<String>,
    pub(crate) tcp_capable: Option<bool>,
    pub(crate) udp_capable: Option<bool>,
    pub(crate) fallback_mode: Option<String>,
    pub(crate) last_handshake_error: Option<String>,
    pub(crate) chain_entry_state: Option<String>,
    pub(crate) chain_exit_state: Option<String>,
    pub(crate) tunnel_stats: TunnelStatsSnapshot,
    pub(crate) native_events: Vec<NativeRuntimeEvent>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) latency_distributions: Option<LatencyDistributions>,
    pub(crate) captured_at: u64,
}

#[derive(Clone)]
struct TelemetryStrings {
    listener_address: Option<String>,
    upstream_address: Option<String>,
    upstream_rtt_ms: Option<u64>,
    last_target: Option<String>,
    last_host: Option<String>,
    last_error: Option<String>,
    last_failure_class: Option<String>,
    last_fallback_action: Option<String>,
    adaptive_trigger_mask: Option<u64>,
    adaptive_last_trigger: Option<String>,
    adaptive_override_reason: Option<String>,
    morph_hint_family: Option<String>,
    morph_rollback_reason: Option<String>,
    quic_migration_status: Option<String>,
    quic_migration_reason: Option<String>,
    last_retry_reason: Option<String>,
    last_autolearn_host: Option<String>,
    last_autolearn_action: Option<String>,
    last_block_signal: Option<String>,
    last_block_provider: Option<String>,
}

pub(crate) struct ProxyTelemetryState {
    session_id: String,
    log_scope: String,
    log_context: Option<ProxyLogContext>,
    running: AtomicBool,
    active_sessions: AtomicU64,
    total_sessions: AtomicU64,
    total_errors: AtomicU64,
    network_errors: AtomicU64,
    route_changes: AtomicU64,
    retry_paced_count: AtomicU64,
    last_retry_backoff_ms: AtomicU64,
    candidate_diversification_count: AtomicU64,
    last_route_group: AtomicI64,
    adaptive_override_active: AtomicBool,
    autolearn_enabled: AtomicBool,
    learned_host_count: AtomicU64,
    penalized_host_count: AtomicU64,
    blocked_host_count: AtomicU64,
    last_autolearn_group: AtomicI64,
    slot_exhaustions: AtomicU64,
    strings: ArcSwap<TelemetryStrings>,
    tcp_connect_histogram: LatencyHistogram,
    tls_handshake_histogram: LatencyHistogram,
}

impl ProxyTelemetryState {
    pub(crate) fn new(log_context: Option<ProxyLogContext>) -> Self {
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

    fn emit_event(&self, source: &str, level: &str, message: &str) {
        let log_context = self.log_context.as_ref();
        let runtime_id = log_context.and_then(|context| context.runtime_id.as_deref()).unwrap_or("");
        let mode = log_context.and_then(|context| context.mode.as_deref()).unwrap_or("");
        let policy_signature = log_context.and_then(|context| context.policy_signature.as_deref()).unwrap_or("");
        let fingerprint_hash = log_context.and_then(|context| context.fingerprint_hash.as_deref()).unwrap_or("");
        let diagnostics_session_id =
            log_context.and_then(|context| context.diagnostics_session_id.as_deref()).unwrap_or("");
        match level.trim().to_ascii_lowercase().as_str() {
            "trace" => tracing::trace!(
                ring = "proxy",
                subsystem = "proxy",
                session = self.session_id.as_str(),
                source,
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
        self.running.store(true, Ordering::Relaxed);
        self.adaptive_override_active.store(false, Ordering::Relaxed);
        let message = format!("listener started addr={bind_addr} maxClients={max_clients} groups={group_count}");
        self.emit_event("proxy", "info", &message);
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
        self.running.store(false, Ordering::Relaxed);
        self.active_sessions.store(0, Ordering::Relaxed);
        self.adaptive_override_active.store(false, Ordering::Relaxed);
        let message = "listener stopped".to_string();
        self.emit_event("proxy", "info", &message);
    }

    pub(crate) fn on_client_accepted(&self) {
        self.active_sessions.fetch_add(1, Ordering::Relaxed);
        self.total_sessions.fetch_add(1, Ordering::Relaxed);
    }

    pub(crate) fn on_client_finished(&self) {
        self.active_sessions
            .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |value| Some(value.saturating_sub(1)))
            .ok();
    }

    pub(crate) fn on_client_error(&self, error: String) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        let message = format!("client error: {error}");
        self.emit_event("proxy", "warn", &message);
        self.update_strings(|s| s.last_error = Some(error.clone()));
    }

    pub(crate) fn on_client_io_error(&self, error: &std::io::Error) {
        self.total_errors.fetch_add(1, Ordering::Relaxed);
        if is_transient_network_error(error) {
            self.network_errors.fetch_add(1, Ordering::Relaxed);
        }
        let error_str = error.to_string();
        let message = format!("client error: {error_str}");
        self.emit_event("proxy", "warn", &message);
        self.update_strings(|s| s.last_error = Some(error_str.clone()));
    }

    pub(crate) fn on_route_selected(&self, target: String, group_index: usize, host: Option<String>, phase: &str) {
        self.last_route_group.store(group_index.try_into().unwrap_or(i64::MAX), Ordering::Relaxed);
        let message = format!(
            "route selected phase={} group={} target={} host={}",
            phase,
            group_index,
            target,
            host.as_deref().unwrap_or("<none>")
        );
        self.emit_event("proxy", "info", &message);
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
        self.route_changes.fetch_add(1, Ordering::Relaxed);
        self.last_route_group.store(to_group.try_into().unwrap_or(i64::MAX), Ordering::Relaxed);
        let message = format!(
            "route advanced target={} from={} to={} trigger={} host={}",
            target,
            from_group,
            to_group,
            trigger,
            host.as_deref().unwrap_or("<none>")
        );
        self.emit_event("proxy", "warn", &message);
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
        self.adaptive_override_active.store(true, Ordering::Relaxed);
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
        self.emit_event("proxy", "warn", &message);
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
        self.emit_event("proxy", level, &message);
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
            self.retry_paced_count.fetch_add(1, Ordering::Relaxed);
        }
        self.last_retry_backoff_ms.store(backoff_ms, Ordering::Relaxed);
        if reason == "candidate_order_diversified" {
            self.candidate_diversification_count.fetch_add(1, Ordering::Relaxed);
        }
        let message =
            format!("retry pacing target={target} group={group_index} reason={reason} backoffMs={backoff_ms}");
        self.emit_event("proxy", "info", &message);
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
        self.emit_event("proxy", "info", &message);
        self.update_strings(|s| s.last_target = Some(target.clone()));
    }

    pub(crate) fn on_ws_tunnel_escalation(&self, target: String, dc: u8, success: bool) {
        let level = if success { "info" } else { "warn" };
        let result = if success { "success" } else { "failed" };
        let message = format!("ws tunnel escalation target={target} dc={dc} result={result}");
        self.emit_event("proxy", level, &message);
        self.update_strings(|s| s.last_target = Some(target.clone()));
    }

    pub(crate) fn on_quic_migration_status(&self, target: String, status: &'static str, reason: &'static str) {
        let message = format!("quic migration target={target} status={status} reason={reason}");
        self.emit_event("proxy", "info", &message);
        self.update_strings(|s| {
            s.last_target = Some(target.clone());
            s.quic_migration_status = Some(status.to_string());
            s.quic_migration_reason = Some(reason.to_string());
        });
    }

    pub(crate) fn on_morph_hint_applied(&self, target: String, policy_id: &str, family: &str) {
        let message = format!("morph hint applied target={target} policyId={policy_id} family={family}");
        self.emit_event("proxy", "info", &message);
        self.update_strings(|s| {
            s.last_target = Some(target.clone());
            s.morph_hint_family = Some(family.to_string());
        });
    }

    pub(crate) fn on_morph_rollback(&self, target: String, policy_id: &str, reason: &str) {
        let message = format!("morph rollback target={target} policyId={policy_id} reason={reason}");
        self.emit_event("proxy", "warn", &message);
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
        self.autolearn_enabled.store(enabled, Ordering::Relaxed);
        self.learned_host_count.store(learned_host_count as u64, Ordering::Relaxed);
        self.penalized_host_count.store(penalized_host_count as u64, Ordering::Relaxed);
        self.blocked_host_count.store(blocked_host_count as u64, Ordering::Relaxed);
        self.update_strings(|s| {
            s.last_block_signal = last_block_signal.map(ToOwned::to_owned);
            s.last_block_provider = last_block_provider.map(ToOwned::to_owned);
        });
    }

    pub(crate) fn on_autolearn_event(&self, action: &'static str, host: Option<String>, group_index: Option<usize>) {
        self.last_autolearn_group
            .store(group_index.and_then(|value| i64::try_from(value).ok()).unwrap_or(-1), Ordering::Relaxed);
        let level = if matches!(action, "group_penalized" | "host_blocked") { "warn" } else { "info" };
        let message = format!(
            "autolearn action={} host={} group={}",
            action,
            host.as_deref().unwrap_or("<none>"),
            group_index.map_or_else(|| "<none>".to_string(), |value| value.to_string())
        );
        self.emit_event("autolearn", level, &message);
        {
            let action_str = action.to_string();
            self.update_strings(|s| {
                s.last_autolearn_host = host.clone();
                s.last_autolearn_action = Some(action_str.clone());
            });
        }
    }

    pub(crate) fn snapshot(&self) -> NativeRuntimeSnapshot {
        let strings = self.strings.load();
        let listener_address = strings.listener_address.clone();
        let upstream_address = strings.upstream_address.clone();
        let upstream_rtt_ms = strings.upstream_rtt_ms;
        let last_target = strings.last_target.clone();
        let last_host = strings.last_host.clone();
        let last_error = strings.last_error.clone();
        let last_failure_class = strings.last_failure_class.clone();
        let last_fallback_action = strings.last_fallback_action.clone();
        let adaptive_trigger_mask = strings.adaptive_trigger_mask;
        let adaptive_last_trigger = strings.adaptive_last_trigger.clone();
        let adaptive_override_reason = strings.adaptive_override_reason.clone();
        let morph_hint_family = strings.morph_hint_family.clone();
        let morph_rollback_reason = strings.morph_rollback_reason.clone();
        let quic_migration_status = strings.quic_migration_status.clone();
        let quic_migration_reason = strings.quic_migration_reason.clone();
        let last_retry_reason = strings.last_retry_reason.clone();
        let last_autolearn_host = strings.last_autolearn_host.clone();
        let last_autolearn_action = strings.last_autolearn_action.clone();
        let last_block_signal = strings.last_block_signal.clone();
        let last_block_provider = strings.last_block_provider.clone();
        NativeRuntimeSnapshot {
            source: "proxy".to_string(),
            state: if self.running.load(Ordering::Relaxed) { "running".to_string() } else { "idle".to_string() },
            health: if self.running.load(Ordering::Relaxed) {
                if self.total_errors.load(Ordering::Relaxed) == 0 {
                    "healthy".to_string()
                } else {
                    "degraded".to_string()
                }
            } else {
                "idle".to_string()
            },
            active_sessions: self.active_sessions.load(Ordering::Relaxed),
            total_sessions: self.total_sessions.load(Ordering::Relaxed),
            total_errors: self.total_errors.load(Ordering::Relaxed),
            network_errors: self.network_errors.load(Ordering::Relaxed),
            route_changes: self.route_changes.load(Ordering::Relaxed),
            retry_paced_count: self.retry_paced_count.load(Ordering::Relaxed),
            last_retry_backoff_ms: match self.last_retry_backoff_ms.load(Ordering::Relaxed) {
                0 => None,
                value => Some(value),
            },
            last_retry_reason,
            candidate_diversification_count: self.candidate_diversification_count.load(Ordering::Relaxed),
            last_route_group: match self.last_route_group.load(Ordering::Relaxed) {
                value if value >= 0 => i32::try_from(value).ok(),
                _ => None,
            },
            last_failure_class,
            last_fallback_action,
            adaptive_override_active: self.adaptive_override_active.load(Ordering::Relaxed),
            adaptive_trigger_mask,
            adaptive_last_trigger,
            adaptive_override_reason,
            strategy_pack_id: None,
            strategy_pack_version: None,
            tls_profile_id: None,
            tls_profile_catalog_version: None,
            morph_policy_id: None,
            morph_hint_family,
            morph_rollback_reason,
            quic_migration_status,
            quic_migration_reason,
            pt_runtime_kind: None,
            pt_runtime_state: None,
            listener_address,
            upstream_address,
            upstream_rtt_ms,
            last_target,
            last_host,
            last_error,
            autolearn_enabled: self.autolearn_enabled.load(Ordering::Relaxed),
            learned_host_count: i32::try_from(self.learned_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            penalized_host_count: i32::try_from(self.penalized_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            blocked_host_count: i32::try_from(self.blocked_host_count.load(Ordering::Relaxed)).unwrap_or(i32::MAX),
            last_block_signal,
            last_block_provider,
            last_autolearn_host,
            last_autolearn_group: match self.last_autolearn_group.load(Ordering::Relaxed) {
                value if value >= 0 => i32::try_from(value).ok(),
                _ => None,
            },
            last_autolearn_action,
            slot_exhaustions: self.slot_exhaustions.load(Ordering::Relaxed),
            profile_id: None,
            protocol_kind: None,
            tcp_capable: None,
            udp_capable: None,
            fallback_mode: None,
            last_handshake_error: None,
            chain_entry_state: None,
            chain_exit_state: None,
            tunnel_stats: TunnelStatsSnapshot { tx_packets: 0, tx_bytes: 0, rx_packets: 0, rx_bytes: 0 },
            native_events: drain_proxy_events().into_iter().map(NativeRuntimeEvent::from).collect(),
            latency_distributions: LatencyDistributions {
                tcp_connect: self.tcp_connect_histogram.snapshot(),
                tls_handshake: self.tls_handshake_histogram.snapshot(),
                ..Default::default()
            }
            .into_option(),
            captured_at: now_ms(),
        }
    }

    pub(crate) fn clear_last_error(&self) {
        self.update_strings(|s| s.last_error = None);
    }

    pub(crate) fn push_event(&self, source: &str, level: &str, message: String) {
        self.emit_event(source, level, &message);
    }
}

impl From<NativeEventRecord> for NativeRuntimeEvent {
    fn from(value: NativeEventRecord) -> Self {
        Self {
            source: value.source,
            level: value.level,
            message: value.message,
            created_at: value.created_at,
            runtime_id: value.runtime_id,
            mode: value.mode,
            policy_signature: value.policy_signature,
            fingerprint_hash: value.fingerprint_hash,
            subsystem: value.subsystem,
        }
    }
}

pub(crate) struct ProxyTelemetryObserver {
    pub(crate) state: Arc<ProxyTelemetryState>,
}

impl RuntimeTelemetrySink for ProxyTelemetryObserver {
    fn on_client_slot_exhausted(&self) {
        self.state.slot_exhaustions.fetch_add(1, Ordering::Relaxed);
        self.state.push_event("proxy", "warn", "client rejected: at capacity".to_string());
    }

    fn on_listener_started(&self, bind_addr: std::net::SocketAddr, max_clients: usize, group_count: usize) {
        self.state.mark_running(bind_addr.to_string(), max_clients, group_count);
    }

    fn on_listener_stopped(&self) {
        self.state.mark_stopped();
    }

    fn on_client_accepted(&self) {
        self.state.on_client_accepted();
    }

    fn on_client_finished(&self) {
        self.state.on_client_finished();
    }

    fn on_client_error(&self, error: &std::io::Error) {
        self.state.on_client_io_error(error);
    }

    fn on_route_selected(
        &self,
        target: std::net::SocketAddr,
        group_index: usize,
        host: Option<&str>,
        phase: &'static str,
    ) {
        self.state.on_route_selected(target.to_string(), group_index, host.map(ToOwned::to_owned), phase);
    }

    fn on_failure_classified(&self, target: std::net::SocketAddr, failure: &ClassifiedFailure, host: Option<&str>) {
        self.state.total_errors.fetch_add(1, Ordering::Relaxed);
        if failure.action.as_str() == "retry_with_matching_group" {
            self.state.network_errors.fetch_add(1, Ordering::Relaxed);
        }
        self.state.on_failure_classified(target.to_string(), failure, host.map(ToOwned::to_owned));
    }

    fn on_upstream_connected(&self, upstream_addr: std::net::SocketAddr, rtt_ms: Option<u64>) {
        self.state.on_upstream_connected(upstream_addr.to_string(), rtt_ms);
    }

    fn on_tls_handshake_completed(&self, _target: std::net::SocketAddr, latency_ms: u64) {
        self.state.on_tls_handshake_completed(latency_ms);
    }

    fn on_route_advanced(
        &self,
        target: std::net::SocketAddr,
        from_group: usize,
        to_group: usize,
        trigger: u32,
        host: Option<&str>,
    ) {
        self.state.on_route_advanced(target.to_string(), from_group, to_group, trigger, host.map(ToOwned::to_owned));
    }

    fn on_adaptive_override(
        &self,
        target: std::net::SocketAddr,
        group_index: usize,
        trigger_mask: u32,
        failure_class: &'static str,
        host: Option<&str>,
        reason: &'static str,
    ) {
        self.state.on_adaptive_override(
            target.to_string(),
            group_index,
            trigger_mask,
            failure_class,
            host.map(ToOwned::to_owned),
            reason,
        );
    }

    fn on_retry_paced(&self, target: std::net::SocketAddr, group_index: usize, reason: &'static str, backoff_ms: u64) {
        self.state.on_retry_paced(target.to_string(), group_index, reason, backoff_ms);
    }

    fn on_host_autolearn_state(
        &self,
        enabled: bool,
        learned_host_count: usize,
        penalized_host_count: usize,
        blocked_host_count: usize,
        last_block_signal: Option<&str>,
        last_block_provider: Option<&str>,
    ) {
        self.state.set_autolearn_state(
            enabled,
            learned_host_count,
            penalized_host_count,
            blocked_host_count,
            last_block_signal,
            last_block_provider,
        );
    }

    fn on_host_autolearn_event(&self, action: &'static str, host: Option<&str>, group_index: Option<usize>) {
        self.state.on_autolearn_event(action, host.map(ToOwned::to_owned), group_index);
    }

    fn on_telegram_dc_detected(&self, target: std::net::SocketAddr, dc: u8) {
        self.state.on_telegram_dc_detected(target.to_string(), dc);
    }

    fn on_ws_tunnel_escalation(&self, target: std::net::SocketAddr, dc: u8, success: bool) {
        self.state.on_ws_tunnel_escalation(target.to_string(), dc, success);
    }

    fn on_quic_migration_status(&self, target: std::net::SocketAddr, status: &'static str, reason: &'static str) {
        self.state.on_quic_migration_status(target.to_string(), status, reason);
    }

    fn on_morph_hint_applied(&self, target: std::net::SocketAddr, policy_id: &str, family: &str) {
        self.state.on_morph_hint_applied(target.to_string(), policy_id, family);
    }

    fn on_morph_rollback(&self, target: std::net::SocketAddr, policy_id: &str, reason: &str) {
        self.state.on_morph_rollback(target.to_string(), policy_id, reason);
    }
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::SocketAddr;

    use android_support::{EventRingBuffers, EventRingLayer, RingConfig};
    use golden_test_support::{assert_text_golden, canonicalize_json_with};
    use ripdpi_failure_classifier::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};
    use ripdpi_runtime::RuntimeTelemetrySink;
    use serde_json::Value;
    use tracing_subscriber::prelude::*;

    fn assert_proxy_snapshot_golden(name: &str, snapshot: &NativeRuntimeSnapshot) {
        let actual = canonicalize_json_with(
            &serde_json::to_string(snapshot).expect("serialize proxy snapshot"),
            scrub_runtime_timestamps,
        )
        .expect("canonicalize proxy telemetry");
        assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
    }

    fn scrub_runtime_timestamps(value: &mut Value) {
        match value {
            Value::Array(items) => {
                for item in items {
                    scrub_runtime_timestamps(item);
                }
            }
            Value::Object(map) => {
                for (key, item) in map.iter_mut() {
                    if matches!(key.as_str(), "createdAt" | "capturedAt") {
                        *item = Value::from(0);
                    } else {
                        scrub_runtime_timestamps(item);
                    }
                }
            }
            _ => {}
        }
    }

    fn with_proxy_event_capture<R>(f: impl FnOnce(EventRingBuffers) -> R) -> R {
        let buffers = EventRingBuffers::new(RingConfig::default());
        let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
        tracing::subscriber::with_default(subscriber, || f(buffers))
    }

    fn snapshot_with_captured_events(state: &ProxyTelemetryState, buffers: &EventRingBuffers) -> NativeRuntimeSnapshot {
        let mut snapshot = state.snapshot();
        snapshot.native_events = buffers.drain_proxy().into_iter().map(NativeRuntimeEvent::from).collect();
        snapshot
    }

    #[test]
    fn proxy_telemetry_observer_updates_snapshot_and_drains_events() {
        with_proxy_event_capture(|buffers| {
            let state = Arc::new(ProxyTelemetryState::new(None));
            let observer = ProxyTelemetryObserver { state: state.clone() };
            let listener = SocketAddr::from(([127, 0, 0, 1], 1080));
            let target = SocketAddr::from(([203, 0, 113, 10], 443));

            observer.on_listener_started(listener, 256, 3);
            observer.on_client_accepted();
            observer.on_route_selected(target, 1, Some("example.org"), "connect");
            observer.on_upstream_connected(target, Some(87));
            observer.on_route_advanced(target, 1, 2, 7, Some("example.org"));
            observer.on_host_autolearn_state(true, 4, 1, 2, Some("tcp_reset"), Some("rkn"));
            observer.on_host_autolearn_event("host_promoted", Some("example.org"), Some(2));
            observer.on_client_error(&std::io::Error::other("boom"));
            observer.on_client_finished();

            let first = snapshot_with_captured_events(&state, &buffers);
            assert_eq!(first.state, "running");
            assert_eq!(first.health, "degraded");
            assert_eq!(first.active_sessions, 0);
            assert_eq!(first.total_sessions, 1);
            assert_eq!(first.total_errors, 1);
            assert_eq!(first.route_changes, 1);
            assert_eq!(first.last_route_group, Some(2));
            assert_eq!(first.listener_address.as_deref(), Some("127.0.0.1:1080"));
            assert_eq!(first.upstream_address.as_deref(), Some("203.0.113.10:443"));
            assert_eq!(first.upstream_rtt_ms, Some(87));
            assert_eq!(first.last_target.as_deref(), Some("203.0.113.10:443"));
            assert_eq!(first.last_host.as_deref(), Some("example.org"));
            assert_eq!(first.last_error.as_deref(), Some("boom"));
            assert!(first.autolearn_enabled);
            assert_eq!(first.learned_host_count, 4);
            assert_eq!(first.penalized_host_count, 1);
            assert_eq!(first.blocked_host_count, 2);
            assert_eq!(first.last_block_signal.as_deref(), Some("tcp_reset"));
            assert_eq!(first.last_block_provider.as_deref(), Some("rkn"));
            assert_eq!(first.last_autolearn_host.as_deref(), Some("example.org"));
            assert_eq!(first.last_autolearn_group, Some(2));
            assert_eq!(first.last_autolearn_action.as_deref(), Some("host_promoted"));
            assert_eq!(first.native_events.len(), 5);

            let second = snapshot_with_captured_events(&state, &buffers);
            assert!(second.native_events.is_empty());
            assert_eq!(second.total_sessions, 1);

            observer.on_listener_stopped();
            let stopped = snapshot_with_captured_events(&state, &buffers);
            assert_eq!(stopped.state, "idle");
            assert_eq!(stopped.active_sessions, 0);
            assert_eq!(stopped.native_events.len(), 1);
        });
    }

    #[test]
    fn proxy_retry_pacing_telemetry_tracks_backoff_and_diversification_separately() {
        with_proxy_event_capture(|buffers| {
            let state = Arc::new(ProxyTelemetryState::new(None));
            let observer = ProxyTelemetryObserver { state: state.clone() };
            let target = SocketAddr::from(([203, 0, 113, 10], 443));

            observer.on_retry_paced(target, 1, "same_signature_retry", 700);
            let paced = snapshot_with_captured_events(&state, &buffers);
            assert_eq!(paced.retry_paced_count, 1);
            assert_eq!(paced.last_retry_backoff_ms, Some(700));
            assert_eq!(paced.last_retry_reason.as_deref(), Some("same_signature_retry"));
            assert_eq!(paced.candidate_diversification_count, 0);
            assert_eq!(paced.native_events.len(), 1);

            observer.on_retry_paced(target, 2, "candidate_order_diversified", 0);
            let diversified = snapshot_with_captured_events(&state, &buffers);
            assert_eq!(diversified.retry_paced_count, 1);
            assert_eq!(diversified.last_retry_backoff_ms, None);
            assert_eq!(diversified.last_retry_reason.as_deref(), Some("candidate_order_diversified"));
            assert_eq!(diversified.candidate_diversification_count, 1);
            assert_eq!(diversified.native_events.len(), 1);
        });
    }

    #[test]
    fn proxy_ws_tunnel_events_preserve_proxy_snapshot_contract() {
        with_proxy_event_capture(|buffers| {
            let state = Arc::new(ProxyTelemetryState::new(None));
            let observer = ProxyTelemetryObserver { state: state.clone() };
            let target = SocketAddr::from(([149, 154, 167, 51], 443));

            observer.on_telegram_dc_detected(target, 2);
            observer.on_ws_tunnel_escalation(target, 2, false);

            let snapshot = snapshot_with_captured_events(&state, &buffers);
            assert_eq!(snapshot.last_target.as_deref(), Some("149.154.167.51:443"));
            assert_eq!(snapshot.native_events.len(), 2);
            assert!(snapshot.native_events[0].message.contains("telegram dc detected"));
            assert!(snapshot.native_events[1].message.contains("ws tunnel escalation"));
            assert_eq!(snapshot.native_events[0].level, "info");
            assert_eq!(snapshot.native_events[1].level, "warn");
        });
    }

    #[test]
    fn failure_classification_telemetry_records_strategy_execution_context() {
        with_proxy_event_capture(|buffers| {
            let state = Arc::new(ProxyTelemetryState::new(None));
            let observer = ProxyTelemetryObserver { state: state.clone() };
            let target = SocketAddr::from(([203, 0, 113, 10], 443));
            let failure = ClassifiedFailure::new(
                FailureClass::StrategyExecutionFailure,
                FailureStage::FirstWrite,
                FailureAction::RetryWithMatchingGroup,
                "desync action=set_ttl: Invalid argument (os error 22)",
            )
            .with_tag("action", "set_ttl")
            .with_tag("errno", "22");

            observer.on_failure_classified(target, &failure, Some("example.org"));

            let snapshot = snapshot_with_captured_events(&state, &buffers);
            assert_eq!(snapshot.last_failure_class.as_deref(), Some("strategy_execution_failure"));
            assert_eq!(snapshot.last_fallback_action.as_deref(), Some("retry_with_matching_group"));
            assert_eq!(snapshot.last_error.as_deref(), Some("desync action=set_ttl: Invalid argument (os error 22)"));
            assert_eq!(snapshot.network_errors, 1);
            assert!(snapshot.native_events.iter().any(|event| {
                event.message.contains("class=strategy_execution_failure") && event.message.contains("action=set_ttl")
            }));
        });
    }

    #[test]
    fn proxy_telemetry_snapshots_match_goldens() {
        with_proxy_event_capture(|buffers| {
            let idle = snapshot_with_captured_events(&ProxyTelemetryState::new(None), &buffers);
            assert_proxy_snapshot_golden("proxy_idle", &idle);

            let state = Arc::new(ProxyTelemetryState::new(None));
            let observer = ProxyTelemetryObserver { state: state.clone() };
            let listener = SocketAddr::from(([127, 0, 0, 1], 1080));
            let target = SocketAddr::from(([203, 0, 113, 10], 443));

            observer.on_listener_started(listener, 256, 3);
            observer.on_client_accepted();
            observer.on_route_selected(target, 1, Some("example.org"), "connect");
            observer.on_upstream_connected(target, Some(87));
            observer.on_route_advanced(target, 1, 2, 7, Some("example.org"));
            observer.on_host_autolearn_state(true, 4, 1, 2, Some("tcp_reset"), Some("rkn"));
            observer.on_host_autolearn_event("host_promoted", Some("example.org"), Some(2));
            observer.on_client_error(&std::io::Error::other("boom"));
            observer.on_client_finished();

            let running = snapshot_with_captured_events(&state, &buffers);
            assert_proxy_snapshot_golden("proxy_running_degraded_first_poll", &running);

            let drained = snapshot_with_captured_events(&state, &buffers);
            assert_proxy_snapshot_golden("proxy_running_degraded_second_poll", &drained);

            observer.on_listener_stopped();
            let stopped = snapshot_with_captured_events(&state, &buffers);
            assert_proxy_snapshot_golden("proxy_stopped", &stopped);
        });
    }

    #[test]
    fn clear_last_error_resets_field_via_arc_swap() {
        let state = ProxyTelemetryState::new(None);
        state.on_client_error("first failure".to_string());
        assert_eq!(state.snapshot().last_error.as_deref(), Some("first failure"));

        state.clear_last_error();
        assert!(state.snapshot().last_error.is_none());
    }

    #[test]
    fn events_and_strings_are_independent_after_split() {
        with_proxy_event_capture(|buffers| {
            let state = ProxyTelemetryState::new(None);

            // Push an event without updating any string field
            state.push_event("test", "info", "standalone event".to_string());
            let snap = snapshot_with_captured_events(&state, &buffers);
            assert!(snap.listener_address.is_none());
            assert!(snap.last_error.is_none());

            // Update a string field without pushing an event
            state.on_upstream_connected("10.0.0.1:443".to_string(), Some(42));
            let snap2 = snapshot_with_captured_events(&state, &buffers);
            assert!(snap2.native_events.is_empty(), "events were drained in previous snapshot");
            assert_eq!(snap2.upstream_address.as_deref(), Some("10.0.0.1:443"));
            assert_eq!(snap2.upstream_rtt_ms, Some(42));
        });
    }

    #[test]
    fn concurrent_writers_do_not_lose_string_updates() {
        use std::sync::Barrier;

        let state = Arc::new(ProxyTelemetryState::new(None));
        let barrier = Arc::new(Barrier::new(2));
        let iterations = 500;

        // Thread A: repeatedly sets last_error
        let state_a = state.clone();
        let barrier_a = barrier.clone();
        let thread_a = std::thread::spawn(move || {
            barrier_a.wait();
            for i in 0..iterations {
                state_a.on_client_error(format!("error-{i}"));
            }
        });

        // Thread B: repeatedly sets route/target
        let state_b = state.clone();
        let barrier_b = barrier.clone();
        let thread_b = std::thread::spawn(move || {
            barrier_b.wait();
            for i in 0..iterations {
                state_b.on_route_selected(format!("target-{i}"), i % 3, Some(format!("host-{i}")), "connect");
            }
        });

        thread_a.join().expect("thread_a panicked");
        thread_b.join().expect("thread_b panicked");

        // Final snapshot should have the last values from both threads
        let snap = state.snapshot();
        assert!(snap.last_error.is_some(), "last_error should be set");
        assert!(snap.last_target.is_some(), "last_target should be set");
        assert!(snap.last_host.is_some(), "last_host should be set");

        // Verify values are from the last iterations (rcu ensures no lost updates)
        assert!(
            snap.last_error.as_ref().unwrap().starts_with("error-"),
            "last_error should be from thread_a: {:?}",
            snap.last_error,
        );
        assert!(
            snap.last_target.as_ref().unwrap().starts_with("target-"),
            "last_target should be from thread_b: {:?}",
            snap.last_target,
        );
    }

    #[test]
    fn snapshot_reads_do_not_block_under_concurrent_writes() {
        use std::sync::Barrier;

        let state = Arc::new(ProxyTelemetryState::new(None));
        let barrier = Arc::new(Barrier::new(2));
        let iterations = 500;

        let writer_state = state.clone();
        let writer_barrier = barrier.clone();
        let writer = std::thread::spawn(move || {
            writer_barrier.wait();
            for i in 0..iterations {
                writer_state.on_client_error(format!("err-{i}"));
            }
        });

        let reader_state = state.clone();
        let reader_barrier = barrier.clone();
        let reader = std::thread::spawn(move || {
            reader_barrier.wait();
            let mut snapshots = 0u32;
            for _ in 0..iterations {
                let snap = reader_state.snapshot();
                // String fields should never be partially updated
                if let Some(ref error) = snap.last_error {
                    assert!(error.starts_with("err-"), "corrupted error field: {error}");
                }
                snapshots += 1;
            }
            snapshots
        });

        writer.join().expect("writer panicked");
        let count = reader.join().expect("reader panicked");
        assert_eq!(count, iterations as u32);
    }

    #[test]
    fn proxy_snapshot_field_manifest_matches_contract_fixture() {
        use golden_test_support::{assert_contract_fixture, extract_field_paths};

        let snapshot = NativeRuntimeSnapshot {
            source: "proxy".to_string(),
            state: "running".to_string(),
            health: "healthy".to_string(),
            active_sessions: 1,
            total_sessions: 10,
            total_errors: 2,
            network_errors: 1,
            route_changes: 3,
            retry_paced_count: 1,
            last_retry_backoff_ms: Some(500),
            last_retry_reason: Some("backoff".to_string()),
            candidate_diversification_count: 1,
            last_route_group: Some(0),
            last_failure_class: Some("tcp_reset".to_string()),
            last_fallback_action: Some("retry_with_matching_group".to_string()),
            adaptive_override_active: true,
            adaptive_trigger_mask: Some(7),
            adaptive_last_trigger: Some("dns_timeout".to_string()),
            adaptive_override_reason: Some("forced_reorder".to_string()),
            strategy_pack_id: Some("baseline-stable".to_string()),
            strategy_pack_version: Some("2026.04.1".to_string()),
            tls_profile_id: Some("chrome_stable".to_string()),
            tls_profile_catalog_version: Some("v1".to_string()),
            morph_policy_id: Some("balanced".to_string()),
            morph_hint_family: Some("tcp:wide:entropy".to_string()),
            morph_rollback_reason: Some("direct_path_capability_downgrade".to_string()),
            quic_migration_status: Some("rebind_only".to_string()),
            quic_migration_reason: Some("udp_source_port_rebind_after_handshake".to_string()),
            pt_runtime_kind: None,
            pt_runtime_state: None,
            listener_address: Some("127.0.0.1:1080".to_string()),
            upstream_address: Some("203.0.113.10:443".to_string()),
            upstream_rtt_ms: Some(42),
            last_target: Some("203.0.113.10:443".to_string()),
            last_host: Some("example.org".to_string()),
            last_error: Some("connection reset".to_string()),
            autolearn_enabled: true,
            learned_host_count: 5,
            penalized_host_count: 1,
            blocked_host_count: 2,
            last_block_signal: Some("tcp_reset".to_string()),
            last_block_provider: Some("rkn".to_string()),
            last_autolearn_host: Some("example.org".to_string()),
            last_autolearn_group: Some(0),
            last_autolearn_action: Some("group_penalized".to_string()),
            slot_exhaustions: 1,
            profile_id: Some("relay-profile".to_string()),
            protocol_kind: Some("hysteria2".to_string()),
            tcp_capable: Some(true),
            udp_capable: Some(true),
            fallback_mode: None,
            last_handshake_error: None,
            chain_entry_state: Some("connected".to_string()),
            chain_exit_state: Some("ready".to_string()),
            tunnel_stats: TunnelStatsSnapshot { tx_packets: 100, tx_bytes: 5000, rx_packets: 80, rx_bytes: 4000 },
            native_events: vec![NativeRuntimeEvent {
                source: "proxy".to_string(),
                level: "info".to_string(),
                message: "test".to_string(),
                created_at: 1000,
                runtime_id: Some("rt-1".to_string()),
                mode: Some("auto".to_string()),
                policy_signature: Some("sig".to_string()),
                fingerprint_hash: Some("hash".to_string()),
                subsystem: Some("proxy".to_string()),
            }],
            latency_distributions: Some(LatencyDistributions {
                dns_resolution: Some(ripdpi_telemetry::LatencyPercentiles {
                    p50: 10,
                    p95: 20,
                    p99: 30,
                    min: 1,
                    max: 50,
                    count: 100,
                }),
                tcp_connect: Some(ripdpi_telemetry::LatencyPercentiles {
                    p50: 15,
                    p95: 25,
                    p99: 35,
                    min: 2,
                    max: 60,
                    count: 200,
                }),
                tls_handshake: Some(ripdpi_telemetry::LatencyPercentiles {
                    p50: 20,
                    p95: 30,
                    p99: 40,
                    min: 5,
                    max: 80,
                    count: 150,
                }),
            }),
            captured_at: 1000,
        };

        let json = serde_json::to_value(&snapshot).expect("serialize proxy snapshot");
        let paths = extract_field_paths(&json);
        let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
        assert_contract_fixture("proxy_snapshot_fields.json", &manifest);
    }

    #[test]
    fn proxy_event_field_manifest_matches_contract_fixture() {
        use golden_test_support::{assert_contract_fixture, extract_field_paths};

        let event = NativeRuntimeEvent {
            source: "proxy".to_string(),
            level: "info".to_string(),
            message: "test".to_string(),
            created_at: 1000,
            runtime_id: Some("rt-1".to_string()),
            mode: Some("auto".to_string()),
            policy_signature: Some("sig".to_string()),
            fingerprint_hash: Some("hash".to_string()),
            subsystem: Some("proxy".to_string()),
        };

        let json = serde_json::to_value(&event).expect("serialize event");
        let paths = extract_field_paths(&json);
        let manifest = serde_json::to_string_pretty(&paths).expect("serialize field paths");
        assert_contract_fixture("proxy_event_fields.json", &manifest);
    }
}
