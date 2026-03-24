use crate::platform;
use crate::runtime_policy::extract_host;
use crate::sync::{Arc, Mutex};
use ripdpi_session::SessionState;
use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use super::super::desync::send_with_group;
use super::super::state::RuntimeState;
use super::tls_boundary::OutboundTlsFirstRecordAssembler;

const RELAY_IDLE_TIMEOUT: Duration = Duration::from_secs(60);

pub(super) fn relay_streams(
    client: TcpStream,
    upstream: TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session_seed: SessionState,
) -> io::Result<SessionState> {
    client.set_read_timeout(Some(RELAY_IDLE_TIMEOUT))?;
    client.set_write_timeout(None)?;
    upstream.set_read_timeout(Some(RELAY_IDLE_TIMEOUT))?;
    upstream.set_write_timeout(None)?;

    let client_reader = client.try_clone()?;
    let client_writer = client.try_clone()?;
    let upstream_reader = upstream.try_clone()?;
    let upstream_writer = upstream.try_clone()?;
    let session_state = Arc::new(Mutex::new(session_seed));
    let outbound_session = session_state.clone();
    let inbound_session = session_state.clone();
    let outbound_state = state.clone();
    let group = state
        .config
        .groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let drop_sack = group.drop_sack;
    let peer_done = Arc::new(AtomicBool::new(false));

    let down_done = peer_done.clone();
    let up_done = peer_done.clone();
    let down = thread::Builder::new()
        .name("ripdpi-dn".into())
        .spawn(move || copy_inbound_half(upstream_reader, client_writer, inbound_session, down_done))
        .map_err(|err| io::Error::other(format!("failed to spawn inbound relay thread: {err}")))?;
    let up = thread::Builder::new()
        .name("ripdpi-up".into())
        .spawn(move || {
            copy_outbound_half(client_reader, upstream_writer, outbound_state, group_index, outbound_session, up_done)
        })
        .map_err(|err| io::Error::other(format!("failed to spawn outbound relay thread: {err}")))?;

    let up_result = up.join().map_err(|_| io::Error::other("upstream thread panicked"))?;
    let down_result = down.join().map_err(|_| io::Error::other("downstream thread panicked"))?;

    if drop_sack {
        let _ = platform::detach_drop_sack(&upstream);
    }

    up_result?;
    down_result?;
    session_state.lock().map_err(|_| io::Error::other("session mutex poisoned")).map(|state| state.clone())
}

fn copy_inbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    session: Arc<Mutex<SessionState>>,
    peer_done: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    loop {
        match reader.read(&mut buffer) {
            Ok(0) => break,
            Ok(n) => {
                if let Ok(mut state) = session.lock() {
                    state.observe_inbound(&buffer[..n]);
                }
                writer.write_all(&buffer[..n])?;
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

fn flush_outbound_payload(
    writer: &mut TcpStream,
    state: &RuntimeState,
    group_index: usize,
    session: &Arc<Mutex<SessionState>>,
    remembered_host: &mut Option<String>,
    payload: &[u8],
) -> io::Result<()> {
    let progress = {
        let mut state = session.lock().map_err(|_| io::Error::other("session mutex poisoned"))?;
        state.observe_outbound(payload)
    };
    let parsed_host = extract_host(&state.config, payload);
    if parsed_host.is_some() {
        *remembered_host = parsed_host.clone();
    }
    let group = state
        .config
        .groups
        .get(group_index)
        .cloned()
        .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "missing desync group"))?;
    let peer_addr = writer.peer_addr()?;
    send_with_group(
        writer,
        state,
        group_index,
        &group,
        payload,
        progress,
        parsed_host.as_deref().or(remembered_host.as_deref()),
        peer_addr,
    )?;
    Ok(())
}

fn copy_outbound_half(
    mut reader: TcpStream,
    mut writer: TcpStream,
    state: RuntimeState,
    group_index: usize,
    session: Arc<Mutex<SessionState>>,
    peer_done: Arc<AtomicBool>,
) -> io::Result<()> {
    let mut buffer = [0u8; 16_384];
    let mut remembered_host = None::<String>;
    let mut first_tls_record = OutboundTlsFirstRecordAssembler::new();
    let mut forwarded_payload = false;
    loop {
        let read_timeout = first_tls_record.timeout(Instant::now()).unwrap_or(RELAY_IDLE_TIMEOUT);
        reader.set_read_timeout(Some(read_timeout))?;
        match reader.read(&mut buffer) {
            Ok(0) => {
                if let Some(payload) = first_tls_record.finish() {
                    flush_outbound_payload(&mut writer, &state, group_index, &session, &mut remembered_host, &payload)?;
                }
                break;
            }
            Ok(n) => {
                if !forwarded_payload {
                    if let Some(payload) = first_tls_record.push(&buffer[..n], Instant::now()) {
                        flush_outbound_payload(
                            &mut writer,
                            &state,
                            group_index,
                            &session,
                            &mut remembered_host,
                            &payload,
                        )?;
                        forwarded_payload = true;
                    }
                } else {
                    flush_outbound_payload(
                        &mut writer,
                        &state,
                        group_index,
                        &session,
                        &mut remembered_host,
                        &buffer[..n],
                    )?;
                }
            }
            Err(err) if matches!(err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                if let Some(payload) = first_tls_record.flush_on_timeout(Instant::now()) {
                    flush_outbound_payload(&mut writer, &state, group_index, &session, &mut remembered_host, &payload)?;
                    forwarded_payload = true;
                    continue;
                }
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
