use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use tokio::io::duplex;
use tokio_util::sync::CancellationToken;

use crate::Stats;
use crate::io_loop::DUPLEX_BUF;
use crate::session::{Auth, TargetAddr, TcpSession};

use super::SessionDuplex;

pub(in crate::io_loop::tcp_accept) fn create_session_duplex(
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    target: TargetAddr,
    protect_path: Option<&str>,
    connect_timeout: Duration,
    read_write_timeout: Duration,
    parent_cancel: &CancellationToken,
    stats: &Arc<Stats>,
) -> SessionDuplex {
    let (smoltcp_side, mut session_side) = duplex(DUPLEX_BUF);
    let cancel = parent_cancel.child_token();
    let session_inst = TcpSession::with_stats(
        proxy_sockaddr,
        auth.clone(),
        target,
        protect_path.map(str::to_owned),
        connect_timeout,
        read_write_timeout,
        Arc::clone(stats),
    );
    let session_cancel = cancel.clone();
    let handle = tokio::spawn(async move { session_inst.run(&mut session_side, session_cancel).await });
    SessionDuplex { smoltcp_side, cancel, handle }
}
