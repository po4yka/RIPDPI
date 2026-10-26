use tracing::info;

use super::super::state::LoopState;
use super::super::udp_assoc::handle_udp_event;
use super::drain::handle_dns_wait_response;
use super::{WaitEvent, WaitOutcome};

pub(in crate::io_loop) fn handle_wait_event(state: &mut LoopState, event: WaitEvent) -> WaitOutcome {
    match event {
        WaitEvent::TunReadable | WaitEvent::PollTimer | WaitEvent::Udp(None) => WaitOutcome::Continue,
        WaitEvent::Udp(Some(event)) => {
            handle_udp_event(
                &mut state.device,
                &state.stats,
                &mut state.udp_associations,
                &mut state.udp_eviction_heap,
                &mut state.dns_cache,
                &mut state.closed_udp_sources,
                event,
            );
            WaitOutcome::Continue
        }
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
