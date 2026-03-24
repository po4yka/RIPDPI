use loom::sync::{Arc, Mutex};
use ripdpi_runtime::EmbeddedProxyControl;

use super::registry::{ensure_proxy_destroyable, listener_fd_for_proxy_stop, try_mark_proxy_running, ProxySessionState};

#[test]
fn loom_two_concurrent_starts_one_wins() {
    loom::model(|| {
        let state = Arc::new(Mutex::new(ProxySessionState::Idle));
        let ctrl1 = std::sync::Arc::new(EmbeddedProxyControl::default());
        let ctrl2 = std::sync::Arc::new(EmbeddedProxyControl::default());

        let state1 = state.clone();
        let t1 = loom::thread::spawn(move || {
            let mut s = state1.lock().unwrap();
            try_mark_proxy_running(&mut s, 1, ctrl1).is_ok()
        });

        let state2 = state.clone();
        let t2 = loom::thread::spawn(move || {
            let mut s = state2.lock().unwrap();
            try_mark_proxy_running(&mut s, 2, ctrl2).is_ok()
        });

        let r1 = t1.join().unwrap();
        let r2 = t2.join().unwrap();
        assert_ne!(r1, r2, "exactly one start must win");
    });
}

#[test]
fn loom_concurrent_start_and_stop() {
    loom::model(|| {
        let state = Arc::new(Mutex::new(ProxySessionState::Idle));
        let ctrl = std::sync::Arc::new(EmbeddedProxyControl::default());

        let state1 = state.clone();
        let ctrl1 = ctrl.clone();
        let t_start = loom::thread::spawn(move || {
            let mut s = state1.lock().unwrap();
            let _ = try_mark_proxy_running(&mut s, 42, ctrl1);
        });

        let state2 = state.clone();
        let t_stop = loom::thread::spawn(move || {
            let s = state2.lock().unwrap();
            listener_fd_for_proxy_stop(&s).ok().map(|(fd, _)| fd)
        });

        t_start.join().unwrap();
        let _fd = t_stop.join().unwrap();
    });
}

#[test]
fn loom_stop_then_destroy_consistent() {
    loom::model(|| {
        let ctrl = std::sync::Arc::new(EmbeddedProxyControl::default());
        let state = Arc::new(Mutex::new(ProxySessionState::Running { listener_fd: 42, control: ctrl }));

        let state1 = state.clone();
        let t_stop = loom::thread::spawn(move || {
            let mut s = state1.lock().unwrap();
            if listener_fd_for_proxy_stop(&s).is_ok() {
                *s = ProxySessionState::Idle;
            }
        });

        let state2 = state.clone();
        let t_destroy_check = loom::thread::spawn(move || {
            let s = state2.lock().unwrap();
            ensure_proxy_destroyable(&s).is_ok()
        });

        t_stop.join().unwrap();
        let _ = t_destroy_check.join().unwrap();

        // After stop, state must be destroyable.
        let s = state.lock().unwrap();
        assert!(ensure_proxy_destroyable(&s).is_ok());
    });
}
