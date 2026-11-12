use super::*;

#[test]
fn fake_ordering_plain_fake_collapses_expected_sides() {
    let before = build_plain_fake_emissions(
        FakeOrder::AllFakesFirst,
        b"real",
        &[b"fake-a".as_slice(), b"fake-b".as_slice()],
        64,
        7,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );
    let after = build_plain_fake_emissions(
        FakeOrder::AllRealsFirst,
        b"real",
        &[b"fake-a".as_slice(), b"fake-b".as_slice()],
        64,
        7,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );

    assert_eq!(
        before.iter().map(|emission| emission.role).collect::<Vec<_>>(),
        vec![FakeEmissionRole::Fake, FakeEmissionRole::Fake, FakeEmissionRole::Genuine]
    );
    assert_eq!(
        after.iter().map(|emission| emission.role).collect::<Vec<_>>(),
        vec![FakeEmissionRole::Genuine, FakeEmissionRole::Fake, FakeEmissionRole::Fake]
    );
    assert_eq!(before[2].ttl, 64);
    assert_eq!(after[0].ttl, 64);
}

#[test]
fn fake_ordering_split_variants_emit_expected_order() {
    let emissions = build_ordered_fake_split_emissions(
        FakeOrder::AllFakesFirst,
        b"A",
        &[b"FA"],
        b"B",
        &[b"FB"],
        3,
        9,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );

    let labels = emissions.iter().map(|emission| std::str::from_utf8(emission.payload).unwrap()).collect::<Vec<_>>();

    assert_eq!(labels, vec!["FA", "FB", "A", "B"]);
    assert_eq!(emissions[2].ttl, 3);
    assert_eq!(emissions[3].ttl, 3);
    assert_eq!(emissions[3].original_offset, 1);
}

#[test]
fn fake_sequence_sequential_advances_only_fake_offsets() {
    let emissions = build_ordered_fake_split_emissions(
        FakeOrder::BeforeEach,
        b"AAA",
        &[b"F1"],
        b"BBBB",
        &[b"F222"],
        5,
        9,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );

    let segments = ordered_segments_from_emissions(&emissions, FakeSeqMode::Sequential);

    assert_eq!(segments[0].sequence_offset, 0);
    assert_eq!(segments[1].sequence_offset, 0);
    assert_eq!(segments[2].sequence_offset, 2);
    assert_eq!(segments[3].sequence_offset, 3);
}

#[test]
fn fake_split_orders_preserve_each_profile_and_sequence_mode() {
    let cases = [
        (FakeOrder::BeforeEach, ["p1", "s1", "AB", "p22", "s22", "CDE"], [0, 0, 0, 2, 2, 2], [0, 2, 0, 4, 7, 2]),
        (FakeOrder::AllFakesFirst, ["p1", "s1", "p22", "s22", "AB", "CDE"], [0, 0, 2, 2, 0, 2], [0, 2, 4, 7, 0, 2]),
        (FakeOrder::RealFakeRealFake, ["AB", "p1", "s1", "CDE", "p22", "s22"], [0, 0, 0, 2, 2, 2], [0, 0, 2, 2, 4, 7]),
        (FakeOrder::AllRealsFirst, ["AB", "CDE", "p1", "s1", "p22", "s22"], [0, 2, 0, 0, 2, 2], [0, 2, 0, 2, 4, 7]),
    ];
    let fake_flags = platform::TcpFlagOverrides { set: 0x10, unset: 0x08 };
    let original_flags = platform::TcpFlagOverrides { set: 0x08, unset: 0 };
    for (order, expected_payloads, duplicate_offsets, sequential_offsets) in cases {
        let emissions = build_ordered_fake_split_emissions(
            order,
            b"AB",
            &[b"p1", b"s1"],
            b"CDE",
            &[b"p22", b"s22"],
            64,
            8,
            fake_flags,
            original_flags,
        );
        for (mode, expected_offsets) in
            [(FakeSeqMode::Duplicate, duplicate_offsets), (FakeSeqMode::Sequential, sequential_offsets)]
        {
            let segments = ordered_segments_from_emissions(&emissions, mode);
            assert_eq!(segments.len(), 6);
            for (index, segment) in segments.iter().enumerate() {
                assert_eq!(segment.payload, expected_payloads[index].as_bytes(), "{order:?}/{mode:?}");
                assert_eq!(segment.sequence_offset, expected_offsets[index], "{order:?}/{mode:?}");
                if segment.payload == b"AB" || segment.payload == b"CDE" {
                    assert!(!segment.use_fake_timestamp);
                    assert_eq!(segment.ttl, 64);
                    assert_eq!(segment.flags, original_flags);
                } else {
                    assert!(segment.use_fake_timestamp);
                    assert_eq!(segment.ttl, 8);
                    assert_eq!(segment.flags, fake_flags);
                }
            }
        }
    }
}

#[test]
fn tcp_plan_control_fake_family_continues_with_cursor() {
    let configured_step = TcpChainStep::new(TcpChainStepKind::FakeSplit, test_offset());
    let mut cursor = 0usize;

    let result = handle_tcp_plan_step_control(
        TcpChainStepKind::FakeSplit,
        TcpStepControl::ContinueAt(7),
        &configured_step,
        0,
        2,
        20,
        &mut cursor,
    );

    assert!(matches!(result, TcpPlanLoopControl::Continue));
    assert_eq!(cursor, 7);
}

#[test]
fn tcp_plan_control_break_sets_break_cursor() {
    let configured_step = TcpChainStep::new(TcpChainStepKind::IpFrag2, test_offset());
    let mut cursor = 0usize;

    let result = handle_tcp_plan_step_control(
        TcpChainStepKind::IpFrag2,
        TcpStepControl::BreakPlan,
        &configured_step,
        0,
        1,
        42,
        &mut cursor,
    );

    assert!(matches!(result, TcpPlanLoopControl::Break));
    assert_eq!(cursor, 42);
}

#[test]
fn tcp_plan_control_default_continue_advances_to_step_end() {
    let configured_step = TcpChainStep::new(TcpChainStepKind::Split, test_offset());
    let mut cursor = 3usize;

    let result = handle_tcp_plan_step_control(
        TcpChainStepKind::Split,
        TcpStepControl::ContinueAt(9),
        &configured_step,
        0,
        1,
        99,
        &mut cursor,
    );

    assert!(matches!(result, TcpPlanLoopControl::AdvanceToStepEnd));
    assert_eq!(cursor, 3);
}
