use std::time::Duration;

use ripdpi_config::FakeSeqMode;

use super::*;

fn options() -> FakeStepOptions {
    FakeStepOptions {
        fake_ttl: 9,
        fake_flags: crate::platform::TcpFlagOverrides::default(),
        original_flags: crate::platform::TcpFlagOverrides::default(),
        timestamp_delta_ticks: None,
        wait: (false, Duration::from_millis(1)),
        custom_order: true,
    }
}

#[test]
fn secondary_payload_bytes_keep_all_orders_ttls_and_sequence_modes() {
    type Case<'a> = (FakeOrder, [&'a [u8]; 6], [u8; 6], [usize; 6], [bool; 6]);
    let cases: [Case<'_>; 4] = [
        (
            FakeOrder::BeforeEach,
            [b"abc", b"XYZ", b"ABC", b"de", b"UV", b"DE"],
            [1, 1, 1, 9, 9, 64],
            [0, 0, 0, 3, 3, 3],
            [true, true, false, true, true, false],
        ),
        (
            FakeOrder::AllFakesFirst,
            [b"abc", b"XYZ", b"de", b"UV", b"ABC", b"DE"],
            [1, 1, 9, 9, 1, 64],
            [0, 0, 3, 3, 0, 3],
            [true, true, true, true, false, false],
        ),
        (
            FakeOrder::RealFakeRealFake,
            [b"ABC", b"abc", b"XYZ", b"DE", b"de", b"UV"],
            [1, 1, 1, 64, 9, 9],
            [0, 0, 0, 3, 3, 3],
            [false, true, true, false, true, true],
        ),
        (
            FakeOrder::AllRealsFirst,
            [b"ABC", b"DE", b"abc", b"XYZ", b"de", b"UV"],
            [1, 64, 1, 1, 9, 9],
            [0, 3, 0, 0, 3, 3],
            [false, false, true, true, true, true],
        ),
    ];

    for (order, payloads, ttls, duplicate_offsets, fake_roles) in cases {
        for sequence_mode in [FakeSeqMode::Duplicate, FakeSeqMode::Sequential] {
            let emissions =
                ordered_disorder_emissions(order, b"ABC", &[b"abc", b"XYZ"], b"DE", &[b"de", b"UV"], 64, options());
            let segments = ordered_segments_from_emissions(&emissions, sequence_mode);
            assert_eq!(segments.len(), 6);
            let mut fake_index = 0;
            for (index, segment) in segments.iter().enumerate() {
                assert_eq!(segment.payload, payloads[index], "order={order:?}");
                assert_eq!(segment.ttl, ttls[index]);
                assert_eq!(segment.use_fake_timestamp, fake_roles[index]);
                let expected_offset = if sequence_mode == FakeSeqMode::Sequential && fake_roles[index] {
                    let offset = [0, 3, 6, 8][fake_index];
                    fake_index += 1;
                    offset
                } else {
                    duplicate_offsets[index]
                };
                assert_eq!(segment.sequence_offset, expected_offset);
            }
            let genuine = segments
                .iter()
                .filter(|segment| !segment.use_fake_timestamp)
                .flat_map(|segment| segment.payload.iter().copied())
                .collect::<Vec<_>>();
            assert_eq!(genuine, b"ABCDE");
        }
    }
}

#[test]
fn primary_only_payload_bytes_keep_the_existing_order() {
    let emissions =
        ordered_disorder_emissions(FakeOrder::BeforeEach, b"ABC", &[b"abc"], b"DE", &[b"de"], 64, options());
    let payloads = emissions.iter().map(|emission| emission.payload).collect::<Vec<_>>();
    assert_eq!(payloads, vec![b"abc".as_slice(), b"ABC", b"de", b"DE"]);
}
