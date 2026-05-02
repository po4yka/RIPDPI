use super::TcpFlagOverrides;
use crate::{FakeOrder, FakeSeqMode, OffsetExpr};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpFakeOrdering {
    pub order: FakeOrder,
    pub seq_mode: FakeSeqMode,
}

impl TcpFakeOrdering {
    pub const fn before_each_duplicate() -> Self {
        Self { order: FakeOrder::BeforeEach, seq_mode: FakeSeqMode::Duplicate }
    }
}

impl Default for TcpFakeOrdering {
    fn default() -> Self {
        Self::before_each_duplicate()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpFakePayload {
    pub ordering: TcpFakeOrdering,
    pub fake_flags: TcpFlagOverrides,
    pub original_flags: TcpFlagOverrides,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcpHostFakePayload<'a> {
    pub midhost_offset: Option<OffsetExpr>,
    pub fake_host_template: Option<&'a str>,
    pub random_fake_host: bool,
    pub ordering: TcpFakeOrdering,
    pub fake_flags: TcpFlagOverrides,
    pub original_flags: TcpFlagOverrides,
}
