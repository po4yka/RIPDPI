use super::*;

#[test]
fn fake_ordering_plain_fake_collapses_expected_sides() {
    let before = build_plain_fake_emissions(
        FakeOrder::AllFakesFirst,
        b"real",
        &[b"fake-a".as_slice(), b"fake-b".as_slice()],
        7,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );
    let after = build_plain_fake_emissions(
        FakeOrder::AllRealsFirst,
        b"real",
        &[b"fake-a".as_slice(), b"fake-b".as_slice()],
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
}

#[test]
fn fake_ordering_split_variants_emit_expected_order() {
    let emissions = build_ordered_fake_split_emissions(
        FakeOrder::AllFakesFirst,
        b"A",
        b"FA",
        b"B",
        b"FB",
        3,
        9,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );

    let labels = emissions.iter().map(|emission| std::str::from_utf8(emission.payload).unwrap()).collect::<Vec<_>>();

    assert_eq!(labels, vec!["FA", "FB", "A", "B"]);
    assert_eq!(emissions[2].ttl, 3);
    assert_eq!(emissions[3].original_offset, 1);
}

#[test]
fn fake_sequence_sequential_advances_only_fake_offsets() {
    let emissions = build_ordered_fake_split_emissions(
        FakeOrder::BeforeEach,
        b"AAA",
        b"F1",
        b"BBBB",
        b"F222",
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
