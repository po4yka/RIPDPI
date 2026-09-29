"""Check full upstream Lua loading and offline native helpers with interception off."""

import argparse
from pathlib import Path
import subprocess
import re
import json


LIBRARIES = ("zapret-lib", "zapret-antidpi", "zapret-auto", "zapret-obfs", "zapret-pcap", "zapret-tests")
OFFLINE_TESTS = ("test_crypto", "test_bin", "test_time", "test_gzip", "test_ipstr", "test_dissect", "test_csum", "test_resolve", "test_ifaddrs", "test_timer")


def upstream_abi_probe():
    upstream = Path(__file__).resolve().parents[1] / "upstream"
    source = (upstream / "nfq2/lua.c").read_text()
    source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
    # Android and the host probe use the Linux registration branch.
    source = re.sub(r"#ifdef __linux__\s*(.*?)#elif defined\(BSD\).*?#endif", r"\1", source, flags=re.S)
    checks = []
    counts = {}
    for table, kind in (("lfunc", "function"), ("cuint", "number"), ("cstr", "string"), ("cbool", "boolean")):
        body = source.split(table + "[] = {", 1)[1].split("};", 1)[0]
        names = sorted(set(re.findall(r'\{"([\w]+)"\s*,', body)))
        counts[table] = len(names)
        checks.extend(f"assert(type(_G[{json.dumps(name)}])=={json.dumps(kind)},{json.dumps(name)})" for name in names)
    functions = sorted({name for file in (upstream / "lua").glob("*.lua")
                        for name in re.findall(r"^function ([A-Za-z_]\w*)\s*\(", file.read_text(), re.M)})
    checks.extend(f"assert(type(_G[{json.dumps(name)}])=='function',{json.dumps(name)})" for name in functions)
    checks.append("assert(NFQWS2_COMPAT_VER==6 and DEFAULT_MSS==1220 and VERDICT_MASK==3 and VERDICT_PRESERVE_NEXT==4)")
    if counts["lfunc"] != 101 or len(functions) != 235:
        raise RuntimeError(f"Pinned ABI inventory changed: {counts}, Lua functions={len(functions)}")
    print(f"Pinned ABI: {counts}, Lua functions={len(functions)}", flush=True)
    return ";".join(checks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    args = parser.parse_args()
    binary = args.binary.resolve()
    lua = args.assets.resolve() / "zapret2/lua"
    init = [f"--lua-init=@{lua / (name + '.lua')}" for name in LIBRARIES]
    cases = (
        ("version", ["--version"]),
        ("native-argv", ["--dry-run", "--qnum=23145", "--filter-tcp=443", "--lua-desync=multisplit:pos=1,midsld"]),
        ("offline-lua", ["--intercept=0", *init, "--lua-init=" + upstream_abi_probe() + ";" + ";".join(name + "()" for name in OFFLINE_TESTS) + ";print('RIPDPI_NFQWS2_OFFLINE_OK')"]),
    )
    for name, flags in cases:
        print(f"=== {name} ===", flush=True)
        result = subprocess.run([str(binary), *flags], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
        print(result.stdout, end="", flush=True)
        if result.returncode:
            raise RuntimeError(f"{name} failed with exit {result.returncode}")
        if name == "offline-lua" and "RIPDPI_NFQWS2_OFFLINE_OK" not in result.stdout:
            raise RuntimeError("Offline Lua completion marker missing")
    print(f"PASS: {len(cases)} process checks, {len(LIBRARIES)} upstream libraries, {len(OFFLINE_TESTS)} offline test groups")


if __name__ == "__main__":
    main()
