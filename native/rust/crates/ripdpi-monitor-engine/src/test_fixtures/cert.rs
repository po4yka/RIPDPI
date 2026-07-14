use rcgen::generate_simple_self_signed;
use rustls::pki_types::{CertificateDer, PrivateKeyDer};

pub fn make_cert(names: &[String]) -> (CertificateDer<'static>, PrivateKeyDer<'static>) {
    let certified = generate_simple_self_signed(names.to_vec()).expect("generate cert");
    let cert = certified.cert.der().clone();
    (cert, PrivateKeyDer::Pkcs8(certified.signing_key.serialize_der().into()))
}
