use super::*;
use crate::offset::{insert_boundary, random_tail_fragment_lengths, resolve_offset};

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

// ---- gen_offset absolute mode ----

#[test]
fn gen_offset_abs_positive_delta() {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(0);
    let buf = b"0123456789";

    let expr = OffsetExpr::marker(OffsetBase::Abs, 3);
    assert_eq!(gen_offset(expr, buf, buf.len(), 0, &mut info, &mut rng), Some(3));
}

#[test]
fn gen_offset_abs_negative_delta_counts_from_end() {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(0);
    let buf = b"0123456789"; // len=10

    let expr = OffsetExpr::marker(OffsetBase::Abs, -2);
    assert_eq!(gen_offset(expr, buf, buf.len(), 0, &mut info, &mut rng), Some(8));
}

#[test]
fn gen_offset_payload_rand_respects_lp() {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(42);
    let buf = b"0123456789";

    let expr = OffsetExpr::marker(OffsetBase::PayloadRand, 0);
    let result = gen_offset(expr, buf, buf.len(), 5, &mut info, &mut rng).expect("rand with lp");
    assert!(result >= 5, "PayloadRand should start at lp={}, got {result}", 5);
}

#[test]
fn gen_offset_auto_variants_return_none() {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(0);
    let buf = b"test data";

    for base in [
        OffsetBase::AutoBalanced,
        OffsetBase::AutoHost,
        OffsetBase::AutoMidSld,
        OffsetBase::AutoEndHost,
        OffsetBase::AutoMethod,
        OffsetBase::AutoSniExt,
        OffsetBase::AutoExtLen,
    ] {
        let expr = OffsetExpr::marker(base, 0);
        assert_eq!(
            gen_offset(expr, buf, buf.len(), 0, &mut info, &mut rng),
            None,
            "Auto variant {base:?} should return None"
        );
    }
}

// ---- Host/EndHost/HostMid/HostRand ----

#[test]
fn gen_offset_host_and_endhost_for_http() {
    let http = b"\r\nGET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(0);

    let host_pos = gen_offset(OffsetExpr::marker(OffsetBase::Host, 0), http, http.len(), 0, &mut info, &mut rng)
        .expect("Host offset");
    let endhost_pos = gen_offset(OffsetExpr::marker(OffsetBase::EndHost, 0), http, http.len(), 0, &mut info, &mut rng)
        .expect("EndHost offset");

    assert!(host_pos < endhost_pos, "Host start should be before Host end");
    let host = &http[host_pos as usize..endhost_pos as usize];
    assert_eq!(host, b"sub.example.com");
}

#[test]
fn gen_offset_host_mid_is_between_start_and_end() {
    let http = b"\r\nGET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(0);

    let host = gen_offset(OffsetExpr::marker(OffsetBase::Host, 0), http, http.len(), 0, &mut info, &mut rng).unwrap();
    let endhost =
        gen_offset(OffsetExpr::marker(OffsetBase::EndHost, 0), http, http.len(), 0, &mut info, &mut rng).unwrap();
    let mid = gen_offset(OffsetExpr::marker(OffsetBase::HostMid, 0), http, http.len(), 0, &mut info, &mut rng).unwrap();

    assert!(mid >= host && mid <= endhost, "HostMid {mid} should be between {host} and {endhost}");
}

#[test]
fn gen_offset_host_rand_within_bounds() {
    let http = b"\r\nGET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(42);

    let host = gen_offset(OffsetExpr::marker(OffsetBase::Host, 0), http, http.len(), 0, &mut info, &mut rng).unwrap();
    let endhost =
        gen_offset(OffsetExpr::marker(OffsetBase::EndHost, 0), http, http.len(), 0, &mut info, &mut rng).unwrap();

    for seed in 0..10 {
        let mut rng2 = OracleRng::seeded(seed);
        let mut info2 = ProtoInfo::default();
        let rand_pos =
            gen_offset(OffsetExpr::marker(OffsetBase::HostRand, 0), http, http.len(), 0, &mut info2, &mut rng2)
                .unwrap();
        assert!(rand_pos >= host && rand_pos < endhost, "seed {seed}: HostRand {rand_pos} out of [{host}, {endhost})");
    }
}

// ---- SLD variants ----

#[test]
fn gen_offset_sld_endsld_midsld_for_http() {
    let http = b"\r\nGET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(0);

    let sld =
        gen_offset(OffsetExpr::marker(OffsetBase::Sld, 0), http, http.len(), 0, &mut info, &mut rng).expect("Sld");
    let endsld = gen_offset(OffsetExpr::marker(OffsetBase::EndSld, 0), http, http.len(), 0, &mut info, &mut rng)
        .expect("EndSld");
    let midsld = gen_offset(OffsetExpr::marker(OffsetBase::MidSld, 0), http, http.len(), 0, &mut info, &mut rng)
        .expect("MidSld");

    assert!(sld < endsld, "Sld {sld} should be before EndSld {endsld}");
    assert!(midsld >= sld && midsld <= endsld, "MidSld {midsld} between [{sld}, {endsld}]");
}

// ---- insert_boundary ----

#[test]
fn insert_boundary_maintains_sorted_order() {
    let mut boundaries: Vec<usize> = vec![2, 5, 10];
    insert_boundary(&mut boundaries, 7);
    assert_eq!(boundaries, vec![2, 5, 7, 10]);
}

#[test]
fn insert_boundary_skips_duplicates() {
    let mut boundaries: Vec<usize> = vec![2, 5, 10];
    insert_boundary(&mut boundaries, 5);
    assert_eq!(boundaries, vec![2, 5, 10]);
}

#[test]
fn insert_boundary_appends_to_end() {
    let mut boundaries: Vec<usize> = vec![2, 5];
    insert_boundary(&mut boundaries, 15);
    assert_eq!(boundaries, vec![2, 5, 15]);
}

#[test]
fn insert_boundary_prepends_to_start() {
    let mut boundaries: Vec<usize> = vec![5, 10];
    insert_boundary(&mut boundaries, 1);
    assert_eq!(boundaries, vec![1, 5, 10]);
}

#[test]
fn insert_boundary_empty_vec() {
    let mut boundaries: Vec<usize> = vec![];
    insert_boundary(&mut boundaries, 3);
    assert_eq!(boundaries, vec![3]);
}

// ---- random_tail_fragment_lengths ----

#[test]
fn random_tail_fragment_lengths_basic() {
    let mut rng = OracleRng::seeded(42);
    let lengths = random_tail_fragment_lengths(100, 3, 10, 50, &mut rng).expect("fragments");
    assert_eq!(lengths.len(), 3);
    assert_eq!(lengths.iter().sum::<usize>(), 100);
    for len in &lengths {
        assert!(*len >= 10 && *len <= 50, "fragment {len} out of [10, 50]");
    }
}

#[test]
fn random_tail_fragment_lengths_single_fragment() {
    let mut rng = OracleRng::seeded(0);
    let lengths = random_tail_fragment_lengths(42, 1, 1, 100, &mut rng).expect("single");
    assert_eq!(lengths, vec![42]);
}

#[test]
fn random_tail_fragment_lengths_rejects_zero_count() {
    let mut rng = OracleRng::seeded(0);
    assert!(random_tail_fragment_lengths(100, 0, 10, 50, &mut rng).is_none());
}

#[test]
fn random_tail_fragment_lengths_rejects_zero_min_size() {
    let mut rng = OracleRng::seeded(0);
    assert!(random_tail_fragment_lengths(100, 3, 0, 50, &mut rng).is_none());
}

#[test]
fn random_tail_fragment_lengths_rejects_min_greater_than_max() {
    let mut rng = OracleRng::seeded(0);
    assert!(random_tail_fragment_lengths(100, 3, 50, 10, &mut rng).is_none());
}

#[test]
fn random_tail_fragment_lengths_rejects_infeasible_total() {
    let mut rng = OracleRng::seeded(0);
    // 3 fragments of min 50 = 150 minimum, but total is 100
    assert!(random_tail_fragment_lengths(100, 3, 50, 100, &mut rng).is_none());
    // 3 fragments of max 10 = 30 maximum, but total is 100
    assert!(random_tail_fragment_lengths(100, 3, 1, 10, &mut rng).is_none());
}

#[test]
fn random_tail_fragment_lengths_exact_fit() {
    let mut rng = OracleRng::seeded(0);
    // Only one possible solution: 3 * 10 = 30
    let lengths = random_tail_fragment_lengths(30, 3, 10, 10, &mut rng).expect("exact fit");
    assert_eq!(lengths, vec![10, 10, 10]);
}

// ---- resolve_offset delegates ----

#[test]
fn resolve_offset_delegates_to_gen_offset_for_non_adaptive() {
    let buf = b"0123456789";
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(0);
    let ctx = tcp_context(buf);

    let expr = OffsetExpr::marker(OffsetBase::Abs, 5);
    let result = resolve_offset(expr, buf, buf.len(), 0, &mut info, &mut rng, ctx, None);
    assert_eq!(result, Some(5));
}

#[test]
fn resolve_offset_uses_adaptive_path_for_auto_variants() {
    let tls = DEFAULT_FAKE_TLS;
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(0);
    let ctx = tcp_context(tls);

    let expr = OffsetExpr::adaptive(OffsetBase::AutoBalanced);
    let result = resolve_offset(expr, tls, tls.len(), 0, &mut info, &mut rng, ctx, None);
    // AutoBalanced on TLS should find a candidate (ExtLen, SniExt, Host, etc.)
    assert!(result.is_some(), "AutoBalanced on TLS should resolve to a position");
    let pos = result.unwrap();
    assert!(pos > 0 && pos < tls.len() as i64, "resolved position {pos} should be within payload");
}
