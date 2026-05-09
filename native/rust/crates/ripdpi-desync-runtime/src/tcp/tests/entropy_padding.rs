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
// Pure helper function tests
// ---------------------------------------------------------------
