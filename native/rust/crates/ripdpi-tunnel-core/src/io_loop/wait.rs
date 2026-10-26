use std::time::Duration;

use crate::device::PacketFd;
use smoltcp::time::Instant;

use super::DEFAULT_POLL_DELAY_MS;
use super::dns_intercept::DnsResponse;
use super::state::LoopState;
use super::udp_assoc::UdpEvent;

mod drain;
mod event;

use drain::{drain_udp_events, recv_dns_response};
pub(super) use event::handle_wait_event;

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

/// # Cancel safety
///
/// Cancel-safe. Every `select!` arm uses a cancel-safe primitive (`readable`,
/// timer, or channel receive), and the DNS receiver remains borrowed in
/// `LoopState` while suspended instead of being removed and restored around the
/// await. Dropping this future therefore cannot disable DNS responses.
pub(in crate::io_loop) async fn wait_for_next_event(
    tun: &PacketFd,
    state: &mut LoopState,
    work_pending: bool,
) -> WaitOutcome {
    let smol_delay = state
        .iface
        .poll_delay(Instant::now(), &state.socket_set)
        .map_or(Duration::from_millis(DEFAULT_POLL_DELAY_MS), |d| Duration::from_micros(d.total_micros()));

    drain_udp_events(state);

    let dns_enabled = state.dns_resp_rx.is_some();
    // biased; checks the cancellation arm first on every poll so shutdown is prompt
    // under sustained TUN/UDP/DNS readiness (Foreground-Service 5s teardown window).
    let event = tokio::select! {
        biased;
        _ = state.cancel.cancelled() => WaitEvent::Cancelled,
        _ = std::future::ready(()), if work_pending => WaitEvent::PollTimer,
        _ = tun.readable() => WaitEvent::TunReadable,
        _ = state.sessions.bridge_ready() => WaitEvent::PollTimer,
        _ = tokio::time::sleep(smol_delay) => WaitEvent::PollTimer,
        udp_event = state.udp_rx.recv() => WaitEvent::Udp(udp_event),
        dns_result = recv_dns_response(&mut state.dns_resp_rx), if dns_enabled => WaitEvent::Dns(dns_result),
    };

    handle_wait_event(state, event)
}
