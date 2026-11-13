use super::*;

#[test]
fn tlsrandrec_adaptive_profiles_preserve_saved_fragment_bounds() {
    let input = complete_fake_tls();
    let payload_len = input.len() - 5;

    for (min, max, count, tail_len) in [(24, 40, 3, 96), (8, 16, 4, 64), (1, 4096, 4, 96), (24, 40, 3, 72)] {
        let marker = payload_len - tail_len;
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain = vec![tlsrandrec_step(marker as i64, count, min, max)];

        for profile in
            [AdaptiveTlsRandRecProfile::Balanced, AdaptiveTlsRandRecProfile::Tight, AdaptiveTlsRandRecProfile::Wide]
        {
            let mut context = tcp_context(&input);
            context.adaptive.tlsrandrec_profile = Some(profile);

            for seed in 0..64 {
                let output = apply_tls_prelude_steps(&group, &group.actions.tcp_chain, &input, seed, context)
                    .expect("valid prelude");
                assert_eq!(output.tls_prelude.applied_count, 1, "{profile:?}, {min}..{max}, seed {seed}");

                let mut payload = Vec::new();
                let mut lengths = Vec::new();
                let mut records = output.bytes.as_slice();
                while !records.is_empty() {
                    assert!(records.len() >= 5, "complete record header");
                    assert_eq!(&records[..3], &input[..3], "record envelope");
                    let len = usize::from(u16::from_be_bytes([records[3], records[4]]));
                    assert!(len > 0 && records.len() >= 5 + len, "complete nonempty record");
                    payload.extend_from_slice(&records[5..5 + len]);
                    lengths.push(len);
                    records = &records[5 + len..];
                }

                assert_eq!(payload, input[5..], "handshake bytes");
                assert_eq!(lengths.len(), count as usize + 1);
                assert_eq!(lengths[0], marker);
                assert!(
                    lengths[1..].iter().all(|&len| (min as usize..=max as usize).contains(&len)),
                    "{profile:?}, {min}..{max}, seed {seed}: {lengths:?}"
                );
            }
        }
    }
}

#[test]
fn tlsrandrec_adaptive_profiles_skip_infeasible_saved_bounds() {
    let input = complete_fake_tls();
    let marker = (input.len() - 5 - 96) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![tlsrandrec_step(marker, 4, 8, 16)];

    for profile in
        [AdaptiveTlsRandRecProfile::Balanced, AdaptiveTlsRandRecProfile::Tight, AdaptiveTlsRandRecProfile::Wide]
    {
        let mut context = tcp_context(&input);
        context.adaptive.tlsrandrec_profile = Some(profile);
        let output =
            apply_tls_prelude_steps(&group, &group.actions.tcp_chain, &input, 7, context).expect("infeasible prelude");

        assert_eq!(output.bytes, input, "{profile:?}");
        assert_eq!(output.tls_prelude.applied_count, 0, "{profile:?}");
    }
}
