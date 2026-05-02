use ripdpi_config::OffsetBase;

use super::stored::StoredOffsetBase;

pub(in crate::adaptive_tuning) fn restore_offset_base(base: StoredOffsetBase) -> Option<OffsetBase> {
    Some(match base {
        StoredOffsetBase::Abs => OffsetBase::Abs,
        StoredOffsetBase::PayloadEnd => OffsetBase::PayloadEnd,
        StoredOffsetBase::PayloadMid => OffsetBase::PayloadMid,
        StoredOffsetBase::PayloadRand => OffsetBase::PayloadRand,
        StoredOffsetBase::Host => OffsetBase::Host,
        StoredOffsetBase::EndHost => OffsetBase::EndHost,
        StoredOffsetBase::HostMid => OffsetBase::HostMid,
        StoredOffsetBase::HostRand => OffsetBase::HostRand,
        StoredOffsetBase::Sld => OffsetBase::Sld,
        StoredOffsetBase::MidSld => OffsetBase::MidSld,
        StoredOffsetBase::EndSld => OffsetBase::EndSld,
        StoredOffsetBase::Method => OffsetBase::Method,
        StoredOffsetBase::ExtLen => OffsetBase::ExtLen,
        StoredOffsetBase::EchExt => OffsetBase::EchExt,
        StoredOffsetBase::SniExt => OffsetBase::SniExt,
        StoredOffsetBase::AutoBalanced => OffsetBase::AutoBalanced,
        StoredOffsetBase::AutoHost => OffsetBase::AutoHost,
        StoredOffsetBase::AutoMidSld => OffsetBase::AutoMidSld,
        StoredOffsetBase::AutoEndHost => OffsetBase::AutoEndHost,
        StoredOffsetBase::AutoMethod => OffsetBase::AutoMethod,
        StoredOffsetBase::AutoSniExt => OffsetBase::AutoSniExt,
        StoredOffsetBase::AutoExtLen => OffsetBase::AutoExtLen,
    })
}

impl From<OffsetBase> for StoredOffsetBase {
    fn from(base: OffsetBase) -> Self {
        match base {
            OffsetBase::Abs => Self::Abs,
            OffsetBase::PayloadEnd => Self::PayloadEnd,
            OffsetBase::PayloadMid => Self::PayloadMid,
            OffsetBase::PayloadRand => Self::PayloadRand,
            OffsetBase::Host => Self::Host,
            OffsetBase::EndHost => Self::EndHost,
            OffsetBase::HostMid => Self::HostMid,
            OffsetBase::HostRand => Self::HostRand,
            OffsetBase::Sld => Self::Sld,
            OffsetBase::MidSld => Self::MidSld,
            OffsetBase::EndSld => Self::EndSld,
            OffsetBase::Method => Self::Method,
            OffsetBase::ExtLen => Self::ExtLen,
            OffsetBase::EchExt => Self::EchExt,
            OffsetBase::SniExt => Self::SniExt,
            OffsetBase::AutoBalanced => Self::AutoBalanced,
            OffsetBase::AutoHost => Self::AutoHost,
            OffsetBase::AutoMidSld => Self::AutoMidSld,
            OffsetBase::AutoEndHost => Self::AutoEndHost,
            OffsetBase::AutoMethod => Self::AutoMethod,
            OffsetBase::AutoSniExt => Self::AutoSniExt,
            OffsetBase::AutoExtLen => Self::AutoExtLen,
        }
    }
}
