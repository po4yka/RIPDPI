use std::time::Duration;

use tokio::task::JoinHandle;

pub(super) const TASK_SHUTDOWN_GRACE: Duration = Duration::from_secs(5);

/// cancel-safe: dropping this future aborts every task that has not completed.
pub(super) async fn drain_tasks<T>(tasks: Vec<JoinHandle<T>>, grace: Duration) {
    let mut tasks = AbortTasksOnDrop(tasks);
    let mut next = 0;
    let graceful = async {
        while next < tasks.0.len() {
            let _ = (&mut tasks.0[next]).await;
            next += 1;
        }
    };

    if tokio::time::timeout(grace, graceful).await.is_err() {
        for task in &tasks.0[next..] {
            task.abort();
        }
        for task in &mut tasks.0[next..] {
            let _ = task.await;
        }
    }
}

struct AbortTasksOnDrop<T>(Vec<JoinHandle<T>>);

impl<T> Drop for AbortTasksOnDrop<T> {
    fn drop(&mut self) {
        for task in &self.0 {
            task.abort();
        }
    }
}
