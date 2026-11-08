use ripdpi_diagnostics_tls::tls::make_server_name;
use ripdpi_diagnostics_transport::transport::TargetAddress;

#[test]
fn explicit_invalid_tls_identity_never_falls_back_to_the_target() {
    for target in [TargetAddress::Host("fallback.example".into()), TargetAddress::Ip("127.0.0.1".parse().expect("IP"))]
    {
        for name in ["bad name", "bücher.test", "bad..test"] {
            assert!(
                make_server_name(name, &target).is_err(),
                "invalid explicit name {name:?} must not select {target:?}"
            );
        }
    }
}
