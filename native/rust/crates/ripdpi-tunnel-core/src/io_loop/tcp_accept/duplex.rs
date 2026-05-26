use std::io;
use tokio::io::DuplexStream;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

mod launch;

pub(super) use launch::create_session_duplex;

pub(super) struct SessionDuplex {
    pub(super) smoltcp_side: DuplexStream,
    pub(super) cancel: CancellationToken,
    pub(super) handle: JoinHandle<io::Result<()>>,
}
