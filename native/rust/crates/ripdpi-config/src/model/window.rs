/// TCP receive window control through socket options.
/// `SO_RCVBUF` is a pre-connect buffer hint; the OS selects the negotiated scale.
/// `TCP_WINDOW_CLAMP` is applied after connect and does not change an advertised
/// window immediately or set the size of each incoming segment.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WsizeConfig {
    /// Desired TCP receive window size in bytes.
    pub window: u32,
    /// Optional receive-buffer shift (0-14), not a negotiated scale override.
    /// `SO_RCVBUF` requests `window << scale`, or `window` when this is `None`.
    pub scale: Option<u8>,
}

impl WsizeConfig {
    /// Returns the positive buffer request within the signed socket-option range.
    pub fn receive_buffer_size(self) -> Option<u32> {
        let shift = self.scale.unwrap_or(0);
        if self.window == 0 || shift > 14 {
            return None;
        }
        let size = self.window.checked_mul(1u32 << shift)?;
        (size <= i32::MAX as u32).then_some(size)
    }
}
