use std::io;
use std::net::SocketAddr;
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream, duplex};
use tokio::sync::mpsc::Sender;
use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::dns_cache::servfail_response;
use crate::io_loop::DUPLEX_BUF;
use crate::io_loop::dns_intercept::{DirectDnsRequest, DnsRequest, parse_dns_query};
use crate::split_dns::{DnsPolicyPlane, SplitDnsPolicy};
use crate::stats::SplitDnsDecisionKind;

use super::SessionDuplex;

pub(in crate::io_loop::tcp_accept) fn create_tcp_dns_duplex(
    src: SocketAddr,
    dns_req_tx: Option<Sender<DnsRequest>>,
    split_dns_policy: Option<SplitDnsPolicy>,
    parent_cancel: &CancellationToken,
    stats: &Arc<Stats>,
) -> SessionDuplex {
    let (smoltcp_side, session_side) = duplex(DUPLEX_BUF);
    let cancel = parent_cancel.child_token();
    let session_cancel = cancel.clone();
    let stats = Arc::clone(stats);
    let handle = tokio::spawn(async move {
        run_tcp_dns_session(session_side, src, dns_req_tx, split_dns_policy, session_cancel, stats).await
    });
    SessionDuplex { smoltcp_side, cancel, handle }
}

/// # Cancel safety
///
/// NOT cancel-safe: cancellation may consume or write part of a length-prefixed
/// DNS frame. This future is owned by one session task; cancellation permanently
/// closes that duplex stream, so the partially transferred frame is never resumed.
async fn run_tcp_dns_session(
    mut stream: DuplexStream,
    src: SocketAddr,
    dns_req_tx: Option<Sender<DnsRequest>>,
    split_dns_policy: Option<SplitDnsPolicy>,
    cancel: CancellationToken,
    stats: Arc<Stats>,
) -> io::Result<()> {
    loop {
        let query = tokio::select! {
            biased;
            _ = cancel.cancelled() => return Ok(()),
            query = read_dns_frame(&mut stream) => query?,
        };
        let response =
            resolve_tcp_query(src, query, dns_req_tx.as_ref(), split_dns_policy.as_ref(), &cancel, &stats).await?;
        tokio::select! {
            biased;
            _ = cancel.cancelled() => return Ok(()),
            result = write_dns_frame(&mut stream, &response) => result?,
        }
    }
}

/// # Cancel safety
///
/// NOT cancel-safe: `read_exact` may consume a partial frame. The caller only
/// cancels this operation when it is also closing the owning TCP DNS session.
async fn read_dns_frame(stream: &mut DuplexStream) -> io::Result<Vec<u8>> {
    let length = stream.read_u16().await? as usize;
    if length == 0 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "empty TCP DNS frame"));
    }
    let mut query = vec![0u8; length];
    stream.read_exact(&mut query).await?;
    Ok(query)
}

/// # Cancel safety
///
/// NOT cancel-safe: `write_all` may publish a partial frame. The caller only
/// cancels this operation when it is also closing the owning TCP DNS session.
async fn write_dns_frame(stream: &mut DuplexStream, payload: &[u8]) -> io::Result<()> {
    let length = u16::try_from(payload.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "TCP DNS response exceeds 65535 bytes"))?;
    stream.write_u16(length).await?;
    stream.write_all(payload).await
}

/// # Cancel safety
///
/// cancel-safe: the only suspended receive is a `oneshot::Receiver`; dropping
/// it leaves no cache mutation in this task. The central DNS worker and loop
/// either finish or discard their response independently.
async fn resolve_tcp_query(
    src: SocketAddr,
    query: Vec<u8>,
    dns_req_tx: Option<&Sender<DnsRequest>>,
    split_dns_policy: Option<&SplitDnsPolicy>,
    cancel: &CancellationToken,
    stats: &Arc<Stats>,
) -> io::Result<Vec<u8>> {
    let parsed = parse_dns_query(&query);
    let host = parsed.as_ref().map(|value| value.host.clone());
    let mut direct = None;
    if let Some(policy) = split_dns_policy {
        let decision = policy.evaluate(host.as_deref().unwrap_or(""));
        if decision.plane == DnsPolicyPlane::Block {
            stats.record_split_dns_decision(SplitDnsDecisionKind::Block, decision.reason);
            return parsed
                .and_then(|value| value.refused_response().ok())
                .or_else(|| servfail_response(&query).ok())
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "failed to encode blocked TCP DNS reply"));
        }
        direct = if decision.plane == DnsPolicyPlane::Direct {
            crate::tunnel_api::direct_dns_binding::current_direct_dns_generation().map(|generation| DirectDnsRequest {
                generation,
                candidates: policy.direct_resolver_candidates().to_vec().into_boxed_slice(),
            })
        } else {
            None
        };
        if decision.plane == DnsPolicyPlane::Direct && direct.is_none() {
            stats.record_split_dns_decision(
                SplitDnsDecisionKind::DirectProxyFallback,
                Some("direct_underlay_unavailable"),
            );
            stats.record_dns_failure(host.as_deref(), "direct DNS underlay unavailable", None);
            return servfail_response(&query)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()));
        }
        if decision.plane != DnsPolicyPlane::Direct {
            stats.record_split_dns_decision(SplitDnsDecisionKind::ProxyEncrypted, decision.reason);
        }
    }

    let Some(request_tx) = dns_req_tx else {
        stats.record_dns_failure(host.as_deref(), "encrypted DNS resolver is not configured", None);
        return servfail_response(&query)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()));
    };
    let (reply_tx, reply_rx) = tokio::sync::oneshot::channel();
    let request = DnsRequest { src, query, host, direct, tcp_reply: Some(reply_tx) };
    match request_tx.try_send(request) {
        Ok(()) => {}
        Err(tokio::sync::mpsc::error::TrySendError::Full(request)) => {
            stats.record_dns_failure(request.host.as_deref(), "dns worker queue full", None);
            return servfail_response(&request.query)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()));
        }
        Err(tokio::sync::mpsc::error::TrySendError::Closed(request)) => {
            stats.record_dns_failure(request.host.as_deref(), "dns worker unavailable", None);
            return servfail_response(&request.query)
                .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error.to_string()));
        }
    }
    tokio::select! {
        biased;
        _ = cancel.cancelled() => Err(io::Error::new(io::ErrorKind::Interrupted, "TCP DNS session cancelled")),
        reply = reply_rx => reply.map_err(|_| io::Error::new(io::ErrorKind::BrokenPipe, "DNS worker dropped TCP reply")),
    }
}

#[cfg(test)]
mod tests {
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};

    use hickory_proto::op::{Message, MessageType, OpCode, Query};
    use hickory_proto::rr::{Name, RecordType};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::*;

    fn query() -> Vec<u8> {
        let mut message = Message::new(0x1234, MessageType::Query, OpCode::Query);
        message.add_query(Query::new(Name::from_ascii("tcp.example").expect("name"), RecordType::A));
        message.to_vec().expect("query")
    }

    #[tokio::test]
    async fn tcp_dns_session_frames_query_and_worker_reply() {
        let (tx, mut rx) = tokio::sync::mpsc::channel(1);
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let mut session = create_tcp_dns_duplex(
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 10, 10, 10)), 53000),
            Some(tx),
            None,
            &cancel,
            &stats,
        );
        let query = query();
        session.smoltcp_side.write_u16(query.len() as u16).await.expect("length");
        session.smoltcp_side.write_all(&query).await.expect("query write");

        let request = rx.recv().await.expect("worker request");
        assert_eq!(request.query, query);
        request.tcp_reply.expect("TCP reply channel").send(vec![1, 2, 3]).expect("reply accepted");

        assert_eq!(session.smoltcp_side.read_u16().await.expect("reply length"), 3);
        let mut reply = [0u8; 3];
        session.smoltcp_side.read_exact(&mut reply).await.expect("reply");
        assert_eq!(reply, [1, 2, 3]);
        cancel.cancel();
        assert!(session.handle.await.expect("task join").is_ok());
    }
}
