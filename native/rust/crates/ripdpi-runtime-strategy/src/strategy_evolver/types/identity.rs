use std::hash::{Hash, Hasher};

use ripdpi_config::{EntropyMode, OffsetBase, QuicFakeProfile};
use ripdpi_desync::{
    AdaptiveOobBytePlacement, AdaptivePlannerHints, AdaptiveTimingJitterProfile, AdaptiveTlsRandRecProfile,
    AdaptiveUdpBurstProfile,
};

use super::context::StrategyFamily;

/// Snapshot of all adaptive dimensions that together form a single evasion
/// strategy. `None` means "defer to the default planner for that dimension".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StrategyCombo {
    pub split_offset_base: Option<OffsetBase>,
    pub tls_record_offset_base: Option<OffsetBase>,
    pub tlsrandrec_profile: Option<AdaptiveTlsRandRecProfile>,
    pub udp_burst_profile: Option<AdaptiveUdpBurstProfile>,
    pub quic_fake_profile: Option<QuicFakeProfile>,
    pub fake_ttl: Option<u8>,
    pub entropy_mode: Option<EntropyMode>,
    pub timing_jitter_profile: Option<AdaptiveTimingJitterProfile>,
    pub oob_byte_placement: Option<AdaptiveOobBytePlacement>,
}

impl Hash for StrategyCombo {
    fn hash<H: Hasher>(&self, state: &mut H) {
        hash_option_disc(state, 0, self.split_offset_base.map(offset_base_disc));
        hash_option_disc(state, 1, self.tls_record_offset_base.map(offset_base_disc));
        hash_option_disc(state, 2, self.tlsrandrec_profile.map(tls_randrec_disc));
        hash_option_disc(state, 3, self.udp_burst_profile.map(udp_burst_disc));
        hash_option_disc(state, 4, self.quic_fake_profile.map(quic_fake_disc));
        hash_option_disc(state, 5, self.fake_ttl);
        hash_option_disc(state, 6, self.entropy_mode.map(entropy_mode_disc));
        hash_option_disc(state, 7, self.timing_jitter_profile.map(timing_jitter_disc));
        hash_option_disc(state, 8, self.oob_byte_placement.map(oob_placement_disc));
    }
}

impl StrategyCombo {
    pub(crate) fn default_combo() -> Self {
        Self {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: None,
            entropy_mode: None,
            timing_jitter_profile: None,
            oob_byte_placement: None,
        }
    }

    pub(crate) fn to_hints(&self) -> AdaptivePlannerHints {
        AdaptivePlannerHints {
            split_offset_base: self.split_offset_base,
            tls_record_offset_base: self.tls_record_offset_base,
            tlsrandrec_profile: self.tlsrandrec_profile,
            udp_burst_profile: self.udp_burst_profile,
            quic_fake_profile: self.quic_fake_profile,
            entropy_mode: self.entropy_mode,
            timing_jitter_profile: self.timing_jitter_profile,
            oob_byte_placement: self.oob_byte_placement,
        }
    }

    /// Stable total-order key derived from the per-dimension discriminants
    /// (absent dimension = 0xFF). Used to break score ties deterministically
    /// instead of relying on HashMap iteration order.
    pub(crate) fn disc_key(&self) -> [u8; 9] {
        [
            self.split_offset_base.map_or(0xFF, offset_base_disc),
            self.tls_record_offset_base.map_or(0xFF, offset_base_disc),
            self.tlsrandrec_profile.map_or(0xFF, tls_randrec_disc),
            self.udp_burst_profile.map_or(0xFF, udp_burst_disc),
            self.quic_fake_profile.map_or(0xFF, quic_fake_disc),
            self.fake_ttl.unwrap_or(0xFF),
            self.entropy_mode.map_or(0xFF, entropy_mode_disc),
            self.timing_jitter_profile.map_or(0xFF, timing_jitter_disc),
            self.oob_byte_placement.map_or(0xFF, oob_placement_disc),
        ]
    }

    pub(crate) fn family(&self) -> StrategyFamily {
        let dimensions = [
            self.split_offset_base.is_some(),
            self.tls_record_offset_base.is_some(),
            self.tlsrandrec_profile.is_some(),
            self.udp_burst_profile.is_some(),
            self.quic_fake_profile.is_some(),
            self.fake_ttl.is_some(),
            self.entropy_mode.is_some(),
            self.timing_jitter_profile.is_some(),
            self.oob_byte_placement.is_some(),
        ]
        .into_iter()
        .filter(|value| *value)
        .count();
        if dimensions > 1 {
            return StrategyFamily::Mixed;
        }
        if self.entropy_mode.is_some() {
            StrategyFamily::Entropy
        } else if self.oob_byte_placement.is_some() {
            StrategyFamily::OobPlacement
        } else if self.timing_jitter_profile.is_some() {
            StrategyFamily::TimingJitter
        } else if self.fake_ttl.is_some() {
            StrategyFamily::FakeTtl
        } else if self.quic_fake_profile.is_some() {
            StrategyFamily::QuicFake
        } else if self.udp_burst_profile.is_some() {
            StrategyFamily::UdpBurst
        } else if self.tlsrandrec_profile.is_some() {
            StrategyFamily::TlsRandRec
        } else if self.tls_record_offset_base.is_some() {
            StrategyFamily::TlsRecordOffset
        } else if self.split_offset_base.is_some() {
            StrategyFamily::SplitOffset
        } else {
            StrategyFamily::Baseline
        }
    }
}

/// Discriminant reserved for variants added to the non_exhaustive
/// `ripdpi-config` / `ripdpi-desync` enums after this mapping was written.
/// Distinct from every known variant and from the 0xFF "absent dimension"
/// sentinel, so future variants cannot collide with `Disabled` in the Hash
/// impl or the canonical shared-priors key; unknown variants may only
/// collide with each other until their arms are written.
pub(crate) const UNKNOWN_VARIANT_DISC: u8 = 0xFE;

pub(crate) fn offset_base_disc(o: OffsetBase) -> u8 {
    match o {
        OffsetBase::Abs => 0,
        OffsetBase::PayloadEnd => 1,
        OffsetBase::PayloadMid => 2,
        OffsetBase::PayloadRand => 3,
        OffsetBase::Host => 4,
        OffsetBase::EndHost => 5,
        OffsetBase::HostMid => 6,
        OffsetBase::HostRand => 7,
        OffsetBase::Sld => 8,
        OffsetBase::MidSld => 9,
        OffsetBase::EndSld => 10,
        OffsetBase::Method => 11,
        OffsetBase::ExtLen => 12,
        OffsetBase::SniExt => 13,
        OffsetBase::EchExt => 21,
        OffsetBase::AutoBalanced => 14,
        OffsetBase::AutoHost => 15,
        OffsetBase::AutoMidSld => 16,
        OffsetBase::AutoEndHost => 17,
        OffsetBase::AutoMethod => 18,
        OffsetBase::AutoSniExt => 19,
        OffsetBase::AutoExtLen => 20,
    }
}

pub(crate) fn quic_fake_disc(q: QuicFakeProfile) -> u8 {
    match q {
        QuicFakeProfile::Disabled => 0,
        QuicFakeProfile::CompatDefault => 1,
        QuicFakeProfile::RealisticInitial => 2,
        _ => UNKNOWN_VARIANT_DISC,
    }
}

pub(crate) fn tls_randrec_disc(t: AdaptiveTlsRandRecProfile) -> u8 {
    match t {
        AdaptiveTlsRandRecProfile::Balanced => 0,
        AdaptiveTlsRandRecProfile::Tight => 1,
        AdaptiveTlsRandRecProfile::Wide => 2,
    }
}

pub(crate) fn udp_burst_disc(u: AdaptiveUdpBurstProfile) -> u8 {
    match u {
        AdaptiveUdpBurstProfile::Balanced => 0,
        AdaptiveUdpBurstProfile::Conservative => 1,
        AdaptiveUdpBurstProfile::Aggressive => 2,
    }
}

pub(crate) fn entropy_mode_disc(e: EntropyMode) -> u8 {
    match e {
        EntropyMode::Disabled => 0,
        EntropyMode::Popcount => 1,
        EntropyMode::Shannon => 2,
        EntropyMode::Combined => 3,
        _ => UNKNOWN_VARIANT_DISC,
    }
}

pub(crate) fn timing_jitter_disc(profile: AdaptiveTimingJitterProfile) -> u8 {
    match profile {
        AdaptiveTimingJitterProfile::Conservative => 0,
        AdaptiveTimingJitterProfile::Balanced => 1,
        AdaptiveTimingJitterProfile::Aggressive => 2,
    }
}

pub(crate) fn oob_placement_disc(placement: AdaptiveOobBytePlacement) -> u8 {
    match placement {
        AdaptiveOobBytePlacement::PreHandshake => 0,
        AdaptiveOobBytePlacement::PostSni => 1,
        AdaptiveOobBytePlacement::MidPayload => 2,
    }
}

fn hash_option_disc<H: Hasher>(h: &mut H, tag: u8, disc: Option<u8>) {
    h.write_u8(tag);
    match disc {
        None => h.write_u8(0xFF),
        Some(d) => {
            h.write_u8(0);
            h.write_u8(d);
        }
    }
}
