use crate::recorder::global_install::recorder;

/// Resets all histogram data. Call on session stop to avoid stale history
/// bleeding across session boundaries.
pub fn reset_histograms() {
    if let Some(rec) = recorder() {
        rec.reset_histograms();
    }
}
