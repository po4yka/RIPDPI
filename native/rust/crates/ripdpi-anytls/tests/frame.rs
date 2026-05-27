use ripdpi_anytls::frame::{ClientSettings, Command, Frame, FrameError};

const PADDING_MD5: &str = "75cff2ad89aadf5e257059ee571ebe11";

#[test]
fn command_discriminants_match_protocol() {
    let commands = [
        (0, Command::Waste),
        (1, Command::Syn),
        (2, Command::Psh),
        (3, Command::Fin),
        (4, Command::Settings),
        (5, Command::Alert),
        (6, Command::UpdatePaddingScheme),
        (7, Command::SynAck),
        (8, Command::HeartRequest),
        (9, Command::HeartResponse),
        (10, Command::ServerSettings),
    ];

    for (byte, command) in commands {
        assert_eq!(Command::try_from(byte).expect("command parses"), command);
        assert_eq!(u8::from(command), byte);
    }
}

#[test]
fn frame_encodes_big_endian_header_and_payload() {
    let frame = Frame::with_data(Command::Psh, 0x0102_0304, vec![0xaa, 0xbb]);

    assert_eq!(frame.encode().expect("frame encodes"), vec![2, 1, 2, 3, 4, 0, 2, 0xaa, 0xbb]);
}

#[test]
fn every_command_round_trips_as_zero_length_control_frame() {
    for command in [
        Command::Waste,
        Command::Syn,
        Command::Psh,
        Command::Fin,
        Command::Settings,
        Command::Alert,
        Command::UpdatePaddingScheme,
        Command::SynAck,
        Command::HeartRequest,
        Command::HeartResponse,
        Command::ServerSettings,
    ] {
        let encoded = Frame::control(command, 7).encode().expect("control frame encodes");
        let Some((decoded, consumed)) = Frame::decode(&encoded).expect("control frame decodes") else {
            panic!("complete control frame must decode");
        };

        assert_eq!(consumed, 7);
        assert_eq!(decoded.command(), command);
        assert_eq!(decoded.stream_id(), 7);
        assert!(decoded.data().is_empty());
    }
}

#[test]
fn decoder_rejects_unknown_command() {
    let err = Frame::decode(&[99, 0, 0, 0, 0, 0, 0]).expect_err("unknown command must fail");

    assert!(matches!(err, FrameError::UnknownCommand(99)));
}

#[test]
fn decoder_waits_for_truncated_header_or_body() {
    assert_eq!(Frame::decode(&[Command::Psh.into(), 0, 0]).expect("truncated header waits"), None);

    let partial_body = [Command::Psh.into(), 0, 0, 0, 1, 0, 3, b'a'];
    assert_eq!(Frame::decode(&partial_body).expect("truncated body waits"), None);
}

#[test]
fn decoder_consumes_full_waste_frame_data() {
    let encoded = Frame::with_data(Command::Waste, 0, vec![0, 0, 0, 0, 0]).encode().expect("waste frame encodes");
    let Some((decoded, consumed)) = Frame::decode(&encoded).expect("waste frame decodes") else {
        panic!("complete waste frame must decode");
    };

    assert_eq!(decoded.command(), Command::Waste);
    assert_eq!(decoded.data(), &[0, 0, 0, 0, 0]);
    assert_eq!(consumed, encoded.len());
}

#[test]
fn encoder_rejects_payloads_larger_than_u16() {
    let payload = vec![0; usize::from(u16::MAX) + 1];
    let err = Frame::with_data(Command::Psh, 1, payload).encode().expect_err("oversized payload must fail");

    assert!(matches!(err, FrameError::PayloadTooLong(65_536)));
}

#[test]
fn client_settings_encode_in_spec_order_and_decode() {
    let settings = ClientSettings::new("ripdpi-anytls/0.1.0", PADDING_MD5);

    let encoded = settings.encode();

    assert_eq!(encoded, format!("v=2\nclient=ripdpi-anytls/0.1.0\npadding-md5={PADDING_MD5}").into_bytes());
    assert_eq!(ClientSettings::decode(&encoded).expect("settings decode"), settings);
}

#[test]
fn client_settings_reject_missing_padding_md5() {
    let err = ClientSettings::decode(b"v=2\nclient=ripdpi-anytls/0.1.0").expect_err("padding-md5 is required");

    assert!(matches!(err, FrameError::MissingSetting("padding-md5")));
}

#[test]
fn client_settings_require_protocol_version_two() {
    let missing = ClientSettings::decode(format!("client=ripdpi-anytls/0.1.0\npadding-md5={PADDING_MD5}").as_bytes())
        .expect_err("v is required");
    assert!(matches!(missing, FrameError::MissingSetting("v")));

    let unsupported =
        ClientSettings::decode(format!("v=1\nclient=ripdpi-anytls/0.1.0\npadding-md5={PADDING_MD5}").as_bytes())
            .expect_err("v=1 is unsupported for this client settings encoder");
    assert!(matches!(unsupported, FrameError::UnsupportedSettingsVersion(version) if version == "1"));
}
