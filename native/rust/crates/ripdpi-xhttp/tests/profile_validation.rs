use ripdpi_tls_profiles::{ROTATING_PROFILE_MARKER, fingerprint_rotation_count, resolve_connection_profile};
use ripdpi_xhttp::catalog_validated_tls_profile;

#[test]
fn rotating_validation_preserves_the_connection_seed_and_counter() {
    let before = fingerprint_rotation_count();
    for _ in 0..10 {
        assert!(catalog_validated_tls_profile(ROTATING_PROFILE_MARKER, "validation.example"));
    }
    assert_eq!(fingerprint_rotation_count(), before);

    // SHA-256("validation.example|0|browser_family_v2") selects default-pool index 2.
    assert_eq!(resolve_connection_profile(ROTATING_PROFILE_MARKER, "validation.example"), "safari_stable");
    assert_eq!(fingerprint_rotation_count(), before + 1);
}
