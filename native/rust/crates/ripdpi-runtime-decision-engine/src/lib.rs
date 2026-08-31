//! `RuntimeDecisionEngine` — the single boundary policy/adaptive/direct-path
//! decisions flow through.
//!
//! The workspace already had the **port traits** ([`PolicySelectionPort`],
//! [`DirectPathLearningPort`], [`AdaptiveContextPort`], [`RetryPacingPort`],
//! …) and the **snapshot shape** ([`RuntimeDecisionSnapshot`]) in
//! `ripdpi-runtime-decision-ports`. Concrete port impls live in
//! `ripdpi-runtime-services` (`ServicesStateHandle`). What was missing was a
//! single **engine** that consumers can hold instead of composing the ports
//! + scattered helpers themselves.
//!
//! This crate is intentionally a **thin facade**, separate from
//! `ripdpi-runtime-services`, so the services crate stays under its
//! `dependency-hub` limit and the decision-engine seam remains visible as
//! its own layering boundary.
//!
//! [`RuntimeDecisionEngine`] is that single boundary. It:
//!
//! - holds a [`ServicesStateHandle`] so it has access to every port impl
//!   without `proxy-runtime` re-discovering them;
//! - exposes one **per-runtime** entry point — [`decide_runtime`] —
//!   producing a [`RuntimeDecisionSnapshot`] from the current config /
//!   wire context / network snapshot;
//! - exposes one **per-flow** entry point — [`decide_flow_route`] —
//!   delegating to `PolicySelectionPort::select_initial`;
//! - exposes [`direct_path`] hooks delegating to `DirectPathLearningPort`.
//!
//! Behaviour today is byte-identical to invoking the underlying helpers
//! directly — the engine is a **delegating facade**, not a replacement
//! decision algorithm. `proxy-runtime` constructs it through
//! `ripdpi-proxy-runtime-adapter`, emits the per-runtime snapshot through
//! `RuntimeTelemetrySink::on_runtime_decision_snapshot`, and still keeps the
//! field as the migration seam for per-flow routing call sites. The
//! behaviour-parity tests in this module pin the current delegation invariant
//! so later enrichment can move behind this boundary without changing call
//! sites.
//!
//! # Scope today vs. follow-up
//!
//! In scope today: engine struct, typed `Inputs` / `Outputs`,
//! `decide_runtime`, `decide_flow_route`, direct-path observation hooks,
//! `proxy-runtime` startup snapshot emission, and parity tests proving the
//! engine reproduces snapshot derivation byte-identically.
//!
//! Out of scope (follow-up): migrating in-place per-flow logic from the
//! `decision_helpers` re-export wrappers into [`decide_flow_route`] call
//! sites and enriching the engine with adaptive/retry hints. These remain
//! sequenced separately to keep each refactor reviewable.

#![forbid(unsafe_code)]

use std::net::SocketAddr;
use std::sync::Arc;

use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_decision_ports::policy_ports::{
    ConnectionRoute, DirectPathLearningObserver, DirectPathLearningPort, GeoMatcher, PolicySelectionPort,
    TransportProtocol,
};
use ripdpi_runtime_decision_ports::snapshots::RuntimeDecisionSnapshot;

use ripdpi_runtime_services::ServicesStateHandle;

/// Inputs the engine needs to compute the per-runtime decision snapshot.
///
/// Field-by-field intent mirrors the goal-statement inputs:
///
/// - `config` — runtime config (host autolearn, desync groups, scope key).
/// - `context` — wire context that crosses the JNI boundary — encrypted
///   DNS, preferred edges, direct-path capabilities, morph policy.
/// - `network_scope_key` — current network's stable scope key, when known;
///   independent of `config` so callers that already resolved the key (the
///   common case) avoid re-computing it.
///
/// Everything the engine needs to project a [`RuntimeDecisionSnapshot`] is
/// reachable through these references. Policy memory state and adaptive
/// hints are reached **through the engine's ports** (`PolicySelectionPort`,
/// adaptive ports) rather than as struct fields, because those engines are
/// stateful — passing a snapshot would lose the per-target memory and
/// duplicate it across call sites.
#[derive(Debug, Clone, Copy)]
pub struct RuntimeDecisionInputs<'a> {
    pub config: Option<&'a RuntimeConfig>,
    pub context: Option<&'a ProxyRuntimeContext>,
    pub network_scope_key: Option<&'a str>,
}

impl<'a> RuntimeDecisionInputs<'a> {
    /// Construct an empty input set — useful for default scenarios and
    /// tests. Equivalent to "no config, no context, no scope key."
    pub const fn empty() -> Self {
        Self { config: None, context: None, network_scope_key: None }
    }
}

/// Outputs the engine produces for one [`decide_runtime`] call.
///
/// Today this holds the [`RuntimeDecisionSnapshot`] only — the snapshot
/// already carries DNS, relay, warp, strategy-pack and direct-path
/// sub-decisions (see `RuntimeDecisionSnapshot` doc). The struct exists as
/// its own type so follow-up commits can add fields (selected route,
/// resolved retry penalties, etc.) without changing the engine's call
/// shape.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RuntimeDecisionOutputs {
    pub snapshot: RuntimeDecisionSnapshot,
}

/// Inputs the engine needs to select an outbound flow's initial route.
///
/// All fields mirror the existing [`PolicySelectionPort::select_initial`]
/// signature
/// — the engine is a delegating facade today; future commits can enrich
/// this with adaptive-derived hints without changing the call site.
///
/// `Debug` is omitted because [`GeoMatcher`] is a behavioral trait that
/// does not require `Debug`; per-flow inputs are short-lived and never
/// formatted.
#[derive(Clone, Copy)]
pub struct FlowRouteInputs<'a> {
    pub target: SocketAddr,
    pub payload: Option<&'a [u8]>,
    pub host: Option<&'a str>,
    pub allow_unknown_payload: bool,
    pub transport: TransportProtocol,
    pub geo: Option<&'a dyn GeoMatcher>,
}

/// The single decision boundary policy/adaptive/direct-path calls flow
/// through.
///
/// Holds a [`ServicesStateHandle`] for cheap access to every port impl;
/// cloneable so it can be stored alongside the runtime state without
/// indirection.
#[derive(Clone)]
pub struct RuntimeDecisionEngine {
    services: ServicesStateHandle,
}

impl RuntimeDecisionEngine {
    /// Construct an engine over the supplied services handle.
    pub fn new(services: ServicesStateHandle) -> Self {
        Self { services }
    }

    /// Access the underlying services handle — used by call sites that
    /// still talk to individual port traits directly during the migration.
    pub fn services(&self) -> &ServicesStateHandle {
        &self.services
    }

    /// Compute the per-runtime decision snapshot for the current
    /// config + context + scope.
    ///
    /// Byte-identical to calling
    /// [`RuntimeDecisionSnapshot::from_context`] on `inputs.context`
    /// today — the parity test in this module enforces that and is the
    /// safety net for future enrichment.
    pub fn decide_runtime(&self, inputs: &RuntimeDecisionInputs<'_>) -> RuntimeDecisionOutputs {
        let _ = (inputs.config, inputs.network_scope_key);
        RuntimeDecisionOutputs { snapshot: RuntimeDecisionSnapshot::from_context(inputs.context) }
    }

    /// Select the initial route for one outbound flow.
    ///
    /// Delegates to [`PolicySelectionPort::select_initial`] verbatim. This lives
    /// on the engine so future enrichment (consulting adaptive hints, applying
    /// retry penalties) becomes additive in this method, not by editing
    /// every call site.
    pub fn decide_flow_route(&self, inputs: &FlowRouteInputs<'_>) -> Option<ConnectionRoute> {
        // The port traits are implemented on `ServicesStateHandle`, not on
        // the inner `ServicesState`; bind through the handle directly.
        PolicySelectionPort::select_initial(
            &self.services,
            inputs.target,
            inputs.payload,
            inputs.host,
            inputs.allow_unknown_payload,
            inputs.transport,
            inputs.geo,
        )
    }

    /// Direct-path TCP-success observation hook.
    ///
    /// Delegates to [`DirectPathLearningPort::note_direct_path_tcp_success`].
    /// Exposed on the engine so call sites do not import a concrete
    /// direct-path learner.
    pub fn note_direct_path_tcp_success(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        strategy_family: Option<&str>,
        observer: Option<&dyn DirectPathLearningObserver>,
    ) {
        DirectPathLearningPort::note_direct_path_tcp_success(&self.services, host, targets, strategy_family, observer);
    }

    /// Direct-path QUIC-success observation hook. See
    /// [`Self::note_direct_path_tcp_success`].
    pub fn note_direct_path_quic_success(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        observer: Option<&dyn DirectPathLearningObserver>,
    ) {
        DirectPathLearningPort::note_direct_path_quic_success(&self.services, host, targets, observer);
    }

    /// Direct-path "all IPs failed" observation hook. See
    /// [`Self::note_direct_path_tcp_success`].
    pub fn note_direct_path_all_ips_failed(
        &self,
        host: Option<&str>,
        targets: &[SocketAddr],
        observer: Option<&dyn DirectPathLearningObserver>,
    ) {
        DirectPathLearningPort::note_direct_path_all_ips_failed(&self.services, host, targets, observer);
    }
}

/// Construct a [`RuntimeDecisionEngine`] from an `Arc<ServicesStateHandle>`
/// — sugar for the proxy-runtime assembly site.
impl From<Arc<ServicesStateHandle>> for RuntimeDecisionEngine {
    fn from(services: Arc<ServicesStateHandle>) -> Self {
        Self::new(ServicesStateHandle::clone(&services))
    }
}

impl From<ServicesStateHandle> for RuntimeDecisionEngine {
    fn from(services: ServicesStateHandle) -> Self {
        Self::new(services)
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use ripdpi_config::RuntimeConfig;
    use ripdpi_proxy_config::{
        ProxyDirectPathCapability, ProxyEncryptedDnsContext, ProxyMorphPolicy, ProxyPreferredEdge, ProxyRuntimeContext,
    };
    use ripdpi_runtime_services::ServicesState;

    use super::*;

    fn populated_context() -> ProxyRuntimeContext {
        let mut preferred_edges = BTreeMap::new();
        preferred_edges.insert(
            "telegram".to_string(),
            vec![ProxyPreferredEdge { ip: "203.0.113.10".to_string(), transport_kind: "warp".to_string() }],
        );
        ProxyRuntimeContext {
            encrypted_dns: Some(ProxyEncryptedDnsContext {
                resolver_id: Some("adguard".to_string()),
                protocol: "doh".to_string(),
                host: "dns.example".to_string(),
                port: 443,
                tls_server_name: Some("dns.example".to_string()),
                bootstrap_ips: vec!["203.0.113.53".to_string()],
                doh_url: Some("https://dns.example/dns-query".to_string()),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            }),
            protect_path: Some("/run/ripdpi/protect.sock".to_string()),
            preferred_edges,
            direct_path_capabilities: vec![ProxyDirectPathCapability {
                authority: "example.com".to_string(),
                quic_usable: Some(true),
                udp_usable: Some(false),
                fallback_required: Some(false),
                repeated_handshake_failure_class: Some("none".to_string()),
                transport_policy_version: 4,
                ip_set_digest: "digest".to_string(),
                dns_classification: Some("clean".to_string()),
                quic_mode: "ALLOW".to_string(),
                preferred_stack: "H3".to_string(),
                dns_mode: "ENCRYPTED".to_string(),
                tcp_family: "tlsrec".to_string(),
                outcome: "TRANSPARENT_OK".to_string(),
                transport_class: Some("direct".to_string()),
                reason_code: None,
                cooldown_until: None,
                updated_at: 42,
            }],
            morph_policy: Some(ProxyMorphPolicy {
                id: "balanced".to_string(),
                first_flight_size_min: 320,
                first_flight_size_max: 640,
                padding_envelope_min: 16,
                padding_envelope_max: 64,
                entropy_target_permil: 3400,
                tcp_burst_cadence_ms: vec![0, 12],
                tls_burst_cadence_ms: vec![0, 8],
                quic_burst_profile: "compat_burst".to_string(),
                fake_packet_shape_profile: "compat_default".to_string(),
            }),
            connection_concurrency: None,
        }
    }

    fn engine() -> RuntimeDecisionEngine {
        let services = ServicesState::new(RuntimeConfig::default(), None, None);
        RuntimeDecisionEngine::new(ServicesStateHandle::new(services))
    }

    /// The per-runtime snapshot the engine emits is byte-identical to
    /// `RuntimeDecisionSnapshot::from_context`. This is the safety net for
    /// every future enrichment of the engine's logic.
    #[test]
    fn decide_runtime_matches_direct_snapshot_derivation_for_a_populated_context() {
        let context = populated_context();
        let engine = engine();

        let direct = RuntimeDecisionSnapshot::from_context(Some(&context));
        let via_engine = engine.decide_runtime(&RuntimeDecisionInputs {
            config: None,
            context: Some(&context),
            network_scope_key: None,
        });

        assert_eq!(via_engine.snapshot, direct, "engine output must match direct derivation byte-for-byte");
    }

    /// `decide_runtime` with no context produces the default snapshot —
    /// matching `RuntimeDecisionSnapshot::from_context(None)`.
    #[test]
    fn decide_runtime_with_no_context_returns_default_snapshot() {
        let engine = engine();
        let output = engine.decide_runtime(&RuntimeDecisionInputs::empty());
        assert_eq!(output.snapshot, RuntimeDecisionSnapshot::default());
    }

    /// `RuntimeDecisionInputs::empty` carries no references — it is safe to
    /// call without owning any of the source values. Pins the constness of
    /// the constructor.
    #[test]
    fn empty_inputs_carry_no_references() {
        let inputs = RuntimeDecisionInputs::empty();
        assert!(inputs.config.is_none());
        assert!(inputs.context.is_none());
        assert!(inputs.network_scope_key.is_none());
    }

    /// The `From<ServicesStateHandle>` and `From<Arc<ServicesStateHandle>>`
    /// conversions both construct a usable engine that produces the same
    /// `decide_runtime` output as a directly-constructed one. We verify by
    /// behavior because the engine intentionally does not expose its
    /// internal handle for pointer comparisons (`ServicesStateHandle.0` is
    /// `pub(crate)`).
    #[test]
    fn engine_constructs_from_services_state_handle_and_arc_handle() {
        use std::sync::Arc;

        let services = ServicesState::new(RuntimeConfig::default(), None, None);
        let handle = ServicesStateHandle::new(services);

        let engine_direct = RuntimeDecisionEngine::new(handle.clone());
        let engine_from_handle: RuntimeDecisionEngine = handle.clone().into();
        let engine_from_arc: RuntimeDecisionEngine = Arc::new(handle).into();

        let inputs = RuntimeDecisionInputs::empty();
        let baseline = engine_direct.decide_runtime(&inputs).snapshot;
        assert_eq!(engine_from_handle.decide_runtime(&inputs).snapshot, baseline);
        assert_eq!(engine_from_arc.decide_runtime(&inputs).snapshot, baseline);
    }
}
