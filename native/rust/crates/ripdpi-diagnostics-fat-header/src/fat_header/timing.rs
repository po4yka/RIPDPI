use std::time::Instant;

pub(super) struct ProbeTiming {
    last_io_success: Instant,
}

impl ProbeTiming {
    pub(super) fn start() -> Self {
        Self { last_io_success: Instant::now() }
    }

    pub(super) fn mark_io_success(&mut self) {
        self.last_io_success = Instant::now();
    }

    pub(super) fn elapsed_since_success_ms(&self) -> u64 {
        self.last_io_success.elapsed().as_millis() as u64
    }
}
