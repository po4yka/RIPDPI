use super::{TunnelSessionState, ensure_tunnel_destroyable, ensure_tunnel_start_allowed, take_running_tunnel};
use ::loom::sync::{Arc, Mutex};
use tokio_util::sync::CancellationToken;

fn starting_state() -> TunnelSessionState {
    TunnelSessionState::Starting { cancel: std::sync::Arc::new(CancellationToken::new()) }
}

#[test]
fn loom_starting_blocks_concurrent_start() {
    ::loom::model(|| {
        let state = Arc::new(Mutex::new(starting_state()));

        let state1 = state.clone();
        let t1 = ::loom::thread::spawn(move || {
            let s = state1.lock().unwrap();
            ensure_tunnel_start_allowed(&s).is_ok()
        });

        let state2 = state.clone();
        let t2 = ::loom::thread::spawn(move || {
            let s = state2.lock().unwrap();
            ensure_tunnel_start_allowed(&s).is_ok()
        });

        // Both threads observe Starting; neither is allowed to start.
        assert!(!t1.join().unwrap(), "start must be rejected when Starting");
        assert!(!t2.join().unwrap(), "start must be rejected when Starting");
    });
}

#[test]
fn loom_stop_during_starting_returns_not_running() {
    ::loom::model(|| {
        let state = Arc::new(Mutex::new(starting_state()));

        let state1 = state.clone();
        let t_stop = ::loom::thread::spawn(move || {
            let mut s = state1.lock().unwrap();
            take_running_tunnel(&mut s).is_ok()
        });

        let stopped = t_stop.join().unwrap();
        assert!(!stopped, "stop during Starting must return not-running");
        // take_running_tunnel cancels the Starting token but does not reset state.
        let s = state.lock().unwrap();
        match &*s {
            TunnelSessionState::Starting { cancel } => {
                assert!(cancel.is_cancelled(), "cancellation must have been requested");
            }
            _ => panic!("state must remain Starting after stop-during-startup"),
        }
    });
}

#[test]
fn loom_concurrent_start_check_and_destroy_check() {
    ::loom::model(|| {
        let state = Arc::new(Mutex::new(TunnelSessionState::Ready));

        // Thread A: checks start-allowed; if so, transitions to Starting.
        let state1 = state.clone();
        let t_start = ::loom::thread::spawn(move || {
            let mut s = state1.lock().unwrap();
            if ensure_tunnel_start_allowed(&s).is_ok() {
                *s = starting_state();
                true
            } else {
                false
            }
        });

        // Thread B: checks destroyable.
        let state2 = state.clone();
        let t_destroy = ::loom::thread::spawn(move || {
            let s = state2.lock().unwrap();
            ensure_tunnel_destroyable(&s).is_ok()
        });

        let started = t_start.join().unwrap();
        let can_destroy = t_destroy.join().unwrap();

        // The Mutex serializes the two threads.  After both complete, the
        // state is either Ready (A lost the race) or Starting (A won).
        // In both cases the results must be internally consistent:
        // - If A transitioned to Starting, B either saw Ready (destroyable)
        //   or Starting (not destroyable).
        // - If A did not transition, B saw Ready (destroyable).
        let s = state.lock().unwrap();
        match *s {
            TunnelSessionState::Ready => {
                // A did not win; B must have seen Ready -> destroyable.
                assert!(!started);
                assert!(can_destroy);
            }
            TunnelSessionState::Starting { .. } => {
                // A won; B's result was valid for whichever state it observed.
                assert!(started);
                // B saw either Ready (can_destroy=true) or Starting (can_destroy=false).
                // Both are valid; just confirm ensure_tunnel_destroyable now fails.
                assert!(ensure_tunnel_destroyable(&s).is_err());
            }
            TunnelSessionState::CleanupPending { .. }
            | TunnelSessionState::Running { .. }
            | TunnelSessionState::Destroyed => {
                panic!("unexpected cleanup/running/destroyed state in loom test");
            }
        }
    });
}

mod tests {
    use ::loom::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    use super::*;

    #[derive(Default)]
    struct ModelCancel {
        cancelled: AtomicBool,
    }

    struct ModelWorker {
        drop_count: Arc<AtomicUsize>,
    }

    impl Drop for ModelWorker {
        fn drop(&mut self) {
            // Ordering: the test joins both model threads before reading this
            // counter, so no cross-thread data is published through the increment.
            self.drop_count.fetch_add(1, Ordering::Relaxed);
        }
    }

    enum ModelState {
        Ready,
        Starting { cancel: Arc<ModelCancel> },
        Running { cancel: Arc<ModelCancel>, worker: ModelWorker },
    }

    const STOP_CANCELLED_STARTING: usize = 1;
    const STOP_TOOK_RUNNING: usize = 2;
    const PROMOTE_CANCELLED: usize = 1;
    const PROMOTE_RUNNING: usize = 2;

    fn model_stop(state: &Mutex<ModelState>) -> usize {
        let mut state = state.lock().unwrap();
        match &*state {
            ModelState::Starting { cancel } => {
                // Ordering: this models `CancellationToken::cancel()` publishing a
                // stop request while the state mutex serializes the state change.
                cancel.cancelled.store(true, Ordering::Release);
                STOP_CANCELLED_STARTING
            }
            ModelState::Running { .. } => {
                let running = std::mem::replace(&mut *state, ModelState::Ready);
                drop(running);
                STOP_TOOK_RUNNING
            }
            ModelState::Ready => panic!("stop must race only with a starting or promoted model session"),
        }
    }

    fn model_promote_ready(state: &Mutex<ModelState>, cancel: Arc<ModelCancel>, worker: ModelWorker) -> usize {
        let mut worker = Some(worker);
        let mut state = state.lock().unwrap();
        match &*state {
            ModelState::Starting { cancel: state_cancel } if Arc::ptr_eq(state_cancel, &cancel) => {
                // Ordering: pairs with the stop-side Release store; the mutex gives
                // mutual exclusion for state identity, while this load models the
                // cancellation request carried by the generation token.
                if cancel.cancelled.load(Ordering::Acquire) {
                    PROMOTE_CANCELLED
                } else {
                    *state = ModelState::Running { cancel, worker: worker.take().unwrap() };
                    PROMOTE_RUNNING
                }
            }
            ModelState::Ready | ModelState::Starting { .. } | ModelState::Running { .. } => PROMOTE_CANCELLED,
        }
    }

    #[test]
    fn loom_stop_during_starting_races_native_readiness_promotion() {
        ::loom::model(|| {
            let cancel = Arc::new(ModelCancel::default());
            let worker_drop_count = Arc::new(AtomicUsize::new(0));
            let state = Arc::new(Mutex::new(ModelState::Starting { cancel: Arc::clone(&cancel) }));
            let stop_outcome = Arc::new(AtomicUsize::new(0));
            let promote_outcome = Arc::new(AtomicUsize::new(0));

            let stop_state = Arc::clone(&state);
            let stop_result = Arc::clone(&stop_outcome);
            let stop_thread = ::loom::thread::spawn(move || {
                let outcome = model_stop(&stop_state);
                // Ordering: the parent joins this thread before loading the
                // outcome, so Relaxed is enough for the test result slot.
                stop_result.store(outcome, Ordering::Relaxed);
            });

            let promote_state = Arc::clone(&state);
            let promote_cancel = Arc::clone(&cancel);
            let promote_result = Arc::clone(&promote_outcome);
            let promote_worker = ModelWorker { drop_count: Arc::clone(&worker_drop_count) };
            let promote_thread = ::loom::thread::spawn(move || {
                let outcome = model_promote_ready(&promote_state, promote_cancel, promote_worker);
                // Ordering: the parent joins this thread before loading the
                // outcome, so Relaxed is enough for the test result slot.
                promote_result.store(outcome, Ordering::Relaxed);
            });

            stop_thread.join().unwrap();
            promote_thread.join().unwrap();

            // Ordering: both result writers have been joined; these loads do not
            // publish data to other threads.
            let stop = stop_outcome.load(Ordering::Relaxed);
            let promote = promote_outcome.load(Ordering::Relaxed);
            match (stop, promote) {
                // stop won the mutex first: it cancelled the startup token, and
                // promotion must observe that cancellation under the same state
                // generation instead of publishing Running.
                (STOP_CANCELLED_STARTING, PROMOTE_CANCELLED) => {
                    let state = state.lock().unwrap();
                    match &*state {
                        ModelState::Starting { cancel } => {
                            assert!(cancel.cancelled.load(Ordering::Acquire));
                        }
                        ModelState::Ready | ModelState::Running { .. } => {
                            panic!("cancelled startup must not promote Running");
                        }
                    }
                }
                // promotion won the mutex first: it is allowed to publish Running
                // exactly once, and the racing stop then owns and retires that
                // single worker.
                (STOP_TOOK_RUNNING, PROMOTE_RUNNING) => {
                    let state = state.lock().unwrap();
                    assert!(matches!(&*state, ModelState::Ready));
                }
                other => panic!("illegal stop/promote linearization: {other:?}"),
            }

            // Ordering: both model threads have joined and final state was
            // inspected, so Relaxed is sufficient for the single-owner check.
            assert_eq!(worker_drop_count.load(Ordering::Relaxed), 1);
        });
    }
}
