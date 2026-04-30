use ripdpi_config::{FakeOrder, FakeSeqMode};

use crate::platform;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FakeEmissionRole {
    Fake,
    Genuine,
}

#[derive(Debug)]
pub(crate) struct FakeEmission<'a> {
    pub(crate) role: FakeEmissionRole,
    pub(crate) payload: &'a [u8],
    pub(crate) ttl: u8,
    pub(crate) flags: platform::TcpFlagOverrides,
    pub(crate) original_offset: usize,
}

#[allow(clippy::too_many_arguments)]
pub(crate) fn build_ordered_fake_split_emissions<'a>(
    order: FakeOrder,
    first_real: &'a [u8],
    first_fake: &'a [u8],
    second_real: &'a [u8],
    second_fake: &'a [u8],
    first_real_ttl: u8,
    fake_ttl: u8,
    fake_flags: platform::TcpFlagOverrides,
    original_flags: platform::TcpFlagOverrides,
) -> Vec<FakeEmission<'a>> {
    let second_offset = first_real.len();
    let fake_a = FakeEmission {
        role: FakeEmissionRole::Fake,
        payload: first_fake,
        ttl: fake_ttl,
        flags: fake_flags,
        original_offset: 0,
    };
    let real_a = FakeEmission {
        role: FakeEmissionRole::Genuine,
        payload: first_real,
        ttl: first_real_ttl,
        flags: original_flags,
        original_offset: 0,
    };
    let fake_b = FakeEmission {
        role: FakeEmissionRole::Fake,
        payload: second_fake,
        ttl: fake_ttl,
        flags: fake_flags,
        original_offset: second_offset,
    };
    let real_b = FakeEmission {
        role: FakeEmissionRole::Genuine,
        payload: second_real,
        ttl: fake_ttl,
        flags: original_flags,
        original_offset: second_offset,
    };

    match order {
        FakeOrder::BeforeEach => vec![fake_a, real_a, fake_b, real_b],
        FakeOrder::AllFakesFirst => vec![fake_a, fake_b, real_a, real_b],
        FakeOrder::RealFakeRealFake => vec![real_a, fake_a, real_b, fake_b],
        FakeOrder::AllRealsFirst => vec![real_a, real_b, fake_a, fake_b],
        _ => vec![fake_a, real_a, fake_b, real_b],
    }
}

pub(crate) fn build_plain_fake_emissions<'a>(
    order: FakeOrder,
    original: &'a [u8],
    fake_segments: &[&'a [u8]],
    fake_ttl: u8,
    fake_flags: platform::TcpFlagOverrides,
    original_flags: platform::TcpFlagOverrides,
) -> Vec<FakeEmission<'a>> {
    let mut fakes: Vec<FakeEmission<'a>> = fake_segments
        .iter()
        .map(|payload| FakeEmission {
            role: FakeEmissionRole::Fake,
            payload,
            ttl: fake_ttl,
            flags: fake_flags,
            original_offset: 0,
        })
        .collect();
    let original = FakeEmission {
        role: FakeEmissionRole::Genuine,
        payload: original,
        ttl: fake_ttl,
        flags: original_flags,
        original_offset: 0,
    };
    match order {
        FakeOrder::BeforeEach | FakeOrder::AllFakesFirst => {
            fakes.push(original);
            fakes
        }
        FakeOrder::RealFakeRealFake | FakeOrder::AllRealsFirst => {
            let mut result = vec![original];
            result.extend(fakes);
            result
        }
        _ => {
            fakes.push(original);
            fakes
        }
    }
}

pub(crate) fn ordered_segments_from_emissions<'a>(
    emissions: &'a [FakeEmission<'a>],
    fake_seq_mode: FakeSeqMode,
) -> Vec<platform::OrderedTcpSegment<'a>> {
    let mut fake_sequence_offset = 0usize;
    emissions
        .iter()
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
