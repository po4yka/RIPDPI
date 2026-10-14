use std::io::{self, Read};
use std::net::TcpStream;
use std::time::{Duration, Instant};

use crate::runtime::state::RuntimeState;

const FIRST_OUTBOUND_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub(super) struct FirstPayload {
    pub(super) original_request: Vec<u8>,
    pub(super) host: Option<String>,
    pub(super) is_tls: bool,
}

/// Outcome of waiting for the first client payload of a relayed TCP flow.
#[derive(Debug, PartialEq, Eq)]
pub(super) enum FirstOutboundRead<T> {
    /// The client sent its first payload (or a seed request was supplied).
    Payload(T),
    /// The client closed the connection before it sent any byte.
    ClientClosed,
    /// The upstream sent bytes before the client sent any byte
    /// (server-speaks-first protocols such as SMTP, IMAP, FTP, or SSH).
    /// The upstream bytes stay unread in the upstream socket for the relay.
    ServerFirst,
}

impl<T> FirstOutboundRead<T> {
    fn map<U>(self, f: impl FnOnce(T) -> U) -> FirstOutboundRead<U> {
        match self {
            Self::Payload(payload) => FirstOutboundRead::Payload(f(payload)),
            Self::ClientClosed => FirstOutboundRead::ClientClosed,
            Self::ServerFirst => FirstOutboundRead::ServerFirst,
        }
    }
}

pub(super) fn prepare_first_payload(
    client: &mut TcpStream,
    upstream: &TcpStream,
    state: &RuntimeState,
    seed_request: Option<Vec<u8>>,
) -> io::Result<FirstOutboundRead<FirstPayload>> {
    let read = match seed_request {
        Some(seed) => FirstOutboundRead::Payload(seed),
        None => read_first_client_payload(client, upstream, state.first_outbound_payload_buffer_size())?,
    };
    Ok(read.map(|original_request| {
        let info = state.classify_first_outbound_payload(&original_request);
        FirstPayload { original_request, host: info.host, is_tls: info.is_tls }
    }))
}

fn read_first_client_payload(
    client: &mut TcpStream,
    upstream: &TcpStream,
    buffer_size: usize,
) -> io::Result<FirstOutboundRead<Vec<u8>>> {
    let original_timeout = client.read_timeout()?;
    let mut buffer = vec![0u8; buffer_size];
    let mut assembler = RuntimeState::first_outbound_tls_client_hello_assembler();
    let mut watch_upstream = true;
    let result = loop {
        let now = Instant::now();
        let timeout = assembler.timeout(now).unwrap_or(FIRST_OUTBOUND_IDLE_TIMEOUT);
        // Only a flow whose client has sent nothing yet can be server-first.
        // After the client has sent bytes, the TLS ClientHello reassembly
        // path below owns the wait, so no client bytes are dropped.
        if watch_upstream && !assembler.is_buffering() {
            match wait_for_first_readable(client, upstream, timeout) {
                Ok(FirstReadable::Client) => {}
                Ok(FirstReadable::Upstream) => {
                    if upstream_has_payload(upstream) {
                        break Ok(FirstOutboundRead::ServerFirst);
                    }
                    // Upstream EOF or error before any byte: keep the
                    // client-first path, which surfaces the failure on the
                    // first write and lets route retry handle it.
                    watch_upstream = false;
                    continue;
                }
                Ok(FirstReadable::Neither) => continue,
                Err(err) => break Err(err),
            }
        }
        let _ = client.set_read_timeout(Some(timeout));
        match client.read(&mut buffer) {
            Ok(0) => break Ok(assembler.finish().map_or(FirstOutboundRead::ClientClosed, FirstOutboundRead::Payload)),
            Ok(n) => {
                if let Some(payload) = assembler.push(&buffer[..n], now) {
                    break Ok(FirstOutboundRead::Payload(payload));
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if let Some(payload) = assembler.flush_on_timeout(Instant::now()) {
                    break Ok(FirstOutboundRead::Payload(payload));
                }
                continue;
            }
            Err(err) => break Err(err),
        }
    };
    let _ = client.set_read_timeout(original_timeout);
    result
}

/// Returns true when the upstream has at least one unread byte. The peek
/// leaves the byte in the socket so the relay forwards it to the client.
/// Callers invoke this only after readiness was reported, so the peek does
/// not block.
fn upstream_has_payload(upstream: &TcpStream) -> bool {
    matches!(upstream.peek(&mut [0u8; 1]), Ok(read) if read > 0)
}

enum FirstReadable {
    Client,
    Upstream,
    Neither,
}

/// Waits until the client or the upstream becomes readable, or `timeout`
/// elapses. The client has priority when both are readable, so client-first
/// flows keep their first-payload desync. The call does not change the
/// blocking mode or the timeouts of either socket.
#[cfg(unix)]
fn wait_for_first_readable(client: &TcpStream, upstream: &TcpStream, timeout: Duration) -> io::Result<FirstReadable> {
    use std::os::fd::AsRawFd;

    const READY_EVENTS: libc::c_short = libc::POLLIN | libc::POLLERR | libc::POLLHUP | libc::POLLNVAL;
    let mut pollfds = [
        libc::pollfd { fd: client.as_raw_fd(), events: libc::POLLIN, revents: 0 },
        libc::pollfd { fd: upstream.as_raw_fd(), events: libc::POLLIN, revents: 0 },
    ];
    // Truncate to whole milliseconds, but never below 1 ms, so that a
    // sub-millisecond timeout does not spin.
    let timeout_ms = libc::c_int::try_from(timeout.as_millis().max(1)).unwrap_or(libc::c_int::MAX);
    // SAFETY: `pollfds` is a live, mutable, two-element array for the whole
    // call, and `libc::poll` only reads/writes within the pointer plus the
    // exact length passed. Both fds stay open because `client` and
    // `upstream` are borrowed for the duration of the call.
    let ready = unsafe { libc::poll(pollfds.as_mut_ptr(), pollfds.len() as libc::nfds_t, timeout_ms) };
    if ready < 0 {
        let err = io::Error::last_os_error();
        return if err.kind() == io::ErrorKind::Interrupted { Ok(FirstReadable::Neither) } else { Err(err) };
    }
    if pollfds[0].revents & READY_EVENTS != 0 {
        Ok(FirstReadable::Client)
    } else if pollfds[1].revents & READY_EVENTS != 0 {
        Ok(FirstReadable::Upstream)
    } else {
        Ok(FirstReadable::Neither)
    }
}

/// Without `poll(2)`, keep the client-first blocking read.
#[cfg(not(unix))]
fn wait_for_first_readable(
    _client: &TcpStream,
    _upstream: &TcpStream,
    _timeout: Duration,
) -> io::Result<FirstReadable> {
    Ok(FirstReadable::Client)
}

#[cfg(test)]
mod tests {
    use super::{FirstOutboundRead, read_first_client_payload};
    use std::io::{Read, Write};
    use std::net::{Ipv4Addr, TcpListener, TcpStream};
    use std::thread;
    use std::time::{Duration, Instant};

    mod rust_packet_seeds {
        include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../ripdpi-packets/tests/rust_packet_seeds.rs"));
    }

    #[test]
    fn read_first_client_payload_reassembles_multi_write_client_hello() {
        let (mut client, mut app) = connected_pair();
        let (upstream, _server) = connected_pair();
        let payload = build_multi_record_tls_client_hello();
        let expected = payload.clone();

        let writer = thread::spawn(move || {
            for chunk in expected.chunks(128) {
                app.write_all(chunk).expect("write client hello fragment");
                thread::sleep(Duration::from_millis(1));
            }
        });

        let assembled = read_first_client_payload(&mut client, &upstream, 64).expect("read payload");
        writer.join().expect("writer thread");

        assert_eq!(assembled, FirstOutboundRead::Payload(payload));
    }

    #[test]
    fn read_first_client_payload_reports_server_first_without_consuming_upstream_bytes() {
        let (mut client, _app) = connected_pair();
        let (mut upstream, mut server) = connected_pair();
        client.set_read_timeout(Some(Duration::from_secs(7))).expect("set client timeout");
        server.write_all(b"220 ready\r\n").expect("write greeting");

        let started = Instant::now();
        let read = read_first_client_payload(&mut client, &upstream, 64).expect("read first payload");

        assert_eq!(read, FirstOutboundRead::ServerFirst);
        assert!(started.elapsed() < Duration::from_secs(2), "server-first detection took {:?}", started.elapsed());
        assert_eq!(client.read_timeout().expect("client timeout"), Some(Duration::from_secs(7)));
        let mut greeting = [0u8; 11];
        upstream.read_exact(&mut greeting).expect("greeting stays readable for the relay");
        assert_eq!(&greeting, b"220 ready\r\n");
    }

    #[test]
    fn read_first_client_payload_prefers_client_bytes_when_both_sides_are_readable() {
        let (mut client, mut app) = connected_pair();
        let (upstream, mut server) = connected_pair();
        app.write_all(b"GET / HTTP/1.1\r\nHost: example.org\r\n\r\n").expect("write client request");
        server.write_all(b"220 ready\r\n").expect("write greeting");
        thread::sleep(Duration::from_millis(50));

        let read = read_first_client_payload(&mut client, &upstream, 256).expect("read first payload");

        assert_eq!(read, FirstOutboundRead::Payload(b"GET / HTTP/1.1\r\nHost: example.org\r\n\r\n".to_vec()));
    }

    #[test]
    fn read_first_client_payload_finishes_partial_client_hello_even_when_upstream_speaks() {
        let (mut client, mut app) = connected_pair();
        let (mut upstream, mut server) = connected_pair();
        let payload = build_multi_record_tls_client_hello();
        let expected = payload.clone();

        // Both pauses together stay well inside the 75 ms ClientHello
        // assembly window, so the assembler is still buffering when the
        // upstream speaks.
        let writer = thread::spawn(move || {
            app.write_all(&expected[..3]).expect("write partial tls record header");
            thread::sleep(Duration::from_millis(10));
            server.write_all(b"220 ready\r\n").expect("write upstream bytes");
            thread::sleep(Duration::from_millis(10));
            app.write_all(&expected[3..]).expect("write rest of client hello");
            server
        });

        let read = read_first_client_payload(&mut client, &upstream, 64).expect("read first payload");
        let _server = writer.join().expect("writer thread");

        assert_eq!(read, FirstOutboundRead::Payload(payload));
        let mut upstream_bytes = [0u8; 11];
        upstream.read_exact(&mut upstream_bytes).expect("upstream bytes stay readable for the relay");
        assert_eq!(&upstream_bytes, b"220 ready\r\n");
    }

    #[test]
    fn read_first_client_payload_keeps_waiting_for_client_after_upstream_eof() {
        let (mut client, mut app) = connected_pair();
        let (upstream, server) = connected_pair();
        drop(server);

        let writer = thread::spawn(move || {
            thread::sleep(Duration::from_millis(100));
            app.write_all(b"EHLO client\r\n").expect("write client command");
        });

        let read = read_first_client_payload(&mut client, &upstream, 64).expect("read first payload");
        writer.join().expect("writer thread");

        assert_eq!(read, FirstOutboundRead::Payload(b"EHLO client\r\n".to_vec()));
    }

    #[test]
    fn read_first_client_payload_reports_client_close_before_any_byte() {
        let (mut client, app) = connected_pair();
        let (upstream, _server) = connected_pair();
        drop(app);

        let read = read_first_client_payload(&mut client, &upstream, 64).expect("read first payload");

        assert_eq!(read, FirstOutboundRead::ClientClosed);
    }

    fn build_multi_record_tls_client_hello() -> Vec<u8> {
        let client_hello = rust_packet_seeds::tls_client_hello();
        let record_header = &client_hello[..3];
        let handshake = &client_hello[5..];
        let split = 96usize.min(handshake.len().saturating_sub(1));
        let mut payload = Vec::with_capacity(client_hello.len() + 5);
        payload.extend_from_slice(record_header);
        payload.extend_from_slice(&(split as u16).to_be_bytes());
        payload.extend_from_slice(&handshake[..split]);
        payload.extend_from_slice(record_header);
        payload.extend_from_slice(&((handshake.len() - split) as u16).to_be_bytes());
        payload.extend_from_slice(&handshake[split..]);
        payload
    }

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }
}
