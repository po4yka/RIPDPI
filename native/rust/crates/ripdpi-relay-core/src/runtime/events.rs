pub(super) fn emit_runtime_ready(bind_addr: &str) {
    tracing::info!(
        ring = "relay",
        subsystem = "relay",
        source = "relay",
        kind = "runtime_ready",
        "listener started addr={bind_addr}"
    );
}

pub(super) fn emit_runtime_stopped() {
    tracing::info!(ring = "relay", subsystem = "relay", source = "relay", kind = "runtime_stopped", "listener stopped");
}
