use std::sync::atomic::Ordering;

use smoltcp::iface::SocketSet;
use smoltcp::socket::tcp::{Socket as TcpSocket, State};

use crate::ActiveSessions;
use crate::dns_cache::DnsCache;

use super::duplex::{flush_pending_to_smoltcp, try_read_duplex};
use super::session_cleanup::remove_session;
use crate::io_loop::{IO_PHASE_WORK_BUDGET, PUMP_CHUNK};

mod completion;
mod from_app;

/// # Cancel safety:
/// cancel-safe: the bounded pump has no suspension point.
pub(in crate::io_loop) async fn pump_active_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    dns_cache: &mut Option<DnsCache>,
) {
    let mut to_remove: Vec<_> = Vec::new();
    let work_batch = sessions.next_work_batch(IO_PHASE_WORK_BUDGET);
    let waker = sessions.bridge_waker().clone();

    for handle in work_batch {
        let Some(session) = sessions.get_mut(handle) else {
            continue;
        };
        let tcp = socket_set.get_mut::<TcpSocket>(handle);
        if tcp.state() == State::Closed && !tcp.is_closed_normally() {
            to_remove.push(handle);
            continue;
        }

        if from_app::pump_from_app(tcp, session, &waker).is_err() {
            to_remove.push(handle);
            continue;
        }

        if let Err(_err) = flush_pending_to_smoltcp(tcp, &mut session.pending_to_smoltcp) {
            to_remove.push(handle);
            continue;
        }

        if session.upstream_closed && session.pending_to_smoltcp.is_empty() && tcp.is_open() {
            tcp.close();
        }

        if session.pending_to_smoltcp.is_empty() && !session.upstream_closed {
            let mut tmp = [0u8; PUMP_CHUNK];
            match try_read_duplex(&mut session.smoltcp_side, &mut tmp, &waker) {
                Some(Ok(0)) => {
                    // A failed upstream (refused, timed out, reset) must reach the app as a RST, not
                    // as a clean end of stream. Acquire pairs with the session task's Release store.
                    if session.upstream_failed.load(Ordering::Acquire) {
                        to_remove.push(handle);
                        continue;
                    }
                    session.upstream_closed = true;
                    if tcp.is_open() {
                        tcp.close();
                    }
                }
                Some(Ok(read)) => match tcp.send_slice(&tmp[..read]) {
                    Ok(sent) => {
                        if sent < read {
                            session.pending_to_smoltcp.extend_from_slice(&tmp[sent..read]);
                        }
                    }
                    Err(_err) => {
                        to_remove.push(handle);
                        continue;
                    }
                },
                Some(Err(_err)) => {
                    to_remove.push(handle);
                    continue;
                }
                None => {}
            }
        }

        if !tcp.is_active()
            && !tcp.can_recv()
            && session.pending_to_session.is_empty()
            && session.pending_to_smoltcp.is_empty()
            && completion::relay_finished(session, &waker)
        {
            to_remove.push(handle);
        }
    }

    for handle in to_remove.drain(..) {
        remove_session(handle, sessions, socket_set, dns_cache);
    }
}
