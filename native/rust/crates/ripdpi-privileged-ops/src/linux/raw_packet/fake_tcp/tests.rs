use std::io::Read;
use std::net::TcpListener;
use std::time::Duration;

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

fn clear_child_capabilities() {
    #[repr(C)]
    struct CapHeader {
        version: u32,
        pid: i32,
    }
    #[derive(Clone, Copy)]
    #[repr(C)]
    struct CapData {
        effective: u32,
        permitted: u32,
        inheritable: u32,
    }
    const _: () = {
        assert!(std::mem::size_of::<CapHeader>() == 8);
        assert!(std::mem::size_of::<CapData>() == 12);
    };
    let mut header = CapHeader { version: 0x2008_0522, pid: 0 };
    let data = [CapData { effective: 0, permitted: 0, inheritable: 0 }; 2];
    // SAFETY: capability.h V3 requires one writable 8-byte header and two
    // readable 12-byte capability words. Both objects live through the call.
    // pid=0 changes only this child test thread, and all masks remove rights.
    let result = unsafe { libc::syscall(libc::SYS_capset, &mut header as *mut CapHeader, data.as_ptr()) };
    assert_eq!(result, 0, "clear child capabilities: {}", io::Error::last_os_error());
}

fn assert_raw_fake_modifier_does_not_fall_back(test_name: &str, options: FakeTcpOptions<'_>, fake_prefix: &[u8]) {
    const CHILD_ENV: &str = "RIPDPI_RAW_FAKE_MODIFIER_TEST_CHILD";
    if std::env::var(CHILD_ENV).ok().as_deref() != Some(test_name) {
        let status = std::process::Command::new(std::env::current_exe().expect("test executable"))
            .args(["--exact", test_name, "--nocapture", "--test-threads=1"])
            .env(CHILD_ENV, test_name)
            .status()
            .expect("run unprivileged child fixture");
        assert!(status.success(), "unprivileged child fixture failed");
        return;
    }
    clear_child_capabilities();
    let (stream, mut peer) = connected_pair();
    let error = send_fake_tcp(
        &stream,
        b"REAL",
        fake_prefix,
        1,
        false,
        37,
        options,
        (false, std::time::Duration::from_millis(1)),
    )
    .expect_err("a requested raw modifier requires raw permissions");
    assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
    stream.shutdown(std::net::Shutdown::Write).expect("finish stream");
    let mut actual = Vec::new();
    peer.read_to_end(&mut actual).expect("read peer bytes");
    assert!(actual.is_empty(), "a failed raw modifier must not send a plain fallback");
}

#[test]
fn secondary_raw_fake_modifier_requires_raw_transport() {
    assert_raw_fake_modifier_does_not_fall_back(
        "linux::raw_packet::fake_tcp::tests::secondary_raw_fake_modifier_requires_raw_transport",
        FakeTcpOptions { secondary_fake_prefix: Some(b"SECONDARY"), ..Default::default() },
        b"REAL",
    );
}

#[test]
fn timestamp_raw_fake_modifier_requires_raw_transport() {
    assert_raw_fake_modifier_does_not_fall_back(
        "linux::raw_packet::fake_tcp::tests::timestamp_raw_fake_modifier_requires_raw_transport",
        FakeTcpOptions { timestamp_delta_ticks: Some(1), ..Default::default() },
        b"REAL",
    );
}

#[test]
fn raw_original_option_preserves_default_stream_transport() {
    let (stream, mut peer) = connected_pair();
    send_fake_tcp(
        &stream,
        b"REAL",
        b"REAL",
        1,
        false,
        37,
        FakeTcpOptions { force_raw_original: true, ..Default::default() },
        (false, std::time::Duration::from_millis(1)),
    )
    .expect("default stream transport needs no raw permissions");
    let mut actual = [0; 4];
    peer.read_exact(&mut actual).expect("read original payload");
    assert_eq!(&actual, b"REAL");
    assert_eq!(stream.ttl().expect("read restored TTL"), 37);
}

#[test]
fn stream_fake_failure_restores_queued_real_bytes_before_ttl() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind peer");
    let client = TcpStream::connect(listener.local_addr().expect("peer address")).expect("connect peer");
    let (mut peer, _) = listener.accept().expect("accept peer");
    peer.set_read_timeout(Some(Duration::from_secs(2))).expect("bound peer read");
    set_stream_ttl(&client, 64).expect("set original TTL");
    FAIL_AFTER_STREAM_SPLICE.with(|fault| {
        *fault.borrow_mut() = Some(io::Error::other("injected failure after fake splice"));
    });
    let error = send_fake_tcp(
        &client,
        b"real-payload",
        b"fake-payload",
        8,
        false,
        64,
        FakeTcpOptions::default(),
        (false, Duration::ZERO),
    )
    .expect_err("fault follows the actual splice");
    assert!(error.to_string().contains("injected failure after fake splice"));
    let progress =
        error.get_ref().and_then(|error| error.downcast_ref::<TcpSendProgressError>()).expect("known splice progress");
    assert_eq!(progress.bytes_committed, b"real-payload".len());
    assert_eq!(get_stream_ttl(&client).expect("read restored TTL"), 64);
    let mut actual = [0; 12];
    peer.read_exact(&mut actual).expect("read queued pages after the error");
    assert_eq!(&actual, b"real-payload");
}

#[test]
fn enlarged_fake_requires_packet_owned_transport() {
    assert_raw_fake_modifier_does_not_fall_back(
        "linux::raw_packet::fake_tcp::tests::enlarged_fake_requires_packet_owned_transport",
        FakeTcpOptions::default(),
        b"PADDED-FAKE",
    );
}
