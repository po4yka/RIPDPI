use std::io;
use std::sync::Arc;

use tokio_util::sync::CancellationToken;

use ripdpi_tunnel_config::Config;

use crate::Stats;

use super::dns_intercept::{DnsRequest, DnsResponse, build_encrypted_dns_resolver, spawn_dns_worker};

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
        if config.split_dns_policy.is_some() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "split DNS policy requires an encrypted DNS resolver",
            ));
        }
        return Ok((None, None));
    };

    let (tx, rx) = spawn_dns_worker(resolver, cancel.child_token());
    Ok((Some(tx), Some(rx)))
}

#[cfg(test)]
mod tests {
    use ripdpi_tunnel_config::{Config, MiscConfig, Socks5Config, SplitDnsAction, SplitDnsPolicyConfig, TunnelConfig};
    use tokio_util::sync::CancellationToken;

    use super::build_dns_worker;

    #[test]
    fn split_dns_policy_without_encrypted_resolver_fails_before_worker_spawn() {
        let config = Config {
            tunnel: TunnelConfig::default(),
            socks5: Socks5Config {
                address: "127.0.0.1".to_string(),
                port: 1080,
                udp: None,
                udp_address: None,
                pipeline: None,
                username: None,
                password: None,
                mark: None,
            },
            mapdns: None,
            split_dns_policy: Some(SplitDnsPolicyConfig {
                canonical_digest: String::new(),
                destination_routing_digest: String::new(),
                default_action: SplitDnsAction::Tunneled,
                rules: Vec::new(),
                direct_resolver_candidates: Vec::new(),
                bootstrap_pins: Vec::new(),
                coverage_reason: None,
            }),
            misc: MiscConfig::default(),
        };

        let error = build_dns_worker(&config, &CancellationToken::new()).expect_err("must fail closed");

        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
        assert_eq!(error.to_string(), "split DNS policy requires an encrypted DNS resolver");
    }
}
