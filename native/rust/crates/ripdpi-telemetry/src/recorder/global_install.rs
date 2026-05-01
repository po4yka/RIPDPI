use std::sync::OnceLock;

use crate::recorder::registration::RecorderProxy;
use crate::recorder::state::InMemoryRecorder;

static RECORDER: OnceLock<InMemoryRecorder> = OnceLock::new();

/// Installs the global in-memory metrics recorder.
///
/// Safe to call multiple times: only the first call takes effect.
/// Subsequent calls are silently ignored.
pub fn install() {
    RECORDER.get_or_init(InMemoryRecorder::new);
    // set_global_recorder returns Err if already set -- that is fine.
    let _ = metrics::set_global_recorder(RecorderProxy);
}

pub(crate) fn recorder() -> Option<&'static InMemoryRecorder> {
    RECORDER.get()
}
