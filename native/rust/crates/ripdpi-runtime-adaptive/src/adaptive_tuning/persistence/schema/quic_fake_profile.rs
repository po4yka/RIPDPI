use ripdpi_config::QuicFakeProfile;

use super::stored::StoredQuicFakeProfile;

pub(super) fn restore_quic_fake_profile(profile: StoredQuicFakeProfile) -> Option<QuicFakeProfile> {
    Some(match profile {
        StoredQuicFakeProfile::Disabled => QuicFakeProfile::Disabled,
        StoredQuicFakeProfile::CompatDefault => QuicFakeProfile::CompatDefault,
        StoredQuicFakeProfile::RealisticInitial => QuicFakeProfile::RealisticInitial,
    })
}

impl From<QuicFakeProfile> for StoredQuicFakeProfile {
    fn from(profile: QuicFakeProfile) -> Self {
        match profile {
            QuicFakeProfile::Disabled => Self::Disabled,
            QuicFakeProfile::CompatDefault => Self::CompatDefault,
            QuicFakeProfile::RealisticInitial => Self::RealisticInitial,
            _ => Self::Disabled,
        }
    }
}
