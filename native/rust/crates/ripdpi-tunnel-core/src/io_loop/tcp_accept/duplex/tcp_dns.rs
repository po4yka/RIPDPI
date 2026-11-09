use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt, DuplexStream, ReadHalf, WriteHalf, duplex};
use tokio::sync::mpsc::Sender;
use tokio::task::JoinSet;
use tokio_util::sync::CancellationToken;
use tracing::debug;

use crate::Stats;
use crate::dns_cache::servfail_response;
use crate::io_loop::DUPLEX_BUF;
use crate::io_loop::dns_intercept::{DirectDnsRequest, DnsRequest, parse_dns_query};
use crate::split_dns::{DnsPolicyPlane, SplitDnsPolicy};
use crate::stats::SplitDnsDecisionKind;

use super::SessionDuplex;

/// Queries one session resolves at once: pipelined queries are processed concurrently and
/// answered as they complete, possibly out of order (RFC 7766 sections 6.2.1.1 and 7).
const MAX_PIPELINED_QUERIES: usize = 8;
/// A session with no pending query closes after this idle period (RFC 7766 section 6.2.3);
/// the timer restarts only after a complete query or answer, never on a partial frame.
const IDLE_TIMEOUT: Duration = Duration::from_secs(10);

pub(in crate::io_loop::tcp_accept) fn create_tcp_dns_duplex(
    src: SocketAddr,
    dns_req_tx: Option<Sender<DnsRequest>>,
    split_dns_policy: Option<Arc<SplitDnsPolicy>>,
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
    SessionDuplex { smoltcp_side, cancel, handle, upstream_failed: Arc::default() }
}

/// # Cancel safety
///
/// NOT cancel-safe: cancellation may consume or write part of a length-prefixed
/// DNS frame. This future is owned by one session task; cancellation permanently
/// closes that duplex stream, so the partially transferred frame is never resumed.
/// Inside, the frame read stays pinned across `select!` iterations and is dropped
/// only when the session ends, so a completed lookup never cuts a frame in half.
async fn run_tcp_dns_session(
    stream: DuplexStream,
    src: SocketAddr,
    dns_req_tx: Option<Sender<DnsRequest>>,
    split_dns_policy: Option<Arc<SplitDnsPolicy>>,
    cancel: CancellationToken,
    stats: Arc<Stats>,
) -> io::Result<()> {
    let (reader, mut writer) = tokio::io::split(stream);
    let mut read = Box::pin(read_dns_frame(reader));
    let mut read_result = None;
    let mut pending: JoinSet<io::Result<Vec<u8>>> = JoinSet::new();
    loop {
        if pending.is_empty()
            && let Some(result) = read_result.take()
        {
            // The client stopped sending and every answer is written: end like the serial loop did.
            return result;
        }
        tokio::select! {
            biased;
            _ = cancel.cancelled() => return Ok(()),
            Some(done) = pending.join_next(), if !pending.is_empty() => {
                // One failed lookup must not drop the other answers still in flight.
                let Ok(Ok(response)) = done else {
                    debug!("TCP DNS lookup produced no answer; keeping the session for the others");
                    continue;
                };
                tokio::select! {
                    biased;
                    _ = cancel.cancelled() => return Ok(()),
                    result = write_dns_frame(&mut writer, &response) => result?,
                }
            }
            (reader, frame) = &mut read, if read_result.is_none() && pending.len() < MAX_PIPELINED_QUERIES => {
                match frame {
                    Ok(query) => {
                        read = Box::pin(read_dns_frame(reader));
                        let (tx, policy, cancel, stats) =
                            (dns_req_tx.clone(), split_dns_policy.clone(), cancel.clone(), Arc::clone(&stats));
                        pending.spawn(async move {
                            let fallback = servfail_response(&query);
                            let answer =
                                resolve_tcp_query(src, query, tx.as_ref(), policy.as_deref(), &cancel, &stats).await;
                            answer.or_else(|_| fallback.map_err(|error| io::Error::other(error.to_string())))
                        });
                    }
                    Err(error) => read_result = Some(Err(error)),
                }
            }
            () = tokio::time::sleep(IDLE_TIMEOUT), if pending.is_empty() && read_result.is_none() => return Ok(()),
        }
    }
}

/// # Cancel safety
///
/// NOT cancel-safe: `read_exact` may consume a partial frame. The session keeps
/// this future pinned until it completes and drops it only when the session ends.
/// It owns the read half and hands it back, so the next read can start after it.
async fn read_dns_frame(mut reader: ReadHalf<DuplexStream>) -> (ReadHalf<DuplexStream>, io::Result<Vec<u8>>) {
    let length = match reader.read_u16().await {
        Ok(0) => return (reader, Err(io::Error::new(io::ErrorKind::InvalidData, "empty TCP DNS frame"))),
        Ok(length) => usize::from(length),
        Err(error) => return (reader, Err(error)),
    };
    let mut query = vec![0u8; length];
    let result = reader.read_exact(&mut query).await.map(|_| query);
    (reader, result)
}

/// # Cancel safety
///
/// NOT cancel-safe: `write_all` may publish a partial frame. The caller only
/// cancels this operation when it is also closing the owning TCP DNS session.
async fn write_dns_frame(writer: &mut WriteHalf<DuplexStream>, payload: &[u8]) -> io::Result<()> {
    let length = u16::try_from(payload.len())
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "TCP DNS response exceeds 65535 bytes"))?;
    // Length and message in one write (RFC 7766 section 8).
    let mut frame = Vec::with_capacity(2 + payload.len());
    frame.extend_from_slice(&length.to_be_bytes());
    frame.extend_from_slice(payload);
    writer.write_all(&frame).await
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
            stats
                .record_split_dns_decision(SplitDnsDecisionKind::DirectFailClosed, Some("direct_underlay_unavailable"));
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

    /// Query for `p.example` A IN with the given ID, built byte by byte (RFC 1035 4.1).
    fn wire_query(id: u16) -> Vec<u8> {
        let mut bytes = id.to_be_bytes().to_vec();
        bytes.extend_from_slice(&[0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]);
        bytes.extend_from_slice(&[1, b'p', 7, b'e', b'x', b'a', b'm', b'p', b'l', b'e', 0, 0, 1, 0, 1]);
        bytes
    }

    #[tokio::test]
    async fn pipelined_queries_are_resolved_concurrently_and_answered_as_they_complete() {
        let (tx, mut rx) = tokio::sync::mpsc::channel(8);
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let mut session = create_tcp_dns_duplex(
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 10, 10, 10)), 53000),
            Some(tx),
            None,
            &cancel,
            &stats,
        );
        for id in [1_u16, 2] {
            let query = wire_query(id);
            session.smoltcp_side.write_u16(query.len() as u16).await.expect("length");
            session.smoltcp_side.write_all(&query).await.expect("query");
        }
        let first = rx.recv().await.expect("first query reaches the worker");
        let second = tokio::time::timeout(std::time::Duration::from_secs(1), rx.recv())
            .await
            .expect("second query reaches the worker before the first is answered")
            .expect("second request");
        assert_eq!(first.query, wire_query(1));
        assert_eq!(second.query, wire_query(2));

        second.tcp_reply.expect("reply channel").send(vec![0, 2, 0xAA]).expect("second reply");
        first.tcp_reply.expect("reply channel").send(vec![0, 1, 0xBB]).expect("first reply");
        let mut frames = [0_u8; 10];
        session.smoltcp_side.read_exact(&mut frames).await.expect("both answers");
        assert_eq!(frames, [0, 3, 0, 2, 0xAA, 0, 3, 0, 1, 0xBB], "the faster answer is written first");
        cancel.cancel();
        assert!(session.handle.await.expect("task join").is_ok());
    }

    #[tokio::test]
    async fn a_dropped_lookup_gets_servfail_and_the_session_keeps_answering() {
        let (tx, mut rx) = tokio::sync::mpsc::channel(8);
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let mut session = create_tcp_dns_duplex(
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 10, 10, 10)), 53000),
            Some(tx),
            None,
            &cancel,
            &stats,
        );
        for id in [3_u16, 4] {
            let query = wire_query(id);
            session.smoltcp_side.write_u16(query.len() as u16).await.expect("length");
            session.smoltcp_side.write_all(&query).await.expect("query");
        }
        drop(rx.recv().await.expect("first request").tcp_reply);
        let mut length = [0_u8; 2];
        session.smoltcp_side.read_exact(&mut length).await.expect("SERVFAIL length");
        let mut servfail = vec![0_u8; usize::from(u16::from_be_bytes(length))];
        session.smoltcp_side.read_exact(&mut servfail).await.expect("SERVFAIL");
        assert_eq!(&servfail[..2], &[0, 3], "answers query 3");
        assert_eq!(servfail[2] & 0x80, 0x80, "QR set");
        assert_eq!(servfail[3] & 0x0F, 2, "RCODE SERVFAIL");

        rx.recv().await.expect("second request").tcp_reply.expect("reply channel").send(vec![0, 4]).expect("reply");
        let mut frame = [0_u8; 4];
        session.smoltcp_side.read_exact(&mut frame).await.expect("second answer");
        assert_eq!(frame, [0, 2, 0, 4]);
        cancel.cancel();
        assert!(session.handle.await.expect("task join").is_ok());
    }

    #[tokio::test(start_paused = true)]
    async fn idle_session_closes_after_the_idle_timeout() {
        let cancel = CancellationToken::new();
        let stats = Arc::new(Stats::default());
        let session = create_tcp_dns_duplex(
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(10, 10, 10, 10)), 53000),
            None,
            None,
            &cancel,
            &stats,
        );
        let started = tokio::time::Instant::now();
        assert!(session.handle.await.expect("task join").is_ok());
        assert_eq!(started.elapsed(), IDLE_TIMEOUT);
        drop(session.smoltcp_side);
    }
}
