mod telemetry;

use std::process::ExitCode;
use std::sync::Arc;

use ripdpi_config::{ParseResult, StartupEnv};
use ripdpi_runtime::{install_runtime_telemetry, process::ProcessGuard, runtime};

use crate::telemetry::TracingTelemetrySink;

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let startup = StartupEnv::default();

    let parsed = match ripdpi_config::parse_cli(&args, &startup) {
        Ok(result) => result,
        Err(err) => {
            eprintln!("error: {err}");
            return ExitCode::FAILURE;
        }
    };

    match parsed {
        ParseResult::Help => {
            print_help();
            ExitCode::SUCCESS
        }
        ParseResult::Version => {
            println!("ripdpi {}", env!("CARGO_PKG_VERSION"));
            ExitCode::SUCCESS
        }
        ParseResult::Run(config) => run_proxy(*config),
    }
}

fn run_proxy(config: ripdpi_config::RuntimeConfig) -> ExitCode {
    init_logging(config.process.debug);
    ripdpi_telemetry::recorder::install();

    let sink = Arc::new(TracingTelemetrySink::new());
    install_runtime_telemetry(sink.clone());

    let _guard = match ProcessGuard::prepare(&config) {
        Ok(guard) => guard,
        Err(err) => {
            tracing::error!(%err, "failed to prepare process");
            return ExitCode::FAILURE;
        }
    };

    tracing::info!(
        ip = %config.network.listen.listen_ip,
        port = config.network.listen.listen_port,
        groups = config.groups.len(),
        "starting proxy"
    );

    if let Err(err) = runtime::run_proxy(config) {
        tracing::error!(%err, "proxy error");
        return ExitCode::FAILURE;
    }

    sink.print_summary();
    ExitCode::SUCCESS
}

fn init_logging(debug_level: i32) {
    use tracing_subscriber::{fmt, EnvFilter};

    let default_filter = match debug_level {
        0 => "warn,ripdpi=info",
        1 => "info",
        _ => "debug",
    };

    let env_filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(default_filter));

    fmt().with_env_filter(env_filter).with_writer(std::io::stderr).with_target(false).init();

    tracing_log::LogTracer::init().ok();
}

fn print_help() {
    println!(
        "ripdpi {} -- SOCKS5 proxy with DPI circumvention\n\n\
         Usage: ripdpi [OPTIONS]\n\n\
         Options:\n  \
           -i, --ip <IP[:PORT]>   Listen address (default: 127.0.0.1)\n  \
           -p, --port <PORT>      Listen port (default: 1080)\n  \
           -b, --buf-size <SIZE>  Socket buffer size (default: 16384)\n  \
           -c, --max-conn <N>     Max concurrent connections (default: 512)\n  \
           -x, --debug <LEVEL>    Debug verbosity (0=warn, 1=info, 2+=debug)\n  \
           -N, --no-domain        Disable domain resolution\n  \
           -F, --tfo              Enable TCP Fast Open\n  \
           -D, --daemon           Daemonize\n  \
           -w, --pidfile <PATH>   PID file path\n  \
           -h, --help             Show this help\n  \
           -v, --version          Show version\n\n\
         Environment:\n  \
           RUST_LOG               Override log filter (e.g. RUST_LOG=debug)",
        env!("CARGO_PKG_VERSION")
    );
}
