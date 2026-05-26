pub const MAX_PADDED_FRAMES: usize = 8;
const MAX_PAYLOAD_SIZE: usize = u16::MAX as usize;

#[derive(Default)]
pub struct PaddingEncoder {
    written_frames: usize,
}

impl PaddingEncoder {
    pub fn encode_with_padding_size(&mut self, payload: &[u8], padding_size: u8, out: &mut Vec<u8>) -> usize {
        if self.written_frames >= MAX_PADDED_FRAMES {
            out.extend_from_slice(payload);
            return payload.len();
        }

        let payload_consumed = payload.len().min(MAX_PAYLOAD_SIZE);
        let payload_len = u16::try_from(payload_consumed).expect("payload_consumed is capped at u16::MAX");
        out.extend_from_slice(&payload_len.to_be_bytes());
        out.push(padding_size);
        out.extend_from_slice(&payload[..payload_consumed]);
        out.resize(out.len() + usize::from(padding_size), 0);
        self.written_frames += 1;
        payload_consumed
    }
}

#[derive(Default)]
pub struct PaddingDecoder {
    state: DecodeState,
    read_frames: usize,
    payload_len: usize,
    padding_len: usize,
}

impl PaddingDecoder {
    pub fn decode(&mut self, mut input: &[u8], out: &mut Vec<u8>) {
        while !input.is_empty() {
            if self.read_frames >= MAX_PADDED_FRAMES && matches!(self.state, DecodeState::PayloadLength1) {
                out.extend_from_slice(input);
                break;
            }

            match self.state {
                DecodeState::PayloadLength1 => {
                    self.payload_len = usize::from(input[0]) << 8;
                    input = &input[1..];
                    self.state = DecodeState::PayloadLength2;
                }
                DecodeState::PayloadLength2 => {
                    self.payload_len += usize::from(input[0]);
                    input = &input[1..];
                    self.state = DecodeState::PaddingLength;
                }
                DecodeState::PaddingLength => {
                    self.padding_len = usize::from(input[0]);
                    input = &input[1..];
                    self.state = DecodeState::Payload;
                }
                DecodeState::Payload => {
                    let copy_len = self.payload_len.min(input.len());
                    out.extend_from_slice(&input[..copy_len]);
                    self.payload_len -= copy_len;
                    input = &input[copy_len..];
                    if self.payload_len == 0 {
                        self.state = DecodeState::Padding;
                    }
                }
                DecodeState::Padding => {
                    let skip_len = self.padding_len.min(input.len());
                    self.padding_len -= skip_len;
                    input = &input[skip_len..];
                    if self.padding_len == 0 {
                        self.read_frames += 1;
                        self.state = DecodeState::PayloadLength1;
                    }
                }
            }
        }
    }
}

#[derive(Default)]
enum DecodeState {
    #[default]
    PayloadLength1,
    PayloadLength2,
    PaddingLength,
    Payload,
    Padding,
}
