use std::sync::atomic::Ordering;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use proptest::collection::vec;
use proptest::prelude::*;
use ripdpi_tunnel_core::Stats;
use tokio_util::sync::CancellationToken;

use crate::config::{config_from_payload, mapdns_resolver_protocol, sample_payload};
use crate::telemetry::{NativeRuntimeSnapshot, TunnelTelemetryState};
use crate::to_handle;

use super::{
    ensure_tunnel_destroyable, ensure_tunnel_start_allowed, lookup_tunnel_session, remove_tunnel_session,
    stats_snapshots_for_state, take_running_tunnel, TunnelSession, TunnelSessionState, SESSIONS,
};

#[derive(Clone, Copy, Debug)]
enum TunnelStateCommand {
    EnsureCreated,
    Start,
    Stop,
    Stats,
    Telemetry,
    Destroy,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum TunnelModelState {
    Absent,
    Ready,
    Running,
}

#[derive(Default)]
struct TunnelSessionHarness {
    active_handle: Option<jni::sys::jlong>,
    stale_handle: Option<jni::sys::jlong>,
}

impl TunnelSessionHarness {
    fn tracked_handle(&self) -> jni::sys::jlong {
        self.active_handle.or(self.stale_handle).unwrap_or(0)
    }

    fn ensure_created(&mut self) -> jni::sys::jlong {
        if let Some(handle) = self.active_handle {
            return handle;
        }

        let handle = SESSIONS.insert(TunnelSession {
            runtime: Arc::new(tokio::runtime::Builder::new_current_thread().build().expect("test runtime")),
            config: Arc::new(config_from_payload(sample_payload()).expect("config")),
            last_error: Arc::new(Mutex::new(None)),
            telemetry: Arc::new(TunnelTelemetryState::new()),
            state: Mutex::new(TunnelSessionState::Ready),
        }) as jni::sys::jlong;
        self.active_handle = Some(handle);
        self.stale_handle = Some(handle);
        handle
    }

    fn start(&mut self) -> Result<(), &'static str> {
        let session = lookup_tunnel_session(self.tracked_handle())?;
        let state = session.state.lock().expect("tunnel state lock");
        ensure_tunnel_start_allowed(&state)?;
        drop(state);

        let cancel = Arc::new(CancellationToken::new());
        let stats = Arc::new(Stats::new());
        stats.tx_packets.fetch_add(7, Ordering::Relaxed);
        stats.tx_bytes.fetch_add(70, Ordering::Relaxed);
        stats.rx_packets.fetch_add(8, Ordering::Relaxed);
        stats.rx_bytes.fetch_add(80, Ordering::Relaxed);

        session.telemetry.mark_started(format!("{}:{}", session.config.socks5.address, session.config.socks5.port));

        let worker_cancel = cancel.clone();
        let worker_telemetry = session.telemetry.clone();
        let worker = std::thread::spawn(move || {
            while !worker_cancel.is_cancelled() {
                std::thread::sleep(Duration::from_millis(1));
            }
            worker_telemetry.mark_stopped();
        });

        let mut state = session.state.lock().expect("tunnel state lock");
        *state = TunnelSessionState::Running { cancel, stats, worker };
        Ok(())
    }

    fn stop(&mut self) -> Result<(), &'static str> {
        let session = lookup_tunnel_session(self.tracked_handle())?;
        let running = {
            let mut state = session.state.lock().expect("tunnel state lock");
            take_running_tunnel(&mut state)?
        };

        session.telemetry.mark_stop_requested();
        running.0.cancel();
        let _ = running.1.join();
        Ok(())
    }

    fn stats(&self) -> Result<(u64, u64, u64, u64), &'static str> {
        let session = lookup_tunnel_session(self.tracked_handle())?;
        let state = session.state.lock().expect("tunnel state lock");
        Ok(stats_snapshots_for_state(&state).0)
    }

    fn telemetry(&self) -> Result<NativeRuntimeSnapshot, &'static str> {
        let session = lookup_tunnel_session(self.tracked_handle())?;
        let state = session.state.lock().expect("tunnel state lock");
        let (traffic, dns) = stats_snapshots_for_state(&state);
        let resolver_id = session.config.mapdns.as_ref().and_then(|mapdns| mapdns.resolver_id.clone());
        let resolver_protocol = session.config.mapdns.as_ref().and_then(mapdns_resolver_protocol);
        Ok(session.telemetry.snapshot(traffic, dns, resolver_id, resolver_protocol))
    }

    fn destroy(&mut self) -> Result<(), &'static str> {
        let session = lookup_tunnel_session(self.tracked_handle())?;
        let state = session.state.lock().expect("tunnel state lock");
        ensure_tunnel_destroyable(&state)?;
        drop(state);
        let handle = self.active_handle.take().unwrap_or_else(|| self.tracked_handle());
        self.stale_handle = Some(handle);
        let _ = remove_tunnel_session(handle)?;
        Ok(())
    }

    fn cleanup(&mut self) {
        if let Some(handle) = self.active_handle.take() {
            if let Ok(session) = lookup_tunnel_session(handle) {
                let running = {
                    let mut state = session.state.lock().expect("tunnel state lock");
                    take_running_tunnel(&mut state).ok()
                };
                if let Some(running) = running {
                    running.0.cancel();
                    let _ = running.1.join();
                }
            }
            let _ = remove_tunnel_session(handle);
            self.stale_handle = Some(handle);
        }
    }
}

impl Drop for TunnelSessionHarness {
    fn drop(&mut self) {
        self.cleanup();
    }
}

fn tunnel_absent_error(handle: jni::sys::jlong) -> &'static str {
    if to_handle(handle).is_some() {
        "Unknown tunnel handle"
    } else {
        "Invalid tunnel handle"
    }
}

fn tunnel_state_command_strategy() -> impl Strategy<Value = Vec<TunnelStateCommand>> {
    vec(
        prop_oneof![
            Just(TunnelStateCommand::EnsureCreated),
            Just(TunnelStateCommand::Start),
            Just(TunnelStateCommand::Stop),
            Just(TunnelStateCommand::Stats),
            Just(TunnelStateCommand::Telemetry),
            Just(TunnelStateCommand::Destroy),
        ],
        1..32,
    )
}

proptest! {
    #[test]
    fn tunnel_session_state_machine(commands in tunnel_state_command_strategy()) {
        let mut harness = TunnelSessionHarness::default();
        let mut model = TunnelModelState::Absent;

        for command in commands {
            match command {
                TunnelStateCommand::EnsureCreated => {
                    let handle = harness.ensure_created();
                    prop_assert!(lookup_tunnel_session(handle).is_ok());
                    if matches!(model, TunnelModelState::Absent) {
                        model = TunnelModelState::Ready;
                    }
                }
                TunnelStateCommand::Start => {
                    match model {
                        TunnelModelState::Absent => {
                            let err = harness.start().expect_err("absent start must fail");
                            prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                        }
                        TunnelModelState::Ready => {
                            harness.start().expect("ready start");
                            model = TunnelModelState::Running;
                        }
                        TunnelModelState::Running => {
                            let err = harness.start().expect_err("duplicate start must fail");
                            prop_assert_eq!(err, "Tunnel session is already running");
                        }
                    }
                }
                TunnelStateCommand::Stop => {
                    match model {
                        TunnelModelState::Absent => {
                            let err = harness.stop().expect_err("absent stop must fail");
                            prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                        }
                        TunnelModelState::Ready => {
                            let err = harness.stop().expect_err("ready stop must fail");
                            prop_assert_eq!(err, "Tunnel session is not running");
                        }
                        TunnelModelState::Running => {
                            harness.stop().expect("running stop");
                            model = TunnelModelState::Ready;
                        }
                    }
                }
                TunnelStateCommand::Stats => {
                    match model {
                        TunnelModelState::Absent => {
                            let err = harness.stats().expect_err("absent stats must fail");
                            prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                        }
                        TunnelModelState::Ready => {
                            prop_assert_eq!(harness.stats().expect("ready stats"), (0, 0, 0, 0));
                        }
                        TunnelModelState::Running => {
                            prop_assert_eq!(harness.stats().expect("running stats"), (7, 70, 8, 80));
                        }
                    }
                }
                TunnelStateCommand::Telemetry => {
                    match model {
                        TunnelModelState::Absent => {
                            let err = harness.telemetry().expect_err("absent telemetry must fail");
                            prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                        }
                        TunnelModelState::Ready => {
                            let snapshot = harness.telemetry().expect("ready telemetry");
                            prop_assert_eq!(snapshot.state, "idle");
                            prop_assert_eq!(snapshot.tunnel_stats.tx_packets, 0);
                        }
                        TunnelModelState::Running => {
                            let snapshot = harness.telemetry().expect("running telemetry");
                            prop_assert_eq!(snapshot.state, "running");
                            prop_assert_eq!(snapshot.active_sessions, 1);
                            prop_assert_eq!(snapshot.tunnel_stats.tx_packets, 7);
                            prop_assert_eq!(snapshot.tunnel_stats.rx_bytes, 80);
                        }
                    }
                }
                TunnelStateCommand::Destroy => {
                    match model {
                        TunnelModelState::Absent => {
                            let err = harness.destroy().expect_err("absent destroy must fail");
                            prop_assert_eq!(err, tunnel_absent_error(harness.tracked_handle()));
                        }
                        TunnelModelState::Ready => {
                            harness.destroy().expect("ready destroy");
                            model = TunnelModelState::Absent;
                        }
                        TunnelModelState::Running => {
                            let err = harness.destroy().expect_err("running destroy must fail");
                            prop_assert_eq!(err, "Cannot destroy a running tunnel session");
                        }
                    }
                }
            }

            match model {
                TunnelModelState::Absent => {
                    if to_handle(harness.tracked_handle()).is_some() {
                        let err = match lookup_tunnel_session(harness.tracked_handle()) {
                            Ok(_) => panic!("absent tunnel must be removed"),
                            Err(err) => err,
                        };
                        prop_assert_eq!(err, "Unknown tunnel handle");
                    }
                }
                TunnelModelState::Ready => {
                    let session = lookup_tunnel_session(harness.tracked_handle()).expect("ready tunnel");
                    let state = session.state.lock().expect("tunnel state lock");
                    prop_assert!(matches!(*state, TunnelSessionState::Ready));
                }
                TunnelModelState::Running => {
                    let session = lookup_tunnel_session(harness.tracked_handle()).expect("running tunnel");
                    let state = session.state.lock().expect("tunnel state lock");
                    let is_running = matches!(*state, TunnelSessionState::Running { .. });
                    prop_assert!(is_running);
                }
            }
        }
    }
}
