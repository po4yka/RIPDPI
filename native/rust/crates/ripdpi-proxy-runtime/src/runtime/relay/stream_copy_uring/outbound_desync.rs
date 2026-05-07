use crate::sync::{Arc, Mutex};

use std::io::{self, Read};
use std::net::TcpStream;
use std::sync::atomic::{AtomicBool, Ordering};

use ripdpi_proxy_runtime_adapter::model::session::{extract_payload_host, SessionState};

use super::super::super::desync::{send_with_group, DesyncSendRequest, OutboundSendError};
use super::super::super::state::RuntimeState;
use super::cleanup::shutdown_direction;
use super::observations::observe_outbound_payload;
use super::RELAY_IDLE_TIMEOUT;

/// Outbound copy using the standard desync pipeline.
/// Identical to the non-uring version.
pub(super) fn copy_outbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    state: RuntimeState,
    group_index: usize,
    session: Arc<Mutex<SessionState>>,
    peer_done: Arc<AtomicBool>,
    mut remembered_host: Option<String>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        let _ = reader.set_read_timeout(Some(RELAY_IDLE_TIMEOUT));
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                flush_outbound_payload(&mut writer, &state, group_index, &session, &mut remembered_host, &buffer[..n])?;
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if peer_done.load(Ordering::Acquire) {
                    break;
                }
                continue;
            }
            Err(err) => return Err(err),
        }
    }
    peer_done.store(true, Ordering::Release);
    shutdown_direction(&writer, &reader);
    Ok(())
}

fn flush_outbound_payload(
    writer: &mut TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session: &Arc<Mutex<SessionState>>,
    remembered_host: &mut Option<String>,
    payload: &[u8],
) -> io::Result<()> {
    let progress = observe_outbound_payload(session, payload)?;
    let parsed_host = extract_payload_host(&state.config, payload);
    if parsed_host.is_some() {
        *remembered_host = parsed_host.clone();
    }
    let peer_addr = writer.peer_addr()?;
    let send_outcome = send_with_group(
        writer,
        state,
        DesyncSendRequest {
            group_index,
            group_override: None,
            payload,
            progress,
            host: parsed_host.as_deref().or(remembered_host.as_deref()),
            target: peer_addr,
        },
    )
    .map_err(OutboundSendError::into_io_error)?;
    tracing::trace!(
        target = %peer_addr,
        strategy_family = send_outcome.strategy_family.unwrap_or("plain"),
        bytes_committed = send_outcome.bytes_committed,
        "steady-state outbound payload forwarded"
    );
    Ok(())
}
