use ripdpi_config::{FakeOrder, FakeSeqMode, TcpChainStep};

use crate::emissions::{FakeEmission, FakeEmissionRole, build_ordered_fake_split_emissions};
use crate::platform;

pub(super) fn needs_custom_ordering(configured_step: &TcpChainStep, midhost: Option<usize>) -> bool {
    let ordering = configured_step.fake_ordering();
    ordering.seq_mode != FakeSeqMode::Duplicate || (midhost.is_some() && ordering.order != FakeOrder::BeforeEach)
}

/// The default order: the whole fake host, the real host (split at
/// `midhost_split` when set), then the whole fake host again. Both whole-host
/// fakes start at the host's sequence number; real parts keep their stream offsets.
pub(super) fn build_default_ordered_segments<'a>(
    real_host: &'a [u8],
    fake_host: &'a [u8],
    midhost_split: Option<usize>,
    real_ttl: u8,
    fake_ttl: u8,
    fake_flags: platform::TcpFlagOverrides,
    original_flags: platform::TcpFlagOverrides,
) -> Vec<platform::OrderedTcpSegment<'a>> {
    let fake = platform::OrderedTcpSegment {
        payload: fake_host,
        ttl: fake_ttl,
        flags: fake_flags,
        sequence_offset: 0,
        use_fake_timestamp: true,
    };
    let split = midhost_split.unwrap_or(real_host.len());
    let mut segments = vec![fake];
    for (sequence_offset, part) in [(0, &real_host[..split]), (split, &real_host[split..])] {
        if !part.is_empty() {
            segments.push(platform::OrderedTcpSegment {
                payload: part,
                ttl: real_ttl,
                flags: original_flags,
                sequence_offset,
                use_fake_timestamp: false,
            });
        }
    }
    segments.push(fake);
    segments
}

pub(super) fn build_custom_ordered_segments<'a>(
    configured_step: &TcpChainStep,
    real_host: &'a [u8],
    fake_host: &'a [u8],
    midhost_split: Option<usize>,
    real_ttl: u8,
    fake_ttl: u8,
    fake_flags: platform::TcpFlagOverrides,
    original_flags: platform::TcpFlagOverrides,
) -> Vec<platform::OrderedTcpSegment<'a>> {
    let ordering = configured_step.fake_ordering();
    let emissions = build_hostfake_emissions(
        ordering.order,
        real_host,
        fake_host,
        midhost_split,
        real_ttl,
        fake_ttl,
        fake_flags,
        original_flags,
    );
    ordered_segments_from_hostfake_emissions(emissions, ordering.seq_mode)
}

fn build_hostfake_emissions<'a>(
    fake_order: FakeOrder,
    real_host: &'a [u8],
    fake_host: &'a [u8],
    midhost_split: Option<usize>,
    real_ttl: u8,
    fake_ttl: u8,
    fake_flags: platform::TcpFlagOverrides,
    original_flags: platform::TcpFlagOverrides,
) -> Vec<FakeEmission<'a>> {
    if let Some(split) = midhost_split {
        return build_ordered_fake_split_emissions(
            fake_order,
            &real_host[..split],
            &[&fake_host[..split]],
            &real_host[split..],
            &[&fake_host[split..]],
            real_ttl,
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
            ttl: real_ttl,
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

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_config::{OffsetExpr, TcpChainStepKind, TcpFakeOrdering, TcpFakePayload};

    #[test]
    fn default_hostfake_layout_depends_on_sequence_mode_and_midhost() {
        type ExpectedSegment = (&'static [u8], usize, bool);
        let cases: [(Option<usize>, FakeSeqMode, Vec<ExpectedSegment>); 4] = [
            (None, FakeSeqMode::Duplicate, vec![(b"abcde", 0, true), (b"ABCDE", 0, false), (b"abcde", 0, true)]),
            (None, FakeSeqMode::Sequential, vec![(b"abcde", 0, true), (b"ABCDE", 0, false), (b"abcde", 5, true)]),
            (
                Some(2),
                FakeSeqMode::Duplicate,
                vec![(b"abcde", 0, true), (b"AB", 0, false), (b"CDE", 2, false), (b"abcde", 0, true)],
            ),
            (
                Some(2),
                FakeSeqMode::Sequential,
                vec![(b"ab", 0, true), (b"AB", 0, false), (b"cde", 2, true), (b"CDE", 2, false)],
            ),
        ];

        for (midhost, seq_mode, expected) in cases {
            let step = TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::absolute(5))
                .with_midhost_offset(midhost.map(|_| OffsetExpr::absolute(2)))
                .with_fake_payload(TcpFakePayload {
                    ordering: TcpFakeOrdering { order: FakeOrder::BeforeEach, seq_mode },
                    fake_flags: ripdpi_config::TcpFlagOverrides::disabled(),
                    original_flags: ripdpi_config::TcpFlagOverrides::disabled(),
                });
            let flags = platform::TcpFlagOverrides::default();
            let segments = if needs_custom_ordering(&step, midhost) {
                build_custom_ordered_segments(&step, b"ABCDE", b"abcde", midhost, 64, 8, flags, flags)
            } else {
                build_default_ordered_segments(b"ABCDE", b"abcde", midhost, 64, 8, flags, flags)
            };
            let actual = segments
                .iter()
                .map(|segment| (segment.payload, segment.sequence_offset, segment.use_fake_timestamp))
                .collect::<Vec<_>>();
            assert_eq!(actual, expected, "midhost={midhost:?}, seq_mode={seq_mode:?}");
        }
    }
}
