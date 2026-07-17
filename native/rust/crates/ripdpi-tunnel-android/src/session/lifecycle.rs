use android_support::{throw_illegal_argument_env, throw_illegal_state_env};
use jni::Env;
use jni::sys::jlong;

use super::pcap;
use super::registry::{TunnelSessionState, lookup_tunnel_session, remove_tunnel_session};

mod create;
mod fd;
mod readiness;
mod start;
mod state;
mod telemetry;
mod validation;
mod worker;

pub(crate) use create::create_session;
pub(crate) use readiness::rollback_failed_tunnel_start;
pub(crate) use start::start_session;
pub(crate) use state::{ensure_tunnel_destroyable, ensure_tunnel_start_allowed, take_running_tunnel};
pub(crate) use validation::validate_tun_fd;

pub(crate) fn stop_session(env: &mut Env<'_>, handle: jlong) {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return;
        }
    };

    let running = {
        let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        match take_running_tunnel(&mut state) {
            Ok(running) => running,
            Err(message) => {
                throw_illegal_state_env(env, message);
                return;
            }
        }
    };

    running.0.cancel();
    pcap::pcap_retire_entry(handle);
    session.telemetry.mark_stop_requested();
    if running.1.join().is_err() {
        session.telemetry.log_line("worker", "error", "tunnel worker panicked during shutdown");
    }
}

pub(crate) fn destroy_session(env: &mut Env<'_>, handle: jlong) {
    let session = match lookup_tunnel_session(handle) {
        Ok(session) => session,
        Err(message) => {
            throw_illegal_argument_env(env, message);
            return;
        }
    };
    let mut state = session.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Err(message) = ensure_tunnel_destroyable(&state) {
        throw_illegal_state_env(env, message);
        return;
    }
    *state = TunnelSessionState::Destroyed;
    drop(state);
    pcap::pcap_retire_entry(handle);
    let _ = remove_tunnel_session(handle);
}
