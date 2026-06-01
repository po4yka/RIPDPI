use ripdpi_config::{DesyncGroup, OffsetExpr, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::{
    ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, TcpDesyncStrategy, plan_tcp,
};

fn activation_context(payload: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1,
        payload_size: payload.len() as i64,
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1) as i64,
        seqovl_supported: false,
        transport: ActivationTransport::Tcp,
        tcp_segment_hint: None,
        tcp_state: ActivationTcpState::default(),
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
}

#[test]
fn tcp_desync_strategy_plan_matches_plan_tcp_output() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(8))];
    let context = activation_context(payload);
    let seed = 7;
    let default_ttl = 64;

    let direct = plan_tcp(&group, payload, seed, default_ttl, context).expect("direct plan");
    let strategy = TcpDesyncStrategy::new(&group, seed, default_ttl, context);
    let wrapped = strategy.plan(payload).expect("wrapped plan");

    assert_eq!(wrapped, direct);
}
