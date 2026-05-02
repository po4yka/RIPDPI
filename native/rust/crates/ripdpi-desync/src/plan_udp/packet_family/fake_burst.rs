use ripdpi_config::{DesyncGroup, QuicFakeProfile};
use ripdpi_packets::{build_realistic_quic_initial, default_fake_quic_compat, udp_fake_profile_bytes};

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
    fake
}
