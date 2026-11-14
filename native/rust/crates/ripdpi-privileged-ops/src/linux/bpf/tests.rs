use std::io;
use std::os::fd::AsRawFd;
use std::os::unix::net::UnixDatagram;
use std::time::Duration;

use super::{drop_sack_program, timestamp_program};

fn filtered_pair(code: &mut [libc::sock_filter]) -> (UnixDatagram, UnixDatagram) {
    let (sender, receiver) = UnixDatagram::pair().expect("datagram pair");
    receiver.set_read_timeout(Some(Duration::from_millis(50))).expect("receive timeout");
    let program = libc::sock_fprog { len: code.len() as u16, filter: code.as_mut_ptr() };
    // SAFETY: `receiver` owns a live socket. `program` borrows the initialized
    // filter slice for this call; the kernel copies it before setsockopt returns.
    unsafe {
        crate::linux::socket_options::setsockopt_raw(
            receiver.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_ATTACH_FILTER,
            &program,
        )
    }
    .expect("attach production program");
    (sender, receiver)
}

fn tcp_bytes(options: &[u8]) -> Vec<u8> {
    assert!(options.len().is_multiple_of(4) && options.len() <= 40);
    let mut bytes = vec![0u8; 20];
    bytes[12] = ((20 + options.len()) / 4) as u8 * 16;
    bytes[13] = 0x18;
    bytes.extend_from_slice(options);
    bytes.extend_from_slice(b"keep this payload");
    bytes
}

fn timestamp() -> [u8; 10] {
    [8, 10, 1, 2, 3, 4, 0, 0, 5, 0]
}

fn common_sack(blocks: u8) -> Vec<u8> {
    let mut options = vec![1, 1];
    options.extend_from_slice(&timestamp());
    options.extend_from_slice(&[1, 1, 5, 2 + blocks * 8]);
    for index in 0..blocks {
        options.extend_from_slice(&(1000u32 + u32::from(index) * 100).to_be_bytes());
        options.extend_from_slice(&(1050u32 + u32::from(index) * 100).to_be_bytes());
    }
    options
}

fn assert_received(sender: &UnixDatagram, receiver: &UnixDatagram, bytes: &[u8]) {
    sender.send(bytes).expect("send TCP bytes");
    let mut actual = [0u8; 128];
    let received = receiver.recv(&mut actual).expect("non-SACK input must arrive");
    assert_eq!(&actual[..received], bytes, "filter must preserve header and payload");
}

fn assert_dropped(sender: &UnixDatagram, receiver: &UnixDatagram, bytes: &[u8]) {
    sender.send(bytes).expect("send TCP bytes");
    let error = receiver.recv(&mut [0u8; 128]).expect_err("matched input must be dropped entirely");
    assert!(matches!(error.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut), "{error}");
}

#[test]
fn drop_sack_preserves_timestamp_data_that_looks_like_an_option_kind() {
    let (sender, receiver) = filtered_pair(&mut drop_sack_program());
    let mut options = vec![1; 6];
    options.extend_from_slice(&timestamp());
    options.extend_from_slice(&[1; 8]);
    let bytes = tcp_bytes(&options);
    assert_eq!(bytes[34], 5, "counterexample must place TSecr data at the old fixed offset");
    assert_received(&sender, &receiver, &bytes);
}

#[test]
fn drop_sack_drops_only_bounded_common_layout_blocks() {
    let (sender, receiver) = filtered_pair(&mut drop_sack_program());
    for blocks in 1..=3 {
        assert_dropped(&sender, &receiver, &tcp_bytes(&common_sack(blocks)));
    }
    for length in [0, 2, 9, 11, 14, 18] {
        let mut options = common_sack(1);
        options[15] = length;
        assert_received(&sender, &receiver, &tcp_bytes(&options));
    }
}

#[test]
fn drop_sack_accepts_unrecognized_or_incomplete_headers() {
    let (sender, receiver) = filtered_pair(&mut drop_sack_program());
    assert_received(&sender, &receiver, &tcp_bytes(&[]));
    let mut options = common_sack(1);
    options[12] = 0;
    assert_received(&sender, &receiver, &tcp_bytes(&options));
    let mut trailing = common_sack(1);
    trailing.extend_from_slice(&[1; 4]);
    assert_received(&sender, &receiver, &tcp_bytes(&trailing));
    let mut bytes = tcp_bytes(&common_sack(1));
    bytes[12] = 0xf0;
    bytes.truncate(44);
    assert_received(&sender, &receiver, &bytes);
    for len in [0, 13, 35, 43] {
        let mut bytes = tcp_bytes(&common_sack(1));
        bytes.truncate(len);
        assert_received(&sender, &receiver, &bytes);
    }
}

#[test]
fn timestamp_filter_only_drops_received_matching_segments() {
    let (sender, receiver) = filtered_pair(&mut timestamp_program());
    sender.set_read_timeout(Some(Duration::from_millis(50))).expect("reverse receive timeout");
    let mut options = timestamp().to_vec();
    options.extend_from_slice(&[1, 1]);
    let matched = tcp_bytes(&options);
    assert_dropped(&sender, &receiver, &matched);
    assert_received(&receiver, &sender, &matched);

    let mut padded = vec![1, 1];
    padded.extend_from_slice(&timestamp());
    assert_received(&sender, &receiver, &tcp_bytes(&padded));
}
