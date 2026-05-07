use std::io::{self, Read};
use std::net::TcpStream;
use std::time::{Duration, Instant};

use crate::runtime::state::RuntimeState;
use ripdpi_proxy_runtime_adapter::model::session::{classify_first_outbound_payload, first_outbound_payload_policy};
use ripdpi_proxy_runtime_adapter::protocol_payload::OutboundTlsClientHelloAssembler;

const FIRST_OUTBOUND_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub(super) struct FirstPayload {
    pub(super) original_request: Vec<u8>,
    pub(super) host: Option<String>,
    pub(super) is_tls: bool,
}

pub(super) fn prepare_first_payload(
    client: &mut TcpStream,
    state: &RuntimeState,
    seed_request: Option<Vec<u8>>,
) -> io::Result<Option<FirstPayload>> {
    let policy = first_outbound_payload_policy(&state.config);
    let original_request =
        seed_request.map_or_else(|| read_first_client_payload(client, policy.buffer_size), |seed| Ok(Some(seed)))?;
    Ok(original_request.map(|original_request| {
        let info = classify_first_outbound_payload(&policy, &original_request);
        FirstPayload { original_request, host: info.host, is_tls: info.is_tls }
    }))
}

fn read_first_client_payload(client: &mut TcpStream, buffer_size: usize) -> io::Result<Option<Vec<u8>>> {
    let original_timeout = client.read_timeout()?;
    let mut buffer = vec![0u8; buffer_size];
    let mut assembler = OutboundTlsClientHelloAssembler::new();
    let result = loop {
        let now = Instant::now();
        let timeout = assembler.timeout(now).unwrap_or(FIRST_OUTBOUND_IDLE_TIMEOUT);
        let _ = client.set_read_timeout(Some(timeout));
        match client.read(&mut buffer) {
            Ok(0) => break Ok(assembler.finish()),
            Ok(n) => {
                if let Some(payload) = assembler.push(&buffer[..n], now) {
                    break Ok(Some(payload));
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if let Some(payload) = assembler.flush_on_timeout(Instant::now()) {
                    break Ok(Some(payload));
                }
                continue;
            }
            Err(err) => break Err(err),
        }
    };
    let _ = client.set_read_timeout(original_timeout);
    result
}

#[cfg(test)]
mod tests {
    use super::read_first_client_payload;
    use std::io::Write;
    use std::net::{Ipv4Addr, TcpListener, TcpStream};
    use std::thread;
    use std::time::Duration;

    mod rust_packet_seeds {
        include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../ripdpi-packets/tests/rust_packet_seeds.rs"));
    }

    #[test]
    fn read_first_client_payload_reassembles_multi_write_client_hello() {
        let (mut client, mut app) = connected_pair();
        let payload = build_multi_record_tls_client_hello();
        let expected = payload.clone();

        let writer = thread::spawn(move || {
            for chunk in expected.chunks(128) {
                app.write_all(chunk).expect("write client hello fragment");
                thread::sleep(Duration::from_millis(1));
            }
        });

        let assembled = read_first_client_payload(&mut client, 64).expect("read payload");
        writer.join().expect("writer thread");

        assert_eq!(assembled, Some(payload));
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
