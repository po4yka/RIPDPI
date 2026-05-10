use ripdpi_strategy_http::{DomcaseStrategy, HostcaseStrategy, MethodeolStrategy, UnixeolStrategy};
use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, DesyncStrategy, Dissect, FlowDirection,
    FlowId, HttpDissect, L7Protocol, StrategyContext, StrategyVerdict, TlsDissect,
};

fn context<'a>(
    payload: &'a [u8],
    dissect: &'a Dissect,
    conn: &'a ConnectionState,
    caps: &'a Capabilities,
) -> StrategyContext<'a> {
    StrategyContext { dissect, conn, caps, flow_id: FlowId(42), payload, direction: FlowDirection::Outbound }
}

#[test]
fn http_strategies_match_http_only_and_require_no_capabilities() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let http = Dissect {
        proto: L7Protocol::Http(HttpDissect { host: Some("example.com".to_owned()), is_request: true }),
        ..Dissect::default()
    };
    let tls = Dissect {
        proto: L7Protocol::Tls(TlsDissect { sni: Some("example.com".to_owned()), is_client_hello: true }),
        ..Dissect::default()
    };
    let http_ctx = context(payload, &http, &conn, &caps);
    let tls_ctx = context(payload, &tls, &conn, &caps);
    let strategies: Vec<Box<dyn DesyncStrategy>> = vec![
        Box::new(DomcaseStrategy),
        Box::new(HostcaseStrategy),
        Box::new(MethodeolStrategy),
        Box::new(UnixeolStrategy),
    ];

    for strategy in strategies {
        assert!(strategy.matches(&http_ctx));
        assert!(!strategy.matches(&tls_ctx));
        let descriptor = strategy.describe();
        assert_eq!(descriptor.required_tier, CapabilityTier::Tier0);
        assert!(descriptor.required_capabilities.is_empty());
    }
}

#[test]
fn strategy_writes_modified_payload() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let http = Dissect {
        proto: L7Protocol::Http(HttpDissect { host: Some("example.com".to_owned()), is_request: true }),
        ..Dissect::default()
    };
    let http_ctx = context(payload, &http, &conn, &caps);
    let mut plan = DesyncPlan::default();

    DomcaseStrategy.plan(&http_ctx, &mut plan).expect("plan domcase");

    assert_eq!(plan.verdict, StrategyVerdict::Apply);
    assert_eq!(plan.actions, vec![DesyncAction::Write(b"GET / HTTP/1.1\r\nHost: eXaMpLe.CoM\r\n\r\n".to_vec())]);
}
