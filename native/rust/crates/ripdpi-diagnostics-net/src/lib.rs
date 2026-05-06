//! Compatibility namespace for legacy diagnostics-network imports.
//!
//! New code should import directly from the split protocol crates:
//! `ripdpi-diagnostics-dns`, `ripdpi-diagnostics-http`,
//! `ripdpi-diagnostics-tls`, `ripdpi-diagnostics-telegram`,
//! `ripdpi-diagnostics-transport`, `ripdpi-diagnostics-fat-header`, and
//! `ripdpi-diagnostics-contracts`.

#[deprecated(since = "0.1.0", note = "import from the split ripdpi-diagnostics-* protocol crates instead")]
pub mod compat {
    include!("compat.rs");
}
