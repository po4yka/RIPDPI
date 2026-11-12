use std::io::Read;
use std::net::TcpListener;

use super::*;

fn connected_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback");
    let stream = TcpStream::connect(listener.local_addr().expect("listener address")).expect("connect");
    let (peer, _) = listener.accept().expect("accept");
    (stream, peer)
}

#[test]
fn failed_fake_send_restores_retained_pages_before_transport_state() {
    let (stream, _peer) = connected_pair();
    stream.set_ttl(1).expect("set temporary TTL");
    let mut region = MmapRegion::new(4).expect("map fake pages");
    region.write(b"FAKE");
    let (reader, writer) = nix::unistd::pipe().expect("create pipe");
    assert_eq!(region.vmsplice_to(writer.as_fd(), 4).expect("queue fake pages"), 4);
    drop(writer);

    let error = finish_stream_fake_send(
        &stream,
        &mut region,
        b"REAL",
        false,
        37,
        2,
        Err(io::Error::from_raw_os_error(libc::EPIPE)),
    )
    .expect_err("retain the send error");
    drop(region);

    let mut actual = [0; 4];
    std::fs::File::from(reader).read_exact(&mut actual).expect("read retained pages");
    assert_eq!(&actual, b"REAL");
    assert_eq!(stream.ttl().expect("read restored TTL"), 37);
    assert_eq!(error.kind(), io::ErrorKind::BrokenPipe);
    let progress = error
        .get_ref()
        .and_then(|source| source.downcast_ref::<crate::TcpSendProgressError>())
        .expect("known send progress");
    assert_eq!(progress.bytes_committed, 2);
    assert_eq!(progress.source.raw_os_error(), Some(libc::EPIPE));
    assert_eq!(error.raw_os_error(), None);
}

#[test]
fn failed_fake_send_without_progress_preserves_errno() {
    let (stream, _peer) = connected_pair();
    stream.set_ttl(1).expect("set temporary TTL");
    let mut region = MmapRegion::new(4).expect("map fake pages");
    region.write(b"FAKE");

    let error = finish_stream_fake_send(
        &stream,
        &mut region,
        b"REAL",
        false,
        37,
        0,
        Err(io::Error::from_raw_os_error(libc::EPERM)),
    )
    .expect_err("retain the send error");

    assert_eq!(error.raw_os_error(), Some(libc::EPERM));
    assert_eq!(region.to_vec(), b"REAL");
    assert_eq!(stream.ttl().expect("read restored TTL"), 37);
}
