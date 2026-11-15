use super::*;
use crate::runtime::desync::OutboundSendOutcome;
use ripdpi_proxy_runtime_adapter::desync_platform::EvolutionHintContext;
use ripdpi_proxy_runtime_adapter::model::config::OffsetBase;

struct EvolutionFixture {
    state: RuntimeState,
    config: RuntimeConfig,
    writer: TcpStream,
    peer: TcpStream,
    target: std::net::SocketAddr,
    attempt: Option<EvolutionAttempt>,
    hello: Vec<u8>,
}

impl EvolutionFixture {
    fn new() -> Self {
        let mut group = DesyncGroup::new(0);
        group
            .actions
            .tcp_chain
            .push(TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::AutoBalanced, 0)));
        group.matches.activation_filter =
            Some(ActivationFilter { round: Some(NumericRange::new(1, 1)), ..Default::default() });
        let mut config = RuntimeConfig { groups: vec![group], ..Default::default() };
        config.adaptive.strategy_evolution = true;
        config.adaptive.evolution_epsilon_permil = 0;
        let state = RuntimeState::test(config.clone());
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let target = listener.local_addr().unwrap();
        let writer = TcpStream::connect(target).unwrap();
        let (peer, _) = listener.accept().unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let tls_config = rustls::ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .unwrap()
            .with_root_certificates(rustls::RootCertStore::empty())
            .with_no_client_auth();
        let mut client = rustls::ClientConnection::new(
            std::sync::Arc::new(tls_config),
            rustls::pki_types::ServerName::try_from("evolution.example").unwrap(),
        )
        .unwrap();
        let mut hello = Vec::new();
        client.write_tls(&mut hello).unwrap();
        Self { state, config, writer, peer, target, attempt: None, hello }
    }

    fn send(&mut self, round: u32, payload: &[u8]) -> (OutboundSendOutcome, Vec<u8>) {
        let outcome = self
            .state
            .send_tcp_desync_payload(
                &mut self.writer,
                DesyncSendRequest {
                    fake_ttl_attempt: None,
                    evolution_attempt: Some(&mut self.attempt),
                    lua_flow: None,
                    group_index: 0,
                    group_override: None,
                    payload,
                    progress: OutboundProgress {
                        round,
                        payload_size: payload.len(),
                        stream_start: 0,
                        stream_end: payload.len().saturating_sub(1),
                    },
                    host: Some("evolution.example"),
                    target: self.target,
                },
            )
            .unwrap();
        assert!(outcome.bytes_committed <= payload.len() + 128);
        let mut wire = vec![0; outcome.bytes_committed];
        self.peer.read_exact(&mut wire).unwrap();
        (outcome, wire)
    }

    fn held_split_hint(&self, payload: &[u8]) -> Option<OffsetBase> {
        self.state
            .services
            .resolve_tcp_hints_with_evolver(
                &self.config,
                EvolutionHintContext { runtime_context: None, existing_attempt: self.attempt },
                0,
                self.target,
                Some("evolution.example"),
                &self.config.groups[0],
                payload,
            )
            .unwrap()
            .hints
            .split_offset_base
    }
}

#[test]
fn applied_attempt_survives_round_two_activation_skip() {
    let mut fixture = EvolutionFixture::new();
    let hello = fixture.hello.clone();
    let (applied, wire) = fixture.send(1, &hello);
    assert_eq!(applied.execution_receipt.disposition, TcpExecutionDisposition::Applied);
    let held = fixture.attempt.expect("applied attempt");
    let mut acceptor = rustls::server::Acceptor::default();
    acceptor.read_tls(&mut std::io::Cursor::new(&wire)).unwrap();
    let accepted = acceptor.accept().map_err(|(error, _)| error).unwrap().expect("valid emitted ClientHello");
    assert_eq!(accepted.client_hello().server_name(), Some("evolution.example"));
    let (skipped, wire) = fixture.send(2, b"continuation");
    assert_eq!(skipped.execution_receipt.disposition, TcpExecutionDisposition::ActivationSkipped);
    assert_eq!(&wire, b"continuation");
    assert_eq!(fixture.attempt, Some(held));
    assert_eq!(fixture.held_split_hint(b"continuation"), Some(OffsetBase::AutoHost));
    fixture.state.note_evolver_success(Some(held));
    assert_eq!(fixture.held_split_hint(b"continuation"), None, "completed observation must use fallback hints");
}

#[test]
fn fresh_activation_skip_cannot_credit_a_response() {
    let mut fixture = EvolutionFixture::new();
    let hello = fixture.hello.clone();
    let (skipped, wire) = fixture.send(2, &hello);
    assert_eq!(skipped.execution_receipt.disposition, TcpExecutionDisposition::ActivationSkipped);
    assert_eq!(wire, hello);
    let held = fixture.attempt.expect("dead identity prevents replacement");
    assert_eq!(fixture.held_split_hint(&hello), None);
    fixture.state.note_evolver_success(Some(held));
    assert_eq!(fixture.held_split_hint(&hello), None);
}
