use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_tunnel_intercept::egress::TunEgressPacketHandler;
use ripdpi_tunnel_intercept::ingress::{RawSynAckPacketInjector, TunIngressInterceptor};

use crate::session::Auth;

use super::super::dns_intercept::MapDnsRuntime;

pub(in crate::io_loop) struct LoopRuntime {
    pub(in crate::io_loop) proxy_sockaddr: SocketAddr,
    pub(in crate::io_loop) auth: Auth,
    pub(in crate::io_loop) mapdns_runtime: Option<MapDnsRuntime>,
    pub(in crate::io_loop) mapdns_classify: Option<(u32, u32, u16)>,
    pub(in crate::io_loop) filter_injected_resets: bool,
    pub(in crate::io_loop) tun_ingress_interceptor: TunIngressInterceptor<RawSynAckPacketInjector>,
    pub(in crate::io_loop) tun_egress_interceptor: Box<dyn TunEgressPacketHandler>,
    pub(in crate::io_loop) udp_idle_timeout: Duration,
}
