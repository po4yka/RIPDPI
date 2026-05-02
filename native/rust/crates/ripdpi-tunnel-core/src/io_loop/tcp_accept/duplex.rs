use std::io;
use std::net::SocketAddr;

use tokio::io::{duplex, DuplexStream};
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

use crate::io_loop::DUPLEX_BUF;
use crate::session::{Auth, TargetAddr, TcpSession};

pub(super) struct SessionDuplex {
    pub(super) smoltcp_side: DuplexStream,
    pub(super) cancel: CancellationToken,
    pub(super) handle: JoinHandle<io::Result<()>>,
}

pub(super) fn create_session_duplex(
    proxy_sockaddr: SocketAddr,
    auth: &Auth,
    target_addr: SocketAddr,
    parent_cancel: &CancellationToken,
) -> SessionDuplex {
    let (smoltcp_side, mut session_side) = duplex(DUPLEX_BUF);
    let cancel = parent_cancel.child_token();
    let session_inst = TcpSession::new(proxy_sockaddr, auth.clone(), TargetAddr::Ip(target_addr));
    let session_cancel = cancel.clone();
    let handle = tokio::spawn(async move { session_inst.run(&mut session_side, session_cancel).await });

    SessionDuplex { smoltcp_side, cancel, handle }
}
