use super::*;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener};
use std::os::fd::AsRawFd;
use std::time::Duration;

use crate::runtime::config::RuntimeConfig;
use crate::runtime::relay::failure_retry::prepare_relay;
use crate::runtime::types::RuntimeConnectionRoute;
use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
use ripdpi_proxy_runtime_adapter::model::runtime_api::{ListenerAddress, RuntimeTelemetrySink};

#[derive(Default)]
struct Recorder(std::sync::Mutex<Vec<String>>);
impl RuntimeTelemetrySink for Recorder {
    fn on_listener_started(&self, _: ListenerAddress, _: usize, _: usize) {}
    fn on_listener_stopped(&self) {}
    fn on_client_accepted(&self) {}
    fn on_client_finished(&self) {}
    fn on_client_error(&self, _: &io::Error) {}
    fn on_route_selected(&self, _: SocketAddr, _: usize, _: Option<&str>, _: &'static str) {}
    fn on_failure_classified(&self, _: SocketAddr, f: &ClassifiedFailure, _: Option<&str>) {
        self.0.lock().unwrap().push(format!("{}: {}", f.class.as_str(), f.evidence.summary));
    }
    fn on_route_advanced(&self, _: SocketAddr, _: usize, _: usize, _: u32, _: Option<&str>) {}
    fn on_host_autolearn_state(&self, _: bool, _: usize, _: usize, _: usize, _: Option<&str>, _: Option<&str>) {}
    fn on_host_autolearn_event(&self, _: &'static str, _: Option<&str>, _: Option<usize>) {}
}

fn pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
    let first = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
    let second = listener.accept().unwrap().0;
    (first, second)
}

fn rst(s: TcpStream) {
    let l = libc::linger { l_onoff: 1, l_linger: 0 };
    // SAFETY: the fd is open for the lifetime of `s`, and `l` outlives the call with the size passed.
    let r = unsafe {
        libc::setsockopt(
            s.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_LINGER,
            (&raw const l).cast(),
            size_of::<libc::linger>() as libc::socklen_t,
        )
    };
    assert_eq!(r, 0);
    drop(s);
}

#[derive(Clone, Copy, Debug)]
enum Scenario {
    ClientRstOriginSilent,
    ClientFinOriginClosesOnEof,
    ClientFinOriginAnswers,
    OriginAnswers,
}

fn run(scenario: Scenario) -> Vec<String> {
    let (client, mut app) = pair();
    let mut client = ClientStream::Tcp(client);
    let (upstream, mut origin) = pair();
    let target = upstream.peer_addr().unwrap();
    let request = b"GET / HTTP/1.1\r\nHost: fixture.test\r\n\r\n";
    app.write_all(request).unwrap();
    let origin_thread = thread::spawn(move || {
        let mut buf = vec![0; request.len()];
        origin.read_exact(&mut buf).unwrap();
        let resp = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
        match scenario {
            Scenario::OriginAnswers => {
                origin.write_all(resp).unwrap();
            }
            Scenario::ClientRstOriginSilent => {
                thread::sleep(Duration::from_millis(1500));
            }
            Scenario::ClientFinOriginClosesOnEof => {
                let mut b = [0; 8];
                let _ = origin.read(&mut b);
            }
            Scenario::ClientFinOriginAnswers => {
                let mut b = [0; 8];
                let _ = origin.read(&mut b);
                let _ = origin.write_all(resp);
            }
        }
    });
    let app_thread = thread::spawn(move || match scenario {
        Scenario::OriginAnswers => {
            let mut b = [0; 64];
            let _ = app.read(&mut b);
        }
        Scenario::ClientRstOriginSilent => {
            thread::sleep(Duration::from_millis(200));
            rst(app);
        }
        _ => {
            thread::sleep(Duration::from_millis(200));
            drop(app);
        }
    });
    let mut config = RuntimeConfig::default();
    config.host_autolearn.enabled = true;
    let telemetry = std::sync::Arc::new(Recorder::default());
    let state = RuntimeState::test_with_telemetry(config, Some(telemetry.clone()));
    let route = RuntimeConnectionRoute { group_index: 0, attempted_mask: 1, route_advances: 0 };
    match prepare_relay(
        &mut client,
        upstream,
        &state,
        target,
        route.clone(),
        None,
        crate::runtime::relay::RelayFlow::default(),
        None,
    ) {
        Ok(prepared) => {
            let settings = RelayStreamSettings {
                group: state.relay_group(0).unwrap(),
                rotation_seed: state.relay_rotation_seed(0).unwrap(),
            };
            let _ = relay_streams(client, prepared.upstream, &state, 0, settings, prepared.session_state, None);
        }
        Err(error) => panic!("prepare_relay failed: {error}"),
    }
    app_thread.join().unwrap();
    origin_thread.join().unwrap();
    telemetry.0.lock().unwrap().clone()
}

#[test]
fn first_response_outcomes_distinguish_client_reset_from_origin_eof() {
    for (scenario, expected) in [
        (Scenario::OriginAnswers, Vec::<String>::new()),
        (Scenario::ClientRstOriginSilent, Vec::new()),
        (Scenario::ClientFinOriginClosesOnEof, vec!["silent_drop: upstream closed before first response".to_owned()]),
        (Scenario::ClientFinOriginAnswers, Vec::new()),
    ] {
        assert_eq!(run(scenario), expected, "{scenario:?}");
    }
}

#[derive(Clone, Copy, Debug)]
enum RotationEnd {
    ClientReset,
    ClientFinOriginFin,
    ClientFinOriginReset,
    ClientFinOriginResponse,
    RuntimeStop,
}

fn run_rotation(end: RotationEnd) -> Option<String> {
    use ripdpi_proxy_runtime_adapter::model::config::{RotationCandidate, RotationPolicy};
    use ripdpi_proxy_runtime_adapter::model::ports::PolicyLearningPort;
    use ripdpi_proxy_runtime_adapter::model::runtime_api::EmbeddedProxyControl;
    use std::sync::mpsc;

    const FIRST: &[u8] = b"GET /first HTTP/1.1\r\nHost: fixture.test\r\n\r\n";
    const NEXT: &[u8] = b"GET /next HTTP/1.1\r\nHost: fixture.test\r\n\r\n";
    const RESPONSE: &[u8] = b"HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n";
    let (client, mut app) = pair();
    let mut client = ClientStream::Tcp(client);
    let (upstream, mut origin) = pair();
    for socket in [&app, &origin] {
        socket.set_read_timeout(Some(Duration::from_secs(3))).expect("bounded fixture read");
    }
    let target = upstream.peer_addr().expect("target");
    let mut config = RuntimeConfig::default();
    config.host_autolearn.enabled = true;
    config.groups[0].actions.rotation_policy =
        Some(RotationPolicy { candidates: vec![RotationCandidate::default()], ..RotationPolicy::default() });
    let control = std::sync::Arc::new(EmbeddedProxyControl::new(None));
    let state = RuntimeState::test_with_control(config, control.clone());
    let _client_guard = state.register_active_client_socket(&client).expect("register client");
    let _upstream_guard = state.register_active_upstream_tcp_socket(&upstream).expect("register upstream");
    let (next_tx, next_rx) = mpsc::sync_channel(1);
    let server = thread::spawn(move || {
        let mut first = vec![0; FIRST.len()];
        origin.read_exact(&mut first).expect("first request arrives");
        assert_eq!(first, FIRST);
        origin.write_all(RESPONSE).expect("accepted first response");
        let mut next = vec![0; NEXT.len()];
        origin.read_exact(&mut next).expect("new rotation round arrives");
        assert_eq!(next, NEXT);
        next_tx.send(()).expect("new round is on the wire");
        match end {
            RotationEnd::ClientReset | RotationEnd::RuntimeStop => {
                assert_eq!(origin.read(&mut [0]).expect("local relay shutdown closes upstream"), 0);
            }
            RotationEnd::ClientFinOriginFin => {
                assert_eq!(origin.read(&mut [0]).expect("client FIN reaches origin"), 0);
            }
            RotationEnd::ClientFinOriginReset => {
                assert_eq!(origin.read(&mut [0]).expect("client FIN reaches origin"), 0);
                rst(origin);
            }
            RotationEnd::ClientFinOriginResponse => {
                assert_eq!(origin.read(&mut [0]).expect("client FIN reaches origin"), 0);
                origin.write_all(RESPONSE).expect("response after client FIN");
            }
        }
    });
    app.write_all(FIRST).expect("initial request");
    let prepared = prepare_relay(
        &mut client,
        upstream,
        &state,
        target,
        RuntimeConnectionRoute { group_index: 0, attempted_mask: 1, route_advances: 0 },
        None,
        crate::runtime::relay::RelayFlow::default(),
        None,
    )
    .expect("real first exchange accepted");
    assert_eq!(prepared.success_host.as_deref(), Some("fixture.test"));
    // One prior signal is unconfirmed. Cancellation must not provide its second confirmation.
    state.note_block_signal("fixture.test", crate::runtime::failure::RuntimeBlockSignal::TcpReset, None, true);
    assert_eq!(state.test_services().autolearn_state().last_block_signal, None);
    let stopping_state = state.clone();
    let app_thread = thread::spawn(move || {
        let mut response = vec![0; RESPONSE.len()];
        app.read_exact(&mut response).expect("accepted response delivered");
        assert_eq!(response, RESPONSE);
        app.write_all(NEXT).expect("next request");
        next_rx.recv_timeout(Duration::from_secs(3)).expect("wait for actual next round bytes");
        match end {
            RotationEnd::ClientReset => rst(app),
            RotationEnd::RuntimeStop => {
                control.request_shutdown();
                stopping_state.shutdown_active_tcp_sockets();
            }
            _ => {
                app.shutdown(Shutdown::Write).expect("ordinary client half-close");
                if matches!(end, RotationEnd::ClientFinOriginResponse) {
                    app.read_exact(&mut response).expect("response survives ordinary FIN");
                    assert_eq!(response, RESPONSE);
                } else {
                    let _ = app.read(&mut [0]);
                }
            }
        }
    });
    let settings = RelayStreamSettings {
        group: state.relay_group(0).expect("relay group"),
        rotation_seed: state.relay_rotation_seed(0).expect("rotation policy"),
    };
    let _ =
        relay_streams(client, prepared.upstream, &state, 0, settings, prepared.session_state, prepared.success_host);
    app_thread.join().expect("app worker");
    server.join().expect("origin worker");
    state.test_services().autolearn_state().last_block_signal
}

#[test]
fn client_reset_during_a_rotation_round_is_not_a_block_signal() {
    assert_eq!(run_rotation(RotationEnd::ClientReset), None);
}

#[test]
fn runtime_stop_during_a_rotation_round_is_not_a_block_signal() {
    assert_eq!(run_rotation(RotationEnd::RuntimeStop), None);
}

#[test]
fn ordinary_client_fin_preserves_genuine_origin_closure_failures() {
    for end in [RotationEnd::ClientFinOriginFin, RotationEnd::ClientFinOriginReset] {
        assert_eq!(run_rotation(end).as_deref(), Some("tcp_reset"), "{end:?}");
    }
}

#[test]
fn ordinary_client_fin_preserves_a_later_origin_response() {
    assert_eq!(run_rotation(RotationEnd::ClientFinOriginResponse), None);
}
