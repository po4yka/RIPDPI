use std::time::Duration;

use smoltcp::time::Instant;
use tracing::info;
use tun_rs::AsyncDevice;

use super::dns_intercept::{handle_dns_result, DnsResponse};
use super::state::LoopState;
use super::udp_assoc::{handle_udp_event, UdpEvent};
use super::DEFAULT_POLL_DELAY_MS;

pub(in crate::io_loop) enum WaitOutcome {
    Continue,
    Cancelled,
}

enum WaitEvent {
    TunReadable,
    PollTimer,
    Udp(Option<UdpEvent>),
    Dns(Option<DnsResponse>),
    Cancelled,
}

pub(in crate::io_loop) async fn wait_for_next_event(tun: &AsyncDevice, state: &mut LoopState) -> WaitOutcome {
    let smol_delay = state
        .iface
        .poll_delay(Instant::now(), &state.socket_set)
        .map_or(Duration::from_millis(DEFAULT_POLL_DELAY_MS), |d| Duration::from_micros(d.total_micros()));

    drain_udp_events(state);

    let mut dns_resp_rx = state.dns_resp_rx.take();
    let dns_enabled = dns_resp_rx.is_some();
    let event = tokio::select! {
        _ = tun.readable() => WaitEvent::TunReadable,
        _ = tokio::time::sleep(smol_delay) => WaitEvent::PollTimer,
        udp_event = state.udp_rx.recv() => WaitEvent::Udp(udp_event),
        dns_result = recv_dns_response(&mut dns_resp_rx), if dns_enabled => WaitEvent::Dns(dns_result),
        _ = state.cancel.cancelled() => WaitEvent::Cancelled,
    };
    state.dns_resp_rx = dns_resp_rx;

    match event {
        WaitEvent::TunReadable | WaitEvent::PollTimer => WaitOutcome::Continue,
        WaitEvent::Udp(Some(event)) => {
            handle_udp_event(&mut state.device, &mut state.udp_associations, event);
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

fn drain_udp_events(state: &mut LoopState) {
    while let Ok(event) = state.udp_rx.try_recv() {
        handle_udp_event(&mut state.device, &mut state.udp_associations, event);
    }
}

async fn recv_dns_response(receiver: &mut Option<tokio::sync::mpsc::Receiver<DnsResponse>>) -> Option<DnsResponse> {
    match receiver.as_mut() {
        Some(receiver) => receiver.recv().await,
        None => None,
    }
}

fn handle_dns_wait_response(state: &mut LoopState, response: DnsResponse) {
    if let (Some(mapdns), Some(cache)) = (state.runtime.mapdns_runtime, state.dns_cache.as_mut()) {
        handle_dns_result(&mut state.device, &state.stats, mapdns, cache, response);
    }
}
