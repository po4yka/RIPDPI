mod common;

use ripdpi_strategy_ipv6::{Ipv6ExtType, apply_ipv6_ext_header};

#[test]
fn routing_injection_is_rejected() {
    let packet = common::ipv6_tcp_packet();
    assert_eq!(apply_ipv6_ext_header(&packet, Ipv6ExtType::Routing), None);
}

#[test]
fn routing_strategy_returns_configuration_error_without_actions() {
    use ripdpi_strategy_ipv6::Ipv6ExtHdrStrategy;
    use ripdpi_strategy_trait::{ConnectionState, DesyncPlan, DesyncStrategy, Dissect, StrategyError};

    let strategy = Ipv6ExtHdrStrategy::new(Ipv6ExtType::Routing);
    let packet = common::ipv6_tcp_packet();
    let dissect = Dissect { is_ipv6: true, ..Dissect::default() };
    let conn = ConnectionState::default();
    let caps = common::vpn_caps();
    let ctx = common::vpn_ipv6_context(&dissect, &conn, &caps, &packet);
    let mut plan = DesyncPlan::default();
    assert!(matches!(strategy.plan(&ctx, &mut plan), Err(StrategyError::InvalidConfig(_))));
    assert!(plan.actions.is_empty());
}

#[test]
fn ext_type_parser_accepts_yaml_names() {
    assert_eq!(Ipv6ExtType::parse("hopbyhop"), Some(Ipv6ExtType::HopByHop));
    assert_eq!(Ipv6ExtType::parse("destopts"), Some(Ipv6ExtType::DestOpts));
    assert_eq!(Ipv6ExtType::parse("routing"), Some(Ipv6ExtType::Routing));
    assert_eq!(Ipv6ExtType::parse("unknown"), None);
}
