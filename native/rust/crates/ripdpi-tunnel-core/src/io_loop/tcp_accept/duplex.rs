use std::io;
use std::sync::Arc;
use std::sync::atomic::AtomicBool;
use tokio::io::DuplexStream;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;

mod launch;
mod tcp_dns;

pub(super) use launch::create_session_duplex;
pub(super) use tcp_dns::create_tcp_dns_duplex;

pub(super) struct SessionDuplex {
    pub(super) smoltcp_side: DuplexStream,
    pub(super) cancel: CancellationToken,
    pub(super) handle: JoinHandle<io::Result<()>>,
    pub(super) upstream_failed: Arc<AtomicBool>,
}
