use bytes::Bytes;
use odoh_rs::{
    compose, decrypt_query, encrypt_response, parse, ObliviousDoHKeyPair, ObliviousDoHMessage,
    ObliviousDoHMessagePlaintext,
};
use ripdpi_dns_resolver::{OdohTargetConfig, ODOH_MESSAGE_MEDIA_TYPE};

const KEM_ID: u16 = 32;
const KDF_ID: u16 = 1;
const AEAD_ID: u16 = 1;
const PUBLIC_KEY_SEED: &str = "c9d84d04e6369fccb8a4d5a264001491221f1b97d9b80dd32c35834bb4462383";
const KEY_ID: &str = "9265d14d640ff991b31892f36326ab601ea84d61964fc7a9c7f981a5313e58b9";
const ODOHCONFIGS: &str =
    "002c000100280020000100010020c6a793bedbd601c25970b1cc46bea80fdb1a8ec51540d79e4f9f17b8baa9da33";
const QUERY: &str = "9db1072b0ab473d1e4b74b09637d5f8f253ad4047426ab4dfcc58350bb67b60c";
const RESPONSE: &str =
    "9db1072b0ab473d1e4b74b09637d5f8f253ad4047426ab4dfcc58350bb67b60c9db1072b0ab473d1e4b74b09637d5f8f253ad4047426ab4dfcc58350bb67b60c";
const OBLIVIOUS_QUERY: &str = "0100209265d14d640ff991b31892f36326ab601ea84d61964fc7a9c7f981a5313e58b90054655d2fa2b2271e40b5a78745e41e6d6c5c181fd1fcffcc30fc451d5ec7fdcc5a6ae0da1cba2bd379da93d02d0e42a3849ec6ba53a54c7c8216f0d3cc2cef30ac54f824f5d8b57657d8c7b95e2c0276580b3851d9";
const OBLIVIOUS_RESPONSE: &str = "0200100f474d14998a841b15f84388a8af1881005413556bcd8d86194fb47a51982b715b7f253f4f3ea14d89a5dd9d2b67e6c13b0b7eb7ae740c09d915ef77461956ba8acac5c5f1d8965ca0888b1c0aa2a0084b4c2c375b74e1c3d4e51ad3fe8080b7941fd5719df5";

#[test]
fn odoh_rfc9230_vector_parses_configs_and_reproduces_oblivious_response() {
    let target_config = OdohTargetConfig::parse_configs(&hex_bytes(ODOHCONFIGS)).expect("ODoH configs parse");
    let expected_key_id = hex_bytes(KEY_ID);
    assert_eq!(target_config.key_id(), expected_key_id.as_slice());
    assert_eq!(ODOH_MESSAGE_MEDIA_TYPE, "application/oblivious-dns-message");

    let key_pair = vector_key_pair();
    let mut query_wire: Bytes = hex_bytes(OBLIVIOUS_QUERY).into();
    assert_eq!(query_wire[0], 0x01);
    let odoh_query: ObliviousDoHMessage = parse(&mut query_wire).expect("oblivious query parses");
    assert_eq!(odoh_query.key_id(), expected_key_id.as_slice());

    let (plain_query, response_secret) = decrypt_query(&odoh_query, &key_pair).expect("vector query decrypts");
    assert_eq!(plain_query.clone().into_msg().as_ref(), hex_bytes(QUERY).as_slice());
    assert_eq!(plain_query.padding_len(), 0);

    let response_wire = hex_bytes(OBLIVIOUS_RESPONSE);
    assert_eq!(response_wire[0], 0x02);
    let mut response_bytes: Bytes = response_wire.clone().into();
    let vector_response: ObliviousDoHMessage = parse(&mut response_bytes).expect("oblivious response parses");
    let response_plaintext = ObliviousDoHMessagePlaintext::new(hex_bytes(RESPONSE), 0);
    let response_nonce = vector_response.key_id().try_into().expect("RFC vector response nonce length");
    let reproduced_response = encrypt_response(&plain_query, &response_plaintext, response_secret, response_nonce)
        .expect("response encrypts");
    let reproduced_wire = compose(&reproduced_response).expect("response serializes");
    assert_eq!(reproduced_wire.as_ref(), response_wire.as_slice());
}

#[test]
fn wrapper_encrypts_query_and_decrypts_response_with_odoh_rs_hpke() {
    let target_config = OdohTargetConfig::parse_configs(&hex_bytes(ODOHCONFIGS)).expect("ODoH configs parse");
    let encrypted_query = target_config.encrypt_query(&hex_bytes(QUERY), 0).expect("query encrypts");
    assert_eq!(encrypted_query.wire_message()[0], 0x01);
    assert_eq!(encrypted_query.key_id(), hex_bytes(KEY_ID).as_slice());

    let mut query_wire: Bytes = encrypted_query.wire_message().to_vec().into();
    let odoh_query: ObliviousDoHMessage = parse(&mut query_wire).expect("wrapper query parses");
    let (plain_query, response_secret) =
        decrypt_query(&odoh_query, &vector_key_pair()).expect("wrapper query decrypts");
    assert_eq!(plain_query.clone().into_msg().as_ref(), hex_bytes(QUERY).as_slice());
    assert_eq!(plain_query.padding_len(), 0);

    let response_plaintext = ObliviousDoHMessagePlaintext::new(hex_bytes(RESPONSE), 0);
    let response_message =
        encrypt_response(&plain_query, &response_plaintext, response_secret, [7; 16]).expect("response encrypts");
    let response_wire = compose(&response_message).expect("response serializes");
    let decrypted_response = encrypted_query.decrypt_response(response_wire.as_ref()).expect("response decrypts");
    assert_eq!(decrypted_response.dns_message(), hex_bytes(RESPONSE).as_slice());
    assert_eq!(decrypted_response.padding_len(), 0);
}

fn vector_key_pair() -> ObliviousDoHKeyPair {
    ObliviousDoHKeyPair::from_parameters(KEM_ID, KDF_ID, AEAD_ID, &hex_bytes(PUBLIC_KEY_SEED))
}

fn hex_bytes(value: &str) -> Vec<u8> {
    hex::decode(value).expect("hex fixture decodes")
}
