use ripdpi_strategy_trait::{
    BitTorrentDissect, DhtDissect, DiscordDissect, DnsDissect, DtlsDissect, HttpDissect, L7Protocol, MarkerName,
    MtprotoDissect, QuicDissect, StunDissect, TlsDissect, UtpBitTorrentDissect, WireGuardDissect, XmppDissect,
};

#[test]
fn l7_protocol_covers_zapret2_variants() {
    let variants = [
        L7Protocol::Any,
        L7Protocol::Unknown,
        L7Protocol::Known,
        L7Protocol::Http(HttpDissect::default()),
        L7Protocol::Tls(TlsDissect::default()),
        L7Protocol::Dtls(DtlsDissect::default()),
        L7Protocol::Quic(QuicDissect::default()),
        L7Protocol::WireGuard(WireGuardDissect::default()),
        L7Protocol::Dht(DhtDissect),
        L7Protocol::Discord(DiscordDissect),
        L7Protocol::Stun(StunDissect),
        L7Protocol::Xmpp(XmppDissect),
        L7Protocol::Dns(DnsDissect::default()),
        L7Protocol::Mtproto(MtprotoDissect),
        L7Protocol::BitTorrent(BitTorrentDissect),
        L7Protocol::UtpBitTorrent(UtpBitTorrentDissect),
    ];

    assert_eq!(variants.len(), 16);
    for variant in variants {
        assert!(!format!("{variant:?}").is_empty());
    }
}

#[test]
fn marker_name_covers_zapret2_markers() {
    let markers = [
        MarkerName::Absolute,
        MarkerName::Host,
        MarkerName::HostEnd,
        MarkerName::HostSld,
        MarkerName::HostMidSld,
        MarkerName::HostEndSld,
        MarkerName::HttpMethod,
        MarkerName::ExtLen,
        MarkerName::SniExt,
        MarkerName::Data,
        MarkerName::End,
    ];

    assert_eq!(markers.len(), 11);
    for marker in markers {
        assert!(!format!("{marker:?}").is_empty());
    }
}
