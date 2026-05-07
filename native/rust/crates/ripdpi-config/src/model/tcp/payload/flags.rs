use crate::TcpChainStep;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TcpFlagOverrides {
    pub set: Option<u16>,
    pub unset: Option<u16>,
}

impl TcpFlagOverrides {
    pub const fn disabled() -> Self {
        Self { set: None, unset: None }
    }
}

impl TcpChainStep {
    pub const fn fake_flag_overrides(&self) -> TcpFlagOverrides {
        self.payload.fake_flag_overrides()
    }

    pub const fn original_flag_overrides(&self) -> TcpFlagOverrides {
        self.payload.original_flag_overrides()
    }
}
