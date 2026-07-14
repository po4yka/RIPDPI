use smoltcp::iface::SocketSet;
use smoltcp::socket::tcp::Socket as TcpSocket;

use crate::ActiveSessions;
use crate::dns_cache::DnsCache;

use super::duplex::{flush_pending_to_session, flush_pending_to_smoltcp, try_read_duplex, try_write_duplex};
use super::session_cleanup::remove_session;
use crate::io_loop::{IO_PHASE_WORK_BUDGET, PUMP_CHUNK};

pub(in crate::io_loop) async fn pump_active_sessions(
    socket_set: &mut SocketSet<'static>,
    sessions: &mut ActiveSessions,
    dns_cache: &mut Option<DnsCache>,
) {
    let mut to_remove: Vec<_> = Vec::new();
    let work_batch = sessions.next_work_batch(IO_PHASE_WORK_BUDGET);

    for handle in work_batch {
        let Some(session) = sessions.get_mut(handle) else {
            continue;
        };
        let tcp = socket_set.get_mut::<TcpSocket>(handle);

        if let Some(Err(_err)) = flush_pending_to_session(&mut session.smoltcp_side, &mut session.pending_to_session) {
            to_remove.push(handle);
            continue;
        }

        if session.pending_to_session.is_empty() {
            let mut tmp = [0u8; PUMP_CHUNK];
            if let Ok(read) = tcp.recv_slice(&mut tmp)
                && read > 0
            {
                match try_write_duplex(&mut session.smoltcp_side, &tmp[..read]) {
                    Some(Ok(0)) => {
                        to_remove.push(handle);
                        continue;
                    }
                    Some(Ok(sent)) => {
                        if sent < read {
                            session.pending_to_session.extend_from_slice(&tmp[sent..read]);
                        }
                    }
                    Some(Err(_err)) => {
                        to_remove.push(handle);
                        continue;
                    }
                    None => {
                        session.pending_to_session.extend_from_slice(&tmp[..read]);
                    }
                }
            }
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
            match try_read_duplex(&mut session.smoltcp_side, &mut tmp) {
                Some(Ok(0)) => {
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

        if !tcp.is_active() && session.pending_to_session.is_empty() && session.pending_to_smoltcp.is_empty() {
            to_remove.push(handle);
        }
    }

    for handle in to_remove.drain(..) {
        remove_session(handle, sessions, socket_set, dns_cache).await;
    }
}
