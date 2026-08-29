#!/usr/bin/env python3
"""Bounded TCP/UDP peer for the Android 17 LAN permission smoke."""

import argparse
import asyncio
import ipaddress
import json
from contextlib import asynccontextmanager
from pathlib import Path


PAYLOAD = b"ripdpi-target37-lan-smoke"


async def handle_tcp(reader, writer):
    try:
        data = await asyncio.wait_for(reader.readexactly(len(PAYLOAD)), timeout=5)
        if data == PAYLOAD:
            writer.write(PAYLOAD)
            await writer.drain()
    except (asyncio.IncompleteReadError, TimeoutError, ConnectionError):
        pass
    finally:
        writer.close()
        await writer.wait_closed()


class EchoDatagram(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, address):
        if data == PAYLOAD:
            self.transport.sendto(PAYLOAD, address)


@asynccontextmanager
async def lan_fixture(host):
    server = await asyncio.start_server(handle_tcp, host=host, port=0, limit=256)
    transport = None
    try:
        transport, _ = await asyncio.get_running_loop().create_datagram_endpoint(
            EchoDatagram, local_addr=(host, 0),
        )
        yield {
            "host": host,
            "tcpPort": server.sockets[0].getsockname()[1],
            "udpPort": transport.get_extra_info("sockname")[1],
        }
    finally:
        server.close()
        await server.wait_closed()
        if transport is not None:
            transport.close()


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", required=True, help="Assigned non-loopback LAN address of this host")
    parser.add_argument("--ready-file", type=Path, required=True)
    parser.add_argument("--lifetime-seconds", type=int, default=900)
    args = parser.parse_args()
    address = ipaddress.ip_address(args.host)
    if address.is_loopback or address.is_unspecified or not address.is_private:
        parser.error("--host must be an assigned LAN address, not loopback, wildcard or public")
    if str(address) in {"10.0.2.2", "10.0.3.2"}:
        parser.error("Emulator host aliases are not LAN evidence")
    if not 1 <= args.lifetime_seconds <= 3600:
        parser.error("lifetime must be between 1 and 3600 seconds")
    async with lan_fixture(str(address)) as ports:
        args.ready_file.write_text(json.dumps(ports) + "\n", encoding="utf-8")
        await asyncio.sleep(args.lifetime_seconds)


if __name__ == "__main__":
    asyncio.run(main())
