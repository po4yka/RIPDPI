use super::*;
use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener};
use std::os::fd::AsRawFd;
use std::time::Duration;

use crate::runtime::config::RuntimeConfig;
use crate::runtime::relay::failure_retry::prepare_relay;
use crate::runtime::types::RuntimeConnectionRoute;
use ripdpi_proxy_runtime_adapter::failure::ClassifiedFailure;
use ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink;

#[derive(Default)]
struct Recorder(std::sync::Mutex<Vec<String>>);
impl RuntimeTelemetrySink for Recorder {
    fn on_listener_started(&self, _: SocketAddr, _: usize, _: usize) {}
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

#[derive(Clone, Copy)]
enum Scenario {
    ClientRstOriginSilent,
    ClientFinOriginClosesOnEof,
    ClientFinOriginAnswers,
    OriginAnswers,
}

fn run(scenario: Scenario) -> Vec<String> {
    let (mut client, mut app) = pair();
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
    let route = RuntimeConnectionRoute { group_index: 0, attempted_mask: 1 };
    match prepare_relay(&mut client, upstream, &state, target, route.clone(), None, None, None) {
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
fn a_client_that_leaves_before_the_first_response_is_not_a_failure() {
    for scenario in [
        Scenario::OriginAnswers,
        Scenario::ClientRstOriginSilent,
        Scenario::ClientFinOriginClosesOnEof,
        Scenario::ClientFinOriginAnswers,
    ] {
        assert_eq!(run(scenario), Vec::<String>::new());
    }
}
