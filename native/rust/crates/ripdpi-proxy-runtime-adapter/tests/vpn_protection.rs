use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener};
use std::sync::Arc;
use std::time::Duration;

use ripdpi_proxy_config::{
    ProxyConfigPayload, ProxyRuntimeContext, ProxyUiConfig, ProxyUiDestinationRoutingConfig,
    runtime_config_envelope_from_payload,
};
use ripdpi_runtime_platform::protect::{
    ProtectCallback, ProtectGeneration, register_protect_callback_versioned, unregister_protect_callback_if,
};

struct RejectProtection;

impl ProtectCallback for RejectProtection {
    fn protect(&self, _fd: i32) -> io::Result<()> {
        Err(io::Error::new(io::ErrorKind::PermissionDenied, "test protection rejected"))
    }
}

struct Registration(ProtectGeneration);

impl Drop for Registration {
    fn drop(&mut self) {
        let _ = unregister_protect_callback_if(self.0);
    }
}

#[test]
fn runtime_protection_context_prevents_tcp_and_udp_egress() {
    let _registration = Registration(register_protect_callback_versioned(Arc::new(RejectProtection)));
    let context_path = "/tmp/vpn-required.sock";
    let context = Some(ProxyRuntimeContext { protect_path: Some(format!("  {context_path}  ")), ..Default::default() });
    let cli = |args, runtime_context| ProxyConfigPayload::CommandLine {
        args,
        host_autolearn_store_path: None,
        destination_routing: ProxyUiDestinationRoutingConfig::default(),
        geoip_db_path: None,
        geosite_db_path: None,
        runtime_context,
        log_context: None,
        session_overrides: None,
        schema_version: 2,
    };
    let payloads = [
        (
            ProxyConfigPayload::Ui {
                strategy_preset: None,
                config: ProxyUiConfig::default(),
                runtime_context: context.clone(),
                log_context: None,
                session_overrides: None,
                schema_version: 2,
            },
            context_path,
        ),
        (cli(vec![], context.clone()), context_path),
        (cli(vec!["--protect-path".into(), "/tmp/explicit.sock".into()], context), "/tmp/explicit.sock"),
    ];
    for (payload, expected_path) in payloads {
        let envelope = runtime_config_envelope_from_payload(payload).expect("valid proxy config");
        assert_eq!(envelope.config.process.protect_path.as_deref(), Some(expected_path));
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("TCP listener");
        listener.set_nonblocking(true).expect("nonblocking listener");
        let error = ripdpi_proxy_runtime_adapter::platform::connect::connect_tcp_stream(
            listener.local_addr().expect("listener address"),
            IpAddr::V4(Ipv4Addr::UNSPECIFIED),
            envelope.config.process.protect_path.as_deref(),
            false,
            Some(Duration::from_secs(1)),
            None,
        )
        .expect_err("protection denial must prevent TCP connect");
        assert_eq!(error.source.kind(), io::ErrorKind::PermissionDenied);
        assert_eq!(listener.accept().expect_err("no TCP connection").kind(), io::ErrorKind::WouldBlock);
        let udp_target: SocketAddr = "203.0.113.7:443".parse().expect("numeric UDP peer");
        let error = ripdpi_proxy_runtime_adapter::platform::udp::build_udp_upstream_socket(
            udp_target,
            envelope.config.process.protect_path.as_deref(),
            false,
        )
        .expect_err("protection denial must prevent UDP connect");
        assert_eq!(error.kind(), io::ErrorKind::PermissionDenied);
    }
    let inactive = runtime_config_envelope_from_payload(cli(vec![], None)).expect("inactive proxy config");
    assert!(inactive.config.process.protect_path.is_none());
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("inactive listener");
    let stream = ripdpi_proxy_runtime_adapter::platform::connect::connect_tcp_stream(
        listener.local_addr().expect("inactive address"),
        IpAddr::V4(Ipv4Addr::UNSPECIFIED),
        inactive.config.process.protect_path.as_deref(),
        false,
        Some(Duration::from_secs(1)),
        None,
    )
    .expect("inactive proxy remains callback-free");
    let _accepted = listener.accept().expect("inactive connection reaches listener");
    drop(stream);
}
