//! Post-start warmup probe that pre-populates the autolearn table.
//!
//! After the proxy listener starts, a background thread attempts TLS
//! connections to a small set of commonly-blocked domains.  Each probe
//! walks the normal route-selection -> desync -> failure-classification
//! pipeline so that autolearn records are created before any user traffic
//! arrives.  The warmup is non-blocking: it runs on a dedicated thread
//! and respects the runtime shutdown signal.

use std::io::{self, Read};
use std::net::SocketAddr;
use std::thread;
use std::time::Duration;

use ripdpi_failure_classifier::{classify_transport_error, FailureAction, FailureStage};
use ripdpi_packets::{tls_fake_profile_bytes, TlsFakeProfile};
use ripdpi_session::OutboundProgress;

use super::desync::send_with_group;
use super::routing::{
    advance_route_for_failure, classify_response_failure, connect_target, emit_failure_classified,
    note_block_signal_for_failure, note_route_success,
};
use super::state::{flush_autolearn_updates, RuntimeState};
use crate::platform;

/// Commonly-blocked domains used for warmup probes.
const PROBE_DOMAINS: &[&str] = &["youtube.com", "discord.com", "telegram.org", "signal.org", "instagram.com"];

/// Maximum time to wait for a single probe connection + first response.
const PROBE_TIMEOUT: Duration = Duration::from_secs(5);

/// Maximum total wall-clock time for the entire warmup pass.
const WARMUP_DEADLINE: Duration = Duration::from_secs(30);

/// Spawn the warmup probe as a background thread.
///
/// The thread probes each domain sequentially, stopping early if the
/// runtime shutdown is requested or the total deadline expires.
pub(super) fn spawn_warmup_thread(state: RuntimeState) {
    if !state.config.host_autolearn.enabled || !state.config.host_autolearn.warmup_probe_enabled {
        return;
    }
    if state.config.groups.len() < 2 {
        // Warmup is only useful when there are fallback groups to escalate to.
        return;
    }
    thread::Builder::new().name("ripdpi-warmup".into()).spawn(move || run_warmup(&state)).ok();
}

fn run_warmup(state: &RuntimeState) {
    let deadline = std::time::Instant::now() + WARMUP_DEADLINE;
    tracing::info!(domain_count = PROBE_DOMAINS.len(), "warmup probe started");

    let mut probed = 0u32;
    let mut learned = 0u32;

    for &domain in PROBE_DOMAINS {
        if is_shutdown(state) {
            tracing::debug!("warmup probe aborted: shutdown requested");
            break;
        }
        if std::time::Instant::now() >= deadline {
            tracing::debug!("warmup probe stopped: deadline reached");
            break;
        }

        match probe_domain(state, domain) {
            Ok(escalated) => {
                probed += 1;
                if escalated {
                    learned += 1;
                }
            }
            Err(err) => {
                tracing::debug!(domain, error = %err, "warmup probe skipped");
                probed += 1;
            }
        }
    }

    // Flush any remaining autolearn updates produced by the probes.
    if let Ok(mut cache) = state.cache.lock() {
        flush_autolearn_updates(state, &mut cache);
    }

    tracing::info!(probed, learned, "warmup probe finished");
}

/// Probe a single domain by resolving it, connecting through the desync
/// pipeline, sending a TLS ClientHello, and reading the first response.
///
/// Returns `Ok(true)` if the probe triggered an autolearn escalation,
/// `Ok(false)` if the connection succeeded on the first group.
fn probe_domain(state: &RuntimeState, domain: &str) -> io::Result<bool> {
    let target = resolve_probe_target(state, domain)?;
    let payload = build_probe_client_hello(domain);

    // Use the normal connect_target flow which handles route selection,
    // connect-level failures, and autolearn escalation.
    let (mut upstream, route) = connect_target(target, state, Some(&payload), false, Some(domain.to_owned()))?;

    // Apply the probe timeout for both send and receive.
    let _ = upstream.set_write_timeout(Some(PROBE_TIMEOUT));
    let _ = upstream.set_read_timeout(Some(PROBE_TIMEOUT));

    // Send the TLS ClientHello through the desync pipeline.
    let group = state.config.groups[route.group_index].clone();
    let progress = OutboundProgress {
        round: 1,
        payload_size: payload.len(),
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1),
    };
    let send_result =
        send_with_group(&mut upstream, state, route.group_index, &group, &payload, progress, Some(domain), target);

    if let Err(err) = send_result {
        // Send failure -- classify and try to advance.
        let io_err = err.into_io_error();
        let failure = classify_transport_error(FailureStage::FirstWrite, &io_err);
        emit_failure_classified(state, target, &failure, Some(domain));
        let advanced =
            advance_route_for_failure(state, target, &route, Some(domain.to_owned()), Some(&payload), &failure)?;
        return Ok(advanced.is_some());
    }

    // Read the first response to detect DPI interference.
    let _ = platform::enable_recv_ttl(&upstream);
    let mut response_buf = vec![0u8; state.config.network.buffer_size.max(16_384)];
    let read_result = upstream.read(&mut response_buf);

    match read_result {
        Ok(0) => {
            // Connection closed immediately -- likely DPI interference.
            let failure = ripdpi_failure_classifier::ClassifiedFailure::new(
                ripdpi_failure_classifier::FailureClass::SilentDrop,
                FailureStage::FirstResponse,
                FailureAction::RetryWithMatchingGroup,
                "warmup: upstream closed before first response",
            );
            note_block_signal_for_failure(state, Some(domain), &failure, None);
            emit_failure_classified(state, target, &failure, Some(domain));
            let advanced =
                advance_route_for_failure(state, target, &route, Some(domain.to_owned()), Some(&payload), &failure)?;
            Ok(advanced.is_some())
        }
        Ok(n) => {
            let response = &response_buf[..n];
            if let Some(failure) = classify_response_failure(state, target, &payload, response, Some(domain)) {
                note_block_signal_for_failure(state, Some(domain), &failure, None);
                emit_failure_classified(state, target, &failure, Some(domain));
                let advanced = advance_route_for_failure(
                    state,
                    target,
                    &route,
                    Some(domain.to_owned()),
                    Some(&payload),
                    &failure,
                )?;
                Ok(advanced.is_some())
            } else {
                // Connection succeeded -- record the winning route.
                note_route_success(state, target, &route, Some(domain))?;
                Ok(false)
            }
        }
        Err(err) => {
            let failure = classify_transport_error(FailureStage::FirstResponse, &err);
            note_block_signal_for_failure(state, Some(domain), &failure, None);
            emit_failure_classified(state, target, &failure, Some(domain));
            let advanced =
                advance_route_for_failure(state, target, &route, Some(domain.to_owned()), Some(&payload), &failure)?;
            Ok(advanced.is_some())
        }
    }
}

/// Resolve a probe domain to a `SocketAddr` on port 443.
fn resolve_probe_target(state: &RuntimeState, domain: &str) -> io::Result<SocketAddr> {
    use crate::ws_bootstrap::resolve_host_via_encrypted_dns;

    // Try encrypted DNS first (respects protect_path for VPN bypass).
    if let Ok(mut addr) = resolve_host_via_encrypted_dns(
        domain,
        state.runtime_context.as_ref(),
        state.config.process.protect_path.as_deref(),
        state.config.network.ipv6,
    ) {
        addr.set_port(443);
        return Ok(addr);
    }

    // Fall back to system resolver.
    use std::net::ToSocketAddrs;
    let addr = (domain, 443u16)
        .to_socket_addrs()
        .map_err(|err| io::Error::new(io::ErrorKind::NotFound, format!("warmup: cannot resolve {domain}: {err}")))?
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, format!("warmup: no addresses for {domain}")))?;
    Ok(addr)
}

/// Build a TLS ClientHello with the given domain as SNI.
///
/// Uses the Google Chrome fake TLS profile (a single-record ClientHello
/// that the SNI replacement function handles reliably) and patches in the
/// probe domain.
fn build_probe_client_hello(domain: &str) -> Vec<u8> {
    let template = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome);
    let capacity = template.len() + domain.len() + 64;
    let mutation = ripdpi_packets::change_tls_sni_seeded_like_c(template, domain.as_bytes(), capacity, 0);
    if mutation.rc == 0 {
        mutation.bytes
    } else {
        // If SNI patching fails, use the template as-is.
        // This still exercises the desync pipeline, just with the template SNI.
        template.to_vec()
    }
}

fn is_shutdown(state: &RuntimeState) -> bool {
    state.control.as_ref().map_or_else(crate::process::shutdown_requested, |c| c.shutdown_requested())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_probe_client_hello_produces_valid_tls_record() {
        let hello = build_probe_client_hello("youtube.com");
        assert!(ripdpi_packets::is_tls_client_hello(&hello), "probe payload must be a TLS ClientHello");
    }

    #[test]
    fn build_probe_client_hello_embeds_domain_as_sni() {
        let hello = build_probe_client_hello("discord.com");
        let sni = ripdpi_packets::parse_tls(&hello);
        assert!(sni.is_some(), "SNI should be extractable after patching");
        assert_eq!(sni.unwrap().len(), "discord.com".len(), "SNI length must match domain");
    }

    #[test]
    fn probe_domains_are_non_empty() {
        assert!(!PROBE_DOMAINS.is_empty());
        for domain in PROBE_DOMAINS {
            assert!(!domain.is_empty());
            assert!(domain.contains('.'));
        }
    }
}
