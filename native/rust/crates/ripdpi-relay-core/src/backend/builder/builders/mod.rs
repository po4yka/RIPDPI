//! Per-kind in-process relay backend builders.
//!
//! Each submodule owns one relay kind's `build` factory; the
//! [`RelayTransportRegistration`](crate::transport_descriptor::RelayTransportRegistration)
//! table pairs each builder with its descriptor. `build_backend` in the parent
//! module dispatches by `relay_kind`, so a builder no longer carries its own
//! `supports` predicate — the registry key is the `relay_kind` string.

mod anytls;
mod chain_relay;
mod cloudflare_tunnel;
mod common;
mod hysteria2;
mod hysteria_v1;
mod masque;
mod mieru;
mod shadowsocks;
mod shadowtls;
mod ssh;
mod tor;
mod trojan;
mod trojan_go;
mod tuic;
mod vless_reality;
mod vmess;

pub(crate) use anytls::build as build_anytls;
pub(crate) use chain_relay::build as build_chain_relay;
pub(crate) use cloudflare_tunnel::build as build_cloudflare_tunnel;
pub(crate) use hysteria2::build as build_hysteria2;
pub(crate) use hysteria_v1::build as build_hysteria_v1;
pub(crate) use masque::build as build_masque;
pub(crate) use mieru::build as build_mieru;
pub(crate) use shadowsocks::build as build_shadowsocks;
pub(crate) use shadowtls::build as build_shadowtls;
pub(crate) use ssh::build as build_ssh;
pub(crate) use tor::build as build_tor;
pub(crate) use trojan::build as build_trojan;
pub(crate) use trojan_go::build as build_trojan_go;
pub(crate) use tuic::build as build_tuic;
pub(crate) use vless_reality::build as build_vless_reality;
pub(crate) use vmess::build as build_vmess;
