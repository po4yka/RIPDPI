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
pub struct SharedState {
    pub progress: Option<ScanProgress>,
    pub report: Option<ScanReport>,
    pub log_context: Option<ripdpi_proxy_config::ProxyLogContext>,
}
