use std::io;
use std::sync::Arc;

use tokio_util::sync::CancellationToken;

use ripdpi_tunnel_config::Config;

use crate::Stats;

use super::dns_intercept::{build_encrypted_dns_resolver, spawn_dns_worker, DnsRequest, DnsResponse};

type DnsWorkerChannels =
    (Option<tokio::sync::mpsc::Sender<DnsRequest>>, Option<tokio::sync::mpsc::Receiver<DnsResponse>>);

pub(in crate::io_loop) fn configure_resolver_fallback(config: &Config, stats: &Arc<Stats>) {
    if let Some(mapdns) = config.mapdns.as_ref() {
        stats.configure_resolver_fallback(mapdns.resolver_fallback_active, mapdns.resolver_fallback_reason.as_deref());
    }
}

pub(in crate::io_loop) fn build_dns_worker(
    config: &Config,
    cancel: &CancellationToken,
) -> io::Result<DnsWorkerChannels> {
    let Some(resolver) = build_encrypted_dns_resolver(config)
        .map_err(|e| io::Error::other(format!("build encrypted DNS resolver: {e}")))?
    else {
        return Ok((None, None));
    };

    let (tx, rx) = spawn_dns_worker(resolver, cancel.child_token());
    Ok((Some(tx), Some(rx)))
}
