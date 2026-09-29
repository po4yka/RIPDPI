//! Payload-only Lua planning for existing protected proxy sockets.

use std::io;
use std::net::SocketAddr;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};

use ripdpi_packets::{
    http_marker_info, parse_quic_initial, parse_quic_initial_layout, parse_tls, second_level_domain_span,
    tls_marker_info,
};
use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_strategy_config::{LoadedStrategyConfig, ProtocolName, StrategyMatch, parse_yaml_str};
use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, HttpDissect, L7Protocol,
    MarkerName, QuicDissect, StrategyContext, StrategyVerdict, TlsDissect,
};

pub use ripdpi_strategy_trait::DesyncPlan as PayloadPlan;

static NEXT_FLOW_ID: AtomicU64 = AtomicU64::new(1);

struct SocketLuaRule {
    matcher: StrategyMatch,
    tcp: StrategyRegistry,
    udp: StrategyRegistry,
}

/// Compiled Lua rules. Payloads and endpoint data remain in live calls only.
pub struct SocketLuaStrategies {
    rules: Vec<SocketLuaRule>,
}

impl SocketLuaStrategies {
    /// Loads configured scripts within the supplied app-private script jail.
    pub fn load(context: Option<&ProxyRuntimeContext>) -> io::Result<Option<Arc<Self>>> {
        let Some(context) = context else { return Ok(None) };
        let Some(yaml) = context.strategy_chain_yaml.as_deref().filter(|value| !value.trim().is_empty()) else {
            return Ok(None);
        };
        let base_dir = context
            .lua_script_base_dir
            .as_deref()
            .filter(|value| !value.trim().is_empty())
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "Lua script jail is required"))?;
        if !Path::new(base_dir).is_absolute() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Lua script jail must be absolute"));
        }
        Self::from_yaml(yaml, Path::new(base_dir))
    }

    fn from_yaml(yaml: &str, base_dir: &Path) -> io::Result<Option<Arc<Self>>> {
        let config = parse_yaml_str(yaml, base_dir).map_err(invalid_config)?;
        let mut rules = Vec::new();
        for strategy in config.strategies {
            if !strategy.steps.iter().any(|step| step.kind.registry_id() == "lua") {
                continue;
            }
            let matcher = StrategyMatch {
                hosts: strategy
                    .matcher
                    .hosts
                    .iter()
                    .map(|host| host.trim_end_matches('.').to_ascii_lowercase())
                    .collect(),
                ..strategy.matcher.clone()
            };
            let isolated = LoadedStrategyConfig {
                version: config.version,
                base_dir: config.base_dir.clone(),
                strategies: vec![strategy],
            };
            rules.push(SocketLuaRule {
                matcher,
                tcp: StrategyRegistry::from_loaded_socket_config(&isolated, true).map_err(invalid_config)?,
                udp: StrategyRegistry::from_loaded_socket_config(&isolated, false).map_err(invalid_config)?,
            });
        }
        Ok((!rules.is_empty()).then(|| Arc::new(Self { rules })))
    }

    /// Allocates an independent live flow whose final owner releases Lua state.
    pub fn open_flow(self: &Arc<Self>) -> SocketLuaFlow {
        SocketLuaFlow {
            strategies: Arc::clone(self),
            flow_id: FlowId(NEXT_FLOW_ID.fetch_add(1, Ordering::Relaxed)),
            packet_count: AtomicU64::new(0),
            tcp_target: OnceLock::new(),
        }
    }
}

/// Per-session state. Cloning an Arc around this handle retains the same flow.
pub struct SocketLuaFlow {
    strategies: Arc<SocketLuaStrategies>,
    flow_id: FlowId,
    packet_count: AtomicU64,
    tcp_target: OnceLock<SocketAddr>,
}

impl SocketLuaFlow {
    /// Returns a validated write/drop plan, or None for the ordinary built-in path.
    pub fn plan(
        &self,
        src: SocketAddr,
        dst: SocketAddr,
        proto: &str,
        payload: &[u8],
        host: Option<&str>,
    ) -> io::Result<Option<DesyncPlan>> {
        let tcp = match proto {
            "tcp" => true,
            "udp" => false,
            _ => return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid Lua flow transport")),
        };
        // The stream peer can be an upstream SOCKS server after the first write.
        let dst = if tcp { *self.tcp_target.get_or_init(|| dst) } else { dst };
        let (dissect, protocol, detected_host) = dissect_payload(src, dst, tcp, payload);
        let host = host.or(detected_host.as_deref()).map(|host| host.trim_end_matches('.').to_ascii_lowercase());
        let conn =
            ConnectionState { packet_count: self.packet_count.fetch_add(1, Ordering::Relaxed).saturating_add(1) };
        let caps = Capabilities::default();
        let ctx = StrategyContext {
            dissect: &dissect,
            conn: &conn,
            caps: &caps,
            flow_id: self.flow_id,
            payload,
            direction: FlowDirection::Outbound,
        };
        for rule in &self.strategies.rules {
            if !matches_rule(&rule.matcher, src.port(), dst.port(), protocol, host.as_deref()) {
                continue;
            }
            let registry = if tcp { &rule.tcp } else { &rule.udp };
            let mut plan = DesyncPlan::default();
            let Some(verdict) = registry.execute_if_handled(&ctx, &mut plan) else {
                continue;
            };
            if verdict == StrategyVerdict::FallbackPlain
                || (verdict == StrategyVerdict::Apply && plan.actions.is_empty())
            {
                plan.actions = vec![DesyncAction::Write(payload.to_vec())];
                plan.verdict = StrategyVerdict::Apply;
            }
            return Ok(Some(plan));
        }
        Ok(None)
    }
}

impl Drop for SocketLuaFlow {
    fn drop(&mut self) {
        for rule in &self.strategies.rules {
            let _ = rule.tcp.close_flow(self.flow_id);
            let _ = rule.udp.close_flow(self.flow_id);
        }
    }
}

fn invalid_config(error: impl std::fmt::Display) -> io::Error {
    // Do not include script error text: Lua can place payload bytes in an error.
    let _ = error;
    io::Error::new(io::ErrorKind::InvalidInput, "invalid Lua socket strategy configuration")
}

fn matches_rule(
    matcher: &StrategyMatch,
    src: u16,
    dst: u16,
    protocol: Option<ProtocolName>,
    host: Option<&str>,
) -> bool {
    (matcher.port.is_empty() || matcher.port.contains(&src) || matcher.port.contains(&dst))
        && (matcher.proto.is_empty()
            || matcher.proto.contains(&ProtocolName::Any)
            || protocol.is_some_and(|proto| matcher.proto.contains(&proto)))
        && (matcher.hosts.is_empty()
            || host.is_some_and(|host| {
                matcher
                    .hosts
                    .iter()
                    .any(|rule| host == rule || host.strip_suffix(rule).is_some_and(|prefix| prefix.ends_with('.')))
            }))
}

fn dissect_payload(
    src: SocketAddr,
    dst: SocketAddr,
    tcp: bool,
    payload: &[u8],
) -> (Dissect, Option<ProtocolName>, Option<String>) {
    let mut dissect =
        Dissect { src_port: src.port(), dst_port: dst.port(), is_ipv6: dst.is_ipv6(), ..Dissect::default() };
    dissect.markers.insert(MarkerName::Data, 0);
    dissect.markers.insert(MarkerName::End, payload.len());
    dissect.proto = ripdpi_protocol_detect::classify_l7(payload, src.port(), dst.port(), !tcp);
    let mut protocol = protocol_name(&dissect.proto);
    let mut hostname = None;
    if tcp {
        if let Some(host) = parse_tls(payload) {
            hostname = Some(String::from_utf8_lossy(host).into_owned());
            protocol = Some(ProtocolName::Tls);
            dissect.proto = L7Protocol::Tls(TlsDissect { sni: hostname.clone(), is_client_hello: true });
            if let Some(info) = tls_marker_info(payload) {
                insert_host_markers(&mut dissect, payload, info.host_start, info.host_end);
                dissect.markers.insert(MarkerName::ExtLen, info.ext_len_start);
                dissect.markers.insert(MarkerName::SniExt, info.sni_ext_start);
            }
        } else if let Some(info) = http_marker_info(payload) {
            hostname = Some(String::from_utf8_lossy(&payload[info.host_start..info.host_end]).into_owned());
            protocol = Some(ProtocolName::Http);
            dissect.proto = L7Protocol::Http(HttpDissect { host: hostname.clone(), is_request: true });
            insert_host_markers(&mut dissect, payload, info.host_start, info.host_end);
            dissect.markers.insert(MarkerName::HttpMethod, info.method_start);
        }
    } else if let Some(quic) = parse_quic_initial(payload) {
        protocol = Some(ProtocolName::Quic);
        dissect.proto = L7Protocol::Quic(QuicDissect { version: Some(quic.version) });
        if let Some(layout) = parse_quic_initial_layout(payload) {
            hostname = layout
                .info
                .client_hello
                .get(layout.info.tls_info.host_start..layout.info.tls_info.host_end)
                .map(|host| String::from_utf8_lossy(host).into_owned());
        }
    }
    (dissect, protocol, hostname)
}

fn protocol_name(proto: &L7Protocol) -> Option<ProtocolName> {
    match proto {
        L7Protocol::Tls(_) => Some(ProtocolName::Tls),
        L7Protocol::Http(_) => Some(ProtocolName::Http),
        L7Protocol::Quic(_) => Some(ProtocolName::Quic),
        L7Protocol::WireGuard(_) => Some(ProtocolName::Wireguard),
        L7Protocol::Dtls(_) => Some(ProtocolName::Dtls),
        L7Protocol::Dht(_) => Some(ProtocolName::Dht),
        L7Protocol::Mtproto(_) => Some(ProtocolName::Mtproto),
        L7Protocol::Stun(_) => Some(ProtocolName::Stun),
        _ => None,
    }
}

fn insert_host_markers(dissect: &mut Dissect, payload: &[u8], start: usize, end: usize) {
    dissect.markers.insert(MarkerName::Host, start);
    dissect.markers.insert(MarkerName::HostEnd, end);
    if let Some((left, right)) = payload.get(start..end).and_then(second_level_domain_span) {
        dissect.markers.insert(MarkerName::HostSld, start + left);
        dissect.markers.insert(MarkerName::HostMidSld, start + left + (right - left) / 2);
        dissect.markers.insert(MarkerName::HostEndSld, start + right);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Scripts(std::path::PathBuf);
    impl Scripts {
        fn new(script: &str) -> Self {
            let dir = std::env::temp_dir().join(format!(
                "ripdpi-socket-lua-{}-{}",
                std::process::id(),
                NEXT_FLOW_ID.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&dir).unwrap();
            std::fs::write(dir.join("candidate.lua"), script).unwrap();
            Self(dir)
        }
        fn load(&self, rules: &str) -> Arc<SocketLuaStrategies> {
            SocketLuaStrategies::from_yaml(&format!("version: 1\nstrategies:\n{rules}"), &self.0).unwrap().unwrap()
        }
    }
    impl Drop for Scripts {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }
    fn endpoints() -> (SocketAddr, SocketAddr) {
        ("127.0.0.1:1234".parse().unwrap(), "127.0.0.1:443".parse().unwrap())
    }
    fn rule(id: &str, function: &str, policy: &str, matcher: &str) -> String {
        format!(
            "  - id: {id}\n    on_fail: {policy}\n{matcher}    steps:\n      - type: lua\n        function: {function}\n        script_paths: [candidate.lua]\n"
        )
    }

    #[test]
    fn socket_load_requires_absolute_jail_and_ignores_builtin_only_yaml() {
        assert!(SocketLuaStrategies::load(None).unwrap().is_none());
        let yaml = format!("version: 1\nstrategies:\n{}", rule("lua", "write", "drop", ""));
        let mut context = ProxyRuntimeContext { strategy_chain_yaml: Some(yaml), ..ProxyRuntimeContext::default() };
        assert!(SocketLuaStrategies::load(Some(&context)).is_err());
        context.lua_script_base_dir = Some("relative".to_owned());
        assert!(SocketLuaStrategies::load(Some(&context)).is_err());
        context.lua_script_base_dir = Some("/missing-ripdpi-script-directory".to_owned());
        context.strategy_chain_yaml =
            Some("version: 1\nstrategies:\n  - id: split\n    steps:\n      - type: split\n".to_owned());
        assert!(SocketLuaStrategies::load(Some(&context)).unwrap().is_none());
    }

    #[test]
    fn socket_rules_keep_matchers_and_failure_policies() {
        let scripts = Scripts::new(
            "function unsupported(d) d.rawsend('bad'); return 'early' end\nfunction write(d) return 'good' end",
        );
        let rules = rule("first", "unsupported", "next_strategy", "")
            + &rule(
                "second",
                "write",
                "drop",
                "    match:\n      proto: [http]\n      port: [443]\n      hosts: [example.org]\n",
            );
        let strategies = scripts.load(&rules);
        let flow = strategies.open_flow();
        let (src, dst) = endpoints();
        let payload = b"GET / HTTP/1.1\r\nHost: sub.example.org\r\n\r\n";
        let plan = flow.plan(src, dst, "tcp", payload, None).unwrap().unwrap();
        assert_eq!(plan.actions, vec![DesyncAction::Write(b"good".to_vec())]);
        assert!(flow.plan(src, dst, "tcp", b"unknown", Some("example.org")).unwrap().is_none());
        assert!(flow.plan(src, dst, "tcp", payload, Some("notexample.org")).unwrap().is_none());
        assert!(
            strategies.open_flow().plan(src, "127.0.0.1:80".parse().unwrap(), "tcp", payload, None).unwrap().is_none()
        );
        assert!(flow.plan(src, dst, "udp", payload, None).unwrap().is_none());
        for policy in ["fallback_plain", "drop"] {
            let strategies =
                scripts.load(&(rule("first", "unsupported", policy, "") + &rule("second", "write", "drop", "")));
            let plan = strategies.open_flow().plan(src, dst, "tcp", payload, None).unwrap().unwrap();
            if policy == "drop" {
                assert_eq!(plan.verdict, StrategyVerdict::Drop);
                assert!(plan.actions.is_empty());
            } else {
                assert_eq!(plan.actions, vec![DesyncAction::Write(payload.to_vec())]);
            }
        }
    }

    #[test]
    fn socket_flow_state_is_owned_and_released() {
        let scripts =
            Scripts::new("function counter(d) d.conn.count=(d.conn.count or 0)+1; return tostring(d.conn.count) end");
        let strategies = scripts.load(&rule("state", "counter", "drop", ""));
        let first = strategies.open_flow();
        let second = strategies.open_flow();
        let (src, dst) = endpoints();
        for expected in [b"1", b"2"] {
            let plan = first.plan(src, dst, "tcp", b"original", None).unwrap().unwrap();
            assert_eq!(plan.actions, vec![DesyncAction::Write(expected.to_vec())]);
        }
        let plan = second.plan(src, dst, "tcp", b"original", None).unwrap().unwrap();
        assert_eq!(plan.actions, vec![DesyncAction::Write(b"1".to_vec())]);
        let id = first.flow_id;
        assert!(strategies.rules[0].tcp.has_flow(id));
        drop(first);
        assert!(!strategies.rules[0].tcp.has_flow(id));
        assert!(strategies.rules[0].tcp.has_flow(second.flow_id));
    }

    #[test]
    fn socket_udp_rewrite_has_one_real_write_and_jail_rejects_escape() {
        let scripts = Scripts::new("function write(d) assert(d.dis.tcp==nil); return 'new datagram' end");
        let strategies = scripts.load(&rule("udp", "write", "drop", ""));
        let (src, dst) = endpoints();
        let plan = strategies.open_flow().plan(src, dst, "udp", b"original", None).unwrap().unwrap();
        assert_eq!(plan.actions, vec![DesyncAction::Write(b"new datagram".to_vec())]);
        let yaml = format!(
            "version: 1\nstrategies:\n{}",
            rule("escape", "write", "drop", "").replace("candidate.lua", "../candidate.lua")
        );
        assert!(SocketLuaStrategies::from_yaml(&yaml, &scripts.0).is_err());
    }
}
