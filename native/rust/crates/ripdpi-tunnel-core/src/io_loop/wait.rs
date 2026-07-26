use std::time::Duration;

use smoltcp::time::Instant;
use tracing::info;
use tun_rs::AsyncDevice;

use super::DEFAULT_POLL_DELAY_MS;
use super::dns_intercept::DnsResponse;
use super::state::LoopState;
use super::udp_assoc::{UdpEvent, handle_udp_event};

mod drain;

use drain::{drain_udp_events, handle_dns_wait_response, recv_dns_response};

pub(in crate::io_loop) enum WaitOutcome {
    Continue,
    Cancelled,
}

pub(super) enum WaitEvent {
    TunReadable,
    PollTimer,
    Udp(Option<UdpEvent>),
    Dns(Option<DnsResponse>),
    Cancelled,
}

pub(in crate::io_loop) async fn wait_for_next_event(
    tun: &AsyncDevice,
    state: &mut LoopState,
    work_pending: bool,
) -> WaitOutcome {
    let smol_delay = state
        .iface
        .poll_delay(Instant::now(), &state.socket_set)
        .map_or(Duration::from_millis(DEFAULT_POLL_DELAY_MS), |d| Duration::from_micros(d.total_micros()));

    drain_udp_events(state);

    let mut dns_resp_rx = state.dns_resp_rx.take();
    let dns_enabled = dns_resp_rx.is_some();
    // biased; checks the cancellation arm first on every poll so shutdown is prompt
    // under sustained TUN/UDP/DNS readiness (Foreground-Service 5s teardown window).
    let event = tokio::select! {
        biased;
        _ = state.cancel.cancelled() => WaitEvent::Cancelled,
        _ = std::future::ready(()), if work_pending => WaitEvent::PollTimer,
        _ = tun.readable() => WaitEvent::TunReadable,
        _ = tokio::time::sleep(smol_delay) => WaitEvent::PollTimer,
        udp_event = state.udp_rx.recv() => WaitEvent::Udp(udp_event),
        dns_result = recv_dns_response(&mut dns_resp_rx), if dns_enabled => WaitEvent::Dns(dns_result),
    };
    state.dns_resp_rx = dns_resp_rx;

    handle_wait_event(state, event)
}

pub(super) fn handle_wait_event(state: &mut LoopState, event: WaitEvent) -> WaitOutcome {
    match event {
        WaitEvent::TunReadable | WaitEvent::PollTimer => WaitOutcome::Continue,
        WaitEvent::Udp(Some(event)) => {
            handle_udp_event(
                &mut state.device,
                &mut state.udp_associations,
                &mut state.udp_eviction_heap,
                &mut state.dns_cache,
                event,
            );
            WaitOutcome::Continue
        }
        WaitEvent::Udp(None) => WaitOutcome::Continue,
        WaitEvent::Dns(Some(response)) => {
            handle_dns_wait_response(state, response);
            WaitOutcome::Continue
        }
        WaitEvent::Dns(None) => {
            state.stats.record_dns_failure(None, "dns worker exited unexpectedly", None);
            state.dns_req_tx = None;
            state.dns_resp_rx = None;
            WaitOutcome::Continue
        }
        WaitEvent::Cancelled => {
            info!("io_loop cancelled -- shutting down");
            WaitOutcome::Cancelled
        }
    }
}
