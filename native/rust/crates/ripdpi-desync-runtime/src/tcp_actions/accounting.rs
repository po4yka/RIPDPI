use crate::types::{TcpExecutionReceipt, TcpTerminalReason};

pub(super) struct ActionContext {
    pub(super) strategy_family: Option<&'static str>,
    pub(super) default_ttl: u8,
    pub(super) md5sig: bool,
    pub(super) ip_id_mode: Option<ripdpi_config::IpIdMode>,
}

pub(super) struct FallbackAccounting {
    fallback: Option<&'static str>,
    bytes_committed: usize,
    attempted_actions: usize,
    completed_actions: usize,
    real_writes_committed: usize,
    completed_awaits: usize,
    payload_bytes_committed: usize,
}

impl FallbackAccounting {
    pub(super) fn new(fallback: Option<&'static str>) -> Self {
        Self {
            fallback,
            bytes_committed: 0,
            attempted_actions: 0,
            completed_actions: 0,
            real_writes_committed: 0,
            completed_awaits: 0,
            payload_bytes_committed: 0,
        }
    }

    pub(super) fn fallback(&self) -> Option<&'static str> {
        self.fallback
    }

    pub(super) fn bytes_committed(&self) -> usize {
        self.bytes_committed
    }

    pub(super) fn set_bytes_committed(&mut self, bytes_committed: usize) {
        self.bytes_committed = bytes_committed;
    }

    pub(super) fn add_bytes_committed(&mut self, bytes_committed: usize) {
        self.bytes_committed += bytes_committed;
    }

    pub(super) fn begin_action(&mut self) {
        self.attempted_actions = self.attempted_actions.saturating_add(1);
    }

    pub(super) fn complete_action(&mut self) {
        self.completed_actions = self.completed_actions.saturating_add(1);
    }

    pub(super) fn complete_real_write(&mut self, bytes_committed: usize) {
        self.real_writes_committed = self.real_writes_committed.saturating_add(1);
        self.payload_bytes_committed = self.payload_bytes_committed.saturating_add(bytes_committed);
        self.complete_action();
    }

    pub(super) fn complete_real_writes(&mut self, writes: usize, bytes_committed: usize) {
        self.real_writes_committed = self.real_writes_committed.saturating_add(writes);
        self.payload_bytes_committed = self.payload_bytes_committed.saturating_add(bytes_committed);
        self.complete_action();
    }

    pub(super) fn complete_await(&mut self) {
        self.completed_awaits = self.completed_awaits.saturating_add(1);
        self.complete_action();
    }

    pub(super) fn has_android_ttl_fallback(&self) -> bool {
        self.fallback.is_some()
    }

    pub(super) fn failure_receipt(
        &self,
        strategy_family: Option<&'static str>,
        terminal_reason: TcpTerminalReason,
    ) -> TcpExecutionReceipt {
        TcpExecutionReceipt::failed_strategy_execution(
            strategy_family,
            self.attempted_actions,
            self.completed_actions,
            self.real_writes_committed,
            self.completed_awaits,
            self.payload_bytes_committed,
            terminal_reason,
        )
    }
}
