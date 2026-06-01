mod capability_counter;
mod global_install;
mod histogram_handle;
mod histogram_reset;
mod registration;
mod snapshot;
mod state;

#[cfg(test)]
mod tests;

pub use capability_counter::record_capability_skipped;
pub use global_install::install;
pub use histogram_reset::reset_histograms;
pub use snapshot::{RecorderSnapshot, snapshot};
pub use state::InMemoryRecorder;
