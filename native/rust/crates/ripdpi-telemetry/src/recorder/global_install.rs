use std::sync::OnceLock;

use crate::recorder::registration::RecorderProxy;
use crate::recorder::state::InMemoryRecorder;

static RECORDER: OnceLock<InMemoryRecorder> = OnceLock::new();
static INSTALLED: OnceLock<bool> = OnceLock::new();

/// Installs the global in-memory metrics recorder.
///
/// Returns `false` if another global recorder was already installed.
/// Repeated calls return the original result.
pub fn install() -> bool {
    RECORDER.get_or_init(InMemoryRecorder::new);
    *INSTALLED.get_or_init(|| metrics::set_global_recorder(RecorderProxy).is_ok())
}

pub(crate) fn recorder() -> Option<&'static InMemoryRecorder> {
    RECORDER.get()
}

pub(crate) fn is_installed() -> bool {
    INSTALLED.get().copied().unwrap_or(false)
}
