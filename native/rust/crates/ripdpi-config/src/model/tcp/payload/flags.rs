use crate::TcpChainStep;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
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
        TcpFlagOverrides { set: self.tcp_flags_set, unset: self.tcp_flags_unset }
    }

    pub const fn original_flag_overrides(&self) -> TcpFlagOverrides {
        TcpFlagOverrides { set: self.tcp_flags_orig_set, unset: self.tcp_flags_orig_unset }
    }
}
