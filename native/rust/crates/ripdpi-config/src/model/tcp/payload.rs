mod fake;
mod fake_accessors;
mod flags;
mod fragments;
mod host_fake;
mod invariant;
mod seq_overlap;
mod tls_prelude;
mod typed;

pub use fake::{TcpFakeOrdering, TcpFakePayload};
pub use flags::TcpFlagOverrides;
pub use fragments::{TcpIpFragPayload, TcpIpv6ExtensionPayload};
pub use host_fake::TcpHostFakePayload;
pub use invariant::TcpStepPayloadInvariantError;
pub use seq_overlap::TcpSeqOverlapPayload;
pub use tls_prelude::TcpTlsRandRecPayload;
pub(crate) use typed::TcpStepPayloadStorage;
pub use typed::{TcpStepCommon, TcpStepPayload, TcpTypedChainStep};

#[cfg(test)]
mod tests {
    use super::*;

    use crate::{FakeOrder, FakeSeqMode, OffsetBase, OffsetExpr, SeqOverlapFakeMode, TcpChainStep, TcpChainStepKind};

    #[test]
    fn seq_overlap_payload_round_trips_through_compatible_fields() {
        let payload = TcpSeqOverlapPayload {
            overlap_size: 16,
            fake_mode: SeqOverlapFakeMode::Rand,
            fake_flags: TcpFlagOverrides { set: Some(0x18), unset: Some(0x02) },
        };
        let step = TcpChainStep::new(TcpChainStepKind::SeqOverlap, OffsetExpr::marker(OffsetBase::Host, 2))
            .with_seq_overlap_payload(payload);

        assert_eq!(step.seq_overlap_payload(), Some(payload));
        assert_eq!(step.fake_flag_overrides(), TcpFlagOverrides { set: Some(0x18), unset: Some(0x02) });
    }

    #[test]
    fn payload_accessors_are_variant_specific() {
        let step = TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(3));

        assert_eq!(step.seq_overlap_payload(), None);
        assert_eq!(step.host_fake_payload(), None);
        assert_eq!(step.tls_randrec_payload(), None);
        assert_eq!(step.ip_frag_payload(), None);
    }

    #[test]
    fn host_fake_payload_borrows_compatible_fields() {
        let step = TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::Host, 1))
            .with_midhost_offset(Some(OffsetExpr::marker(OffsetBase::HostMid, 0)))
            .with_fake_host_template(Some("cdn.example".to_string()))
            .with_random_fake_host(true)
            .with_fake_payload(TcpFakePayload {
                ordering: TcpFakeOrdering { order: FakeOrder::AllFakesFirst, seq_mode: FakeSeqMode::Sequential },
                fake_flags: TcpFlagOverrides::disabled(),
                original_flags: TcpFlagOverrides::disabled(),
            });

        let payload = step.host_fake_payload().expect("hostfake payload");

        assert_eq!(payload.midhost_offset, Some(OffsetExpr::marker(OffsetBase::HostMid, 0)));
        assert_eq!(payload.fake_host_template, Some("cdn.example"));
        assert!(payload.random_fake_host);
        assert_eq!(
            payload.ordering,
            TcpFakeOrdering { order: FakeOrder::AllFakesFirst, seq_mode: FakeSeqMode::Sequential }
        );
    }

    #[test]
    fn ip_frag_payload_groups_fragment_and_ipv6_fields() {
        let payload = TcpIpFragPayload {
            fragment_count: 2,
            min_fragment_size: 8,
            max_fragment_size: 32,
            disorder: true,
            ipv6_extensions: TcpIpv6ExtensionPayload {
                hop_by_hop: true,
                dest_opt: true,
                dest_opt2: false,
                routing: true,
                second_frag_next_override: Some(6),
            },
        };
        let step = TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::absolute(5)).with_ip_frag_payload(payload);

        assert_eq!(step.ip_frag_payload(), Some(payload));
        assert_eq!(step.ipv6_extension_payload(), payload.ipv6_extensions);
    }

    #[test]
    fn typed_payload_separates_step_families() {
        let seq = TcpChainStep::new(TcpChainStepKind::SeqOverlap, OffsetExpr::absolute(3));
        assert!(matches!(seq.typed_payload(), TcpStepPayload::SeqOverlap(_)));

        let split = TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(3));
        assert_eq!(split.typed_payload(), TcpStepPayload::Plain);
    }

    #[test]
    fn typed_step_projects_plain_step() {
        let step = TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(3));

        assert!(matches!(step.try_typed_step(), Ok(TcpTypedChainStep::Plain { .. })));
    }

    #[test]
    fn typed_step_ignores_hostfake_payload_on_fake_step() {
        let step = TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::absolute(3))
            .with_fake_host_template(Some("example.invalid".to_string()));

        assert_eq!(step.host_fake_payload(), None);
        assert!(matches!(step.try_typed_step(), Ok(TcpTypedChainStep::Fake { .. })));
    }

    #[test]
    fn typed_step_projects_ipfrag_without_other_payload_families() {
        let payload = TcpIpFragPayload {
            fragment_count: 2,
            min_fragment_size: 8,
            max_fragment_size: 32,
            disorder: true,
            ipv6_extensions: TcpIpv6ExtensionPayload { hop_by_hop: true, ..Default::default() },
        };
        let step = TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::absolute(3)).with_ip_frag_payload(payload);

        let typed = step.try_typed_step().expect("typed ipfrag");

        assert!(matches!(typed, TcpTypedChainStep::IpFrag { payload: typed_payload, .. } if typed_payload == payload));
    }

    #[test]
    fn typed_step_constructor_owns_hostfake_template() {
        let common = TcpStepCommon::new(OffsetExpr::marker(OffsetBase::Host, 1));
        let template = String::from("cdn.example");
        let step = TcpChainStep::from_typed_step(TcpTypedChainStep::HostFake {
            common,
            payload: TcpHostFakePayload {
                midhost_offset: Some(OffsetExpr::marker(OffsetBase::HostMid, 0)),
                fake_host_template: Some(template.as_str()),
                random_fake_host: false,
                ordering: TcpFakeOrdering::before_each_duplicate(),
                fake_flags: TcpFlagOverrides::disabled(),
                original_flags: TcpFlagOverrides::disabled(),
            },
        });
        drop(template);

        assert_eq!(step.fake_host_template(), Some("cdn.example"));
    }
}
