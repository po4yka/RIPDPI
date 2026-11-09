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

#[test]
fn fixed_profile_validation_accepts_catalog_names_and_the_default_alias() {
    for profile in [
        "chrome_stable",
        "chrome_desktop_stable",
        "firefox_stable",
        "firefox_ech_stable",
        "safari_stable",
        "edge_stable",
        "native_default",
    ] {
        assert!(catalog_validated_tls_profile(profile, "validation.example"), "profile {profile}");
    }

    for unknown in ["", "unknown_fallback", "firefox", "firefox_ech_grease"] {
        assert!(!catalog_validated_tls_profile(unknown, "validation.example"), "profile {unknown:?}");
    }
}
