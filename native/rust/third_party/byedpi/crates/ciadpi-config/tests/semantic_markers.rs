use ciadpi_config::{
    parse_offset_expr, DesyncGroup, DesyncMode, OffsetBase, OffsetExpr, PartSpec, TcpChainStep, TcpChainStepKind,
};

#[test]
fn named_marker_parser_covers_full_vocab_with_repeat_skip() {
    assert_eq!(
        parse_offset_expr("host:2:3").expect("parse host marker"),
        OffsetExpr::marker(OffsetBase::Host, 0).with_repeat_skip(2, 3)
    );
    assert_eq!(
        parse_offset_expr("endhost-1").expect("parse endhost marker"),
        OffsetExpr::marker(OffsetBase::EndHost, -1)
    );
    assert_eq!(parse_offset_expr("sld").expect("parse sld marker"), OffsetExpr::marker(OffsetBase::Sld, 0));
    assert_eq!(
        parse_offset_expr("endsld+1:3:-2").expect("parse endsld marker"),
        OffsetExpr::marker(OffsetBase::EndSld, 1).with_repeat_skip(3, -2)
    );
    assert_eq!(parse_offset_expr("abs+2").expect("parse abs marker"), OffsetExpr::absolute(2));
}

#[test]
fn legacy_marker_parser_retains_protocol_hint_and_repeat_skip() {
    assert_eq!(
        parse_offset_expr("4+se:2:-1").expect("parse tls endhost"),
        OffsetExpr::tls_marker(OffsetBase::EndHost, 4).with_repeat_skip(2, -1)
    );
    assert_eq!(
        parse_offset_expr("3+hm:3:1").expect("parse hostmid"),
        OffsetExpr::marker(OffsetBase::HostMid, 3).with_repeat_skip(3, 1)
    );
    assert_eq!(parse_offset_expr("6+sr").expect("parse tls hostrand"), OffsetExpr::tls_marker(OffsetBase::HostRand, 6));
    assert_eq!(parse_offset_expr("5+ne").expect("parse payload end"), OffsetExpr::marker(OffsetBase::PayloadEnd, 5));
}

#[test]
fn marker_based_legacy_views_roundtrip_through_tcp_chain_projection() {
    let mut group = DesyncGroup::new(0);
    group.tls_records = vec![OffsetExpr::marker(OffsetBase::ExtLen, 0)];
    group.parts = vec![
        PartSpec { mode: DesyncMode::Fake, offset: OffsetExpr::marker(OffsetBase::Host, 0) },
        PartSpec { mode: DesyncMode::Split, offset: OffsetExpr::marker(OffsetBase::EndSld, 0) },
    ];

    let chain = group.effective_tcp_chain();
    assert_eq!(
        chain,
        vec![
            TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
            TcpChainStep::new(TcpChainStepKind::Fake, OffsetExpr::marker(OffsetBase::Host, 0)),
            TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::marker(OffsetBase::EndSld, 0)),
        ]
    );

    let mut projected = DesyncGroup::new(1);
    projected.tcp_chain = chain;
    projected.sync_legacy_views_from_chains();

    assert_eq!(projected.tls_records, vec![OffsetExpr::marker(OffsetBase::ExtLen, 0)]);
    assert_eq!(
        projected.parts,
        vec![
            PartSpec { mode: DesyncMode::Fake, offset: OffsetExpr::marker(OffsetBase::Host, 0) },
            PartSpec { mode: DesyncMode::Split, offset: OffsetExpr::marker(OffsetBase::EndSld, 0) },
        ]
    );
}
