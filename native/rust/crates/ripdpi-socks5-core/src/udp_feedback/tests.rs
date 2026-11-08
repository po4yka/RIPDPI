use super::*;

const NONCE: Nonce = [0x41; 16];

fn ipv4_error() -> RemoteError {
    RemoteError {
        source: "192.0.2.1".parse().unwrap(),
        target: "203.0.113.9:443".parse().unwrap(),
        icmp_type: 3,
        icmp_code: 3,
        info: 0,
    }
}

#[test]
fn preface_uses_the_versioned_magic_and_exact_nonce() {
    let expected = [82, 85, 70, 49, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65];
    assert_eq!(encode_preface(&NONCE), expected);
    assert_eq!(decode_preface(&expected).unwrap(), NONCE);
    for len in 0..PREFACE_LEN {
        assert!(decode_preface(&expected[..len]).is_err());
    }
    let mut wrong_version = expected;
    wrong_version[3] = b'2';
    assert!(decode_preface(&wrong_version).is_err());
    assert!(decode_preface(&[expected.as_slice(), &[0]].concat()).is_err());
}

#[test]
fn request_matches_hand_derived_bytes_and_preserves_socks_body() {
    let socks = [0, 0, 0, 1, 203, 0, 113, 9, 1, 187, 0xfe];
    let expected = [
        82, 85, 70, 49, 1, 0, 0, 11, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 1, 2, 3, 4, 5, 6,
        7, 8, 0, 0, 0, 1, 203, 0, 113, 9, 1, 187, 254,
    ];
    let id = 0x0102_0304_0506_0708;
    assert_eq!(encode_request(&NONCE, id, &socks).unwrap(), expected);
    assert_eq!(decode(&expected, &NONCE).unwrap(), Frame::Request { id, payload: &socks });
    let response = encode_response(&NONCE, &socks).unwrap();
    assert_eq!(response[4], 2);
    assert_eq!(&response[24..32], &[0; 8]);
    assert_eq!(decode(&response, &NONCE).unwrap(), Frame::Response { payload: &socks });
}

#[test]
fn remote_port_error_matches_hand_derived_ipv4_bytes() {
    let expected = [
        82, 85, 70, 49, 3, 0, 0, 19, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 0, 0, 0, 0, 0, 0,
        0, 7, 4, 3, 3, 0, 0, 0, 0, 0, 192, 0, 2, 1, 4, 203, 0, 113, 9, 1, 187,
    ];
    let error = ipv4_error();
    assert_eq!(encode_error(&NONCE, 7, &error).unwrap(), expected);
    assert_eq!(decode(&expected, &NONCE).unwrap(), Frame::Error { id: 7, error });
}

#[test]
fn rejects_wrong_nonce_version_kind_flags_lengths_and_ids() {
    let good = encode_request(&NONCE, 1, &[0, 0, 0, 1]).unwrap();
    for len in 0..good.len() {
        assert!(decode(&good[..len], &NONCE).is_err(), "truncated at {len}");
    }
    for (offset, value) in [(0, b'X'), (3, b'2'), (4, 0), (4, 4), (5, 1), (6, 1), (7, 3), (8, 0), (23, 0)] {
        let mut corrupt = good.clone();
        corrupt[offset] = value;
        assert!(decode(&corrupt, &NONCE).is_err(), "header byte {offset}");
    }
    let mut corrupt = good.clone();
    corrupt[24..32].fill(0);
    assert!(decode(&corrupt, &NONCE).is_err());
    let mut corrupt = encode_response(&NONCE, &[]).unwrap();
    corrupt[31] = 1;
    assert!(decode(&corrupt, &NONCE).is_err());
    assert!(decode(&[good.as_slice(), &[0]].concat(), &NONCE).is_err());
    assert!(encode_request(&NONCE, 0, &[]).is_err());
    assert!(encode_error(&NONCE, 0, &ipv4_error()).is_err());
}

#[test]
fn exact_wire_limit_accepts_boundary_and_rejects_one_more_byte() {
    let body = vec![0; MAX_WIRE_LEN - HEADER_LEN];
    let frame = encode_request(&NONCE, u64::MAX, &body).unwrap();
    assert_eq!(frame.len(), MAX_WIRE_LEN);
    assert_eq!(decode(&frame, &NONCE).unwrap(), Frame::Request { id: u64::MAX, payload: &body });
    assert!(encode_request(&NONCE, 1, &vec![0; body.len() + 1]).is_err());
    assert!(encode_response(&NONCE, &vec![0; body.len() + 1]).is_err());
    assert!(decode(&vec![0; MAX_WIRE_LEN + 1], &NONCE).is_err());
}

#[test]
fn supported_icmp_types_codes_and_information_are_explicit() {
    let mut error = ipv4_error();
    for code in 0..=15 {
        error.icmp_code = code;
        assert!(encode_error(&NONCE, 1, &error).is_ok());
    }
    error.icmp_code = 16;
    assert!(encode_error(&NONCE, 1, &error).is_err());
    error.icmp_type = 11;
    error.icmp_code = 0;
    assert!(encode_error(&NONCE, 1, &error).is_ok());
    error.icmp_code = 1;
    assert!(encode_error(&NONCE, 1, &error).is_err());
    error.icmp_type = 3;
    error.icmp_code = 4;
    for info in [0, 576, 1500, 65535] {
        error.info = info;
        assert!(encode_error(&NONCE, 1, &error).is_ok());
    }
    error.info = 65536;
    assert!(encode_error(&NONCE, 1, &error).is_err());
    error.icmp_code = 3;
    error.info = 1;
    assert!(encode_error(&NONCE, 1, &error).is_err());

    let mut error = RemoteError {
        source: "2001:db8::1".parse().unwrap(),
        target: "[2001:db8::9]:443".parse().unwrap(),
        icmp_type: 1,
        icmp_code: 0,
        info: 0,
    };
    for code in 0..=6 {
        error.icmp_code = code;
        let frame = encode_error(&NONCE, 1, &error).unwrap();
        assert_eq!(frame.len(), HEADER_LEN + 43);
        assert_eq!(decode(&frame, &NONCE).unwrap(), Frame::Error { id: 1, error });
    }
    error.icmp_code = 7;
    assert!(encode_error(&NONCE, 1, &error).is_err());
    for (kind, code, info) in [(2, 0, 1), (2, 0, 900), (2, 0, u32::MAX), (3, 0, 0), (3, 1, 0)] {
        error.icmp_type = kind;
        error.icmp_code = code;
        error.info = info;
        assert!(encode_error(&NONCE, 1, &error).is_ok());
    }
    for (kind, code, info) in [(2, 0, 0), (2, 1, 1280), (3, 2, 0), (4, 0, 0), (1, 0, 1)] {
        error.icmp_type = kind;
        error.icmp_code = code;
        error.info = info;
        assert!(encode_error(&NONCE, 1, &error).is_err());
    }
}

#[test]
fn error_decoder_rejects_bad_body_addresses_and_cross_family() {
    let good = encode_error(&NONCE, 7, &ipv4_error()).unwrap();
    for (offset, value) in [(32, 5), (33, 12), (34, 16), (35, 1), (36, 1), (44, 5), (44, 6), (50, 0)] {
        let mut corrupt = good.clone();
        corrupt[offset] = value;
        if offset == 50 {
            corrupt[49] = 0;
        }
        assert!(decode(&corrupt, &NONCE).is_err(), "error byte {offset}");
    }
    for bad in ["0.0.0.0", "224.0.0.1", "255.255.255.255", "::", "ff02::1"] {
        let bad: IpAddr = bad.parse().unwrap();
        let mut error = ipv4_error();
        error.source = bad;
        assert!(encode_error(&NONCE, 1, &error).is_err());
        error = ipv4_error();
        error.target.set_ip(bad);
        assert!(encode_error(&NONCE, 1, &error).is_err());
    }
    let mut error = ipv4_error();
    error.source = "2001:db8::1".parse().unwrap();
    assert!(encode_error(&NONCE, 1, &error).is_err());
    error = ipv4_error();
    error.target.set_port(0);
    assert!(encode_error(&NONCE, 1, &error).is_err());
    error.source = "127.0.0.1".parse().unwrap();
    error.target = "127.0.0.1:53".parse().unwrap();
    assert!(encode_error(&NONCE, 1, &error).is_ok());
}
