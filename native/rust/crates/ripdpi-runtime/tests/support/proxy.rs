use std::io;
use std::sync::Arc;
use std::thread;

use ripdpi_config::{parse_cli, ParseResult, StartupEnv};
use ripdpi_runtime::process::prepare_embedded;
use ripdpi_runtime::runtime::{create_listener, run_proxy_with_embedded_control};
use ripdpi_runtime::{clear_runtime_telemetry, EmbeddedProxyControl, RuntimeTelemetrySink};

use super::telemetry::{ProxyHarnessTelemetry, StartupLatch};
use super::START_TIMEOUT;

pub struct RunningProxy {
    pub port: u16,
    pub control: Arc<EmbeddedProxyControl>,
    thread: Option<thread::JoinHandle<io::Result<()>>>,
}

impl Drop for RunningProxy {
    fn drop(&mut self) {
        self.control.request_shutdown();
        if let Some(thread) = self.thread.take() {
            let result = thread.join().expect("join proxy thread");
            result.expect("proxy stopped cleanly");
        }
        clear_runtime_telemetry();
    }
}

pub fn start_proxy(
    config: ripdpi_config::RuntimeConfig,
    telemetry: Option<Arc<dyn RuntimeTelemetrySink>>,
) -> RunningProxy {
    prepare_embedded();
    clear_runtime_telemetry();
    let startup = Arc::new(StartupLatch::default());
    let harness_telemetry: Arc<dyn RuntimeTelemetrySink> =
        Arc::new(ProxyHarnessTelemetry { startup: startup.clone(), delegate: telemetry });
    let control = Arc::new(EmbeddedProxyControl::new(Some(harness_telemetry)));
    let listener = create_listener(&config).expect("create listener");
    let port = listener.local_addr().expect("listener addr").port();
    let control_for_thread = control.clone();
    let thread = thread::spawn(move || run_proxy_with_embedded_control(config, listener, control_for_thread));
    startup.wait(START_TIMEOUT);
    RunningProxy { port, control, thread: Some(thread) }
}

pub fn proxy_config(args: &[&str]) -> ripdpi_config::RuntimeConfig {
    let args = args.iter().map(|value| (*value).to_string()).collect::<Vec<_>>();
    match parse_cli(&args, &StartupEnv::default()).expect("parse runtime config") {
        ParseResult::Run(config) => *config,
        other => panic!("unexpected parse result: {other:?}"),
    }
}

pub fn ephemeral_proxy_config(args: &[&str]) -> ripdpi_config::RuntimeConfig {
    let mut config = proxy_config(args);
    config.network.listen.listen_port = 0;
    config
}
