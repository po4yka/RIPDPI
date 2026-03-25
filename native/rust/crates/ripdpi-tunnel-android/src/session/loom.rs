use super::*;
use loom::sync::{Arc, Mutex};

#[test]
fn loom_starting_blocks_concurrent_start() {
    loom::model(|| {
        let state = Arc::new(Mutex::new(TunnelSessionState::Starting));

        let state1 = state.clone();
        let t1 = loom::thread::spawn(move || {
            let s = state1.lock().unwrap();
            ensure_tunnel_start_allowed(&s).is_ok()
        });

        let state2 = state.clone();
        let t2 = loom::thread::spawn(move || {
            let s = state2.lock().unwrap();
            ensure_tunnel_start_allowed(&s).is_ok()
        });

        let r1 = t1.join().unwrap();
        let r2 = t2.join().unwrap();
        // Both threads observe Starting; neither is allowed to start.
        assert!(!r1, "start must be rejected when Starting");
        assert!(!r2, "start must be rejected when Starting");
    });
}

#[test]
fn loom_stop_during_starting_returns_not_running() {
    loom::model(|| {
        let state = Arc::new(Mutex::new(TunnelSessionState::Starting));

        let state1 = state.clone();
        let t_stop = loom::thread::spawn(move || {
            let mut s = state1.lock().unwrap();
            take_running_tunnel(&mut s).is_ok()
        });

        let stopped = t_stop.join().unwrap();
        assert!(!stopped, "stop during Starting must return not-running");
        // take_running_tunnel resets Starting -> Ready on non-Running match.
        let s = state.lock().unwrap();
        assert!(matches!(*s, TunnelSessionState::Ready));
    });
}

#[test]
fn loom_concurrent_start_check_and_destroy_check() {
    loom::model(|| {
        let state = Arc::new(Mutex::new(TunnelSessionState::Ready));

        // Thread A: checks start-allowed; if so, transitions to Starting.
        let state1 = state.clone();
        let t_start = loom::thread::spawn(move || {
            let mut s = state1.lock().unwrap();
            if ensure_tunnel_start_allowed(&s).is_ok() {
                *s = TunnelSessionState::Starting;
                true
            } else {
                false
            }
        });

        // Thread B: checks destroyable.
        let state2 = state.clone();
        let t_destroy = loom::thread::spawn(move || {
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
            TunnelSessionState::Starting => {
                // A won; B's result was valid for whichever state it observed.
                assert!(started);
                // B saw either Ready (can_destroy=true) or Starting (can_destroy=false).
                // Both are valid; just confirm ensure_tunnel_destroyable now fails.
                assert!(ensure_tunnel_destroyable(&s).is_err());
            }
            TunnelSessionState::Running { .. } | TunnelSessionState::Destroyed => {
                panic!("unexpected Running/Destroyed state in loom test");
            }
        }
    });
}
