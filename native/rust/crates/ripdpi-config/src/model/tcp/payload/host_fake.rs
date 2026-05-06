use super::{TcpFakeOrdering, TcpFlagOverrides};
use crate::{OffsetExpr, TcpChainStep};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TcpHostFakePayload<'a> {
    pub midhost_offset: Option<OffsetExpr>,
    pub fake_host_template: Option<&'a str>,
    pub random_fake_host: bool,
    pub ordering: TcpFakeOrdering,
    pub fake_flags: TcpFlagOverrides,
    pub original_flags: TcpFlagOverrides,
}

impl TcpChainStep {
    pub fn host_fake_payload(&self) -> Option<TcpHostFakePayload<'_>> {
        self.payload.host_fake_payload()
    }

    pub(crate) fn hostfake_storage_active(&self) -> bool {
        self.payload.hostfake_storage_active()
    }
}
