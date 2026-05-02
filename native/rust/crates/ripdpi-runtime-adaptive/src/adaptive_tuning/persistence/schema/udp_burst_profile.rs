use ripdpi_desync::AdaptiveUdpBurstProfile;

use super::stored::StoredAdaptiveUdpBurstProfile;

pub(super) fn restore_udp_burst_profile(profile: StoredAdaptiveUdpBurstProfile) -> Option<AdaptiveUdpBurstProfile> {
    Some(match profile {
        StoredAdaptiveUdpBurstProfile::Balanced => AdaptiveUdpBurstProfile::Balanced,
        StoredAdaptiveUdpBurstProfile::Conservative => AdaptiveUdpBurstProfile::Conservative,
        StoredAdaptiveUdpBurstProfile::Aggressive => AdaptiveUdpBurstProfile::Aggressive,
    })
}

impl From<AdaptiveUdpBurstProfile> for StoredAdaptiveUdpBurstProfile {
    fn from(profile: AdaptiveUdpBurstProfile) -> Self {
        match profile {
            AdaptiveUdpBurstProfile::Balanced => Self::Balanced,
            AdaptiveUdpBurstProfile::Conservative => Self::Conservative,
            AdaptiveUdpBurstProfile::Aggressive => Self::Aggressive,
        }
    }
}
