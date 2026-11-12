#![cfg(unix)]

use std::io::{IoSlice, Read, Write};
use std::os::fd::{AsFd, AsRawFd};
use std::os::unix::net::UnixStream;

use ripdpi_root_helper_protocol::{
    CMD_PROTOCOL_PREFLIGHT, CMD_SEND_FAKE_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, FakeTcpParams, HelperRequest,
    OrderedTcpSegmentParams, OrderedTcpSegmentsParams, PROTOCOL_VERSION, bulk_tcp_preflight_data, recv_request_message,
    send_message, send_request_message, validate_request_with_peer,
};
use serde_json::{Value, json};

const MAX_BULK_REQUEST_BYTES: usize = 1_048_576;
const MAX_TCP_PAYLOAD_BYTES: usize = 65_535;
const MAX_ORDERED_TCP_SEGMENTS: usize = 64;

fn request(command: &str, params: Value) -> HelperRequest {
    HelperRequest {
        protocol_version: Some(PROTOCOL_VERSION),
        command: command.to_owned(),
        params,
        session_nonce: Some("abcdefghijklmnopqrstuvwxyzABCDEF".to_owned()),
    }
}

fn fake_params(len: usize) -> FakeTcpParams {
    serde_json::from_value(json!({
        "original_prefix": vec![255_u8; len], "fake_prefix": vec![170_u8; len],
        "secondary_fake_prefix": vec![85_u8; len], "ttl": 8, "default_ttl": 64,
    }))
    .expect("fake params")
}

fn ordered_params(len: usize) -> OrderedTcpSegmentsParams {
    OrderedTcpSegmentsParams {
        segments: [255_u8, 170, 85]
            .into_iter()
            .enumerate()
            .map(|(index, byte)| OrderedTcpSegmentParams {
                payload: vec![byte; len],
                ttl: if index == 2 { 64 } else { 8 },
                tcp_flags_set: 0,
                tcp_flags_unset: 0,
                sequence_offset: 0,
                use_fake_timestamp: index != 2,
            })
            .collect(),
        original_payload_len: len,
        default_ttl: 64,
        md5sig: false,
        timestamp_delta_ticks: None,
        ipv4_identifications: Vec::new(),
        wait_enabled: false,
        wait_poll_ms: 10,
    }
}

fn round_trip(request: HelperRequest) {
    let expected = serde_json::to_vec(&request).expect("encode request");
    let (sender, receiver) = UnixStream::pair().expect("IPC pair");
    let (passed, mut peer) = UnixStream::pair().expect("passed socket");
    let (actual, fd) = std::thread::scope(|scope| {
        let send = scope.spawn(|| send_request_message(&sender, &expected, Some(passed.as_fd())));
        let received = recv_request_message(&receiver, "closed").expect("receive bulk request");
        send.join().expect("sender thread").expect("send bulk request");
        received
    });
    assert_eq!(actual, expected, "the full request must survive framing");
    let decoded: HelperRequest = serde_json::from_slice(&actual).expect("decode request");
    assert_eq!(decoded.params, request.params, "payload arrays must not change");
    let mut received = UnixStream::from(fd.expect("passed descriptor"));
    received.write_all(b"fd-alive").expect("write received socket");
    let mut bytes = [0; 8];
    peer.read_exact(&mut bytes).expect("read received socket");
    assert_eq!(&bytes, b"fd-alive");
}

#[test]
fn fake_and_ordered_frames_preserve_normal_and_maximum_payloads_and_fds() {
    for len in [2048, 16384, MAX_TCP_PAYLOAD_BYTES] {
        round_trip(request(CMD_SEND_FAKE_TCP, serde_json::to_value(fake_params(len)).expect("fake JSON")));
        round_trip(request(
            CMD_SEND_ORDERED_TCP_SEGMENTS,
            serde_json::to_value(ordered_params(len)).expect("ordered JSON"),
        ));
    }
}

#[test]
fn bulk_requires_peer_advertisement_but_control_sized_legacy_requests_still_work() {
    let large = request(CMD_SEND_FAKE_TCP, serde_json::to_value(fake_params(2048)).expect("fake JSON"));
    let len = serde_json::to_vec(&large).expect("request JSON").len();
    assert!(validate_request_with_peer(&large, len, &bulk_tcp_preflight_data()).is_ok());
    assert!(validate_request_with_peer(&large, len, &Value::Null).is_err());
    let small = request(CMD_SEND_FAKE_TCP, serde_json::to_value(fake_params(1)).expect("fake JSON"));
    assert!(validate_request_with_peer(&small, serde_json::to_vec(&small).expect("JSON").len(), &Value::Null).is_ok());

    let mut smaller_peer = bulk_tcp_preflight_data();
    smaller_peer["bulk_tcp"]["max_payload_bytes"] = json!(1024);
    assert!(validate_request_with_peer(&large, len, &smaller_peer).is_err());
}

#[test]
fn small_fake_and_ordered_frames_honor_advertised_peer_limits() {
    let mut fake = fake_params(0);
    fake.original_prefix = vec![0; 1400];
    let fake = serde_json::to_value(fake).expect("fake JSON");
    for (command, params, field, limit) in [
        (CMD_SEND_FAKE_TCP, fake.clone(), "max_payload_bytes", 1024),
        (CMD_SEND_FAKE_TCP, fake, "max_aggregate_bytes", 1024),
        (
            CMD_SEND_ORDERED_TCP_SEGMENTS,
            serde_json::to_value(ordered_params(1)).expect("ordered JSON"),
            "max_segments",
            2,
        ),
    ] {
        let request = request(command, params);
        let len = serde_json::to_vec(&request).expect("request JSON").len();
        assert!(len < 8192, "the regression must exercise a control-sized frame");
        assert!(validate_request_with_peer(&request, len, &Value::Null).is_ok());
        assert!(validate_request_with_peer(&request, len, &bulk_tcp_preflight_data()).is_ok());
        let mut smaller_peer = bulk_tcp_preflight_data();
        smaller_peer["bulk_tcp"][field] = json!(limit);
        assert!(validate_request_with_peer(&request, len, &smaller_peer).is_err(), "{field}");
        let mut smaller_frame = bulk_tcp_preflight_data();
        smaller_frame["bulk_tcp"]["max_message_bytes"] = json!(len - 1);
        assert!(validate_request_with_peer(&request, len, &smaller_frame).is_err());
    }
}

#[test]
fn request_payload_segment_and_aggregate_bounds_are_enforced() {
    for field in ["original_prefix", "fake_prefix", "secondary_fake_prefix"] {
        let mut params = serde_json::to_value(fake_params(1)).expect("fake JSON");
        params[field] = json!(vec![255_u8; MAX_TCP_PAYLOAD_BYTES + 1]);
        let request = request(CMD_SEND_FAKE_TCP, params);
        let len = serde_json::to_vec(&request).expect("request JSON").len();
        assert!(validate_request_with_peer(&request, len, &bulk_tcp_preflight_data()).is_err(), "{field}");
    }

    let mut params = ordered_params(1);
    params.segments.resize(MAX_ORDERED_TCP_SEGMENTS, params.segments[0].clone());
    let at_limit = request(CMD_SEND_ORDERED_TCP_SEGMENTS, serde_json::to_value(&params).expect("ordered JSON"));
    assert!(
        validate_request_with_peer(
            &at_limit,
            serde_json::to_vec(&at_limit).expect("JSON").len(),
            &bulk_tcp_preflight_data()
        )
        .is_ok()
    );
    params.segments.push(params.segments[0].clone());
    let over_count = request(CMD_SEND_ORDERED_TCP_SEGMENTS, serde_json::to_value(params).expect("ordered JSON"));
    assert!(
        validate_request_with_peer(
            &over_count,
            serde_json::to_vec(&over_count).expect("JSON").len(),
            &bulk_tcp_preflight_data()
        )
        .is_err()
    );

    let mut params = ordered_params(MAX_TCP_PAYLOAD_BYTES);
    params.segments.push(params.segments[0].clone());
    let over_aggregate = request(CMD_SEND_ORDERED_TCP_SEGMENTS, serde_json::to_value(params).expect("ordered JSON"));
    assert!(
        validate_request_with_peer(
            &over_aggregate,
            serde_json::to_vec(&over_aggregate).expect("JSON").len(),
            &bulk_tcp_preflight_data()
        )
        .is_err()
    );

    let mut params = ordered_params(1);
    params.original_payload_len = MAX_TCP_PAYLOAD_BYTES + 1;
    let over_original = request(CMD_SEND_ORDERED_TCP_SEGMENTS, serde_json::to_value(params).expect("ordered JSON"));
    assert!(
        validate_request_with_peer(
            &over_original,
            serde_json::to_vec(&over_original).expect("JSON").len(),
            &bulk_tcp_preflight_data()
        )
        .is_err()
    );
}

#[test]
fn nonbulk_and_generic_frames_keep_the_control_limit() {
    let json =
        serde_json::to_vec(&request(CMD_PROTOCOL_PREFLIGHT, json!({"padding": "a".repeat(8192)}))).expect("JSON");
    let (sender, receiver) = UnixStream::pair().expect("IPC pair");
    assert!(send_request_message(&sender, &json, None).is_err());
    assert!(send_message(&sender, &json, None).is_err());
    receiver.set_nonblocking(true).expect("nonblocking receiver");
    let mut receiver = receiver;
    assert_eq!(receiver.read(&mut [0]).expect_err("no frame sent").kind(), std::io::ErrorKind::WouldBlock);
}

#[test]
fn bulk_frame_length_is_rejected_before_reading_its_body() {
    let (mut sender, receiver) = UnixStream::pair().expect("IPC pair");
    sender.write_all(&u32::try_from(MAX_BULK_REQUEST_BYTES + 1).expect("frame length").to_be_bytes()).expect("header");
    assert_eq!(
        recv_request_message(&receiver, "closed").expect_err("oversized frame").kind(),
        std::io::ErrorKind::InvalidData
    );
}

#[test]
fn rejected_nonbulk_frame_closes_its_received_descriptor() {
    use nix::sys::socket::{ControlMessage, MsgFlags, sendmsg};

    let json =
        serde_json::to_vec(&request(CMD_PROTOCOL_PREFLIGHT, json!({"padding": "a".repeat(8192)}))).expect("JSON");
    let (mut sender, receiver) = UnixStream::pair().expect("IPC pair");
    let (passed, mut peer) = UnixStream::pair().expect("descriptor lifetime oracle");
    peer.set_read_timeout(Some(std::time::Duration::from_secs(2))).expect("peer timeout");
    let send = std::thread::spawn(move || {
        let header = u32::try_from(json.len()).expect("frame length").to_be_bytes();
        let fds = [passed.as_raw_fd()];
        let bytes = sendmsg::<()>(
            sender.as_raw_fd(),
            &[IoSlice::new(&header)],
            &[ControlMessage::ScmRights(&fds)],
            MsgFlags::empty(),
            None,
        )
        .expect("header and fd");
        sender.write_all(&header[bytes..]).expect("remaining header");
        sender.write_all(&json).expect("unvalidated peer payload");
    });
    assert_eq!(
        recv_request_message(&receiver, "closed").expect_err("nonbulk frame limit").kind(),
        std::io::ErrorKind::InvalidData
    );
    send.join().expect("sender");
    assert_eq!(peer.read(&mut [0]).expect("received fd must close"), 0);
}
