#![cfg(unix)]

use std::io::{IoSlice, Read};
use std::os::fd::AsRawFd;
use std::os::unix::net::UnixStream;
use std::time::{Duration, Instant};

use nix::sys::socket::{ControlMessage, MsgFlags, sendmsg};
use ripdpi_root_helper_protocol::{
    CMD_PROTOCOL_PREFLIGHT, CMD_RECV_ICMP_WRAPPED_UDP, HelperRequest, PROTOCOL_VERSION, bulk_tcp_preflight_data,
    recv_response_message_with_deadline, send_response_message, validate_request_with_peer,
};

#[test]
fn receive_response_bound_preserves_the_largest_payload_without_raising_control_limits() {
    let bytes = serde_json::to_vec(&serde_json::json!({ "payload": vec![255_u8; 65_493] })).expect("JSON");
    let (sender, receiver) = UnixStream::pair().expect("IPC pair");
    assert_eq!(
        send_response_message(&sender, CMD_PROTOCOL_PREFLIGHT, &bytes, None).expect_err("control limit").kind(),
        std::io::ErrorKind::InvalidInput,
    );
    let worker = std::thread::spawn(move || {
        send_response_message(&sender, CMD_RECV_ICMP_WRAPPED_UDP, &bytes, None).expect("ICMP response");
    });
    let (received, fd) = recv_response_message_with_deadline(
        &receiver,
        CMD_RECV_ICMP_WRAPPED_UDP,
        "closed",
        Instant::now() + Duration::from_secs(2),
    )
    .expect("complete ICMP response");
    assert!(fd.is_none());
    let response: serde_json::Value = serde_json::from_slice(&received).expect("independent JSON decode");
    assert_eq!(response["payload"], serde_json::json!(vec![255_u8; 65_493]));
    worker.join().expect("sender");
}

#[test]
fn receive_refuses_legacy_response_support_before_command_delivery() {
    let request = HelperRequest {
        protocol_version: Some(PROTOCOL_VERSION),
        command: CMD_RECV_ICMP_WRAPPED_UDP.into(),
        params: serde_json::json!({}),
        session_nonce: None,
    };
    let len = serde_json::to_vec(&request).expect("request").len();
    for peer in
        [serde_json::Value::Null, serde_json::json!({ "icmp_wrapped_udp_receive": { "max_response_bytes": 8192 } })]
    {
        assert_eq!(
            validate_request_with_peer(&request, len, &peer).expect_err("legacy peer").kind(),
            std::io::ErrorKind::Unsupported
        );
    }
    validate_request_with_peer(&request, len, &bulk_tcp_preflight_data()).expect("supported peer");
}

#[test]
fn oversized_response_header_closes_the_received_descriptor() {
    let (sender, receiver) = UnixStream::pair().expect("IPC pair");
    let (passed, mut peer) = UnixStream::pair().expect("fd lifetime oracle");
    peer.set_read_timeout(Some(Duration::from_secs(1))).expect("read timeout");
    let limit = bulk_tcp_preflight_data()["icmp_wrapped_udp_receive"]["max_response_bytes"].as_u64().expect("limit");
    for command in [CMD_PROTOCOL_PREFLIGHT, CMD_RECV_ICMP_WRAPPED_UDP] {
        let length = if command == CMD_PROTOCOL_PREFLIGHT { 8193 } else { limit as u32 + 1 };
        sendmsg::<()>(sender.as_raw_fd(), &[IoSlice::new(&length.to_be_bytes())], &[], MsgFlags::empty(), None)
            .expect("header");
        let error =
            recv_response_message_with_deadline(&receiver, command, "closed", Instant::now() + Duration::from_secs(1))
                .expect_err("over limit");
        assert_eq!(error.kind(), std::io::ErrorKind::InvalidData);
    }
    // A descriptor installed with a refused header must not outlive the receive call.
    let fds = [passed.as_raw_fd()];
    sendmsg::<()>(
        sender.as_raw_fd(),
        &[IoSlice::new(&(limit as u32 + 1).to_be_bytes())],
        &[ControlMessage::ScmRights(&fds)],
        MsgFlags::empty(),
        None,
    )
    .expect("header with fd");
    drop(passed);
    recv_response_message_with_deadline(
        &receiver,
        CMD_RECV_ICMP_WRAPPED_UDP,
        "closed",
        Instant::now() + Duration::from_secs(1),
    )
    .expect_err("refused fd frame");
    assert_eq!(peer.read(&mut [0]).expect("received descriptor closed"), 0);
}

#[test]
fn response_receive_keeps_the_total_deadline() {
    let (sender, receiver) = UnixStream::pair().expect("IPC pair");
    send_response_message(&sender, CMD_RECV_ICMP_WRAPPED_UDP, b"{}", None).expect("response");
    let error = recv_response_message_with_deadline(
        &receiver,
        CMD_RECV_ICMP_WRAPPED_UDP,
        "closed",
        Instant::now() - Duration::from_millis(1),
    )
    .expect_err("expired deadline");
    assert_eq!(error.kind(), std::io::ErrorKind::TimedOut);
}
