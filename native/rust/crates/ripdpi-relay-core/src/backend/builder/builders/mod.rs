mod chain_relay;
mod cloudflare_tunnel;
mod common;
mod hysteria2;
mod masque;
mod shadowtls;
mod tuic;
mod vless_reality;

use std::io;

use crate::backend::builder::BuildContext;
use crate::backend::RelayBackend;
use crate::config::ResolvedRelayRuntimeConfig;

type BuildFn = fn(&ResolvedRelayRuntimeConfig, &BuildContext) -> io::Result<RelayBackend>;
type SupportsFn = fn(&ResolvedRelayRuntimeConfig) -> bool;

pub(crate) struct BackendBuilder {
    supports: SupportsFn,
    build: BuildFn,
}

impl BackendBuilder {
    const fn new(supports: SupportsFn, build: BuildFn) -> Self {
        Self { supports, build }
    }
}

const BUILDERS: &[BackendBuilder] = &[
    vless_reality::XHTTP_BUILDER,
    vless_reality::BUILDER,
    hysteria2::BUILDER,
    tuic::BUILDER,
    cloudflare_tunnel::BUILDER,
    chain_relay::BUILDER,
    masque::BUILDER,
    shadowtls::BUILDER,
];

pub(crate) fn build_backend(config: &ResolvedRelayRuntimeConfig, context: &BuildContext) -> io::Result<RelayBackend> {
    if let Some(builder) = BUILDERS.iter().find(|builder| (builder.supports)(config)) {
        return (builder.build)(config, context);
    }

    Ok(RelayBackend::Unsupported { kind: config.kind_id().to_string() })
}
