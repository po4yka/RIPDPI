#!/usr/bin/env python3
"""Seal CI APKs and run them with Android Test Orchestrator, without Gradle."""

import argparse
import hashlib
import json
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


VARIANTS = {"githubFullDebug": "com.poyka.ripdpi", "githubSimpleDebug": "com.poyka.ripdpi.simple"}


def bundle_file(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError(f"Invalid bundle file: {relative}")
    return path


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def seal(root, sha):
    manifest = {"sha": sha, "variants": {}, "files": {}}
    for variant, package in VARIANTS.items():
        entry = {"package": package}
        for kind, expected_id in (("app", package), ("test", package + ".test")):
            directory = root / variant / kind
            metadata = json.loads((directory / "output-metadata.json").read_text())
            elements = metadata["elements"]
            if metadata["applicationId"] != expected_id or len(elements) != 1 or elements[0].get("filters"):
                raise ValueError(f"Expected one universal {variant} {kind} APK for {expected_id}")
            relative = f"{variant}/{kind}/{elements[0]['outputFile']}"
            bundle_file(root, relative)
            entry[kind] = relative
        manifest["variants"][variant] = entry
    utilities = []
    for pattern in ("orchestrator-*.apk", "test-services-*.apk"):
        matches = list((root / "utilities").glob(pattern))
        if len(matches) != 1:
            raise ValueError(f"Expected one resolved {pattern}")
        utilities.append(matches[0].relative_to(root).as_posix())
    manifest["utilities"] = utilities
    files = utilities + [entry[kind] for entry in manifest["variants"].values() for kind in ("app", "test")]
    manifest["files"] = {name: digest(bundle_file(root, name)) for name in files}
    (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def verify(root, sha):
    manifest = json.loads((root / "manifest.json").read_text())
    if not re.fullmatch(r"[0-9a-f]{40}", sha) or manifest["sha"] != sha:
        raise ValueError("APK bundle does not match the requested source SHA")
    if set(manifest["variants"]) != set(VARIANTS) or len(manifest["utilities"]) != 2:
        raise ValueError("Incomplete APK bundle")
    files = list(manifest["utilities"])
    for variant, expected in VARIANTS.items():
        entry = manifest["variants"][variant]
        if entry["package"] != expected:
            raise ValueError("Unexpected application ID")
        files.extend(entry[kind] for kind in ("app", "test"))
    if len(set(files)) != 6 or set(files) != set(manifest["files"]):
        raise ValueError("APK bundle file inventory differs")
    for name in files:
        if digest(bundle_file(root, name)) != manifest["files"][name]:
            raise ValueError(f"APK checksum differs: {name}")
    return manifest


def parse_results(output):
    """Read am instrument -r status bundles; reject truncated or ambiguous runs."""
    suite = ET.Element("testsuite", name="Android instrumentation")
    bundle, active, seen = {}, None, set()
    expected, final, last_key = None, None, None
    for line in output.replace("\r", "").splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if final is not None:
                raise ValueError("Test status after instrumentation completion")
            key, separator, value = line[24:].partition("=")
            if not separator:
                raise ValueError("Malformed instrumentation status")
            bundle[key], last_key = value, key
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.split(": ", 1)[1])
            if "numtests" in bundle:
                count = int(bundle["numtests"])
                if count < 1 or expected not in (None, count):
                    raise ValueError("Inconsistent instrumentation test count")
                expected = count
            identity = (bundle.get("class"), bundle.get("test"))
            if code == 1:
                if active or not all(identity) or identity in seen:
                    raise ValueError("Invalid or duplicate test start")
                active = identity
            elif code in (0, -1, -2, -3, -4):
                if not all(identity):
                    identity = active
                if not identity or identity in seen or (active and active != identity):
                    raise ValueError("Unmatched or duplicate test result")
                if active is None and code not in (-3, -4):
                    raise ValueError("Test result without a start")
                case = ET.SubElement(suite, "testcase", classname=identity[0], name=identity[1])
                tag = {-1: "error", -2: "failure", -3: "skipped", -4: "skipped"}.get(code)
                if tag:
                    ET.SubElement(case, tag).text = bundle.get("stack", bundle.get("stream", f"status {code}"))
                seen.add(identity)
                active = None
            elif code != 2:
                raise ValueError(f"Unknown instrumentation status {code}")
            bundle, last_key = {}, None
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if final is not None:
                raise ValueError("Multiple instrumentation completions")
            final = int(line.split(": ", 1)[1])
            last_key = None
        elif line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:", "INSTRUMENTATION_RESULT: shortMsg=")):
            raise ValueError(line)
        elif line.startswith("INSTRUMENTATION_RESULT:"):
            last_key = None
        elif last_key:
            bundle[last_key] += "\n" + line
    if final != -1 or active or bundle or not seen or len(seen) != expected:
        raise ValueError("Incomplete instrumentation run")
    for attribute, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped")):
        suite.set(attribute, str(len(suite.findall(f"testcase/{tag}"))))
    suite.set("tests", str(len(seen)))
    return suite


def orchestrator_command(target, arguments):
    command = ["am", "instrument", "-w", "-r", "-e", "targetInstrumentation", target]
    for key, value in {"clearPackageData": "true", "coverage": "false", **arguments}.items():
        command.extend(("-e", key, value))
    command.append("androidx.test.orchestrator/.AndroidTestOrchestrator")
    return "CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain " + shlex.join(command)


def run(args):
    manifest = verify(args.bundle, args.sha)
    args.results.mkdir(parents=True, exist_ok=True)
    if list(args.results.glob("*.xml")):
        raise ValueError("Results directory must not contain stale XML")
    devices = subprocess.check_output(["adb", "devices"], text=True)
    serials = re.findall(r"^(emulator-\d+)\s+device$", devices, re.MULTILINE)
    if len(serials) != 1:
        raise ValueError("Expected exactly one disposable CI emulator")

    def adb(*command, timeout=120, check=True):
        return subprocess.run(["adb", "-s", serials[0], *map(str, command)],
                              capture_output=True, text=True, timeout=timeout, check=check)

    if adb("shell", "getprop", "ro.build.version.sdk").stdout.strip() != str(args.api):
        raise ValueError("Emulator API differs from the CI matrix")
    entry = manifest["variants"][args.variant]
    package = entry["package"]
    packages = [package, package + ".test", "androidx.test.orchestrator", "androidx.test.services"]
    try:
        install_flags = ["-r", "-t", "-g"] + (["--force-queryable"] if args.api >= 30 else [])
        for name in [entry["app"], entry["test"], *manifest["utilities"]]:
            adb("install", *install_flags, bundle_file(args.bundle, name))
        for setting in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
            adb("shell", "settings", "put", "global", setting, "0")
        installed = adb("shell", "pm", "list", "instrumentation").stdout
        targets = re.findall(rf"^instrumentation:({re.escape(package)}\.test/\S+) \(target={re.escape(package)}\)$", installed, re.MULTILINE)
        if len(targets) != 1:
            raise ValueError("Expected one instrumentation runner for the installed APK pair")
        arguments = {"class" if args.test_class else "package": args.test_class or args.test_package}
        if args.xray_port:
            arguments["ripdpi.xrayFixturePort"] = str(args.xray_port)
        output_file = args.results / "instrumentation.txt"
        # Write output as it arrives so timeouts and runner crashes retain evidence.
        with output_file.open("w") as output:
            started = time.monotonic()
            result = subprocess.run(["adb", "-s", serials[0], "shell", orchestrator_command(targets[0], arguments)],
                                    stdout=output, stderr=subprocess.STDOUT, timeout=1800)
        result.check_returncode()
        suite = parse_results(output_file.read_text())
        suite.set("time", f"{time.monotonic() - started:.3f}")
        ET.ElementTree(suite).write(args.results / "TEST-instrumentation.xml", encoding="utf-8", xml_declaration=True)
        if suite.get("failures") != "0" or suite.get("errors") != "0":
            raise ValueError("Instrumentation reported failed tests")
    finally:
        with (args.results / "cleanup.txt").open("w") as output:
            for name in reversed(packages):
                result = adb("uninstall", name, check=False)
                output.write(f"{name}: {result.returncode}\n{result.stdout}{result.stderr}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("seal", "run"):
        child = commands.add_parser(name)
        child.add_argument("--bundle", type=Path, required=True)
        child.add_argument("--sha", required=True)
        if name == "run":
            child.add_argument("--variant", choices=VARIANTS, required=True)
            child.add_argument("--api", type=int, required=True)
            child.add_argument("--results", type=Path, required=True)
            selection = child.add_mutually_exclusive_group(required=True)
            selection.add_argument("--test-class")
            selection.add_argument("--test-package")
            child.add_argument("--xray-port", type=int)
    args = parser.parse_args()
    if args.command == "run" and args.xray_port is not None and not 1 <= args.xray_port <= 65535:
        parser.error("--xray-port must be between 1 and 65535")
    try:
        if args.command == "seal":
            seal(args.bundle, args.sha)
            verify(args.bundle, args.sha)
        else:
            run(args)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"Prebuilt instrumentation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
