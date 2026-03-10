#!/usr/bin/env python3

from __future__ import annotations

import sys
from pathlib import Path


TLS_CLIENT_HELLO_HEX = """
1603010200010001fc0303035f6f2ced1322f8dcb2f260482d72666f57dd139d1b37dcfa362ebaf992993a20
f9df0c2e8a55898231631aefa8be0858a7a35a18d3965f045cb462af89d70f8b003e130213031301c02cc030
009fcca9cca8ccaac02bc02f009ec024c028006bc023c0270067c00ac0140039c009c0130033009d009c003d
003c0035002f00ff010001750000001600140000117777772e77696b6970656469612e6f7267000b00040300
0102000a00160014001d0017001e00190018010001010102010301040010000e000c02683208687474702f31
2e31001600000017000000310000000d002a0028040305030603080708080809080a080b0804080508060401
05010601030303010302040205020602002b0009080304030303020301002d00020101003300260024001d00
20118cb88ce88a08901eee19d9dde8d406b1d1e2abe01663d6dcda84a4b84bfb0e001500ac000000000000
"""

HTTP_REQUEST = b"GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n"
HTTP_REDIRECT_RESPONSE = (
    b"HTTP/1.1 302 Found\r\n"
    b"Location: https://example.net/wiki\r\n"
    b"Content-Length: 0\r\n"
    b"\r\n"
)


def read_u16(data: bytearray | bytes, offset: int) -> int:
    return (data[offset] << 8) | data[offset + 1]


def write_u16(data: bytearray, offset: int, value: int) -> None:
    data[offset] = (value >> 8) & 0xFF
    data[offset + 1] = value & 0xFF


def write_u24(data: bytearray, offset: int, value: int) -> None:
    data[offset] = (value >> 16) & 0xFF
    data[offset + 1] = (value >> 8) & 0xFF
    data[offset + 2] = value & 0xFF


def find_ext_block(data: bytearray | bytes) -> int:
    sid_len = data[43]
    cip_len = read_u16(data, 44 + sid_len)
    return 44 + sid_len + 2 + cip_len + 2


def find_extension(data: bytearray | bytes, ext_type: int) -> int:
    block = find_ext_block(data)
    pos = block + 2
    end = pos + read_u16(data, block)
    while pos + 4 <= end:
        curr_type = read_u16(data, pos)
        curr_len = read_u16(data, pos + 2)
        if curr_type == ext_type:
            return pos
        pos += 4 + curr_len
    raise ValueError(f"extension 0x{ext_type:04x} not found")


def build_tls_client_hello() -> bytes:
    data = bytearray(bytes.fromhex(TLS_CLIENT_HELLO_HEX))
    if len(data) > 517:
        raise ValueError("base TLS seed is larger than expected")
    data.extend(b"\x00" * (517 - len(data)))
    return bytes(data)


def build_tls_server_hello_like(request: bytes) -> bytes:
    data = bytearray(request)
    sid_len = data[43]
    ext_block = 44 + sid_len + 3
    data[5] = 0x02
    if data[43]:
        data[44] ^= 0x01
    write_u16(data, ext_block, 5)
    write_u16(data, ext_block + 2, 0x002B)
    write_u16(data, ext_block + 4, 1)
    data[ext_block + 6] = 0
    return bytes(data)


def build_tls_client_hello_ech(request: bytes) -> bytes:
    data = bytearray(request)
    ech_extension = bytes.fromhex(
        "fe0d000e000000000000000201020002aabb"
    )
    insert_at = find_extension(data, 0x0015)

    data[insert_at:insert_at] = ech_extension

    ext_block = find_ext_block(data)
    write_u16(data, ext_block, read_u16(data, ext_block) + len(ech_extension))
    write_u16(data, 3, read_u16(data, 3) + len(ech_extension))

    handshake_len = ((data[6] << 16) | (data[7] << 8) | data[8]) + len(ech_extension)
    if handshake_len > 0xFFFFFF:
        raise ValueError("handshake length overflow")
    write_u24(data, 6, handshake_len)

    return bytes(data)


def main(argv: list[str]) -> int:
    out_dir = Path(argv[1]) if len(argv) > 1 else Path("tests/corpus/packets")
    out_dir.mkdir(parents=True, exist_ok=True)

    tls_client_hello = build_tls_client_hello()
    seeds = {
        "http_request.bin": HTTP_REQUEST,
        "http_redirect_response.bin": HTTP_REDIRECT_RESPONSE,
        "tls_client_hello.bin": tls_client_hello,
        "tls_client_hello_ech.bin": build_tls_client_hello_ech(tls_client_hello),
        "tls_server_hello_like.bin": build_tls_server_hello_like(tls_client_hello),
    }

    for name, content in seeds.items():
        (out_dir / name).write_bytes(content)

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
