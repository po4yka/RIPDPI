use std::time::Duration;

use tokio::task::JoinHandle;

pub(super) const TASK_SHUTDOWN_GRACE: Duration = Duration::from_secs(5);

/// # Cancel safety
///
/// Cancel-safe. Every handle is placed in an abort-on-drop guard before the
/// first await, so dropping this future aborts every task not yet joined.
pub(super) async fn drain_tasks<T>(tasks: Vec<JoinHandle<T>>, grace: Duration) {
    drain_guarded_tasks(AbortTasksOnDrop(tasks), grace).await;
}

pub(super) fn spawn_task_drain<T>(task: JoinHandle<T>, grace: Duration)
where
    T: Send + 'static,
{
    let tasks = AbortTasksOnDrop(vec![task]);
    tokio::spawn(drain_guarded_tasks(tasks, grace));
}

/// # Cancel safety
///
/// Cancel-safe. `tasks` aborts every unfinished handle in `Drop`, including if
/// this future is cancelled while joining or while the grace timer is pending.
async fn drain_guarded_tasks<T>(mut tasks: AbortTasksOnDrop<T>, grace: Duration) {
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

#[cfg(test)]
mod tests {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};

    use super::*;

    struct Alive(Arc<AtomicBool>);

    impl Drop for Alive {
        fn drop(&mut self) {
            self.0.store(false, Ordering::Release);
        }
    }

    #[tokio::test(start_paused = true)]
    async fn spawned_drain_gives_task_grace_then_aborts_and_joins() {
        let alive = Arc::new(AtomicBool::new(true));
        let task_alive = Alive(Arc::clone(&alive));
        let task = tokio::spawn(async move {
            let _task_alive = task_alive;
            std::future::pending::<()>().await;
        });
        tokio::task::yield_now().await;

        spawn_task_drain(task, Duration::from_secs(1));
        tokio::task::yield_now().await;
        assert!(alive.load(Ordering::Acquire), "task must receive its graceful drain window");

        tokio::time::advance(Duration::from_secs(1)).await;
        tokio::task::yield_now().await;
        assert!(!alive.load(Ordering::Acquire), "stalled task must be aborted and joined after grace");
    }
}
