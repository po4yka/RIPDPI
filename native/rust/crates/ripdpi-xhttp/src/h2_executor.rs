use std::future::Future;
use std::sync::{Arc, Mutex};

use tokio::task::JoinHandle;

#[derive(Clone)]
pub(crate) struct CarrierExecutor(Arc<Mutex<Option<Vec<CarrierTask>>>>);

struct CarrierTask(JoinHandle<()>);

impl Drop for CarrierTask {
    fn drop(&mut self) {
        self.0.abort();
    }
}

pub(crate) struct CarrierTaskOwner {
    executor: CarrierExecutor,
}

impl CarrierTaskOwner {
    pub(crate) fn new() -> Self {
        Self { executor: CarrierExecutor(Arc::new(Mutex::new(Some(Vec::new())))) }
    }

    pub(crate) fn executor(&self) -> CarrierExecutor {
        self.executor.clone()
    }

    /// # Cancel safety:
    /// cancel-safe: every taken task aborts on drop; no lock is held during a join.
    pub(crate) async fn close(&self) {
        let tasks = self.executor.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take();
        if let Some(tasks) = tasks {
            for task in &tasks {
                task.0.abort();
            }
            for mut task in tasks {
                let _ = (&mut task.0).await;
            }
        }
    }
}

impl Drop for CarrierTaskOwner {
    fn drop(&mut self) {
        // Break executor/task references even when handshake or driver is cancelled.
        let tasks = self.executor.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner).take();
        drop(tasks);
    }
}

impl<F> hyper::rt::Executor<F> for CarrierExecutor
where
    F: Future<Output = ()> + Send + 'static,
{
    fn execute(&self, future: F) {
        let mut tasks = self.0.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(tasks) = tasks.as_mut() {
            tasks.retain(|task| !task.0.is_finished());
            tasks.push(CarrierTask(tokio::spawn(future)));
        }
    }
}

#[cfg(test)]
mod tests {
    use hyper::rt::Executor;
    use tokio::sync::oneshot;

    use super::CarrierTaskOwner;

    struct DropNotice(Option<oneshot::Sender<()>>);

    impl Drop for DropNotice {
        fn drop(&mut self) {
            if let Some(sender) = self.0.take() {
                let _ = sender.send(());
            }
        }
    }

    #[test]
    fn completed_request_tasks_do_not_accumulate_and_shutdown_drops_live_tasks() {
        let runtime = tokio::runtime::Builder::new_current_thread().build().expect("runtime");
        runtime.block_on(async {
            let owner = CarrierTaskOwner::new();
            let executor = owner.executor();
            let (dropped_tx, dropped_rx) = oneshot::channel();
            executor.execute(async move {
                let _notice = DropNotice(Some(dropped_tx));
                std::future::pending::<()>().await;
            });
            for _ in 0..64 {
                let (finished_tx, finished_rx) = oneshot::channel();
                executor.execute(async move {
                    let _ = finished_tx.send(());
                });
                finished_rx.await.expect("request task finished");
                let count = executor.0.lock().expect("task registry").as_ref().expect("open registry").len();
                assert!(count <= 2, "retain only one live carrier task and the latest completed request");
            }
            owner.close().await;
            dropped_rx.await.expect("shutdown joins dropped live task");
        });
    }
}
