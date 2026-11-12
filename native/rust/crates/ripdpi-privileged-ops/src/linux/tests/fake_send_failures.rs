use crate::linux::raw_packet;
use std::io;

#[test]
fn partial_fake_splice_restores_real_pages_and_reports_progress() {
    use std::io::Read;
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::time::Duration;
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
    socket2::SockRef::from(&listener).set_recv_buffer_size(1024).expect("receive buffer");
    let client = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
    let (mut server, _) = listener.accept().expect("accept");
    socket2::SockRef::from(&client).set_send_buffer_size(1024).expect("send buffer");
    client.set_nonblocking(true).expect("nonblocking");
    server.set_read_timeout(Some(Duration::from_secs(2))).expect("timeout");
    let real = vec![b'R'; 8192];
    let fake = vec![b'F'; real.len()];
    let error = raw_packet::send_fake_tcp(
        &client,
        &real,
        &fake,
        8,
        false,
        64,
        crate::FakeTcpOptions::default(),
        (false, Duration::ZERO),
    )
    .expect_err("partial splice");
    let progress =
        error.get_ref().and_then(|source| source.downcast_ref::<crate::TcpSendProgressError>()).expect("progress");
    assert!(progress.bytes_committed > 0);
    assert!(progress.bytes_committed < real.len());
    client.shutdown(Shutdown::Write).expect("shutdown");
    let mut actual = Vec::new();
    server.read_to_end(&mut actual).expect("peer payload");
    assert_eq!(actual.len(), progress.bytes_committed);
    assert_eq!(actual, real[..actual.len()]);
    assert_eq!(crate::linux::socket_options::get_stream_ttl(&client).expect("restored TTL"), 64);
}

#[test]
fn failed_fake_wait_restores_all_queued_real_pages() {
    use std::io::Read;
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::time::Duration;
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
    socket2::SockRef::from(&listener).set_recv_buffer_size(1024).expect("receive buffer");
    let client = TcpStream::connect(listener.local_addr().expect("address")).expect("connect");
    let (mut server, _) = listener.accept().expect("accept");
    socket2::SockRef::from(&client).set_send_buffer_size(32768).expect("send buffer");
    server.set_read_timeout(Some(Duration::from_secs(2))).expect("timeout");
    let real = vec![b'R'; 8192];
    let fake = vec![b'F'; real.len()];
    let error = raw_packet::send_fake_tcp(
        &client,
        &real,
        &fake,
        8,
        false,
        64,
        crate::FakeTcpOptions::default(),
        (false, Duration::from_millis(1)),
    )
    .expect_err("send queue cannot drain");
    assert_eq!(error.kind(), io::ErrorKind::TimedOut);
    let progress =
        error.get_ref().and_then(|source| source.downcast_ref::<crate::TcpSendProgressError>()).expect("progress");
    assert_eq!(progress.bytes_committed, real.len());
    client.shutdown(Shutdown::Write).expect("shutdown");
    let mut actual = Vec::new();
    server.read_to_end(&mut actual).expect("peer payload");
    assert_eq!(actual, real);
    assert_eq!(crate::linux::socket_options::get_stream_ttl(&client).expect("restored TTL"), 64);
}
