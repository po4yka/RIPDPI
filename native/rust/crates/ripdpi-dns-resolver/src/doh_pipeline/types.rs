use hickory_proto::rr::RecordType;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DohBatchRecordType {
    A,
    Aaaa,
    Cname,
    Https,
    Svcb,
}

impl DohBatchRecordType {
    pub(super) const ALL: [Self; 5] = [Self::A, Self::Aaaa, Self::Cname, Self::Https, Self::Svcb];
    pub(super) const ADDRESS: [Self; 2] = [Self::A, Self::Aaaa];
    pub(super) const OPTIONAL: [Self; 3] = [Self::Cname, Self::Https, Self::Svcb];

    pub(super) fn record_type(self) -> RecordType {
        match self {
            Self::A => RecordType::A,
            Self::Aaaa => RecordType::AAAA,
            Self::Cname => RecordType::CNAME,
            Self::Https => RecordType::HTTPS,
            Self::Svcb => RecordType::SVCB,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DohResolverRole {
    Primary,
    Secondary,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DohIpFamily {
    Ipv4,
    Ipv6,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DohBatchRecordResponse {
    pub record_type: DohBatchRecordType,
    pub response_bytes: Vec<u8>,
    pub min_ttl_secs: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DohBatchLookup {
    pub domain: String,
    pub resolver_role: DohResolverRole,
    pub endpoint_label: String,
    pub records: Vec<DohBatchRecordResponse>,
    pub cache_ttl_secs: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DohIpAnswerCandidate {
    pub ip: String,
    pub ip_family: DohIpFamily,
    pub resolver_role: DohResolverRole,
    pub ttl_secs: Option<u32>,
}
