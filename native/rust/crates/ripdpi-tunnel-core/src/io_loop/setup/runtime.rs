use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use ripdpi_tunnel_config::Config;
use ripdpi_tunnel_intercept::egress::{RawTunPacketInjector, TunEgressInterceptor};
use ripdpi_tunnel_intercept::ingress::{RawSynAckPacketInjector, SynAckStrategy, TunIngressInterceptor};

use crate::session::Auth;
use crate::uid_policy::UidFlowPolicy;

use super::super::dns_intercept::MapDnsRuntime;
use super::super::state::LoopRuntime;

pub(super) fn build_loop_runtime(
    config: &Config,
    proxy_sockaddr: SocketAddr,
    auth: Auth,
    mapdns_runtime: Option<MapDnsRuntime>,
) -> LoopRuntime {
    let mapdns_classify = mapdns_runtime.map(|value| {
        (
            match value.intercept_addr.ip() {
                IpAddr::V4(v4) => u32::from(v4),
                IpAddr::V6(_) => unreachable!("mapdns runtime only supports IPv4"),
            },
            u32::MAX,
            value.intercept_port,
        )
    });
    let policy_uids = config.misc.uid_policy_uids.iter().copied().collect();
    let uid_policy = match config.misc.uid_policy_mode.as_str() {
        "allowlist" => UidFlowPolicy::enforcing(policy_uids),
        "denylist" => UidFlowPolicy::denying(policy_uids),
        _ => UidFlowPolicy::disarmed(),
    };
    LoopRuntime {
        proxy_sockaddr,
        auth,
        mapdns_runtime,
        mapdns_classify,
        filter_injected_resets: config.misc.filter_injected_resets,
        webrtc_protection_enabled: config.misc.webrtc_protection_enabled,
        uid_policy,
        tun_ingress_interceptor: TunIngressInterceptor::new(
            SynAckStrategy::from_yaml(config.misc.strategy_chain_yaml.as_deref()),
            RawSynAckPacketInjector::new(config.misc.protect_path.clone()),
        ),
        // Jail the egress `lua`-step `script_paths` to the app's absolute `<filesDir>/lua` dir when supplied, not `"."` (ill-defined Android CWD).
        tun_egress_interceptor: Box::new(TunEgressInterceptor::new_with_base_dir(
            config.misc.strategy_chain_yaml.as_deref(),
            config.misc.lua_script_base_dir.as_deref().map_or(std::path::Path::new("."), std::path::Path::new),
            RawTunPacketInjector::new(config.misc.protect_path.clone()),
        )),
        udp_idle_timeout: Duration::from_millis(u64::from(config.misc.udp_read_write_timeout)),
        tcp_connect_timeout: Duration::from_millis(u64::from(config.misc.connect_timeout)),
        tcp_read_write_timeout: Duration::from_millis(u64::from(config.misc.tcp_read_write_timeout)),
        protect_path: config.misc.protect_path.clone(),
    }
}
