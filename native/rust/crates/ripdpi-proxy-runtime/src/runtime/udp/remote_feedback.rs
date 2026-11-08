use std::cell::Cell;
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::rc::Rc;

pub(super) use ripdpi_proxy_runtime_adapter::model::session::udp_feedback::{self, Nonce};
use ripdpi_proxy_runtime_adapter::platform::udp_error_queue;

use super::flow::{UdpFlowActivationState, UdpFlowKey};

const PREFIX_LEN: usize = 64;
const HISTORY_LIMIT: usize = 64;
// Includes the payload prefix and Vec allocation growth for each retained record.
const RECORD_CHARGE: usize = 256;
const ASSOCIATION_BUDGET: usize = 256 * 1024;

pub(super) type FeedbackBudget = Rc<Cell<usize>>;

pub(super) fn new_budget() -> FeedbackBudget {
    Rc::new(Cell::new(0))
}

struct SentDatagram {
    id: u64,
    prefix: Vec<u8>,
    reported: bool,
}

#[derive(Default)]
pub(super) struct FlowFeedback {
    budget: Option<FeedbackBudget>,
    history: Vec<SentDatagram>,
    poisoned: bool,
    enabled: bool,
    pending_id: Option<u64>,
}

impl FlowFeedback {
    pub(super) fn begin(&mut self, socket: &UdpSocket, id: Option<u64>, budget: &FeedbackBudget) {
        self.pending_id = id;
        if self.poisoned || id.is_none() || self.budget.is_some() {
            return;
        }
        if udp_error_queue::enable(socket).is_err() {
            self.poison();
            return;
        }
        self.budget = Some(budget.clone());
        self.enabled = true;
    }

    pub(super) fn execution_failed(&mut self, committed_writes: usize) {
        if committed_writes > 0 {
            // A later action failed after a write that has no successful-send record.
            self.poison();
        }
    }

    pub(super) fn reset_socket(&mut self) {
        self.poison();
        self.enabled = false;
    }

    pub(super) fn poison(&mut self) {
        self.release();
        self.poisoned = true;
        self.pending_id = None;
    }

    fn release(&mut self) {
        if let Some(budget) = self.budget.take() {
            budget.set(budget.get().saturating_sub(self.history.len() * RECORD_CHARGE));
        }
        self.history = Vec::new();
    }

    pub(super) fn record_send(&mut self, payload: &[u8]) {
        let Some(budget) = &self.budget else { return };
        let Some(id) = self.pending_id.take() else {
            self.poison();
            return;
        };
        if self.history.len() == HISTORY_LIMIT || budget.get() + RECORD_CHARGE > ASSOCIATION_BUDGET {
            // Late errors must never become uniquely attributable after history eviction.
            self.poison();
            return;
        }
        budget.set(budget.get() + RECORD_CHARGE);
        self.history.push(SentDatagram {
            id,
            prefix: payload[..payload.len().min(PREFIX_LEN)].to_vec(),
            reported: false,
        });
    }

    fn matching_id(&mut self, quoted: &[u8]) -> Option<u64> {
        if self.poisoned {
            return None;
        }
        let quoted = &quoted[..quoted.len().min(PREFIX_LEN)];
        let mut matches = self.history.iter_mut().filter(|record| record.prefix.starts_with(quoted));
        let record = matches.next()?;
        if matches.next().is_some() || record.reported {
            return None;
        }
        record.reported = true;
        Some(record.id)
    }
}

impl Drop for FlowFeedback {
    fn drop(&mut self) {
        self.release();
    }
}

pub(super) fn drain_errors(
    state: &crate::runtime::state::RuntimeState,
    client_relay: &UdpSocket,
    key: &UdpFlowKey,
    entry: &mut UdpFlowActivationState,
    nonce: Option<&Nonce>,
) -> io::Result<bool> {
    let Some(nonce) = nonce else { return Ok(false) };
    if !entry.remote_feedback.enabled || !entry.is_active(state) {
        return Ok(false);
    }
    let Ok(errors) = udp_error_queue::drain(&entry.upstream) else {
        entry.remote_feedback.reset_socket();
        return Ok(false);
    };
    let progressed = !errors.is_empty();
    for error in errors {
        if error.target != entry.current_target
            || error.source.is_ipv4() != entry.logical_target.is_ipv4()
            || entry.current_target.is_ipv4() != entry.logical_target.is_ipv4()
        {
            continue;
        }
        let Some(id) = entry.remote_feedback.matching_id(&error.payload_prefix) else { continue };
        let error = udp_feedback::RemoteError {
            source: error.source,
            target: entry.logical_target,
            icmp_type: error.icmp_type,
            icmp_code: error.icmp_code,
            info: error.info,
        };
        let Ok(frame) = udp_feedback::encode_error(nonce, id, &error) else { continue };
        if !entry.is_active(state) {
            break;
        }
        send_frame(client_relay, key.client, &frame)?;
    }
    Ok(progressed)
}

pub(super) fn send_frame(socket: &UdpSocket, client: SocketAddr, frame: &[u8]) -> io::Result<()> {
    match socket.send_to(frame, client) {
        Ok(_) => Ok(()),
        Err(error) if error.kind() == io::ErrorKind::WouldBlock || error.raw_os_error() == Some(libc::EMSGSIZE) => {
            Ok(())
        }
        Err(error) => Err(error),
    }
}

#[cfg(test)]
mod tests;
