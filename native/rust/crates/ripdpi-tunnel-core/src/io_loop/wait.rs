use std::time::Duration;

use smoltcp::time::Instant;
use tracing::info;
use tun_rs::AsyncDevice;

use super::DEFAULT_POLL_DELAY_MS;
use super::IO_PHASE_WORK_BUDGET;
use super::dns_intercept::{DnsResponse, handle_dns_result};
use super::state::LoopState;
use super::udp_assoc::{UdpEvent, handle_udp_event};

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
    drain_ready_with_budget(
        IO_PHASE_WORK_BUDGET,
        || state.udp_rx.try_recv(),
        |event| handle_udp_event(&mut state.device, &mut state.udp_associations, event),
    );
}

fn drain_ready_with_budget<T>(
    budget: usize,
    mut receive: impl FnMut() -> Result<T, tokio::sync::mpsc::error::TryRecvError>,
    mut handle: impl FnMut(T),
) -> usize {
    let mut drained = 0;
    while drained < budget {
        let Ok(item) = receive() else {
            break;
        };
        handle(item);
        drained += 1;
    }
    drained
}

async fn recv_dns_response(receiver: &mut Option<tokio::sync::mpsc::Receiver<DnsResponse>>) -> Option<DnsResponse> {
    receiver.as_mut()?.recv().await
}

fn handle_dns_wait_response(state: &mut LoopState, response: DnsResponse) {
    if let (Some(mapdns), Some(cache)) = (state.runtime.mapdns_runtime, state.dns_cache.as_mut()) {
        handle_dns_result(&mut state.device, &state.stats, mapdns, cache, response);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ready_event_drain_yields_at_phase_budget() {
        let mut remaining = IO_PHASE_WORK_BUDGET + 1;
        let mut handled = 0;

        let drained = drain_ready_with_budget(
            IO_PHASE_WORK_BUDGET,
            || {
                if remaining == 0 {
                    Err(tokio::sync::mpsc::error::TryRecvError::Empty)
                } else {
                    remaining -= 1;
                    Ok(())
                }
            },
            |()| handled += 1,
        );

        assert_eq!(drained, IO_PHASE_WORK_BUDGET);
        assert_eq!(handled, IO_PHASE_WORK_BUDGET);
        assert_eq!(remaining, 1, "one ready event must remain for the next loop tick");
    }
}
