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
