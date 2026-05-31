use super::*;

#[test]
fn entropy_padding_disabled_returns_borrowed() {
    let group = test_group(); // entropy_mode defaults to Disabled
    let payload = b"test payload";
    let result = apply_entropy_padding(&group, payload, None);
    assert!(matches!(result, Cow::Borrowed(_)));
    assert_eq!(&*result, payload);
}

#[test]
fn entropy_padding_popcount_mode_pads_non_exempt_payload() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Popcount;
    // 0xAA has popcount 4.0 (in GFW detection window 3.4-4.6)
    let payload = vec![0xAA; 100];
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Owned(_)), "should pad non-exempt payload");
    assert!(result.len() > payload.len(), "padded should be longer");
    // Padded payload should start with padding, end with original
    assert_eq!(&result[result.len() - payload.len()..], &payload[..]);
}

#[test]
fn entropy_padding_popcount_mode_skips_exempt_payload() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Popcount;
    // All zeros: popcount 0.0, already exempt
    let payload = vec![0x00; 100];
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Borrowed(_)), "exempt payload should not be padded");
}

#[test]
fn entropy_padding_shannon_mode_pads_high_entropy() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    // High entropy payload
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Owned(_)), "should pad high-entropy payload");
    assert!(result.len() > payload.len());
}

#[test]
fn entropy_padding_shannon_mode_skips_low_entropy() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    let payload = b"AAAAAAAAAAAAAAAAAAAAA"; // very low entropy
    let result = apply_entropy_padding(&group, payload, None);
    assert!(matches!(result, Cow::Borrowed(_)), "low entropy should not be padded");
}

#[test]
fn entropy_padding_combined_mode_works() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Combined;
    // High entropy: needs Shannon padding
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(result.len() > payload.len(), "combined mode should pad high-entropy");
}

#[test]
fn entropy_padding_adaptive_override_takes_precedence() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Disabled; // group says disabled
                                                        // But adaptive override says Shannon
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, Some(EntropyMode::Shannon));
    assert!(matches!(result, Cow::Owned(_)), "adaptive override should enable padding");
    assert!(result.len() > payload.len());
}

#[test]
fn entropy_padding_adaptive_override_can_disable() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon; // group says Shannon
                                                       // But adaptive override says Disabled
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, Some(EntropyMode::Disabled));
    assert!(matches!(result, Cow::Borrowed(_)), "adaptive Disabled should skip padding");
}

#[test]
fn entropy_padding_custom_shannon_target_permil() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    group.actions.shannon_entropy_target_permil = Some(7920); // 7.92 bits/byte
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Owned(_)));
    // Padded result should bring entropy below 7.92
    let combined_entropy = entropy::shannon_entropy(&result);
    assert!(combined_entropy <= 7.92, "expected <= 7.92, got {combined_entropy}");
}

#[test]
fn entropy_padding_custom_popcount_target_permil() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Popcount;
    group.actions.entropy_padding_target_permil = Some(3200); // 3.2 target
    let payload = vec![0xAA; 100]; // popcount 4.0
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Owned(_)));
    let pc = entropy::popcount_per_byte(&result);
    assert!(pc <= 3.2, "expected popcount <= 3.2, got {pc}");
}

#[test]
fn entropy_padding_preserves_original_payload_at_end() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    let payload: Vec<u8> = (0..512).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    if result.len() > payload.len() {
        let suffix = &result[result.len() - payload.len()..];
        assert_eq!(suffix, &payload[..], "original payload should be at the end");
    }
}

#[test]
fn entropy_padding_respects_max_pad_config() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    group.actions.entropy_padding_max = 10; // very small
    let payload: Vec<u8> = (0..4096).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    // Padding can be at most 10 bytes
    let padding_size = result.len() - payload.len();
    assert!(padding_size <= 10, "padding {padding_size} exceeds max 10");
}

// ---------------------------------------------------------------
// GAP-3 regression guard: entropy padding must not corrupt the
// fake-TLS decoy built for a TCP fake step.
// ---------------------------------------------------------------

/// A genuine TLS ClientHello whose average popcount lands inside the GFW
/// detection window (3.4-4.6 bits/byte), so `EntropyMode::Popcount` actually
/// generates padding. It is `DEFAULT_FAKE_TLS` (a real captured Wikipedia
/// ClientHello, SNI `www.wikipedia.org`) extended with high-popcount filler
/// that completes the record's declared length; the tolerant TLS parser still
/// recognizes it as a ClientHello and still recovers the early SNI.
fn high_popcount_client_hello() -> Vec<u8> {
    let mut hello = ripdpi_packets::DEFAULT_FAKE_TLS.to_vec();
    // Each 0xFF byte contributes popcount 8; 150 of them lift the average of
    // the otherwise low-popcount (~2.5) capture into the detection window.
    hello.extend(std::iter::repeat_n(0xFFu8, 150));
    hello
}

/// Build a group with a captured-ClientHello fake source and a single `Fake`
/// step whose split offset is past the payload end, so the whole fake decoy is
/// emitted. `entropy_mode` is the only varying knob.
fn fake_step_group(entropy_mode: EntropyMode) -> DesyncGroup {
    let mut group = test_group();
    group.actions.fake_tls_source = ripdpi_config::FakePacketSource::CapturedClientHello;
    group.actions.entropy_mode = entropy_mode;
    group.actions.entropy_padding_target_permil = Some(3400);
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(1 << 20))];
    group
}

/// Extract the injected fake-TLS decoy bytes from a planned action list. The
/// fake decoy is the `Write` that immediately follows the fake-TTL `SetTtl`
/// (the genuine chunks are written under the default TTL).
fn extract_fake_decoy(plan: &DesyncPlan, fake_ttl: u8, default_ttl: u8) -> Vec<u8> {
    let mut iter = plan.actions.iter();
    while let Some(action) = iter.next() {
        if matches!(action, DesyncAction::SetTtl(ttl) if *ttl == fake_ttl && *ttl != default_ttl) {
            if let Some(DesyncAction::Write(bytes)) = iter.next() {
                return bytes.clone();
            }
        }
    }
    panic!("no fake decoy Write found in plan actions: {:?}", plan.actions);
}

fn plan_with_padding(group: &DesyncGroup, payload: &[u8], default_ttl: u8) -> DesyncPlan {
    let context = activation_context_from_progress(
        OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() },
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    // Mirror the runtime's entropy-padding decision in `send_prepared_with_group`:
    // padding only reaches the planner when the group carries fake steps, which
    // this group does.
    let effective_payload = apply_entropy_padding(group, payload, None);
    let strategy = TcpDesyncStrategy::new(group, 7, default_ttl, context);
    strategy.plan_with_fake_reference(&effective_payload, payload).expect("tcp plan")
}

#[test]
fn popcount_with_tcp_fake_preserves_fake_fidelity() {
    use ripdpi_packets::{is_tls_client_hello, parse_tls};

    const DEFAULT_TTL: u8 = 64;
    const FAKE_TTL: u8 = 8; // group.actions.ttl is None -> build_fake default 8

    let payload = high_popcount_client_hello();

    // Sanity: the fixture is a genuine ClientHello and its popcount truly sits
    // in the detection window, so Popcount mode WILL pad it. If this drifts the
    // regression guard below becomes meaningless, so assert it loudly.
    assert!(is_tls_client_hello(&payload), "fixture must be a ClientHello");
    assert_eq!(parse_tls(&payload), Some(&b"www.wikipedia.org"[..]), "fixture SNI");
    let pc = entropy::popcount_per_byte(&payload);
    assert!((3.4..4.6).contains(&pc), "fixture popcount {pc} must be in detection window");
    let padded = apply_entropy_padding(&fake_step_group(EntropyMode::Popcount), &payload, None);
    assert!(matches!(padded, Cow::Owned(_)), "Popcount must actually pad this fixture");
    assert!(padded.len() > payload.len());

    // Control: with entropy disabled, the fake decoy is the unpadded captured
    // ClientHello at its natural size.
    let disabled_plan = plan_with_padding(&fake_step_group(EntropyMode::Disabled), &payload, DEFAULT_TTL);
    let disabled_fake = extract_fake_decoy(&disabled_plan, FAKE_TTL, DEFAULT_TTL);

    // With Popcount padding active on a group that also has a TCP fake step.
    let popcount_plan = plan_with_padding(&fake_step_group(EntropyMode::Popcount), &payload, DEFAULT_TTL);
    let popcount_fake = extract_fake_decoy(&popcount_plan, FAKE_TTL, DEFAULT_TTL);

    // (a) the fake decoy is still a valid TLS ClientHello,
    assert!(is_tls_client_hello(&popcount_fake), "fake decoy must stay a ClientHello under Popcount padding");
    // (b) it parses to a host -> the captured-ClientHello path was taken, not
    //     the generic profile fallback (which would have fired had detection
    //     run against the 'A'-prefixed padded buffer),
    assert!(parse_tls(&popcount_fake).is_some(), "fake decoy must expose an SNI host");
    // (c) and its size equals the entropy-disabled fake size -> sizing used the
    //     unpadded ClientHello length, not the inflated padded length.
    assert_eq!(popcount_fake.len(), disabled_fake.len(), "fake decoy length must be independent of entropy padding",);
    // The captured-ClientHello decoy is the unpadded payload itself.
    assert_eq!(popcount_fake, disabled_fake, "fake decoy must be byte-identical regardless of entropy mode");
    assert_eq!(popcount_fake.as_slice(), payload.as_slice(), "captured-CH decoy equals the unpadded payload");
}

#[test]
fn disabled_entropy_with_tcp_fake_decoy_is_unchanged_capture() {
    use ripdpi_packets::{is_tls_client_hello, parse_tls};

    const DEFAULT_TTL: u8 = 64;
    const FAKE_TTL: u8 = 8;

    let payload = high_popcount_client_hello();
    let plan = plan_with_padding(&fake_step_group(EntropyMode::Disabled), &payload, DEFAULT_TTL);
    let fake = extract_fake_decoy(&plan, FAKE_TTL, DEFAULT_TTL);

    assert!(is_tls_client_hello(&fake));
    assert_eq!(parse_tls(&fake), Some(&b"www.wikipedia.org"[..]));
    // No padding -> the captured ClientHello is forwarded verbatim as the decoy.
    assert_eq!(fake.as_slice(), payload.as_slice());
}

// ---------------------------------------------------------------
// Pure helper function tests
// ---------------------------------------------------------------
