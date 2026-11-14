use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener, TcpStream};

use android_support::{EventRingBuffers, EventRingLayer};
use tracing_subscriber::prelude::*;

use super::*;

fn connected_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind");
    let client = TcpStream::connect(listener.local_addr().expect("listener address")).expect("connect");
    let (server, _) = listener.accept().expect("accept");
    (client, server)
}

#[test]
fn window_option_readback_failure_preserves_setter_success_without_an_effective_value() {
    let buffers = EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    let result = tracing::subscriber::with_default(subscriber, || {
        observe_window_option("window_clamp", 4096, Ok(()), || {
            Err(io::Error::new(io::ErrorKind::PermissionDenied, "secret payload at 192.0.2.1 fd=42"))
        })
    });
    assert!(result.is_ok(), "observing an option must not change its result");
    let events = buffers.drain_proxy();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].outcome.as_deref(), Some("readback_failed"));
    assert_eq!(events[0].io_error_kind.as_deref(), Some("permission_denied"));
    for sensitive in ["effective=", "secret", "192.0.2.1", "fd=42"] {
        assert!(!events[0].message.contains(sensitive));
    }
}

#[test]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn unsupported_window_option_records_fallback_and_preserves_payload() {
    let (mut client, mut server) = connected_pair();
    server.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
    let buffers = EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    tracing::subscriber::with_default(subscriber, || {
        assert_eq!(
            set_tcp_window_clamp(&client, 4096).expect_err("unsupported clamp").kind(),
            io::ErrorKind::Unsupported
        );
        assert_eq!(set_rcvbuf(&client, 16384).expect_err("unsupported buffer").kind(), io::ErrorKind::Unsupported);
        client.write_all(b"real payload").expect("write");
    });
    let mut received = [0; 12];
    server.read_exact(&mut received).expect("read");
    assert_eq!(&received, b"real payload");
    let events = buffers.drain_proxy();
    assert_eq!(events.len(), 2);
    for event in &events {
        assert_eq!(event.kind.as_deref(), Some("tcp_socket_option"));
        assert_eq!(event.outcome.as_deref(), Some("unsupported"));
        assert!(!event.message.contains("effective="));
        assert!(!event.message.contains("127.0.0.1"));
        assert!(!event.message.contains("fd="));
        assert!(!event.message.contains("real payload"));
    }
    assert!(events[0].message.contains("window_clamp"));
    assert!(events[0].message.contains("requested=4096"));
    assert!(events[1].message.contains("receive_buffer"));
    assert!(events[1].message.contains("requested=16384"));
}

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn applied_window_options_record_kernel_readback() {
    let (client, _server) = connected_pair();
    let buffers = EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry()
        .with(EventRingLayer::new(buffers.clone()))
        .with(tracing_subscriber::filter::LevelFilter::INFO);
    tracing::subscriber::with_default(subscriber, || {
        set_rcvbuf(&client, 4096).expect("receive buffer");
        set_tcp_window_clamp(&client, 2).expect("window clamp");
    });
    let actual_buffer = ripdpi_privileged_ops::get_rcvbuf(&client).expect("buffer readback");
    let actual_clamp = ripdpi_privileged_ops::get_tcp_window_clamp(&client).expect("clamp readback");
    assert!(actual_buffer >= 4096);
    assert!(actual_clamp > 2, "kernel floor must be visible");
    let events = buffers.drain_proxy();
    assert_eq!(events.len(), 2);
    assert!(events.iter().all(|event| event.outcome.as_deref() == Some("applied")));
    assert!(events[0].message.contains(&format!("effective={actual_buffer}")));
    assert!(events[1].message.contains(&format!("effective={actual_clamp}")));
}

#[test]
#[cfg(any(target_os = "linux", target_os = "android"))]
fn invalid_established_window_option_records_failure_and_preserves_payload() {
    let (mut client, mut server) = connected_pair();
    server.set_read_timeout(Some(Duration::from_secs(2))).expect("read timeout");
    let buffers = EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    tracing::subscriber::with_default(subscriber, || {
        assert!(set_tcp_window_clamp(&client, 0).is_err());
        client.write_all(b"real payload").expect("write after optional failure");
    });
    let mut received = [0; 12];
    server.read_exact(&mut received).expect("read");
    assert_eq!(&received, b"real payload");
    let events = buffers.drain_proxy();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].outcome.as_deref(), Some("failed"));
    assert!(!events[0].message.contains("effective="));
}

#[test]
fn unavailable_protocol_option_records_unsupported_without_changing_the_error() {
    let buffers = EventRingBuffers::default();
    let subscriber = tracing_subscriber::registry().with(EventRingLayer::new(buffers.clone()));
    let result = tracing::subscriber::with_default(subscriber, || {
        observe_window_option("window_clamp", 4096, Err(io::Error::from_raw_os_error(libc::ENOPROTOOPT)), || {
            panic!("readback must not run after an option failure")
        })
    });
    assert_eq!(result.unwrap_err().raw_os_error(), Some(libc::ENOPROTOOPT));
    let events = buffers.drain_proxy();
    assert_eq!(events.len(), 1);
    assert_eq!(events[0].outcome.as_deref(), Some("unsupported"));
    assert_eq!(events[0].io_error_kind.as_deref(), Some("unsupported"));
}
