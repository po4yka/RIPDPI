use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::time::{Duration, Instant};

use hickory_proto::op::Message;
use ripdpi_dns_resolver::{EncryptedDnsExchangeSuccess, ensure_response_matches_query};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::tunnel_api::direct_dns_binding::{bind_direct_dns_socket, is_direct_dns_generation_current};

/// Smallest time slice a candidate gets: an even split of a 4 s budget over many candidates
/// would give each less than one RTT on a slow (2G, satellite) underlay.
const MIN_CANDIDATE_SLOT: Duration = Duration::from_secs(1);

/// # Cancel safety
///
/// Cancel-safe: cancellation drops the sole request-owned socket. DNS is
/// read-only and no shared/cache state is committed before a complete,
/// validated response is returned.
pub(super) async fn exchange(
    query: &[u8],
    candidates: &[IpAddr],
    generation: u64,
    timeout: Duration,
) -> io::Result<EncryptedDnsExchangeSuccess> {
    exchange_on_port(query, candidates, generation, timeout, 53).await
}

/// # Cancel safety
///
/// Cancel-safe: every awaited candidate owns its socket exclusively; dropping
/// it commits no shared/cache state, so the bounded timeout may cancel it.
async fn exchange_on_port(
    query: &[u8],
    candidates: &[IpAddr],
    generation: u64,
    timeout: Duration,
    port: u16,
) -> io::Result<EncryptedDnsExchangeSuccess> {
    let started = Instant::now();
    let deadline = tokio::time::Instant::now() + timeout;
    let mut last = io::Error::new(io::ErrorKind::NotConnected, "direct DNS candidates unavailable");
    for (index, candidate) in candidates.iter().enumerate() {
        if !is_direct_dns_generation_current(generation) {
            return Err(io::Error::new(io::ErrorKind::NotConnected, "direct DNS lease stale"));
        }
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            break;
        }
        let remaining_candidates = u32::try_from(candidates.len() - index).unwrap_or(u32::MAX).max(1);
        let slot = (remaining / remaining_candidates).max(MIN_CANDIDATE_SLOT).min(remaining);
        let slot_deadline = (tokio::time::Instant::now() + slot).min(deadline);
        match exchange_candidate(query, *candidate, generation, port, slot_deadline, slot / 2).await {
            Ok(response) => {
                return Ok(EncryptedDnsExchangeSuccess {
                    response_bytes: response,
                    endpoint_label: "underlay".to_string(),
                    latency_ms: started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64,
                });
            }
            Err(error) => last = error,
        }
    }
    Err(last)
}

/// # Cancel safety
///
/// Cancel-safe: cancellation drops the request-owned UDP/TCP socket and no
/// shared/cache state is committed between the UDP and TCP phases. A TCP
/// phase cut by `deadline` falls back to the validated truncated UDP reply.
async fn exchange_candidate(
    query: &[u8],
    candidate: IpAddr,
    generation: u64,
    port: u16,
    deadline: tokio::time::Instant,
    retransmit_after: Duration,
) -> io::Result<Vec<u8>> {
    let response =
        tokio::time::timeout_at(deadline, udp_exchange(query, candidate, generation, port, retransmit_after))
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "direct DNS candidate deadline exceeded"))??;
    if !validate_response(query, &response)? {
        return Ok(response);
    }
    // A resolver without working TCP still gave a valid truncated answer: forward it with TC set
    // rather than fail the query, so the app can use it or retry over TCP (RFC 7766 section 5).
    match tokio::time::timeout_at(deadline, tcp_exchange(query, candidate, generation, port)).await {
        Ok(Ok(full)) => Ok(full),
        Ok(Err(error)) => {
            tracing::debug!(%error, "direct DNS TCP retry failed; forwarding the truncated UDP answer");
            Ok(response)
        }
        Err(_) => Ok(response),
    }
}

/// # Cancel safety
///
/// Cancel-safe: cancellation at connect/send/recv drops the sole request-owned
/// UDP socket and commits no shared/cache state.
async fn udp_exchange(
    query: &[u8],
    candidate: IpAddr,
    generation: u64,
    port: u16,
    retransmit_after: Duration,
) -> io::Result<Vec<u8>> {
    let domain = if candidate.is_ipv4() { Domain::IPV4 } else { Domain::IPV6 };
    let socket = Socket::new(domain, Type::DGRAM, Some(Protocol::UDP))?;
    bind_direct_dns_socket(socket.as_raw_fd(), generation)?;
    let wildcard = match candidate {
        IpAddr::V4(_) => SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0),
        IpAddr::V6(_) => SocketAddr::new(IpAddr::V6(Ipv6Addr::UNSPECIFIED), 0),
    };
    socket.bind(&SockAddr::from(wildcard))?;
    socket.set_nonblocking(true)?;
    let socket = tokio::net::UdpSocket::from_std(socket.into())?;
    socket.connect(SocketAddr::new(candidate, port)).await?;
    socket.send(query).await?;
    let retransmit_at = tokio::time::Instant::now() + retransmit_after;
    let mut retransmitted = false;
    let mut response = vec![0_u8; 65_535];
    loop {
        let len = if retransmitted {
            socket.recv(&mut response).await?
        } else {
            match tokio::time::timeout_at(retransmit_at, socket.recv(&mut response)).await {
                Ok(len) => len?,
                // One retransmission (RFC 1035 section 4.2.1) covers a single lost datagram.
                Err(_) => {
                    socket.send(query).await?;
                    retransmitted = true;
                    continue;
                }
            }
        };
        // A datagram whose records do not parse may be injected ahead of the real answer: keep listening.
        if validate_response(query, &response[..len]).is_ok() && Message::from_vec(&response[..len]).is_ok() {
            response.truncate(len);
            return Ok(response);
        }
    }
}

/// # Cancel safety
///
/// Cancel-safe: cancellation at connect/write_all/read_exact may abandon a
/// partial frame, but drops the sole request-owned TCP socket and commits no
/// shared/cache state.
async fn tcp_exchange(query: &[u8], candidate: IpAddr, generation: u64, port: u16) -> io::Result<Vec<u8>> {
    let socket = if candidate.is_ipv4() { tokio::net::TcpSocket::new_v4()? } else { tokio::net::TcpSocket::new_v6()? };
    bind_direct_dns_socket(socket.as_raw_fd(), generation)?;
    let mut stream = socket.connect(SocketAddr::new(candidate, port)).await?;
    let len =
        u16::try_from(query.len()).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "DNS query too large"))?;
    // Length and message in one write, so they leave in one segment (RFC 7766 section 8).
    let mut frame = Vec::with_capacity(2 + query.len());
    frame.extend_from_slice(&len.to_be_bytes());
    frame.extend_from_slice(query);
    stream.write_all(&frame).await?;
    let mut prefix = [0_u8; 2];
    stream.read_exact(&mut prefix).await?;
    let mut response = vec![0_u8; usize::from(u16::from_be_bytes(prefix))];
    stream.read_exact(&mut response).await?;
    validate_response(query, &response)?;
    Ok(response)
}

/// Checks that `response` answers `query` (the resolver crate's shared matcher, which also
/// accepts an error reply without a question) and returns whether its TC bit is set.
fn validate_response(query: &[u8], response: &[u8]) -> io::Result<bool> {
    ensure_response_matches_query(query, response)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "DNS response does not match query"))?;
    Ok(response.get(2).is_some_and(|flags| flags & 0x02 != 0))
}

#[cfg(test)]
mod tests {
    use std::io;
    use std::net::{IpAddr, Ipv4Addr};
    use std::os::fd::RawFd;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::{Arc, PoisonError};
    use std::time::{Duration, Instant};

    use hickory_proto::op::{Message, MessageType, OpCode, Query};
    use hickory_proto::rr::{Name, RecordType};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use crate::tunnel_api::direct_dns_binding::{
        DirectDnsSocketBinder, TEST_BINDER_LOCK, register_test_direct_dns_socket_binder,
    };

    use super::{exchange_on_port, validate_response};

    struct FixedBinder {
        generation: u64,
        binds: AtomicUsize,
    }

    impl DirectDnsSocketBinder for FixedBinder {
        fn current_generation(&self) -> Option<u64> {
            Some(self.generation)
        }

        fn is_current(&self, generation: u64) -> bool {
            generation == self.generation
        }

        fn bind_socket(&self, _fd: RawFd, generation: u64) -> io::Result<()> {
            self.binds.fetch_add(1, Ordering::Relaxed);
            if self.is_current(generation) { Ok(()) } else { Err(io::ErrorKind::NotConnected.into()) }
        }
    }

    fn build_query(name: &str, id: u16) -> Message {
        let mut message = Message::new(id, MessageType::Query, OpCode::Query);
        message.add_query(Query::new(Name::from_ascii(name).expect("name"), RecordType::A));
        message
    }

    fn response_for(query: &Message) -> Message {
        let mut response = Message::response(query.metadata.id, query.metadata.op_code);
        response.add_query(query.queries[0].clone());
        response
    }

    fn response_bytes(query: &[u8], truncated: bool) -> Vec<u8> {
        let query = Message::from_vec(query).expect("query");
        let mut response = response_for(&query);
        response.metadata.truncation = truncated;
        response.to_vec().expect("response")
    }

    /// # Cancel safety
    ///
    /// Cancel-safe: cancellation drops any socket reserved by the current
    /// attempt before the test future is discarded.
    async fn bind_udp_tcp_fixture() -> (tokio::net::UdpSocket, tokio::net::TcpListener, u16) {
        for _ in 0..128 {
            let tcp = tokio::net::TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("TCP fixture");
            let port = tcp.local_addr().expect("TCP address").port();
            match tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, port)).await {
                Ok(udp) => return (udp, tcp, port),
                Err(error) if error.kind() == io::ErrorKind::AddrInUse => continue,
                Err(error) => panic!("UDP fixture: {error}"),
            }
        }
        panic!("could not reserve a shared UDP/TCP fixture port after 128 attempts");
    }

    #[test]
    fn response_validation_requires_exact_id_and_question() {
        let query = build_query("direct.example", 0x1234);
        let valid = response_for(&query);
        assert!(validate_response(&query.to_vec().expect("query"), &valid.to_vec().expect("response")).is_ok());

        let mut wrong_id = valid.clone();
        wrong_id.metadata.id ^= 1;
        assert!(validate_response(&query.to_vec().expect("query"), &wrong_id.to_vec().expect("response")).is_err());

        let mut wrong_question = response_for(&query);
        wrong_question.queries.clear();
        wrong_question.add_query(build_query("other.example", 0x1234).queries[0].clone());
        assert!(
            validate_response(&query.to_vec().expect("query"), &wrong_question.to_vec().expect("response")).is_err()
        );
    }

    #[test]
    fn truncation_is_considered_only_after_full_validation() {
        let query = build_query("direct.example", 7);
        let mut response = response_for(&query);
        response.metadata.truncation = true;
        assert!(
            validate_response(&query.to_vec().expect("query"), &response.to_vec().expect("response"))
                .expect("valid truncated response")
        );
    }

    /// Query `q.example` A IN, RD set, built byte by byte (RFC 1035 4.1).
    fn wire_query(id: u16) -> Vec<u8> {
        let mut bytes = id.to_be_bytes().to_vec();
        bytes.extend_from_slice(&[0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]);
        bytes.extend_from_slice(&[1, b'q', 7, b'e', b'x', b'a', b'm', b'p', b'l', b'e', 0, 0, 1, 0, 1]);
        bytes
    }

    /// Reply header with QR and RA set, the given RCODE and no question section.
    fn wire_reply_without_question(id: u16, rcode: u8) -> Vec<u8> {
        let mut bytes = id.to_be_bytes().to_vec();
        bytes.extend_from_slice(&[0x81, 0x80 | rcode, 0, 0, 0, 0, 0, 0, 0, 0]);
        bytes
    }

    #[test]
    fn error_reply_without_question_matches_by_id() {
        let query = wire_query(0x5151);
        for rcode in [1, 4, 5] {
            assert!(validate_response(&query, &wire_reply_without_question(0x5151, rcode)).is_ok(), "rcode {rcode}");
        }
        assert!(
            validate_response(&query, &wire_reply_without_question(0x5151, 0)).is_err(),
            "NOERROR needs a question"
        );
        assert!(validate_response(&query, &wire_reply_without_question(0x5152, 1)).is_err(), "wrong ID");
    }

    #[test]
    fn question_less_formerr_ends_the_exchange_without_waiting_for_the_deadline() {
        let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let _registration = register_test_direct_dns_socket_binder(Arc::new(FixedBinder {
            generation: 17,
            binds: AtomicUsize::new(0),
        }));
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("test runtime");
        runtime.block_on(async {
            let udp = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("UDP fixture");
            let port = udp.local_addr().expect("UDP address").port();
            let formerr = wire_reply_without_question(0x5151, 1);
            let expected = formerr.clone();
            let server = tokio::spawn(async move {
                let mut buffer = [0_u8; 512];
                let (_, peer) = udp.recv_from(&mut buffer).await.expect("UDP query");
                udp.send_to(&formerr, peer).await.expect("FORMERR reply");
            });
            let started = Instant::now();
            let success = exchange_on_port(
                &wire_query(0x5151),
                &[IpAddr::V4(Ipv4Addr::LOCALHOST)],
                17,
                Duration::from_secs(2),
                port,
            )
            .await
            .expect("FORMERR is a valid answer");
            assert_eq!(success.response_bytes, expected);
            assert!(started.elapsed() < Duration::from_millis(500));
            server.await.expect("UDP fixture task");
        });
    }

    #[test]
    fn direct_transport_obeys_candidate_deadline_validates_udp_and_uses_tcp_on_truncation() {
        let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let binder = Arc::new(FixedBinder { generation: 17, binds: AtomicUsize::new(0) });
        let _registration = register_test_direct_dns_socket_binder(binder.clone());
        let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("test runtime");
        runtime.block_on(async {
            let query = build_query("direct.example", 0x1234).to_vec().expect("query");

            let udp = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("UDP fixture");
            let port = udp.local_addr().expect("UDP address").port();
            let expected_query = query.clone();
            let udp_task = tokio::spawn(async move {
                let mut buffer = [0_u8; 512];
                let (len, peer) = udp.recv_from(&mut buffer).await.expect("UDP query");
                assert_eq!(&buffer[..len], expected_query.as_slice());
                let mut wrong = response_bytes(&buffer[..len], false);
                wrong[1] ^= 1;
                udp.send_to(&wrong, peer).await.expect("wrong UDP response");
                udp.send_to(&response_bytes(&buffer[..len], false), peer).await.expect("valid UDP response");
            });

            let success = exchange_on_port(
                &query,
                &[IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2)), IpAddr::V4(Ipv4Addr::LOCALHOST)],
                17,
                // Above twice the minimum candidate slot, so a silent first candidate leaves time.
                Duration::from_millis(2_200),
                port,
            )
            .await
            .expect("second candidate succeeds");
            assert_eq!(success.response_bytes, response_bytes(&query, false));
            udp_task.await.expect("UDP fixture task");

            let (udp, tcp, port) = bind_udp_tcp_fixture().await;
            let udp_task = tokio::spawn(async move {
                let mut buffer = [0_u8; 512];
                let (len, peer) = udp.recv_from(&mut buffer).await.expect("UDP query");
                udp.send_to(&response_bytes(&buffer[..len], true), peer).await.expect("truncated UDP response");
            });
            let tcp_task = tokio::spawn(async move {
                let (mut stream, _) = tcp.accept().await.expect("TCP accept");
                let mut prefix = [0_u8; 2];
                stream.read_exact(&mut prefix).await.expect("TCP prefix");
                let mut request = vec![0_u8; usize::from(u16::from_be_bytes(prefix))];
                stream.read_exact(&mut request).await.expect("TCP request");
                let response = response_bytes(&request, false);
                stream.write_all(&(response.len() as u16).to_be_bytes()).await.expect("TCP response prefix");
                stream.write_all(&response).await.expect("TCP response");
            });
            let success =
                exchange_on_port(&query, &[IpAddr::V4(Ipv4Addr::LOCALHOST)], 17, Duration::from_millis(300), port)
                    .await
                    .expect("TCP fallback succeeds");
            assert_eq!(success.response_bytes, response_bytes(&query, false));
            udp_task.await.expect("UDP truncation task");
            tcp_task.await.expect("TCP fixture task");

            let (udp, tcp, port) = bind_udp_tcp_fixture().await;
            let udp_task = tokio::spawn(async move {
                let mut buffer = [0_u8; 512];
                let (len, peer) = udp.recv_from(&mut buffer).await.expect("UDP query");
                udp.send_to(&response_bytes(&buffer[..len], true), peer).await.expect("truncated UDP response");
            });
            let tcp_task = tokio::spawn(async move {
                let (mut stream, _) = tcp.accept().await.expect("TCP accept");
                let mut prefix = [0_u8; 2];
                stream.read_exact(&mut prefix).await.expect("TCP prefix");
                let mut request = vec![0_u8; usize::from(u16::from_be_bytes(prefix))];
                stream.read_exact(&mut request).await.expect("TCP request");
                let wrong = build_query("wrong.example", 0x1234);
                let response = response_for(&wrong).to_vec().expect("wrong response");
                stream.write_all(&(response.len() as u16).to_be_bytes()).await.expect("TCP response prefix");
                stream.write_all(&response).await.expect("TCP response");
            });
            let truncated =
                exchange_on_port(&query, &[IpAddr::V4(Ipv4Addr::LOCALHOST)], 17, Duration::from_millis(300), port)
                    .await
                    .expect("the valid truncated UDP answer outlives a mismatched TCP reply");
            assert_eq!(truncated.response_bytes, response_bytes(&query, true));
            udp_task.await.expect("UDP truncation task");
            tcp_task.await.expect("TCP wrong-question task");

            let started = Instant::now();
            assert!(
                exchange_on_port(
                    &query,
                    &[IpAddr::V4(Ipv4Addr::new(127, 0, 0, 2))],
                    17,
                    Duration::from_millis(50),
                    port,
                )
                .await
                .is_err()
            );
            assert!(started.elapsed() < Duration::from_millis(500));
            assert!(binder.binds.load(Ordering::Relaxed) >= 6);
        });
    }

    /// Answer to `wire_query(id)` with the question echoed and no records.
    fn wire_empty_answer(id: u16, truncated: bool) -> Vec<u8> {
        let mut bytes = id.to_be_bytes().to_vec();
        bytes.extend_from_slice(&[0x81 | if truncated { 0x02 } else { 0 }, 0x80, 0, 1, 0, 0, 0, 0, 0, 0]);
        bytes.extend_from_slice(&[1, b'q', 7, b'e', b'x', b'a', b'm', b'p', b'l', b'e', 0, 0, 1, 0, 1]);
        bytes
    }

    fn with_binder<F: std::future::Future<Output = ()>>(test: impl FnOnce() -> F) {
        let _guard = TEST_BINDER_LOCK.lock().unwrap_or_else(PoisonError::into_inner);
        let _registration = register_test_direct_dns_socket_binder(Arc::new(FixedBinder {
            generation: 17,
            binds: AtomicUsize::new(0),
        }));
        tokio::runtime::Builder::new_current_thread().enable_all().build().expect("test runtime").block_on(test());
    }

    #[test]
    fn truncated_answer_is_kept_when_the_resolver_refuses_tcp() {
        with_binder(|| async {
            // UDP only: the kernel refuses the TCP connect on this port.
            let udp = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("UDP fixture");
            let port = udp.local_addr().expect("UDP address").port();
            let server = tokio::spawn(async move {
                let mut buffer = [0_u8; 512];
                let (_, peer) = udp.recv_from(&mut buffer).await.expect("UDP query");
                udp.send_to(&wire_empty_answer(0x6161, true), peer).await.expect("TC reply");
            });
            let success = exchange_on_port(
                &wire_query(0x6161),
                &[IpAddr::V4(Ipv4Addr::LOCALHOST)],
                17,
                Duration::from_secs(1),
                port,
            )
            .await
            .expect("truncated answer");
            assert_eq!(success.response_bytes, wire_empty_answer(0x6161, true));
            server.await.expect("UDP fixture task");
        });
    }

    #[test]
    fn a_lost_query_datagram_is_retransmitted_once() {
        with_binder(|| async {
            let udp = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("UDP fixture");
            let port = udp.local_addr().expect("UDP address").port();
            let server = tokio::spawn(async move {
                let mut buffer = [0_u8; 512];
                let (first, _) = udp.recv_from(&mut buffer).await.expect("first datagram, dropped");
                let (second, peer) = udp.recv_from(&mut buffer).await.expect("retransmitted datagram");
                assert_eq!(first, second);
                udp.send_to(&wire_empty_answer(0x7171, false), peer).await.expect("reply");
            });
            let success = exchange_on_port(
                &wire_query(0x7171),
                &[IpAddr::V4(Ipv4Addr::LOCALHOST)],
                17,
                Duration::from_secs(2),
                port,
            )
            .await
            .expect("answer to the retransmission");
            assert_eq!(success.response_bytes, wire_empty_answer(0x7171, false));
            server.await.expect("UDP fixture task");
        });
    }

    #[test]
    fn many_candidates_still_leave_a_slow_first_resolver_a_usable_slot() {
        with_binder(|| async {
            let udp = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("UDP fixture");
            let port = udp.local_addr().expect("UDP address").port();
            let server = tokio::spawn(async move {
                let mut buffer = [0_u8; 512];
                let (_, peer) = udp.recv_from(&mut buffer).await.expect("UDP query");
                // 400 ms exceeds the 250 ms an even 4 s split over 16 candidates would leave.
                tokio::time::sleep(Duration::from_millis(400)).await;
                udp.send_to(&wire_empty_answer(0x8181, false), peer).await.expect("slow reply");
            });
            let candidates = [IpAddr::V4(Ipv4Addr::LOCALHOST); 16];
            let success = exchange_on_port(&wire_query(0x8181), &candidates, 17, Duration::from_secs(4), port)
                .await
                .expect("slow first candidate answers inside its slot");
            assert_eq!(success.response_bytes, wire_empty_answer(0x8181, false));
            server.await.expect("UDP fixture task");
        });
    }
}
