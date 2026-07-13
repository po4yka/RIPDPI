use std::sync::mpsc::{self, Sender};
use std::thread::{self, JoinHandle};

pub(super) static WORKER_REAPER: std::sync::LazyLock<WorkerReaper> = std::sync::LazyLock::new(WorkerReaper::new);

pub(super) struct WorkerReaper {
    sender: Option<Sender<ReaperCommand>>,
}

enum ReaperCommand {
    Join(JoinHandle<()>),
    #[cfg(test)]
    Flush(Sender<()>),
}

impl WorkerReaper {
    fn new() -> Self {
        let (sender, receiver) = mpsc::channel::<ReaperCommand>();
        match thread::Builder::new().name("ripdpi-monitor-reaper".to_string()).spawn(move || {
            while let Ok(command) = receiver.recv() {
                match command {
                    ReaperCommand::Join(handle) => {
                        let _ = handle.join();
                    }
                    #[cfg(test)]
                    ReaperCommand::Flush(done) => {
                        let _ = done.send(());
                    }
                }
            }
        }) {
            Ok(_reaper) => Self { sender: Some(sender) },
            Err(err) => {
                log::error!("failed to start diagnostics worker reaper: {err}");
                Self { sender: None }
            }
        }
    }

    pub(super) fn reap(&self, handle: JoinHandle<()>) {
        let Some(sender) = &self.sender else {
            log::error!("detaching diagnostics worker because the reaper is unavailable");
            return;
        };
        if sender.send(ReaperCommand::Join(handle)).is_err() {
            log::error!("detaching diagnostics worker because the reaper stopped");
        }
    }

    #[cfg(test)]
    fn flush(&self) -> mpsc::Receiver<()> {
        let (done_tx, done_rx) = mpsc::channel();
        if let Some(sender) = &self.sender {
            let _ = sender.send(ReaperCommand::Flush(done_tx));
        }
        done_rx
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn worker_reaper_joins_workers_in_the_background() {
        let reaper = WorkerReaper::new();
        let (release_tx, release_rx) = mpsc::channel();
        let worker = thread::spawn(move || release_rx.recv().expect("wait for release"));

        reaper.reap(worker);
        let flushed = reaper.flush();
        assert!(
            flushed.recv_timeout(Duration::from_millis(50)).is_err(),
            "reaper must wait for the queued worker before processing later commands"
        );
        release_tx.send(()).expect("release worker");
        flushed.recv_timeout(Duration::from_secs(1)).expect("reaper joined worker and flushed queue");
    }
}
