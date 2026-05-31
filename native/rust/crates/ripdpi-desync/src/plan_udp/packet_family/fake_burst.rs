use ripdpi_config::{DesyncGroup, EntropyMode, QuicFakeProfile};
use ripdpi_packets::{
    build_realistic_quic_initial, default_fake_quic_compat,
    entropy::{generate_entropy_padding, generate_shannon_padding},
    udp_fake_profile_bytes,
};

use crate::types::ActivationContext;

use super::super::quic::{effective_quic_realistic_host, NormalizedQuicPlannerInput};

pub(super) fn udp_fake_payload(
    group: &DesyncGroup,
    _payload: &[u8],
    context: ActivationContext,
    normalized_quic: Option<&NormalizedQuicPlannerInput>,
) -> Vec<u8> {
    let quic_fake_profile = context.adaptive.quic_fake_profile.unwrap_or(group.actions.quic_fake_profile);
    if quic_fake_profile != QuicFakeProfile::Disabled {
        if let Some(quic) = normalized_quic {
            match quic_fake_profile {
                QuicFakeProfile::Disabled => {}
                QuicFakeProfile::CompatDefault => return default_fake_quic_compat(),
                QuicFakeProfile::RealisticInitial => {
                    if let Some(fake) = build_realistic_quic_initial(
                        quic.version,
                        effective_quic_realistic_host(group, normalized_quic),
                    ) {
                        return fake;
                    }
                }
                _ => {}
            }
        }
    }

    let mut fake = group
        .actions
        .fake_data
        .clone()
        .unwrap_or_else(|| udp_fake_profile_bytes(group.actions.udp_fake_profile).to_vec());
    if let Some(offset) = group.actions.fake_offset {
        if let Some(pos) = offset.absolute_positive().filter(|pos| (*pos as usize) < fake.len()) {
            fake = fake[pos as usize..].to_vec();
        }
    }

    apply_entropy_padding_to_fake(group, fake)
}

/// Prepend entropy-lowering padding to a fake UDP payload when the config
/// calls for it.  The padding bytes come first so the combined buffer's
/// measured entropy is at or below the requested target.
fn apply_entropy_padding_to_fake(group: &DesyncGroup, fake: Vec<u8>) -> Vec<u8> {
    let entropy_mode = context_entropy_mode(group);
    if entropy_mode == EntropyMode::Disabled {
        return fake;
    }

    let max_pad = group.actions.entropy_padding_max as usize;

    let pad = match entropy_mode {
        EntropyMode::Disabled => return fake,
        EntropyMode::Popcount => {
            let target = group.actions.entropy_padding_target_permil.map_or(3.4_f32, |p| p as f32 / 1000.0);
            generate_entropy_padding(&fake, target, max_pad)
        }
        EntropyMode::Shannon => {
            let target = group.actions.shannon_entropy_target_permil.map_or(7.92_f32, |p| p as f32 / 1000.0);
            generate_shannon_padding(&fake, target, max_pad)
        }
        EntropyMode::Combined => {
            let popcount_target = group.actions.entropy_padding_target_permil.map_or(3.4_f32, |p| p as f32 / 1000.0);
            let shannon_target = group.actions.shannon_entropy_target_permil.map_or(7.92_f32, |p| p as f32 / 1000.0);
            // Apply whichever padding is larger (more defensive).
            let pop_pad = generate_entropy_padding(&fake, popcount_target, max_pad);
            let sha_pad = generate_shannon_padding(&fake, shannon_target, max_pad);
            if sha_pad.len() >= pop_pad.len() {
                sha_pad
            } else {
                pop_pad
            }
        }
        // EntropyMode is #[non_exhaustive]; no padding for unknown future variants.
        _ => return fake,
    };

    if pad.is_empty() {
        return fake;
    }

    let mut out = pad;
    out.extend_from_slice(&fake);
    out
}

fn context_entropy_mode(group: &DesyncGroup) -> EntropyMode {
    group.actions.entropy_mode
}
