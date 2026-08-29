import importlib.util
import socket
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "ci" / "target37_lan_fixture.py"


class Target37LanFixtureTest(unittest.IsolatedAsyncioTestCase):
    async def test_tcp_and_udp_echo_the_exact_probe_payload(self):
        spec = importlib.util.spec_from_file_location("target37_lan_fixture", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        import asyncio

        async with module.lan_fixture("127.0.0.1") as ports:
            reader, writer = await asyncio.open_connection("127.0.0.1", ports["tcpPort"])
            writer.write(module.PAYLOAD)
            await writer.drain()
            self.assertEqual(module.PAYLOAD, await asyncio.wait_for(reader.readexactly(len(module.PAYLOAD)), 2))
            writer.close()
            await writer.wait_closed()
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
                udp.setblocking(False)
                loop = asyncio.get_running_loop()
                await loop.sock_sendto(udp, module.PAYLOAD, ("127.0.0.1", ports["udpPort"]))
                response, _ = await asyncio.wait_for(loop.sock_recvfrom(udp, 256), 2)
                self.assertEqual(module.PAYLOAD, response)


if __name__ == "__main__":
    unittest.main()
