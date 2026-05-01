use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::config::FinalmaskConfig;

use super::masks::TcpOutboundMask;
use super::spec::FinalmaskSpec;
use super::sudoku::{SudokuDecoder, SudokuEncoder};
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
fn sudoku_round_trips_stream_payload() {
    let config = FinalmaskConfig {
        r#type: "sudoku".to_string(),
        sudoku_seed: "fixture-seed".to_string(),
        ..FinalmaskConfig::default()
    };
    let spec = FinalmaskSpec::from_config(&config).expect("config").expect("mask");
    let FinalmaskSpec::Sudoku { table } = spec else {
        unreachable!();
    };
    let mut encoder = SudokuEncoder::new(Arc::clone(&table));
    let encoded = encoder.encode_chunk(b"hello world").expect("encode");
    let mut decoder = SudokuDecoder::new(table);
    let decoded = decoder.decode_stream_chunk(&encoded).expect("decode");

    assert_eq!(b"hello world", decoded.as_slice());
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
