use super::ExecutionRuntime;

impl ExecutionRuntime {
    pub(in crate::engine) fn set_scan_deadline(&mut self, deadline: std::time::Instant) {
        self.scan_deadline = Some(deadline);
    }

    pub(in crate::engine) fn begin_stage_budget(&mut self, remaining_stages: usize) {
        let Some(scan_deadline) = self.scan_deadline else {
            self.stage_deadline = None;
            return;
        };
        if remaining_stages <= 1 {
            self.stage_deadline = None;
            return;
        }

        let now = std::time::Instant::now();
        let remaining = scan_deadline.saturating_duration_since(now);
        let divisor = u32::try_from(remaining_stages).unwrap_or(u32::MAX);
        self.stage_deadline = Some(now + remaining / divisor);
    }

    pub(in crate::engine) fn clear_stage_budget(&mut self) {
        self.stage_deadline = None;
    }

    pub(in crate::engine) fn is_past_deadline(&self) -> bool {
        self.scan_deadline().is_some_and(|deadline| std::time::Instant::now() >= deadline)
    }

    pub(in crate::engine) fn is_past_scan_deadline(&self) -> bool {
        self.scan_deadline.is_some_and(|deadline| std::time::Instant::now() >= deadline)
    }

    pub(in crate::engine) fn scan_deadline(&self) -> Option<std::time::Instant> {
        match (self.scan_deadline, self.stage_deadline) {
            (Some(scan), Some(stage)) => Some(scan.min(stage)),
            (scan, None) => scan,
            (None, stage) => stage,
        }
    }
}
