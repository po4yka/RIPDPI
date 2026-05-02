use ripdpi_desync::AdaptiveTlsRandRecProfile;

use super::stored::StoredAdaptiveTlsRandRecProfile;

pub(super) fn restore_tlsrandrec_profile(
    profile: StoredAdaptiveTlsRandRecProfile,
) -> Option<AdaptiveTlsRandRecProfile> {
    Some(match profile {
        StoredAdaptiveTlsRandRecProfile::Balanced => AdaptiveTlsRandRecProfile::Balanced,
        StoredAdaptiveTlsRandRecProfile::Tight => AdaptiveTlsRandRecProfile::Tight,
        StoredAdaptiveTlsRandRecProfile::Wide => AdaptiveTlsRandRecProfile::Wide,
    })
}

impl From<AdaptiveTlsRandRecProfile> for StoredAdaptiveTlsRandRecProfile {
    fn from(profile: AdaptiveTlsRandRecProfile) -> Self {
        match profile {
            AdaptiveTlsRandRecProfile::Balanced => Self::Balanced,
            AdaptiveTlsRandRecProfile::Tight => Self::Tight,
            AdaptiveTlsRandRecProfile::Wide => Self::Wide,
        }
    }
}
