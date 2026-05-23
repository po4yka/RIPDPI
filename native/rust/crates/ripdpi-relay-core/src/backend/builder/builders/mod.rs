//! Per-kind in-process relay backend builders.
//!
//! Each submodule owns one relay kind's `build` factory; the
//! [`RelayTransportRegistration`](crate::transport_descriptor::RelayTransportRegistration)
//! table pairs each builder with its descriptor. `build_backend` in the parent
//! module dispatches by `relay_kind`, so a builder no longer carries its own
//! `supports` predicate — the registry key is the `relay_kind` string.

mod chain_relay;
mod cloudflare_tunnel;
mod common;
mod hysteria2;
mod masque;
mod shadowtls;
mod tuic;
mod vless_reality;

pub(crate) use chain_relay::build as build_chain_relay;
pub(crate) use cloudflare_tunnel::build as build_cloudflare_tunnel;
pub(crate) use hysteria2::build as build_hysteria2;
pub(crate) use masque::build as build_masque;
pub(crate) use shadowtls::build as build_shadowtls;
pub(crate) use tuic::build as build_tuic;
pub(crate) use vless_reality::build as build_vless_reality;
