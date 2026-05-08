use crate::sync::{Arc, Mutex};
use ripdpi_proxy_runtime_adapter::model::tcp_rotation::CircularTcpRotationController;
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use super::super::super::desync::{send_with_group, DesyncSendRequest, OutboundSendError};
use super::super::super::state::RuntimeState;
use super::super::session::RelaySharedSession;
use super::freeze::FreezeDetector;
use super::observations::{observe_inbound_payload, observe_outbound_payload};
use super::observers::{observe_rotation_inbound_chunk, observe_rotation_transport_failure};
use super::RelayOutboundContext;
use super::RELAY_IDLE_TIMEOUT;
#[allow(clippy::too_many_arguments)]
pub(super) fn copy_inbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    state: &RuntimeState,
    session: RelaySharedSession,
    remembered_host: Arc<Mutex<Option<String>>>,
    rotation: Option<Arc<Mutex<CircularTcpRotationController>>>,
    peer_done: Arc<AtomicBool>,
    mut detector: FreezeDetector,
    freeze_detected: Arc<AtomicBool>,
) -> io::Result<()> {
    let (mut buffer, target) = ([0u8; 16_384], reader.peer_addr().ok());
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => {
                observe_rotation_transport_failure(
                    state,
                    target,
                    &remembered_host,
                    rotation.as_ref(),
                    &reader,
                    io::Error::new(io::ErrorKind::UnexpectedEof, "upstream closed before first response"),
                );
                break;
            }
            Ok(n) => {
                observe_inbound_payload(&session, &buffer[..n]);
                observe_rotation_inbound_chunk(
                    state,
                    target,
                    &remembered_host,
                    rotation.as_ref(),
                    &reader,
                    &buffer[..n],
                );
                writer.write_all(&buffer[..n])?;
                detector.record_bytes(n);
                if detector.check(Instant::now()) {
                    freeze_detected.store(true, Ordering::Release);
                    break;
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if detector.check(Instant::now()) {
                    freeze_detected.store(true, Ordering::Release);
                    break;
                }
                if peer_done.load(Ordering::Acquire) {
                    break;
                }
                continue;
            }
            Err(err) => {
                observe_rotation_transport_failure(
                    state,
                    target,
                    &remembered_host,
                    rotation.as_ref(),
                    &reader,
                    io::Error::new(err.kind(), err.to_string()),
                );
                return Err(err);
            }
        }
    }
    peer_done.store(true, Ordering::Release);
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}

pub(super) fn flush_outbound_payload(
    writer: &mut TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session: &RelaySharedSession,
    remembered_host: &Arc<Mutex<Option<String>>>,
    rotation: Option<&Arc<Mutex<CircularTcpRotationController>>>,
    payload: &[u8],
) -> io::Result<()> {
    let (is_new_round, progress) = observe_outbound_payload(session, payload)?;
    let mut remembered = remembered_host.lock().map_err(|_| io::Error::other("remembered host mutex poisoned"))?;
    if let Some(host) = state.extract_relay_payload_host(payload) {
        *remembered = Some(host);
    }
    let host = remembered.clone();
    drop(remembered);
    let peer_addr = writer.peer_addr()?;
    let group = if let Some(rotation) = rotation {
        let mut rotation = rotation.lock().map_err(|_| io::Error::other("rotation mutex poisoned"))?;
        let retrans_baseline = if is_new_round {
            ripdpi_proxy_runtime_adapter::platform::relay::tcp_total_retransmissions(writer).ok().flatten()
        } else {
            None
        };
        if is_new_round {
            state.start_relay_rotation_round(
                &mut rotation,
                progress,
                payload,
                retrans_baseline,
                host.as_deref(),
                Some(peer_addr),
            );
        } else {
            state.append_relay_rotation_request_chunk(&mut rotation, progress.round, payload);
        }
        Some(rotation.current_send_group())
    } else {
        None
    };
    let send_outcome = send_with_group(
        writer,
        state,
        DesyncSendRequest {
            group_index,
            group_override: group.as_ref(),
            payload,
            progress,
            host: host.as_deref(),
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

pub(super) fn copy_outbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    context: RelayOutboundContext,
    session: RelaySharedSession,
    remembered_host: Arc<Mutex<Option<String>>>,
    rotation: Option<Arc<Mutex<CircularTcpRotationController>>>,
    peer_done: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        let _ = reader.set_read_timeout(Some(RELAY_IDLE_TIMEOUT));
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                flush_outbound_payload(
                    &mut writer,
                    &context.state,
                    context.group_index,
                    &session,
                    &remembered_host,
                    rotation.as_ref(),
                    &buffer[..n],
                )?;
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
    let _ = writer.shutdown(Shutdown::Write);
    let _ = reader.shutdown(Shutdown::Read);
    Ok(())
}
