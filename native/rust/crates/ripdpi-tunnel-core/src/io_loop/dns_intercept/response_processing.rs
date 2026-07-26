use std::sync::Arc;

use crate::dns_cache::DnsCache;
use crate::stats::SplitDnsDecisionKind;
use crate::{Stats, TunDevice};

use super::super::IO_PHASE_WORK_BUDGET;
use super::{
    DnsRequest, DnsResponse, MapDnsRuntime, handle_dns_failure, handle_dns_result, sync_direct_dns_mapping_generation,
};

pub(in crate::io_loop) fn drain_dns_responses(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    cache: &mut DnsCache,
    dns_resp_rx: &mut Option<tokio::sync::mpsc::Receiver<DnsResponse>>,
    dns_req_tx: &mut Option<tokio::sync::mpsc::Sender<DnsRequest>>,
    active_direct_generation: &mut Option<u64>,
) {
    for _ in 0..IO_PHASE_WORK_BUDGET {
        let dns_response = match dns_resp_rx.as_mut() {
            Some(receiver) => match receiver.try_recv() {
                Ok(response) => Some(response),
                Err(tokio::sync::mpsc::error::TryRecvError::Empty) => None,
                Err(tokio::sync::mpsc::error::TryRecvError::Disconnected) => {
                    stats.record_dns_failure(None, "dns worker exited unexpectedly", None);
                    *dns_req_tx = None;
                    *dns_resp_rx = None;
                    None
                }
            },
            None => None,
        };
        let Some(response) = dns_response else {
            break;
        };
        handle_dns_response(device, stats, mapdns, cache, active_direct_generation, response);
    }
}

pub(in crate::io_loop) fn handle_dns_response(
    device: &mut TunDevice,
    stats: &Arc<Stats>,
    mapdns: MapDnsRuntime,
    cache: &mut DnsCache,
    active_direct_generation: &mut Option<u64>,
    response: DnsResponse,
) {
    sync_direct_dns_mapping_generation(Some(cache), active_direct_generation);
    if response.direct_fallback {
        stats.record_split_dns_decision(SplitDnsDecisionKind::DirectProxyFallback, Some("direct_transport_failed"));
    }
    if response
        .direct_generation
        .is_some_and(|generation| !crate::tunnel_api::direct_dns_binding::is_direct_dns_generation_current(generation))
    {
        stats.record_direct_dns_stale_response();
        cache.reset_unleased();
        handle_dns_failure(device, stats, mapdns, cache, response, "direct DNS generation stale");
    } else {
        if response.direct_generation.is_some() && !response.direct_fallback && response.upstream.is_ok() {
            stats.record_direct_dns_success();
        }
        if let Some(generation) = response.direct_generation
            && active_direct_generation.replace(generation) != Some(generation)
        {
            cache.reset_unleased();
        }
        handle_dns_result(device, stats, mapdns, cache, response);
    }
}
