mod observation;
mod request;
mod scan;
mod strategy;
mod target;

pub use observation::*;
pub use request::*;
pub use scan::*;
pub use strategy::*;
pub use target::*;

#[derive(Default)]
pub(crate) struct SharedState {
    pub(crate) progress: Option<ScanProgress>,
    pub(crate) report: Option<ScanReport>,
    pub(crate) log_context: Option<ripdpi_proxy_config::ProxyLogContext>,
}
