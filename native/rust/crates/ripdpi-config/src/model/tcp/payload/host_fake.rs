use super::{TcpFakeOrdering, TcpFlagOverrides};
use crate::{OffsetExpr, TcpChainStep, TcpChainStepKind};

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
        if self.kind == TcpChainStepKind::HostFake {
            Some(TcpHostFakePayload {
                midhost_offset: self.midhost_offset,
                fake_host_template: self.fake_host_template.as_deref(),
                random_fake_host: self.random_fake_host,
                ordering: self.fake_ordering(),
                fake_flags: self.fake_flag_overrides(),
                original_flags: self.original_flag_overrides(),
            })
        } else {
            None
        }
    }

    pub(crate) fn hostfake_storage_active(&self) -> bool {
        self.midhost_offset.is_some() || self.fake_host_template.is_some() || self.random_fake_host
    }
}
