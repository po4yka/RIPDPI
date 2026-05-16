"""Minimal TLS ClientHello builder used by tests and the CI smoke
traffic generator.

The output is just enough to carry a server_name extension that the
pattern classifiers will match against. Cipher suite list, compression
methods, and most extensions are present only at the byte counts the
parser needs to walk past.
"""

from __future__ import annotations


def build_clienthello_with_sni(sni: str) -> bytes:
    """Return a minimal TLS ClientHello record carrying the given SNI."""
    sni_bytes = sni.encode("ascii")
    name_entry = bytes([0x00]) + len(sni_bytes).to_bytes(2, "big") + sni_bytes
    sni_ext_body = len(name_entry).to_bytes(2, "big") + name_entry
    sni_ext = bytes([0x00, 0x00]) + len(sni_ext_body).to_bytes(2, "big") + sni_ext_body
    ext_block = len(sni_ext).to_bytes(2, "big") + sni_ext

    ch_body = (
        bytes([0x03, 0x03])
        + bytes(32)
        + bytes([0x00])
        + bytes([0x00, 0x02])
        + bytes([0x00, 0x35])
        + bytes([0x01])
        + bytes([0x00])
        + ext_block
    )
    handshake = bytes([0x01]) + len(ch_body).to_bytes(3, "big") + ch_body
    return bytes([0x16, 0x03, 0x01]) + len(handshake).to_bytes(2, "big") + handshake
