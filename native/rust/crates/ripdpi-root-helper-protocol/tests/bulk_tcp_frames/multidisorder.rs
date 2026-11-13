use super::*;
use ripdpi_root_helper_protocol::{CMD_SEND_MULTI_DISORDER_TCP, MultiDisorderParams, SegmentSpec};

fn multidisorder_params(payload: Vec<u8>) -> MultiDisorderParams {
    let len = payload.len();
    MultiDisorderParams {
        payload,
        segments: vec![
            SegmentSpec { start: 0, end: 1 },
            SegmentSpec { start: 1, end: 2 },
            SegmentSpec { start: 2, end: len },
        ],
        default_ttl: 64,
        inter_segment_delay_ms: 0,
        md5sig: false,
        tcp_flags_set: 0,
        tcp_flags_unset: 0,
        ipv4_identifications: Vec::new(),
    }
}

#[test]
fn multidisorder_frames_preserve_payload_bytes_and_descriptor() {
    for len in [1024, 2048, 4096, 16384] {
        for payload in [vec![0_u8; len], vec![255; len], (0..len).map(|index| index as u8).collect()] {
            round_trip(request(
                CMD_SEND_MULTI_DISORDER_TCP,
                serde_json::to_value(multidisorder_params(payload)).expect("multidisorder JSON"),
            ));
        }
    }
    round_trip(request(
        CMD_SEND_MULTI_DISORDER_TCP,
        serde_json::to_value(multidisorder_params(vec![255; MAX_TCP_PAYLOAD_BYTES]))
            .expect("maximum multidisorder JSON"),
    ));
}

#[test]
fn multidisorder_bulk_requires_command_support_but_small_legacy_requests_work() {
    let large = request(
        CMD_SEND_MULTI_DISORDER_TCP,
        serde_json::to_value(multidisorder_params(vec![255; 2048])).expect("large JSON"),
    );
    let len = serde_json::to_vec(&large).expect("request JSON").len();
    assert!(len > 8192);
    assert!(validate_request_with_peer(&large, len, &bulk_tcp_preflight_data()).is_ok());
    let mut legacy_bulk = bulk_tcp_preflight_data();
    legacy_bulk["bulk_tcp"].as_object_mut().expect("bulk object").remove("multidisorder");
    let error =
        validate_request_with_peer(&large, len, &legacy_bulk).expect_err("generic bulk support is insufficient");
    assert!(error.to_string().contains("did not advertise bulk multidisorder"));
    legacy_bulk["bulk_tcp"]["multidisorder"] = json!(false);
    assert!(validate_request_with_peer(&large, len, &legacy_bulk).is_err());
    assert!(validate_request_with_peer(&large, len, &Value::Null).is_err());

    let small = request(
        CMD_SEND_MULTI_DISORDER_TCP,
        serde_json::to_value(multidisorder_params(vec![255; 3])).expect("small JSON"),
    );
    let len = serde_json::to_vec(&small).expect("small request").len();
    assert!(len < 8192);
    assert!(validate_request_with_peer(&small, len, &Value::Null).is_ok());
    assert!(validate_request_with_peer(&small, len, &legacy_bulk).is_ok());
}

fn assert_rejected_before_send(params: MultiDisorderParams) {
    let request = request(CMD_SEND_MULTI_DISORDER_TCP, serde_json::to_value(params).expect("params JSON"));
    let json = serde_json::to_vec(&request).expect("request JSON");
    assert!(
        validate_request_with_peer(&request, json.len(), &bulk_tcp_preflight_data()).is_err(),
        "helper-side validation"
    );
    let (sender, mut receiver) = UnixStream::pair().expect("IPC pair");
    assert!(send_request_message(&sender, &json, None).is_err(), "client-side validation");
    receiver.set_nonblocking(true).expect("nonblocking receiver");
    assert_eq!(receiver.read(&mut [0]).expect_err("no frame sent").kind(), std::io::ErrorKind::WouldBlock);
}

#[test]
fn multidisorder_rejects_invalid_ranges_and_payloads_before_send_or_dispatch() {
    let valid = multidisorder_params(vec![255; 8]);
    let mut cases = Vec::new();
    let mut params = valid.clone();
    params.segments.clear();
    cases.push(params);
    let mut params = valid.clone();
    params.segments.remove(1);
    cases.push(params);
    let mut params = valid.clone();
    params.segments[1].start = 0;
    cases.push(params);
    let mut params = valid.clone();
    params.segments[1].start = 2;
    cases.push(params);
    let mut params = valid.clone();
    params.segments[1].end = 1;
    cases.push(params);
    let mut params = valid.clone();
    params.segments[2].end = 7;
    cases.push(params);
    let mut params = valid.clone();
    params.segments[2].end = 9;
    cases.push(params);
    let mut params = valid.clone();
    params.segments[0].start = usize::MAX;
    cases.push(params);
    let mut params = valid;
    params.payload.clear();
    cases.push(params);
    cases.push(multidisorder_params(vec![0; MAX_TCP_PAYLOAD_BYTES + 1]));
    let mut params = multidisorder_params(vec![0; MAX_ORDERED_TCP_SEGMENTS + 1]);
    params.segments = (0..=MAX_ORDERED_TCP_SEGMENTS).map(|start| SegmentSpec { start, end: start + 1 }).collect();
    cases.push(params);
    for params in cases {
        assert_rejected_before_send(params);
    }
}

#[test]
fn multidisorder_honors_advertised_frame_payload_aggregate_and_segment_limits() {
    let mut params = multidisorder_params(vec![0; MAX_ORDERED_TCP_SEGMENTS]);
    params.segments = (0..MAX_ORDERED_TCP_SEGMENTS).map(|start| SegmentSpec { start, end: start + 1 }).collect();
    let request = request(CMD_SEND_MULTI_DISORDER_TCP, serde_json::to_value(params).expect("params JSON"));
    let len = serde_json::to_vec(&request).expect("request JSON").len();
    assert!(validate_request_with_peer(&request, len, &bulk_tcp_preflight_data()).is_ok());
    for (field, limit) in [
        ("max_message_bytes", len - 1),
        ("max_payload_bytes", MAX_ORDERED_TCP_SEGMENTS - 1),
        ("max_aggregate_bytes", MAX_ORDERED_TCP_SEGMENTS - 1),
        ("max_segments", MAX_ORDERED_TCP_SEGMENTS - 1),
    ] {
        let mut peer = bulk_tcp_preflight_data();
        peer["bulk_tcp"][field] = json!(limit);
        assert!(validate_request_with_peer(&request, len, &peer).is_err(), "{field}");
    }
    round_trip(request);
}
