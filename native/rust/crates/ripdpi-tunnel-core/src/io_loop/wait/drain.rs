use super::super::IO_PHASE_WORK_BUDGET;
use super::super::dns_intercept::{DnsResponse, handle_dns_result};
use super::super::state::LoopState;
use super::super::udp_assoc::handle_udp_event;

pub(super) fn drain_udp_events(state: &mut LoopState) {
    drain_ready_with_budget(
        IO_PHASE_WORK_BUDGET,
        || state.udp_rx.try_recv(),
        |event| {
            handle_udp_event(
                &mut state.device,
                &mut state.udp_associations,
                &mut state.udp_eviction_heap,
                &mut state.dns_cache,
                event,
            );
        },
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

pub(super) async fn recv_dns_response(
    receiver: &mut Option<tokio::sync::mpsc::Receiver<DnsResponse>>,
) -> Option<DnsResponse> {
    receiver.as_mut()?.recv().await
}

pub(super) fn handle_dns_wait_response(state: &mut LoopState, response: DnsResponse) {
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
