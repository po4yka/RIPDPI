use super::{TcpFakeOrdering, TcpFlagOverrides, TcpStepPayloadInvariantError};
use crate::{OffsetExpr, TcpChainStep, TcpChainStepKind};

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TcpLegacyPayloadFields {
    pub midhost_offset: Option<OffsetExpr>,
    pub fake_host_template: Option<String>,
    pub random_fake_host: bool,
    pub fake_ordering: TcpFakeOrdering,
    pub fake_flags: TcpFlagOverrides,
    pub original_flags: TcpFlagOverrides,
}

impl TcpChainStep {
    pub fn try_apply_legacy_payload_fields(
        &mut self,
        fields: TcpLegacyPayloadFields,
    ) -> Result<(), TcpStepPayloadInvariantError> {
        self.validate_legacy_payload_fields(&fields)?;

        self.set_midhost_offset(fields.midhost_offset);
        self.set_fake_host_template(fields.fake_host_template);
        self.set_random_fake_host(fields.random_fake_host);
        self.set_fake_ordering(fields.fake_ordering);
        self.set_fake_flag_overrides(fields.fake_flags);
        self.set_original_flag_overrides(fields.original_flags);
        Ok(())
    }

    pub fn validate_legacy_payload_fields(
        &self,
        fields: &TcpLegacyPayloadFields,
    ) -> Result<(), TcpStepPayloadInvariantError> {
        let kind = self.kind();
        if kind != TcpChainStepKind::HostFake
            && (fields.midhost_offset.is_some() || fields.fake_host_template.is_some() || fields.random_fake_host)
        {
            return Err(TcpStepPayloadInvariantError::new(kind, "hostfake"));
        }
        if !kind.supports_fake_ordering() && fields.fake_ordering != TcpFakeOrdering::before_each_duplicate() {
            return Err(TcpStepPayloadInvariantError::new(kind, "fake ordering"));
        }
        if !kind.supports_fake_tcp_flags() && fields.fake_flags != TcpFlagOverrides::disabled() {
            return Err(TcpStepPayloadInvariantError::new(kind, "fake TCP flags"));
        }
        if !kind.supports_orig_tcp_flags() && fields.original_flags != TcpFlagOverrides::disabled() {
            return Err(TcpStepPayloadInvariantError::new(kind, "original TCP flags"));
        }
        Ok(())
    }
}
