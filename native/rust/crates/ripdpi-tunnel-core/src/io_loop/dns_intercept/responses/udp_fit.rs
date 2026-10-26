use hickory_proto::op::Message;
use tracing::debug;

/// IPv4 and UDP header bytes around a reply; the intercept address is IPv4 only.
const IPV4_UDP_HEADERS_LEN: usize = 28;
/// RFC 1035 section 4.2.1: the UDP size every requester accepts.
const MIN_UDP_DNS_PAYLOAD: usize = 512;

/// Returns `payload` when the requester accepts it over UDP and it fits the TUN MTU. A larger
/// reply becomes its header, question and OPT record with TC set, so the stub retries over TCP
/// (RFC 1035 section 4.2.1, RFC 6891 sections 6.2.3 and 7). `None` when that reply cannot be built.
pub(super) fn fit_udp_reply(query: &[u8], payload: Vec<u8>, mtu: usize) -> Option<Vec<u8>> {
    let requester_limit = Message::from_vec(query)
        .ok()
        .and_then(|query| query.edns)
        .map_or(MIN_UDP_DNS_PAYLOAD, |edns| usize::from(edns.max_payload()).max(MIN_UDP_DNS_PAYLOAD));
    if payload.len() <= requester_limit.min(mtu.saturating_sub(IPV4_UDP_HEADERS_LEN)) {
        return Some(payload);
    }
    let truncated = Message::from_vec(&payload).map_err(|error| error.to_string()).and_then(|mut message| {
        message.answers.clear();
        message.authorities.clear();
        message.additionals.clear();
        message.signature = None;
        message.metadata.truncation = true;
        message.to_vec().map_err(|error| error.to_string())
    });
    match truncated {
        Ok(truncated) => Some(truncated),
        Err(error) => {
            debug!("dropping an oversized UDP DNS reply that cannot be truncated: {error}");
            None
        }
    }
}
