use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::os::fd::AsRawFd;
use std::time::{Duration, Instant};

use hickory_proto::op::{Message, MessageType};
use ripdpi_dns_resolver::EncryptedDnsExchangeSuccess;
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::tunnel_api::direct_dns_binding::{bind_direct_dns_socket, is_direct_dns_generation_current};

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
        match tokio::time::timeout(
            remaining / remaining_candidates,
            exchange_candidate(query, *candidate, generation, port),
        )
        .await
        {
            Ok(Ok(response)) => {
                return Ok(EncryptedDnsExchangeSuccess {
                    response_bytes: response,
                    endpoint_label: "underlay".to_string(),
                    latency_ms: started.elapsed().as_millis().min(u128::from(u64::MAX)) as u64,
                });
            }
            Ok(Err(error)) => last = error,
            Err(_) => last = io::Error::new(io::ErrorKind::TimedOut, "direct DNS candidate deadline exceeded"),
        }
    }
    Err(last)
}

/// # Cancel safety
///
/// Cancel-safe: cancellation drops the request-owned UDP/TCP socket and no
/// shared/cache state is committed between the UDP and TCP phases.
async fn exchange_candidate(query: &[u8], candidate: IpAddr, generation: u64, port: u16) -> io::Result<Vec<u8>> {
    let response = udp_exchange(query, candidate, generation, port).await?;
    let message = validate_response(query, &response)?;
    if message.metadata.truncation { tcp_exchange(query, candidate, generation, port).await } else { Ok(response) }
}

/// # Cancel safety
///
/// Cancel-safe: cancellation at connect/send/recv drops the sole request-owned
/// UDP socket and commits no shared/cache state.
async fn udp_exchange(query: &[u8], candidate: IpAddr, generation: u64, port: u16) -> io::Result<Vec<u8>> {
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
    let mut response = vec![0_u8; 65_535];
    loop {
        let len = socket.recv(&mut response).await?;
        if validate_response(query, &response[..len]).is_ok() {
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
    stream.write_all(&len.to_be_bytes()).await?;
    stream.write_all(query).await?;
    let mut prefix = [0_u8; 2];
    stream.read_exact(&mut prefix).await?;
    let mut response = vec![0_u8; usize::from(u16::from_be_bytes(prefix))];
    stream.read_exact(&mut response).await?;
    validate_response(query, &response)?;
    Ok(response)
}

fn validate_response(query: &[u8], response: &[u8]) -> io::Result<Message> {
    let query =
        Message::from_vec(query).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid DNS query"))?;
    let response =
        Message::from_vec(response).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid DNS response"))?;
    if response.metadata.message_type != MessageType::Response
        || response.metadata.id != query.metadata.id
        || response.metadata.op_code != query.metadata.op_code
        || response.queries.len() != 1
        || response.queries != query.queries
    {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "DNS response does not match query"));
    }
    Ok(response)
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
        message.add_query(Query::query(Name::from_ascii(name).expect("name"), RecordType::A));
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
                .metadata
                .truncation
        );
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
                Duration::from_millis(300),
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
            assert!(
                exchange_on_port(&query, &[IpAddr::V4(Ipv4Addr::LOCALHOST)], 17, Duration::from_millis(300), port,)
                    .await
                    .is_err()
            );
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
}
