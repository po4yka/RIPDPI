use std::io;
use std::sync::Arc;

// Intentionally retains `once_cell` rather than `std::sync::OnceLock`: the
// fallible runtime build below relies on `get_or_try_init`, which on
// `std::sync::OnceLock` is still unstable (`once_cell_try`, rust-lang/rust#109737)
// as of the pinned 1.98.1 toolchain. `OnceLock::get_or_init` cannot propagate the
// `io::Result` from `Builder::build()` without redundantly building (and dropping)
// a second tokio runtime under contention -- a behavioral change on this
// lifecycle-critical path (see .claude/rules/android-vpn-lifecycle.md). Migrate
// the rest of this crate to std; revisit this site once `once_cell_try` stabilizes.
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
