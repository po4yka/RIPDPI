"""Read Ethernet/IPv4 UDP records from local tcpdump captures, without dependencies."""
import struct


def udp_packets(data: bytes) -> list[dict]:
    if len(data) < 24:
        raise ValueError('truncated pcap header')
    endian = {b'\xd4\xc3\xb2\xa1': '<', b'\xa1\xb2\xc3\xd4': '>'}.get(data[:4])
    if endian is None or struct.unpack_from(endian+'I', data, 20)[0] != 1:
        raise ValueError('expected microsecond Ethernet pcap')
    position, packets = 24, []
    while position < len(data):
        if len(data)-position < 16:
            raise ValueError('truncated pcap record')
        length = struct.unpack_from(endian+'I', data, position+8)[0]
        position += 16
        frame = data[position:position+length]
        if len(frame) != length:
            raise ValueError('truncated pcap frame')
        position += length
        if len(frame) < 42 or frame[12:14] != b'\x08\x00' or frame[23] != 17:
            continue
        ihl = (frame[14] & 15)*4
        if ihl < 20 or len(frame) < 14+ihl+8:
            continue
        udp = 14+ihl
        length = int.from_bytes(frame[udp+4:udp+6], 'big')
        packets.append({'source': frame[26:30].hex(), 'ttl': frame[22],
                        'payload': frame[udp+8:udp+length]})
    return packets
