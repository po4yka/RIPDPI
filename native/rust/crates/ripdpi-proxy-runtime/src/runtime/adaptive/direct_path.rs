use std::io;
use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_proxy_runtime_adapter::model::decision::TransportProtocol;
use ripdpi_proxy_runtime_adapter::model::ports::DirectPathLearningObserver;
use ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink;

use crate::runtime::state::RuntimeState;

struct RuntimeTelemetryDirectPathObserver<'a>(&'a dyn RuntimeTelemetrySink);

impl DirectPathLearningObserver for RuntimeTelemetryDirectPathObserver<'_> {
    fn on_direct_path_learning_signal(
        &self,
        authority: &str,
        ip_set_digest: &str,
        event: &'static str,
        strategy_family: Option<&str>,
    ) {
        self.0.on_direct_path_learning_signal(authority, ip_set_digest, event, strategy_family);
    }
}

fn direct_path_observer(state: &RuntimeState) -> Option<RuntimeTelemetryDirectPathObserver<'_>> {
    state.telemetry.as_deref().map(RuntimeTelemetryDirectPathObserver)
}

pub(in crate::runtime) fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|value| value.as_millis() as i64).unwrap_or(0)
}

pub(in crate::runtime) fn note_direct_path_transport_attempt(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
    transport: TransportProtocol,
) -> io::Result<()> {
    state.direct_path_learning().note_direct_path_transport_attempt(host, targets, transport);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_udp_suppressed(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    state.direct_path_learning().note_direct_path_udp_suppressed(host, targets, now_millis().max(0) as u64);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_udp_failure(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    state.direct_path_learning().note_direct_path_udp_failure(host, targets);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_quic_success(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let observer = direct_path_observer(state);
    state.direct_path_learning().note_direct_path_quic_success(
        host,
        targets,
        observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
    );
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_tcp_success(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
    strategy_family: Option<&str>,
) -> io::Result<()> {
    let observer = direct_path_observer(state);
    state.direct_path_learning().note_direct_path_tcp_success(
        host,
        targets,
        strategy_family,
        observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
    );
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_tls_post_client_hello_failure(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    state.direct_path_learning().note_direct_path_tls_post_client_hello_failure(host, targets);
    Ok(())
}

pub(in crate::runtime) fn note_direct_path_all_ips_failed(
    state: &RuntimeState,
    host: Option<&str>,
    targets: &[SocketAddr],
) -> io::Result<()> {
    let observer = direct_path_observer(state);
    state.direct_path_learning().note_direct_path_all_ips_failed(
        host,
        targets,
        observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
    );
    Ok(())
}

pub(in crate::runtime) fn emit_due_direct_path_learning_timeouts(state: &RuntimeState) -> io::Result<()> {
    let observer = direct_path_observer(state);
    state.direct_path_learning().emit_due_direct_path_learning_timeouts(
        now_millis().max(0) as u64,
        observer.as_ref().map(|o| o as &dyn DirectPathLearningObserver),
    );
    Ok(())
}
