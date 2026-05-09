use std::sync::Mutex;

/// A warmup request enqueued via [`ripdpi_runtime_api::BackgroundProbes::request_warmup`].
///
/// Proxy-runtime can receive these from [`super::ServicesState::take_warmup_receiver`]
/// and execute them through the normal routing pipeline.
#[derive(Debug, Clone)]
pub struct WarmupRequest {
    pub authority: String,
    pub is_udp: bool,
}

pub(crate) struct WarmupQueueHandle {
    pub(crate) tx: std::sync::mpsc::SyncSender<WarmupRequest>,
    /// Receiver half, wrapped in `Mutex<Option<...>>` so it can be handed out exactly once.
    rx: Mutex<Option<std::sync::mpsc::Receiver<WarmupRequest>>>,
}

impl WarmupQueueHandle {
    pub(crate) fn new(bound: usize) -> Self {
        let (tx, rx) = std::sync::mpsc::sync_channel(bound);
        Self { tx, rx: Mutex::new(Some(rx)) }
    }

    pub(crate) fn take_receiver(&self) -> Option<std::sync::mpsc::Receiver<WarmupRequest>> {
        self.rx.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take()
    }
}
