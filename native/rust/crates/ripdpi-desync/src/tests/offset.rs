use super::*;

#[test]
fn gen_offset_end_mid_rand_flags() {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(42);
    let buf = b"0123456789";

    let expr_end = OffsetExpr::marker(OffsetBase::PayloadEnd, -3);
    assert_eq!(gen_offset(expr_end, buf, buf.len(), 0, &mut info, &mut rng), Some(7));

    let expr_mid = OffsetExpr::marker(OffsetBase::PayloadMid, 0);
    assert_eq!(gen_offset(expr_mid, buf, buf.len(), 0, &mut info, &mut rng), Some(5));

    let expr_rand = OffsetExpr::marker(OffsetBase::PayloadRand, 0);
    let result = gen_offset(expr_rand, buf, buf.len(), 0, &mut info, &mut rng).expect("payload rand");
    assert!(result >= 0 && result <= buf.len() as i64);
}

#[test]
fn gen_offset_resolves_named_markers() {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(7);
    let http = b"\r\nGET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let tls = DEFAULT_FAKE_TLS;
    let expected_http_mid = 24 + 4 + ((11 - 4) / 2);
    let tls_markers = tls_marker_info(tls).expect("tls markers");

    assert_eq!(
        gen_offset(OffsetExpr::marker(OffsetBase::Method, 0), http, http.len(), 0, &mut info, &mut rng),
        Some(2)
    );
    assert_eq!(
        gen_offset(OffsetExpr::marker(OffsetBase::MidSld, 0), http, http.len(), 0, &mut info, &mut rng),
        Some(expected_http_mid as i64)
    );

    let mut tls_info = ProtoInfo::default();
    let mut tls_rng = OracleRng::seeded(7);
    assert_eq!(
        gen_offset(OffsetExpr::marker(OffsetBase::SniExt, 0), tls, tls.len(), 0, &mut tls_info, &mut tls_rng),
        Some(tls_markers.sni_ext_start as i64)
    );
    assert_eq!(
        gen_offset(OffsetExpr::marker(OffsetBase::ExtLen, 0), tls, tls.len(), 0, &mut tls_info, &mut tls_rng),
        Some(tls_markers.ext_len_start as i64)
    );
}
