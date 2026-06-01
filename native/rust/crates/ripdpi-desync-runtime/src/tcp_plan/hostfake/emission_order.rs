use ripdpi_config::{FakeOrder, FakeSeqMode, TcpChainStep};

use crate::emissions::{FakeEmission, FakeEmissionRole, build_ordered_fake_split_emissions};
use crate::platform;

pub(super) fn needs_custom_ordering(configured_step: &TcpChainStep, midhost: Option<usize>) -> bool {
    let ordering = configured_step.fake_ordering();
    ordering.seq_mode != FakeSeqMode::Duplicate || (midhost.is_some() && ordering.order != FakeOrder::BeforeEach)
}

pub(super) fn build_custom_ordered_segments<'a>(
    fake_order: FakeOrder,
    fake_seq_mode: FakeSeqMode,
    real_host: &'a [u8],
    fake_host: &'a [u8],
    midhost_split: Option<usize>,
    fake_ttl: u8,
    fake_flags: platform::TcpFlagOverrides,
    original_flags: platform::TcpFlagOverrides,
) -> Vec<platform::OrderedTcpSegment<'a>> {
    let emissions =
        build_hostfake_emissions(fake_order, real_host, fake_host, midhost_split, fake_ttl, fake_flags, original_flags);
    ordered_segments_from_hostfake_emissions(emissions, fake_seq_mode)
}

fn build_hostfake_emissions<'a>(
    fake_order: FakeOrder,
    real_host: &'a [u8],
    fake_host: &'a [u8],
    midhost_split: Option<usize>,
    fake_ttl: u8,
    fake_flags: platform::TcpFlagOverrides,
    original_flags: platform::TcpFlagOverrides,
) -> Vec<FakeEmission<'a>> {
    if let Some(split) = midhost_split {
        return build_ordered_fake_split_emissions(
            fake_order,
            &real_host[..split],
            &fake_host[..split],
            &real_host[split..],
            &fake_host[split..],
            fake_ttl,
            fake_ttl,
            fake_flags,
            original_flags,
        );
    }

    vec![
        FakeEmission {
            role: FakeEmissionRole::Fake,
            payload: fake_host,
            ttl: fake_ttl,
            flags: fake_flags,
            original_offset: 0,
        },
        FakeEmission {
            role: FakeEmissionRole::Genuine,
            payload: real_host,
            ttl: fake_ttl,
            flags: original_flags,
            original_offset: 0,
        },
        FakeEmission {
            role: FakeEmissionRole::Fake,
            payload: fake_host,
            ttl: fake_ttl,
            flags: fake_flags,
            original_offset: 0,
        },
    ]
}

fn ordered_segments_from_hostfake_emissions<'a>(
    emissions: Vec<FakeEmission<'a>>,
    fake_seq_mode: FakeSeqMode,
) -> Vec<platform::OrderedTcpSegment<'a>> {
    let mut fake_sequence_offset = 0usize;
    emissions
        .into_iter()
        .map(|emission| {
            let sequence_offset = match emission.role {
                FakeEmissionRole::Genuine => emission.original_offset,
                FakeEmissionRole::Fake => match fake_seq_mode {
                    FakeSeqMode::Duplicate => emission.original_offset,
                    FakeSeqMode::Sequential => {
                        let current = fake_sequence_offset;
                        fake_sequence_offset = fake_sequence_offset.saturating_add(emission.payload.len());
                        current
                    }
                    _ => emission.original_offset,
                },
            };
            platform::OrderedTcpSegment {
                payload: emission.payload,
                ttl: emission.ttl,
                flags: emission.flags,
                sequence_offset,
                use_fake_timestamp: emission.role == FakeEmissionRole::Fake,
            }
        })
        .collect()
}
