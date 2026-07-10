//! Feature-scoped runtime decision snapshots.
//!
//! The platform wire context is intentionally broad because it crosses the
//! Android/native boundary. Runtime decision callers should consume the
//! narrower feature slices below instead of treating that wire DTO as a facade.

use std::collections::BTreeMap;

use ripdpi_proxy_config::{
    ProxyDirectPathCapability, ProxyEncryptedDnsContext, ProxyMorphPolicy, ProxyPreferredEdge, ProxyRuntimeContext,
};

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RuntimeDecisionSnapshot {
    pub dns: DnsDecisionSnapshot,
    pub proxy: ProxyDecisionSnapshot,
    pub desync: DesyncDecisionSnapshot,
    pub quic: QuicDecisionSnapshot,
    pub adaptive: AdaptiveDecisionSnapshot,
    pub warp: WarpDecisionSnapshot,
    pub relay: RelayDecisionSnapshot,
    pub routing: RoutingDecisionSnapshot,
    pub ui: UiDecisionSnapshot,
    pub strategy_pack: StrategyPackDecisionSnapshot,
}

impl RuntimeDecisionSnapshot {
    pub fn from_context(context: Option<&ProxyRuntimeContext>) -> Self {
        let Some(context) = context else {
            return Self::default();
        };
        Self {
            dns: DnsDecisionSnapshot::from_context(context),
            proxy: ProxyDecisionSnapshot::from_context(context),
            desync: DesyncDecisionSnapshot::from_context(context),
            quic: QuicDecisionSnapshot::from_context(context),
            adaptive: AdaptiveDecisionSnapshot::from_context(context),
            warp: WarpDecisionSnapshot::from_context(context),
            relay: RelayDecisionSnapshot::from_context(context),
            routing: RoutingDecisionSnapshot::from_context(context),
            ui: UiDecisionSnapshot::from_context(context),
            strategy_pack: StrategyPackDecisionSnapshot::from_context(context),
        }
    }
}

impl From<Option<&ProxyRuntimeContext>> for RuntimeDecisionSnapshot {
    fn from(context: Option<&ProxyRuntimeContext>) -> Self {
        Self::from_context(context)
    }
}

impl From<&ProxyRuntimeContext> for RuntimeDecisionSnapshot {
    fn from(context: &ProxyRuntimeContext) -> Self {
        Self::from_context(Some(context))
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DnsDecisionSnapshot {
    pub encrypted_dns: Option<ProxyEncryptedDnsContext>,
    pub direct_path_dns_modes: Vec<DirectPathDnsMode>,
}

impl DnsDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        Self {
            encrypted_dns: context.encrypted_dns.clone(),
            direct_path_dns_modes: context
                .direct_path_capabilities
                .iter()
                .map(|capability| DirectPathDnsMode {
                    authority: capability.authority.clone(),
                    dns_mode: capability.dns_mode.clone(),
                    dns_classification: capability.dns_classification.clone(),
                })
                .collect(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirectPathDnsMode {
    pub authority: String,
    pub dns_mode: String,
    pub dns_classification: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ProxyDecisionSnapshot {
    pub protect_path: Option<String>,
}

impl ProxyDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        Self { protect_path: context.protect_path.clone() }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DesyncDecisionSnapshot {
    pub morph_policy: Option<ProxyMorphPolicy>,
}

impl DesyncDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        Self { morph_policy: context.morph_policy.clone() }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct QuicDecisionSnapshot {
    pub direct_path_modes: Vec<DirectPathQuicMode>,
    pub burst_profile: Option<String>,
}

impl QuicDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        Self {
            direct_path_modes: context
                .direct_path_capabilities
                .iter()
                .map(|capability| DirectPathQuicMode {
                    authority: capability.authority.clone(),
                    quic_usable: capability.quic_usable,
                    quic_mode: capability.quic_mode.clone(),
                    preferred_stack: capability.preferred_stack.clone(),
                })
                .collect(),
            burst_profile: non_empty_morph_policy_field(context.morph_policy.as_ref(), |policy| {
                &policy.quic_burst_profile
            }),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirectPathQuicMode {
    pub authority: String,
    pub quic_usable: Option<bool>,
    pub quic_mode: String,
    pub preferred_stack: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AdaptiveDecisionSnapshot {
    pub preferred_edges: BTreeMap<String, Vec<ProxyPreferredEdge>>,
    pub direct_path_capabilities: Vec<ProxyDirectPathCapability>,
    pub morph_policy_id: Option<String>,
}

impl AdaptiveDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        Self {
            preferred_edges: context.preferred_edges.clone(),
            direct_path_capabilities: context.direct_path_capabilities.clone(),
            morph_policy_id: context.morph_policy.as_ref().map(|policy| policy.id.clone()).filter(|id| !id.is_empty()),
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct WarpDecisionSnapshot {
    pub preferred_edges: BTreeMap<String, Vec<ProxyPreferredEdge>>,
}

impl WarpDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        Self { preferred_edges: context.preferred_edges.clone() }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RelayDecisionSnapshot {
    pub preferred_edges: BTreeMap<String, Vec<ProxyPreferredEdge>>,
}

impl RelayDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        Self { preferred_edges: context.preferred_edges.clone() }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RoutingDecisionSnapshot {
    pub direct_path_capabilities: Vec<ProxyDirectPathCapability>,
}

impl RoutingDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        Self { direct_path_capabilities: context.direct_path_capabilities.clone() }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct UiDecisionSnapshot {
    pub morph_policy_id: Option<String>,
}

impl UiDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        Self {
            morph_policy_id: context.morph_policy.as_ref().map(|policy| policy.id.clone()).filter(|id| !id.is_empty()),
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct StrategyPackDecisionSnapshot {
    pub fake_packet_shape_profile: Option<String>,
    pub tcp_burst_cadence_ms: Vec<i32>,
    pub tls_burst_cadence_ms: Vec<i32>,
}

impl StrategyPackDecisionSnapshot {
    pub fn from_context(context: &ProxyRuntimeContext) -> Self {
        let Some(policy) = context.morph_policy.as_ref() else {
            return Self::default();
        };
        Self {
            fake_packet_shape_profile: non_empty(policy.fake_packet_shape_profile.clone()),
            tcp_burst_cadence_ms: policy.tcp_burst_cadence_ms.clone(),
            tls_burst_cadence_ms: policy.tls_burst_cadence_ms.clone(),
        }
    }
}

fn non_empty_morph_policy_field(
    policy: Option<&ProxyMorphPolicy>,
    field: impl FnOnce(&ProxyMorphPolicy) -> &String,
) -> Option<String> {
    non_empty(field(policy?).clone())
}

fn non_empty(value: String) -> Option<String> {
    if value.is_empty() { None } else { Some(value) }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn runtime_context() -> ProxyRuntimeContext {
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

    #[test]
    fn root_snapshot_composes_feature_owned_slices() {
        let context = runtime_context();

        let snapshot = RuntimeDecisionSnapshot::from(&context);

        assert_eq!(snapshot.dns.encrypted_dns.as_ref().map(|dns| dns.protocol.as_str()), Some("doh"));
        assert_eq!(snapshot.dns.direct_path_dns_modes[0].dns_mode, "ENCRYPTED");
        assert_eq!(snapshot.proxy.protect_path.as_deref(), Some("/run/ripdpi/protect.sock"));
        assert_eq!(snapshot.desync.morph_policy.as_ref().map(|policy| policy.id.as_str()), Some("balanced"));
        assert_eq!(snapshot.quic.direct_path_modes[0].preferred_stack, "H3");
        assert_eq!(snapshot.quic.burst_profile.as_deref(), Some("compat_burst"));
        assert_eq!(snapshot.adaptive.morph_policy_id.as_deref(), Some("balanced"));
        assert_eq!(snapshot.warp.preferred_edges["telegram"][0].transport_kind, "warp");
        assert_eq!(snapshot.relay.preferred_edges["telegram"][0].ip, "203.0.113.10");
        assert_eq!(snapshot.routing.direct_path_capabilities[0].authority, "example.com");
        assert_eq!(snapshot.ui.morph_policy_id.as_deref(), Some("balanced"));
        assert_eq!(snapshot.strategy_pack.fake_packet_shape_profile.as_deref(), Some("compat_default"));
    }

    #[test]
    fn empty_context_builds_empty_feature_snapshots() {
        let snapshot = RuntimeDecisionSnapshot::from_context(None);

        assert_eq!(snapshot, RuntimeDecisionSnapshot::default());
    }
}
