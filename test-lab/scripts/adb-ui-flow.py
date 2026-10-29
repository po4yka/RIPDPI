#!/usr/bin/env python3
"""Drive the RIPDPI local-network lab UI flows over adb.

Each flow reads the screen with `uiautomator dump`, finds nodes by Compose test tag
(exposed as resource-id) or by text, and taps their centers. The device is selected
through ANDROID_SERIAL, as for every other lab script. The caller pre-grants VPN
consent (`appops set com.poyka.ripdpi ACTIVATE_VPN allow`), so no system dialog appears.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.poyka.ripdpi"
MODES_HEADER = "home-modes-diagnostics-header"
MODES_COLLAPSED = "home-modes-diagnostics-collapsed"
PROXY_CARD = "home-mode-card-local-dpi-bypass"
PROXY_MODE = "home-mode-primary-local-dpi-bypass"
VPN_CARD = "home-mode-card-remote-vpn"
VPN_MODE = "home-mode-primary-remote-vpn"


class FlowError(RuntimeError):
    pass


def adb(*args: str) -> str:
    return subprocess.run(["adb", *args], check=True, capture_output=True, text=True).stdout


def parse_dump(raw: str) -> ET.Element:
    # `uiautomator dump /dev/tty` appends a status line after the XML document.
    start, end = raw.find("<?xml"), raw.rfind(">")
    if start < 0 or end < start:
        raise FlowError(f"uiautomator dump returned no XML: {raw[:200]!r}")
    return ET.fromstring(raw[start : end + 1])


def find_node(root: ET.Element, *, tag: str | None = None, text: str | None = None) -> ET.Element | None:
    pattern = re.compile(text) if text is not None else None
    for node in root.iter("node"):
        if tag is not None and node.get("resource-id") in (tag, f"{PACKAGE}:id/{tag}"):
            return node
        if pattern is not None and any(
            pattern.fullmatch(node.get(attr) or "") for attr in ("text", "content-desc")
        ):
            return node
    return None


def center(node: ET.Element) -> tuple[int, int]:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds") or "")
    if match is None:
        raise FlowError(f"node has no bounds: {node.attrib}")
    left, top, right, bottom = map(int, match.groups())
    return (left + right) // 2, (top + bottom) // 2


def screen(retries: int = 10) -> ET.Element:
    for attempt in range(retries):
        try:
            return parse_dump(adb("exec-out", "uiautomator", "dump", "/dev/tty"))
        except FlowError:
            # uiautomator returns "null root node" while a window is opening.
            if attempt == retries - 1:
                raise
            time.sleep(0.5)
    raise AssertionError("unreachable")


def tap(node: ET.Element) -> None:
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))


def scroll_to(tag: str, attempts: int = 8) -> ET.Element:
    width, height = map(int, re.findall(r"\d+", adb("shell", "wm", "size"))[-2:])
    previous_bounds = None
    for _ in range(attempts * 4):
        node = find_node(screen(), tag=tag)
        if node is not None:
            # A swipe keeps flinging; tap only once the node stops moving.
            if node.get("bounds") == previous_bounds:
                return node
            previous_bounds = node.get("bounds")
            time.sleep(0.3)
            continue
        previous_bounds = None
        adb("shell", "input", "swipe", str(width // 2), str(height * 3 // 4), str(width // 2), str(height // 3), "300")
    raise FlowError(f"{tag} not found after {attempts} scrolls")


def show_modes() -> None:
    header = scroll_to(MODES_HEADER)
    if find_node(screen(), tag=MODES_COLLAPSED) is not None:
        tap(header)


def wait_status(card: str, status: str, timeout_s: float = 20.0) -> None:
    """Wait until the mode card announces `Status: <status>` (its content description)."""
    deadline = time.monotonic() + timeout_s
    while True:
        node = scroll_to(card)
        if find_node(node, text=f"Status: {status}") is not None:
            return
        if time.monotonic() > deadline:
            raise FlowError(f"{card} is not {status} after {timeout_s}s")
        time.sleep(0.5)


def connect_proxy() -> None:
    show_modes()
    tap(scroll_to(PROXY_MODE))
    wait_status(PROXY_CARD, "Active")


def disconnect_proxy() -> None:
    show_modes()
    wait_status(PROXY_CARD, "Active")
    tap(scroll_to(PROXY_MODE))
    wait_status(PROXY_CARD, "Inactive")


def connect_vpn() -> None:
    show_modes()
    tap(scroll_to(VPN_MODE))
    wait_status(VPN_CARD, "Active")


def disconnect_vpn() -> None:
    show_modes()
    wait_status(VPN_CARD, "Active")
    tap(scroll_to(VPN_MODE))
    wait_status(VPN_CARD, "Inactive")


def reconnect() -> None:
    """Stop proxy mode, then start VPN mode from the same Home screen."""
    disconnect_proxy()
    tap(scroll_to(VPN_MODE))
    wait_status(VPN_CARD, "Active")


FLOWS = {
    "connect-proxy": connect_proxy,
    "disconnect-proxy": disconnect_proxy,
    "connect-vpn": connect_vpn,
    "disconnect-vpn": disconnect_vpn,
    "reconnect": reconnect,
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Drive the RIPDPI local-network lab UI flows over adb.")
    parser.add_argument("flow", choices=sorted(FLOWS))
    args = parser.parse_args()
    try:
        FLOWS[args.flow]()
    except (FlowError, subprocess.CalledProcessError) as error:
        print(f"adb-ui-flow {args.flow} failed: {error}", file=sys.stderr)
        return 1
    print(f"adb-ui-flow {args.flow} passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
