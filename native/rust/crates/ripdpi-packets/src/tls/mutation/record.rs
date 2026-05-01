use crate::types::PacketMutation;
use crate::util::{read_u16, write_u16};

pub fn part_tls_like_c(input: &[u8], pos: isize) -> PacketMutation {
    let n = input.len();
    if n < 3 || pos < 0 || pos as usize + 5 > n {
        return PacketMutation { rc: 0, bytes: input.to_vec() };
    }
    let mut output = vec![0; n + 5];
    output[..n].copy_from_slice(input);

    let Some(record_size) = read_u16(&output, 3) else {
        return PacketMutation { rc: 0, bytes: input.to_vec() };
    };
    if record_size < pos as usize {
        return PacketMutation { rc: n as isize, bytes: input.to_vec() };
    }

    let pos = pos as usize;
    output.copy_within(5 + pos..n, 10 + pos);
    output[5 + pos..5 + pos + 3].copy_from_slice(&input[..3]);
    let _ = write_u16(&mut output, 3, pos);
    let _ = write_u16(&mut output, 8 + pos, record_size.saturating_sub(pos));

    PacketMutation { rc: 5, bytes: output }
}
