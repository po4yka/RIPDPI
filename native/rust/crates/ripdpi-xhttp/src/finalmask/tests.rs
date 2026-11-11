use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::config::FinalmaskConfig;

use super::masks::TcpOutboundMask;
use super::spec::FinalmaskSpec;
use super::wrap_tcp_stream;

fn header_config() -> FinalmaskConfig {
    FinalmaskConfig {
        r#type: "header_custom".to_string(),
        header_hex: "abcd".to_string(),
        trailer_hex: "ef01".to_string(),
        rand_range: "2-2".to_string(),
        ..FinalmaskConfig::default()
    }
}

#[test]
fn header_custom_tcp_prelude_is_emitted_once() {
    let mut mask = TcpOutboundMask::new(FinalmaskSpec::from_config(&header_config()).expect("config").expect("mask"));

    let first = mask.encode(b"hello").expect("first");
    let second = mask.encode(b"world").expect("second");

    assert_eq!(2, first.len());
    assert_eq!(b"hello", first[1].as_slice());
    assert_eq!(1, second.len());
    assert_eq!(b"world", second[0].as_slice());
}

#[test]
fn fragment_mode_splits_payload_into_requested_frames() {
    let config = FinalmaskConfig {
        r#type: "fragment".to_string(),
        fragment_packets: 3,
        fragment_min_bytes: 2,
        fragment_max_bytes: 4,
        ..FinalmaskConfig::default()
    };
    let mut mask = TcpOutboundMask::new(FinalmaskSpec::from_config(&config).expect("config").expect("mask"));

    let frames = mask.encode(b"abcdefgh").expect("frames");

    assert_eq!(3, frames.len());
    assert_eq!(8, frames.iter().map(Vec::len).sum::<usize>());
}

#[test]
fn noise_mode_emits_random_prelude_once_then_passes_payload() {
    let config =
        FinalmaskConfig { r#type: "noise".to_string(), rand_range: "4-4".to_string(), ..FinalmaskConfig::default() };
    let mut mask = TcpOutboundMask::new(FinalmaskSpec::from_config(&config).expect("config").expect("mask"));

    let first = mask.encode(b"hello").expect("first");
    let second = mask.encode(b"world").expect("second");

    assert_eq!(2, first.len());
    assert_eq!(4, first[0].len());
    assert_eq!(b"hello", first[1].as_slice());
    assert_eq!(1, second.len());
    assert_eq!(b"world", second[0].as_slice());
}

#[test]
fn fragment_mode_rejects_negative_packets() {
    let config = FinalmaskConfig {
        r#type: "fragment".to_string(),
        fragment_packets: -1,
        fragment_min_bytes: 1,
        fragment_max_bytes: 4,
        ..FinalmaskConfig::default()
    };
    let Err(err) = FinalmaskSpec::from_config(&config) else {
        panic!("negative packets must be rejected");
    };
    assert_eq!(std::io::ErrorKind::InvalidInput, err.kind());
    assert!(err.to_string().contains("fragmentPackets"), "error must name the offending field: {err}");
}

#[test]
fn fragment_mode_rejects_negative_min_bytes() {
    let config = FinalmaskConfig {
        r#type: "fragment".to_string(),
        fragment_packets: 2,
        fragment_min_bytes: -5,
        fragment_max_bytes: 4,
        ..FinalmaskConfig::default()
    };
    let Err(err) = FinalmaskSpec::from_config(&config) else {
        panic!("negative min bytes must be rejected");
    };
    assert!(err.to_string().contains("fragmentMinBytes"), "error must name the offending field: {err}");
}

#[tokio::test]
async fn tcp_wrapper_preserves_plain_streams_when_off() {
    let (mut left, right) = tokio::io::duplex(1024);
    let wrapped = wrap_tcp_stream(right, &FinalmaskConfig::default()).expect("wrapped");

    tokio::spawn(async move {
        left.write_all(b"hello").await.expect("write");
    });

    let mut stream = wrapped;
    let mut buffer = [0u8; 5];
    stream.read_exact(&mut buffer).await.expect("read");
    assert_eq!(b"hello", &buffer);
}

#[test]
fn rand_range_above_cap_is_rejected_for_noise_and_header_custom() {
    for kind in ["noise", "header_custom"] {
        let config = FinalmaskConfig {
            r#type: kind.to_string(),
            rand_range: "0-4000000000".to_string(),
            header_hex: "abcd".to_string(),
            ..FinalmaskConfig::default()
        };
        let Err(err) = FinalmaskSpec::from_config(&config) else {
            panic!("{kind}: a multi-gigabyte randRange must be rejected at load time");
        };
        assert_eq!(std::io::ErrorKind::InvalidInput, err.kind(), "{kind}: {err}");
    }
    let config = FinalmaskConfig {
        r#type: "noise".to_string(),
        rand_range: "16384-16384".to_string(),
        ..FinalmaskConfig::default()
    };
    let mut mask = TcpOutboundMask::new(FinalmaskSpec::from_config(&config).expect("cap is inclusive").expect("mask"));
    assert_eq!(16 * 1024, mask.encode(b"x").expect("frames")[0].len());
}

#[test]
fn fragment_mode_splits_an_oversized_first_write_and_passes_later_writes_whole() {
    let config = FinalmaskConfig {
        r#type: "fragment".to_string(),
        fragment_packets: 3,
        fragment_min_bytes: 50,
        fragment_max_bytes: 150,
        ..FinalmaskConfig::default()
    };
    let mut mask = TcpOutboundMask::new(FinalmaskSpec::from_config(&config).expect("config").expect("mask"));
    // A post-quantum ClientHello is larger than packets * max_bytes (450 B).
    let client_hello = vec![0x16; 1700];

    let frames = mask.encode(&client_hello).expect("first write");

    assert_eq!(3, frames.len(), "the first write must still be fragmented");
    assert!(frames[..2].iter().all(|frame| (50..=150).contains(&frame.len())), "lead fragments use the range");
    assert_eq!(client_hello, frames.concat(), "fragments must reassemble to the original write");
    let later = mask.encode(&[0x17; 600]).expect("later write");
    assert_eq!(vec![vec![0x17; 600]], later, "writes after the first are not re-chunked");
}

#[test]
fn fragment_mode_rejects_zero_and_excessive_packet_counts() {
    for packets in [0, 17, i32::MAX] {
        let config = FinalmaskConfig {
            r#type: "fragment".to_owned(),
            fragment_packets: packets,
            fragment_min_bytes: 1,
            fragment_max_bytes: 1,
            ..FinalmaskConfig::default()
        };
        assert!(FinalmaskSpec::from_config(&config).is_err(), "packet count {packets} must fail");
    }
}

#[test]
fn fragment_storage_is_bounded_by_payload_size() {
    let payload = b"tls";
    let frames = super::fragmentation::fragment_bytes(payload, 1_000_000, 1, 1);
    assert_eq!(frames.concat(), payload);
    assert!(frames.capacity() <= payload.len(), "capacity {} exceeds the payload size", frames.capacity());
}

#[test]
fn fragment_bounds_saturate_for_large_configured_ranges() {
    let payload = b"0123456789abcdef";
    let frames = super::fragmentation::fragment_bytes(payload, payload.len(), 1, usize::MAX);
    assert_eq!(frames.concat(), payload);
    assert_eq!(frames.len(), payload.len());
    assert!(frames.iter().all(|frame| frame.len() == 1));
}

#[test]
fn inactive_random_range_does_not_reject_fragment_or_sudoku() {
    for kind in ["fragment", "sudoku"] {
        let config = FinalmaskConfig {
            r#type: kind.to_owned(),
            rand_range: "invalid".to_owned(),
            fragment_packets: 3,
            fragment_min_bytes: 1,
            fragment_max_bytes: 4,
            sudoku_seed: "fixture-seed".to_owned(),
            ..FinalmaskConfig::default()
        };
        assert!(FinalmaskSpec::from_config(&config).is_ok(), "inactive random range must not reject {kind}");
    }
}

#[test]
fn active_mask_validation_accepts_boundary_values() {
    for kind in ["header_custom", "noise"] {
        for range in ["0-0", "16384-16384"] {
            let config = FinalmaskConfig {
                r#type: kind.to_owned(),
                header_hex: "ab".to_owned(),
                rand_range: range.to_owned(),
                ..FinalmaskConfig::default()
            };
            config.validate().expect("valid boundary range");
            FinalmaskSpec::from_config(&config).expect("transport accepts admission result");
        }
    }
    for packets in [1, 16] {
        let config = FinalmaskConfig {
            r#type: "fragment".to_owned(),
            fragment_packets: packets,
            fragment_min_bytes: 1,
            fragment_max_bytes: 1,
            ..FinalmaskConfig::default()
        };
        config.validate().expect("valid boundary count");
        FinalmaskSpec::from_config(&config).expect("transport accepts admission result");
    }
}
