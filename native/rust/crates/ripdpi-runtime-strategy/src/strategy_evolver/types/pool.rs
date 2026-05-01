use ripdpi_config::{EntropyMode, OffsetBase, QuicFakeProfile};
use ripdpi_desync::{AdaptiveTlsRandRecProfile, AdaptiveUdpBurstProfile};

use super::identity::StrategyCombo;

/// Pre-defined pool entry covering all 7 adaptive dimensions.
pub(crate) struct PoolEntry {
    pub(crate) split_offset_base: Option<OffsetBase>,
    pub(crate) tls_record_offset_base: Option<OffsetBase>,
    pub(crate) tlsrandrec_profile: Option<AdaptiveTlsRandRecProfile>,
    pub(crate) udp_burst_profile: Option<AdaptiveUdpBurstProfile>,
    pub(crate) quic_fake_profile: Option<QuicFakeProfile>,
    pub(crate) fake_ttl: Option<u8>,
    pub(crate) entropy_mode: Option<EntropyMode>,
}

impl PoolEntry {
    pub(crate) const fn new() -> Self {
        Self {
            split_offset_base: None,
            tls_record_offset_base: None,
            tlsrandrec_profile: None,
            udp_burst_profile: None,
            quic_fake_profile: None,
            fake_ttl: None,
            entropy_mode: None,
        }
    }
}

/// Pre-defined pool of combos to explore across all 7 dimensions.
pub(crate) const COMBO_POOL: &[PoolEntry] = &[
    PoolEntry::new(),
    PoolEntry { split_offset_base: Some(OffsetBase::AutoHost), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::AutoHost), fake_ttl: Some(6), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::AutoHost), fake_ttl: Some(8), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::AutoHost), fake_ttl: Some(10), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::MidSld), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::MidSld), fake_ttl: Some(6), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::MidSld), fake_ttl: Some(8), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::MidSld), fake_ttl: Some(10), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::EndHost), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::EndHost), fake_ttl: Some(6), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::EndHost), fake_ttl: Some(8), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::EndHost), fake_ttl: Some(10), ..PoolEntry::new() },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        entropy_mode: Some(EntropyMode::Shannon),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        fake_ttl: Some(8),
        entropy_mode: Some(EntropyMode::Shannon),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        entropy_mode: Some(EntropyMode::Combined),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Tight),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Wide),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Balanced),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Aggressive),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        quic_fake_profile: Some(QuicFakeProfile::CompatDefault),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        quic_fake_profile: Some(QuicFakeProfile::RealisticInitial),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::EndHost),
        quic_fake_profile: Some(QuicFakeProfile::CompatDefault),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        tls_record_offset_base: Some(OffsetBase::EndHost),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        tls_record_offset_base: Some(OffsetBase::SniExt),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::EndHost),
        tls_record_offset_base: Some(OffsetBase::AutoBalanced),
        ..PoolEntry::new()
    },
    PoolEntry { split_offset_base: Some(OffsetBase::EchExt), ..PoolEntry::new() },
    PoolEntry { tls_record_offset_base: Some(OffsetBase::EchExt), ..PoolEntry::new() },
    PoolEntry { split_offset_base: Some(OffsetBase::EchExt), fake_ttl: Some(8), ..PoolEntry::new() },
    PoolEntry {
        split_offset_base: Some(OffsetBase::AutoHost),
        fake_ttl: Some(8),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Tight),
        udp_burst_profile: Some(AdaptiveUdpBurstProfile::Conservative),
        ..PoolEntry::new()
    },
    PoolEntry {
        split_offset_base: Some(OffsetBase::MidSld),
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Wide),
        quic_fake_profile: Some(QuicFakeProfile::RealisticInitial),
        entropy_mode: Some(EntropyMode::Shannon),
        ..PoolEntry::new()
    },
];

pub(crate) fn combo_from_pool(index: usize) -> StrategyCombo {
    let entry = &COMBO_POOL[index % COMBO_POOL.len()];
    StrategyCombo {
        split_offset_base: entry.split_offset_base,
        tls_record_offset_base: entry.tls_record_offset_base,
        tlsrandrec_profile: entry.tlsrandrec_profile,
        udp_burst_profile: entry.udp_burst_profile,
        quic_fake_profile: entry.quic_fake_profile,
        fake_ttl: entry.fake_ttl,
        entropy_mode: entry.entropy_mode,
    }
}
