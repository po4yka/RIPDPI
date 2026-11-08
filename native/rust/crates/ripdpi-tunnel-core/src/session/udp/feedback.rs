use super::*;
use ripdpi_socks5_core::udp_feedback::{self, Frame, RemoteError};

pub(crate) enum UdpSessionEvent {
    Datagram { payload: Vec<u8>, target: TargetAddr },
    RemoteError { id: u64, error: RemoteError },
}

impl UdpSession {
    pub(crate) fn has_feedback(&self) -> bool {
        self.feedback.is_some()
    }

    /// # Cancel safety:
    /// cancel-safe: the receive is datagram-atomic and the control lifetime is checked before delivery.
    pub(crate) async fn recv_event(&self, cancel: CancellationToken) -> io::Result<Option<UdpSessionEvent>> {
        let receive = async {
            loop {
                self.check_control()?;
                self.udp.readable().await?;
                let mut buffer = self.memory_budget.receive_pool.acquire().await?;
                let bytes = buffer.as_mut_slice();
                let len = match self.udp.try_recv(bytes) {
                    Ok(len) => len,
                    Err(error) if error.kind() == io::ErrorKind::WouldBlock => continue,
                    Err(error) => return Err(error),
                };
                if len > self.memory_budget.receive_pool.buffer_len {
                    continue;
                }
                let wire = &bytes[..len];
                let payload = if let Some(nonce) = &self.feedback {
                    match udp_feedback::decode(wire, nonce) {
                        Ok(Frame::Response { payload }) => payload,
                        Ok(Frame::Error { id, error }) => {
                            self.check_control()?;
                            return Ok(UdpSessionEvent::RemoteError { id, error });
                        }
                        _ => continue,
                    }
                } else {
                    wire
                };
                let Ok((target, payload)) = decode_udp_target_frame(payload) else { continue };
                self.check_control()?;
                return Ok(UdpSessionEvent::Datagram { target, payload: payload.to_vec() });
            }
        };
        tokio::select! {
            biased;
            _ = cancel.cancelled() => Ok(None),
            error = self.control_failure() => Err(error),
            result = receive => result.map(Some),
            _ = tokio::time::sleep(self.recv_timeout) => Ok(None),
        }
    }
}
