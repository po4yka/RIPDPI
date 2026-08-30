use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};

use super::super::super::state::{RouteConnectPolicy, RuntimeState};
use super::error::ConnectAttemptError;
use super::post_connect::{apply_group_socket_options, record_connect_telemetry};
use super::socket::connect_socket_detailed_observed;
use super::socks::connect_via_socks;
use crate::runtime::destination_routing::DestinationEgress;

pub(in crate::runtime::routing) fn connect_target_candidates_via_group(
    targets: &[SocketAddr],
    logical_target: Option<&str>,
    state: &RuntimeState,
    group_index: usize,
    payload: Option<&[u8]>,
    allow_tfo: bool,
    acquire_cap_slot: bool,
    egress: DestinationEgress,
) -> Result<(TcpStream, Option<ripdpi_session_limit::ExitIpSessionGuard>), ConnectAttemptError> {
    let policy =
        state.route_connect_policy(group_index, payload, allow_tfo, egress).ok_or_else(|| ConnectAttemptError {
            source: io::Error::new(io::ErrorKind::NotFound, "missing desync group"),
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: false,
        })?;
    let mut last_error = None;
    let mut any_capped = false;
    for &candidate in targets {
        // Per-exit-IP concurrent-session cap (route-preference): if this exit IP
        // is already at cap, skip to the next candidate rather than opening
        // another concurrent session to it.
        let guard = if acquire_cap_slot {
            match state.try_acquire_exit_session(candidate.ip()) {
                Some(guard) => Some(guard),
                None => {
                    any_capped = true;
                    // Privacy: never log the destination/exit IP (network-fingerprint-privacy rule).
                    tracing::debug!(
                        target: "ripdpi::proxy::exit_ip_cap",
                        "per-exit-IP concurrent-session cap reached; preferring an alternate route candidate"
                    );
                    continue;
                }
            }
        } else {
            None
        };
        match connect_target_via_group_with_policy(candidate, logical_target, state, group_index, &policy) {
            Ok(stream) => return Ok((stream, guard)),
            Err(err) => last_error = Some(err), // guard drops here -> slot freed for the failed candidate
        }
    }
    // Every candidate either errored or was at cap. If all were capped (no connect
    // error), the cap is advisory throughput shaping (NOT a hard block) -- preserve
    // connectivity by connecting to the first candidate without holding a slot.
    let advisory_fallback_candidate =
        if acquire_cap_slot && any_capped && last_error.is_none() { targets.first().copied() } else { None };
    if let Some(candidate) = advisory_fallback_candidate {
        tracing::debug!(
            target: "ripdpi::proxy::exit_ip_cap",
            "all route candidates at per-exit-IP cap; proceeding without a slot (advisory cap)"
        );
        match connect_target_via_group_with_policy(candidate, logical_target, state, group_index, &policy) {
            Ok(stream) => return Ok((stream, None)),
            Err(err) => last_error = Some(err),
        }
    }
    Err(last_error.unwrap_or_else(|| ConnectAttemptError {
        source: io::Error::new(io::ErrorKind::AddrNotAvailable, "no target candidates available"),
        tcp_total_retransmissions: None,
        tcp_fast_open_enabled: policy.tfo_enabled,
    }))
}

fn connect_target_via_group_with_policy(
    target: SocketAddr,
    logical_target: Option<&str>,
    state: &RuntimeState,
    group_index: usize,
    policy: &RouteConnectPolicy,
) -> Result<TcpStream, ConnectAttemptError> {
    let started = std::time::Instant::now();
    let connect_result = if let Some(upstream_addr) = policy.upstream_socks_addr {
        connect_via_socks(
            target,
            upstream_addr,
            unspecified_ip_for(upstream_addr),
            policy.protect_path.as_deref(),
            policy.tfo_enabled,
            policy.connect_timeout,
            state,
        )
        .map_err(|source| ConnectAttemptError {
            source,
            tcp_total_retransmissions: None,
            tcp_fast_open_enabled: policy.tfo_enabled,
        })
    } else {
        connect_socket_detailed_observed(
            target,
            unspecified_ip_for(target),
            policy.protect_path.as_deref(),
            policy.tfo_enabled,
            policy.connect_timeout,
            policy.pre_connect_rcvbuf,
            state,
        )
    };
    let stream = match connect_result {
        Ok(stream) => stream,
        Err(err) => {
            // Emit failure-path timing symmetrically with the success-path
            // `record_connect_telemetry` path so QualityWindow sees both arms.
            let rtt_ms = started.elapsed().as_millis() as u64;
            let kind = err.source.kind();
            state.note_candidate_upstream_connect_failed(target, logical_target, kind);
            state.note_upstream_connect_failed(target, rtt_ms, kind);
            return Err(err);
        }
    };

    if let Err(err) = apply_group_socket_options(&stream, policy) {
        let rtt_ms = started.elapsed().as_millis() as u64;
        let kind = err.source.kind();
        state.note_candidate_upstream_connect_failed(target, logical_target, kind);
        state.note_upstream_connect_failed(target, rtt_ms, kind);
        return Err(err);
    }
    record_connect_telemetry(state, &stream, target, group_index, started);
    Ok(stream)
}

pub(super) fn unspecified_ip_for(addr: SocketAddr) -> IpAddr {
    match addr {
        SocketAddr::V4(_) => IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        SocketAddr::V6(_) => IpAddr::V6(Ipv6Addr::UNSPECIFIED),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn outbound_connects_do_not_reuse_listener_bind_ip() {
        assert_eq!(unspecified_ip_for(SocketAddr::from(([203, 0, 113, 7], 443))), IpAddr::V4(Ipv4Addr::UNSPECIFIED));
        assert_eq!(
            unspecified_ip_for(SocketAddr::from(([0u16, 0, 0, 0, 0, 0, 0, 1], 443))),
            IpAddr::V6(Ipv6Addr::UNSPECIFIED),
        );
    }
}

#[cfg(all(test, not(feature = "loom")))]
mod exit_ip_cap_wiring_tests {
    use super::*;
    use crate::runtime::config::RuntimeConfig;
    use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
    use ripdpi_session_limit::DEFAULT_EXIT_IP_SESSION_CAP;
    use std::net::TcpListener;
    use std::sync::mpsc;
    use std::thread;

    /// Stands up a loopback listener that accepts exactly one connection and
    /// holds it open until the returned channel is signalled. Returns the bound
    /// address, the release sender, and the accept thread join handle.
    fn accept_once() -> (SocketAddr, mpsc::Sender<()>, thread::JoinHandle<()>) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind candidate listener");
        let addr = listener.local_addr().expect("listener addr");
        let (release_tx, release_rx) = mpsc::channel();
        let handle = thread::spawn(move || {
            let (_stream, _) = listener.accept().expect("accept candidate");
            release_rx.recv().expect("wait for test release");
        });
        (addr, release_tx, handle)
    }

    fn state_with_one_group() -> RuntimeState {
        let config = RuntimeConfig { groups: vec![DesyncGroup::new(0)], ..Default::default() };
        RuntimeState::test(config)
    }

    #[test]
    fn acquired_slot_is_held_for_session_and_freed_on_guard_drop() {
        let (addr, release_tx, accept_thread) = accept_once();
        let state = state_with_one_group();
        let exit_ip = addr.ip();

        // Connecting with cap-slot acquisition must return a live guard and
        // increment the in-flight session count for this exit IP.
        let (stream, guard) = connect_target_candidates_via_group(
            &[addr],
            None,
            &state,
            0,
            None,
            true,
            true,
            DestinationEgress::Tunneled,
        )
        .expect("connect candidate");
        assert!(guard.is_some(), "acquire_cap_slot=true must yield a slot guard on a clear path");
        assert_eq!(state.active_exit_sessions(exit_ip), 1, "an acquired slot must be counted in-flight");

        // Dropping the guard (end of relay session) frees the slot.
        drop(guard);
        assert_eq!(state.active_exit_sessions(exit_ip), 0, "dropping the guard frees the slot");

        drop(stream);
        release_tx.send(()).expect("release accept thread");
        accept_thread.join().expect("accept thread finished");
    }

    #[test]
    fn route_preference_skips_an_at_cap_candidate_in_favor_of_an_alternate() {
        // Candidate A is a distinct exit IP that is driven to its per-exit-IP
        // cap. Because the loop skips an at-cap candidate BEFORE connecting, A
        // never needs a real listener — only its IP is pre-saturated. Candidate
        // B is the real loopback listener the loop must prefer.
        let addr_a = SocketAddr::from(([203, 0, 113, 1], 443));
        let (addr_b, release_tx_b, accept_thread_b) = accept_once();
        let state = state_with_one_group();

        // Saturate candidate A's exit IP at the default cap so every slot is taken.
        let held: Vec<_> = (0..DEFAULT_EXIT_IP_SESSION_CAP)
            .map(|_| state.try_acquire_exit_session(addr_a.ip()).expect("pre-acquire A slot"))
            .collect();
        assert_eq!(state.active_exit_sessions(addr_a.ip()), DEFAULT_EXIT_IP_SESSION_CAP);
        assert!(state.try_acquire_exit_session(addr_a.ip()).is_none(), "candidate A must be at cap");

        // A is first in the candidate list but at cap, so the loop must skip it
        // and connect to B instead, holding a slot on B.
        let (stream, guard) = connect_target_candidates_via_group(
            &[addr_a, addr_b],
            None,
            &state,
            0,
            None,
            true,
            true,
            DestinationEgress::Tunneled,
        )
        .expect("connect prefers alternate candidate");
        assert_eq!(stream.peer_addr().expect("peer addr"), addr_b, "must connect to the alternate candidate B");
        assert!(guard.is_some(), "a slot must be held on the alternate candidate");
        assert_eq!(state.active_exit_sessions(addr_b.ip()), 1, "the slot is counted for candidate B");
        // Candidate A's count is unchanged (no new session opened to it).
        assert_eq!(state.active_exit_sessions(addr_a.ip()), DEFAULT_EXIT_IP_SESSION_CAP);

        drop(guard);
        drop(held);
        drop(stream);
        release_tx_b.send(()).expect("release accept thread B");
        accept_thread_b.join().expect("accept thread B finished");
    }

    #[test]
    fn all_candidates_at_cap_fall_back_without_a_slot() {
        // Every candidate exit IP is at cap. The cap is advisory throughput
        // shaping, not a hard block, so the loop must still connect -- to the
        // first candidate, WITHOUT holding a slot -- rather than refusing the
        // user's connection.
        let (addr, release_tx, accept_thread) = accept_once();
        let state = state_with_one_group();
        let exit_ip = addr.ip();

        // Saturate the only candidate's exit IP at the default cap.
        let held: Vec<_> = (0..DEFAULT_EXIT_IP_SESSION_CAP)
            .map(|_| state.try_acquire_exit_session(exit_ip).expect("pre-acquire slot"))
            .collect();
        assert!(state.try_acquire_exit_session(exit_ip).is_none(), "candidate must be at cap");

        let (stream, guard) = connect_target_candidates_via_group(
            &[addr],
            None,
            &state,
            0,
            None,
            true,
            true,
            DestinationEgress::Tunneled,
        )
        .expect("advisory fallback must still connect when every candidate is at cap");
        assert!(guard.is_none(), "the advisory-fallback connection must NOT hold a slot");
        // The count is unchanged: the fallback did not open a counted session.
        assert_eq!(
            state.active_exit_sessions(exit_ip),
            DEFAULT_EXIT_IP_SESSION_CAP,
            "advisory fallback must not push the count past the cap",
        );

        drop(guard);
        drop(held);
        drop(stream);
        release_tx.send(()).expect("release accept thread");
        accept_thread.join().expect("accept thread finished");
    }

    #[test]
    fn a_real_connect_error_is_returned_even_when_another_candidate_is_capped() {
        // Mixed case: candidate A is at cap (skipped), candidate B errors. The
        // loop must propagate B's connect error, NOT spuriously fall back as if
        // every candidate were merely at cap.
        let addr_a = SocketAddr::from(([203, 0, 113, 1], 443));
        // A refused loopback port: bind then drop so connecting to it is refused.
        let refused = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind to claim a port");
        let addr_b = refused.local_addr().expect("refused addr");
        drop(refused);
        let state = state_with_one_group();

        let held: Vec<_> = (0..DEFAULT_EXIT_IP_SESSION_CAP)
            .map(|_| state.try_acquire_exit_session(addr_a.ip()).expect("pre-acquire A slot"))
            .collect();

        let result = connect_target_candidates_via_group(
            &[addr_a, addr_b],
            None,
            &state,
            0,
            None,
            true,
            true,
            DestinationEgress::Tunneled,
        );
        assert!(result.is_err(), "a real connect error must propagate, not be masked by an at-cap sibling candidate");
        // B's failed attempt must not leak a slot.
        assert_eq!(state.active_exit_sessions(addr_b.ip()), 0, "a failed connect frees its slot");

        drop(held);
    }
}
