use std::sync::atomic::Ordering;
use std::sync::{Arc, PoisonError};

use super::{DemuxTable, MuxReader, ReplyClock};
use crate::error::{MieruError, Result};

impl ReplyClock {
    /// Consume the SOCKS5 reply before exposing any application data.
    /// # Cancel safety:
    /// NOT cancel-safe: a partial reply is consumed. The stream owner removes the session on failure.
    pub(super) async fn receive_reply(
        &self,
        reader: &mut MuxReader,
        table: &DemuxTable,
        session_id: u32,
    ) -> Result<()> {
        let mut head = [0u8; 4];
        reader.read_exact(&mut head).await?;
        if head[0] != 0x05 {
            return Err(MieruError::Protocol(format!("bad SOCKS5 reply version {}", head[0])));
        }
        if head[2] != 0 {
            return Err(MieruError::Protocol("nonzero SOCKS5 reply reserved byte".into()));
        }
        if head[1] > 0x08 {
            return Err(MieruError::Protocol(format!("unknown SOCKS5 reply code {}", head[1])));
        }
        let addr_len = match head[3] {
            0x01 => 4,
            0x04 => 16,
            0x03 => {
                let mut len = [0u8; 1];
                reader.read_exact(&mut len).await?;
                if len[0] == 0 {
                    return Err(MieruError::Protocol("empty SOCKS5 reply domain".into()));
                }
                usize::from(len[0])
            }
            other => return Err(MieruError::Protocol(format!("unknown SOCKS5 reply atyp {other}"))),
        };
        let mut rest = [0u8; 257];
        reader.read_exact(&mut rest[..addr_len + 2]).await?;

        let table = table.lock().unwrap_or_else(PoisonError::into_inner);
        // Hold the route until publication commits. A canceled stream can have
        // a buffered reply, and its ID can later be used by another stream.
        if table.get(&session_id).is_some_and(|mailbox| Arc::ptr_eq(&mailbox.progress, &reader.progress)) {
            // Ordering: Release pairs with the Acquire in `open_stream`.
            self.authenticated.store(true, Ordering::Release);
            if head[1] == 0x00
                && !self.calibrated.load(Ordering::Relaxed)
                && let Some((timestamp, received)) = reader.leftover.observation
                && self.time.calibrate_at(timestamp, 30, received)
            {
                // The same table lock serializes calibration for this carrier.
                self.calibrated.store(true, Ordering::Relaxed);
            }
        }
        if head[1] != 0x00 {
            return Err(MieruError::Socks5(format!("SOCKS5 CONNECT failed, reply code {}", head[1])));
        }
        Ok(())
    }
}
