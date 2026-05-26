use std::io;
use std::sync::Arc;

use once_cell::sync::OnceCell;
use tokio::runtime::Runtime;

static SHARED_TUNNEL_RUNTIME: OnceCell<Arc<Runtime>> = OnceCell::new();

fn build_shared_tunnel_runtime() -> io::Result<Arc<Runtime>> {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .thread_stack_size(1024 * 1024)
        .thread_name("ripdpi-tunnel-tokio")
        .enable_all()
        .build()
        .map(Arc::new)
}

pub(crate) fn shared_tunnel_runtime() -> io::Result<Arc<Runtime>> {
    SHARED_TUNNEL_RUNTIME.get_or_try_init(build_shared_tunnel_runtime).map(Arc::clone)
}
