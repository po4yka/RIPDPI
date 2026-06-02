use super::{QuicInitialMode, RuntimeQuicSettings};

impl Default for RuntimeQuicSettings {
    fn default() -> Self {
        Self { initial_mode: QuicInitialMode::RouteAndCache, support_v1: true, support_v2: true }
    }
}
