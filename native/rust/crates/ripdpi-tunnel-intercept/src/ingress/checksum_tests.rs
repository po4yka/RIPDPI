use super::*;

// Scapy 2.6.1: IP options, TCP options, sequence wrap and seven data bytes.
#[test]
fn split_synack_checksum_covers_options_payload_and_declared_length() {
    for (input, expected) in [
        (
            "4600003b0001000040068b68c000020ac63364140101010101bb9c40fffffff0000000017012200049980000020404b00103030761626364656667",
            "4600003b0001000040068b68c000020ac63364140101010101bb9c403ffffff0000000017012200009990000020404b00103030761626364656667",
        ),
        (
            "60000000002b004020010db800000000000000000000001020010db8000000000000000000000020060001040000000001bb9c40fffffff00000000170122000da480000020404b00103030761626364656667",
            "60000000002b004020010db800000000000000000000001020010db8000000000000000000000020060001040000000001bb9c403ffffff000000001701220009a490000020404b00103030761626364656667",
        ),
    ] {
        let decode = |text: &str| -> Vec<u8> {
            (0..text.len()).step_by(2).map(|i| u8::from_str_radix(&text[i..i + 2], 16).unwrap()).collect()
        };
        let input = decode(input);
        let expected = decode(expected);
        assert_eq!(with_sequence_delta(&input, 0x4000_0000).unwrap(), expected);
        let mut padded = input;
        padded.extend_from_slice(b"outside IP");
        assert_eq!(with_sequence_delta(&padded, 0x4000_0000).unwrap(), expected);
    }
}
