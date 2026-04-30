use std::io;
use std::net::{IpAddr, SocketAddr};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_config::{
    DETECT_CONNECT, DETECT_CONNECTION_FREEZE, DETECT_DNS_TAMPER, DETECT_HTTP_BLOCKPAGE, DETECT_HTTP_LOCAT,
    DETECT_SILENT_DROP, DETECT_TCP_RESET, DETECT_TLS_ALERT, DETECT_TLS_HANDSHAKE_FAILURE,
};
use ripdpi_dns_resolver::extract_ip_answers;
use ripdpi_failure_classifier::{
    block_signal_from_failure, classify_http_response_block, classify_tls_alert, classify_tls_handshake_failure,
    confirm_dns_tampering, ClassifiedFailure, FailureAction, FailureClass,
};
use ripdpi_runtime_learning::runtime_policy::{
    is_tls_client_hello_payload, ConnectionRoute, RouteAdvance, TransportProtocol,
};
use ripdpi_session::{detect_response_trigger, TriggerEvent};
use ripdpi_ws_bootstrap::{
    build_encrypted_dns_resolver_for_host, encrypted_dns_label, runtime_encrypted_dns_context_for_host,
};

use super::super::adaptive::{
    note_adaptive_fake_ttl_failure, note_adaptive_tcp_failure, note_direct_path_tls_post_client_hello_failure,
    note_evolver_failure,
};
use super::super::retry::{build_retry_selection_penalties, maybe_emit_candidate_diversification, note_retry_failure};
use super::super::state::{flush_autolearn_updates, RuntimeState};
use super::policy::{preferred_targets_for_transport, runtime_supports_trigger};

pub(in crate::runtime) fn failure_trigger_mask(failure: &ClassifiedFailure) -> u32 {
    match failure.class {
        FailureClass::DnsTampering => DETECT_DNS_TAMPER,
        FailureClass::TcpReset => DETECT_TCP_RESET,
        FailureClass::SilentDrop => DETECT_SILENT_DROP,
        FailureClass::TlsAlert => DETECT_TLS_ALERT,
        FailureClass::HttpBlockpage => DETECT_HTTP_BLOCKPAGE,
        FailureClass::QuicBreakage => 0,
        FailureClass::Redirect => DETECT_HTTP_LOCAT,
        FailureClass::TlsHandshakeFailure => DETECT_TLS_HANDSHAKE_FAILURE,
        FailureClass::ConnectFailure => DETECT_CONNECT,
        FailureClass::StrategyExecutionFailure => DETECT_CONNECT,
        FailureClass::ConnectionFreeze => DETECT_CONNECTION_FREEZE,
        FailureClass::Unknown => 0,
        // Capability-skipped runs were never actually emitted; they emit no
        // wire-visible block signals and must not trigger block detection.
        FailureClass::CapabilitySkipped => 0,
    }
}

pub(in crate::runtime) fn failure_penalizes_strategy(failure: &ClassifiedFailure) -> bool {
    matches!(
        failure.class,
        FailureClass::TcpReset
            | FailureClass::SilentDrop
            | FailureClass::TlsAlert
            | FailureClass::HttpBlockpage
            | FailureClass::Redirect
            | FailureClass::TlsHandshakeFailure
            | FailureClass::ConnectionFreeze
    )
}

fn is_tunnel_infrastructure_dns_target(target: SocketAddr) -> bool {
    if target.port() != 853 {
        return false;
    }
    match target.ip() {
        IpAddr::V4(ipv4) => {
            let [a, b, ..] = ipv4.octets();
            a == 198 && matches!(b, 18 | 19)
        }
        IpAddr::V6(_) => false,
    }
}

pub(in crate::runtime) fn should_track_strategy_target(target: SocketAddr) -> bool {
    !is_tunnel_infrastructure_dns_target(target)
}

pub(in crate::runtime) fn emit_failure_classified(
    state: &RuntimeState,
    target: SocketAddr,
    failure: &ClassifiedFailure,
    host: Option<&str>,
) {
    if !should_track_strategy_target(target) {
        return;
    }
    if let Some(telemetry) = &state.telemetry {
        telemetry.on_failure_classified(target, failure, host);
    }
}

pub(in crate::runtime) fn advance_route_for_failure(
    state: &RuntimeState,
    target: SocketAddr,
    route: &ConnectionRoute,
    host: Option<String>,
    payload: Option<&[u8]>,
    failure: &ClassifiedFailure,
) -> io::Result<Option<ConnectionRoute>> {
    if !should_track_strategy_target(target) {
        return Ok(None);
    }
    let trigger = failure_trigger_mask(failure);
    if failure.action != FailureAction::RetryWithMatchingGroup
        || trigger == 0
        || !runtime_supports_trigger(state, trigger)?
    {
        return Ok(None);
    }

    let _ = note_retry_failure(state, target, route.group_index, host.as_deref(), payload, TransportProtocol::Tcp)?;
    let penalize = failure_penalizes_strategy(failure);
    if penalize {
        if matches!(failure.class, FailureClass::TlsAlert | FailureClass::TlsHandshakeFailure) {
            let targets = preferred_targets_for_transport(state, target, host.as_deref(), TransportProtocol::Tcp);
            let _ = note_direct_path_tls_post_client_hello_failure(state, host.as_deref(), &targets);
        }
        if let Some(payload) = payload {
            note_adaptive_tcp_failure(state, target, route.group_index, host.as_deref(), payload)?;
        }
        note_adaptive_fake_ttl_failure(state, target, route.group_index, host.as_deref())?;
        note_evolver_failure(state, failure.class);
    }

    let retry_penalties =
        build_retry_selection_penalties(state, target, host.as_deref(), payload, TransportProtocol::Tcp)?;
    let mut cache = state.cache.write().map_err(|_| io::Error::other("cache lock poisoned"))?;
    let next = cache.advance_route(
        &state.config,
        route,
        RouteAdvance {
            dest: target,
            payload,
            transport: TransportProtocol::Tcp,
            trigger,
            can_reconnect: true,
            host: host.clone(),
            penalize_strategy_failure: penalize,
            retry_penalties: Some(&retry_penalties),
        },
    )?;
    flush_autolearn_updates(state, &mut cache);
    drop(cache);
    if let Some(next_route) = next.as_ref() {
        maybe_emit_candidate_diversification(state, target, next_route, &retry_penalties);
    }
    if let (Some(telemetry), Some(next_route)) = (&state.telemetry, next.as_ref()) {
        telemetry.on_route_advanced(target, route.group_index, next_route.group_index, trigger, host.as_deref());
        telemetry.on_adaptive_override(
            target,
            next_route.group_index,
            trigger,
            failure.class.as_str(),
            host.as_deref(),
            "route_advance",
        );
    }
    Ok(next)
}

pub(in crate::runtime) fn note_block_signal_for_failure(
    state: &RuntimeState,
    host: Option<&str>,
    failure: &ClassifiedFailure,
    tcp_total_retransmissions: Option<u32>,
) {
    let Some(host) = host else {
        return;
    };
    let Some(signal) = block_signal_from_failure(failure, tcp_total_retransmissions) else {
        return;
    };
    let confirmation_allowed = state
        .control
        .as_ref()
        .and_then(|control| control.current_network_snapshot())
        .is_none_or(|snapshot| snapshot.validated && !snapshot.captive_portal);
    if let Ok(mut cache) = state.cache.write() {
        cache.note_block_signal(&state.config, host, signal.signal, signal.provider.as_deref(), confirmation_allowed);
        flush_autolearn_updates(state, &mut cache);
    }
}

pub(in crate::runtime) fn classify_response_failure(
    state: &RuntimeState,
    target: SocketAddr,
    request: &[u8],
    response: &[u8],
    host: Option<&str>,
) -> Option<ClassifiedFailure> {
    if response.starts_with(b"HTTP/1.") && is_tls_client_hello_payload(request) {
        if let Some(host) = host {
            if let Some(dns_tampering) = confirm_dns_tampering_for_host(state, host, target.ip()) {
                return Some(dns_tampering);
            }
        }
    }

    if let Some(alert) = classify_tls_alert(response) {
        return Some(alert);
    }
    if let Some(http_block) = classify_http_response_block(response) {
        return Some(http_block);
    }
    if matches!(detect_response_trigger(request, response), Some(TriggerEvent::SslErr)) {
        return Some(classify_tls_handshake_failure("TLS handshake failed before ServerHello"));
    }
    None
}

fn confirm_dns_tampering_for_host(state: &RuntimeState, host: &str, target_ip: IpAddr) -> Option<ClassifiedFailure> {
    let resolver_context = runtime_encrypted_dns_context_for_host(host, state.runtime_context.as_ref());
    let resolver = build_encrypted_dns_resolver_for_host(
        host,
        state.runtime_context.as_ref(),
        state.config.process.protect_path.as_deref(),
    )
    .ok()?;
    let query_id = ((SystemTime::now().duration_since(UNIX_EPOCH).ok()?.as_nanos() as u64) & 0xffff) as u16;
    let query = build_dns_query(host, query_id.max(1)).ok()?;
    let response = resolver.exchange_blocking(&query).ok()?;
    let answers = extract_ip_answers(&response)
        .ok()?
        .into_iter()
        .filter_map(|answer| answer.parse::<IpAddr>().ok())
        .collect::<Vec<_>>();
    confirm_dns_tampering(host, target_ip, &answers, &encrypted_dns_label(&resolver_context))
}

fn build_dns_query(domain: &str, query_id: u16) -> Result<Vec<u8>, io::Error> {
    let mut packet = Vec::with_capacity(512);
    packet.extend(query_id.to_be_bytes());
    packet.extend(0x0100u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    packet.extend(0u16.to_be_bytes());
    for label in domain.split('.') {
        if label.is_empty() || label.len() > 63 {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid dns name"));
        }
        packet.push(label.len() as u8);
        packet.extend(label.as_bytes());
    }
    packet.push(0);
    packet.extend(1u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    Ok(packet)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn synthetic_tunnel_dns_targets_do_not_participate_in_strategy_tracking() {
        assert!(!should_track_strategy_target(SocketAddr::from(([198, 18, 0, 53], 853))));
        assert!(!should_track_strategy_target(SocketAddr::from(([198, 19, 42, 7], 853))));
        assert!(should_track_strategy_target(SocketAddr::from(([198, 18, 0, 53], 443))));
        assert!(should_track_strategy_target(SocketAddr::from(([142, 251, 127, 84], 443))));
    }
}

#[cfg(test)]
#[allow(clippy::items_after_test_module)]
pub(in crate::runtime) fn trigger_flag(trigger: TriggerEvent) -> u32 {
    match trigger {
        TriggerEvent::Redirect => DETECT_HTTP_LOCAT,
        TriggerEvent::SslErr => DETECT_TLS_HANDSHAKE_FAILURE,
        TriggerEvent::Connect => DETECT_CONNECT,
        TriggerEvent::Torst => ripdpi_config::DETECT_TORST,
    }
}
