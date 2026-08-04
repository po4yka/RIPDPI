#!/usr/bin/env python3
"""Validate the machine-readable release contract and its maintained guidance."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONTRACT = ROOT / "quality/release-gates/release-contract.json"
SOURCE_REFERENCE = re.compile(
    r"(?<![A-Za-z0-9_-])"
    r"((?:\.github|\.agents|scripts|docs|quality|test-lab)/[A-Za-z0-9_./*-]+)"
)
INLINE_CODE = re.compile(r"`([^`\n]+)`")


class ContractError(ValueError):
    """Raised when the checked-in release contract is inconsistent."""


def _object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ContractError(f"{field} must be an object")
    return value


def _string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise ContractError(f"{field} must be a non-empty string")
    return value


def _repo_path(root: Path, value: Any, field: str) -> Path:
    relative = _string(value, field)
    path = root / relative
    if not path.exists():
        raise ContractError(f"{field} does not exist: {relative}")
    return path


def _require_pattern(source: str, pattern: str, message: str) -> None:
    if re.search(pattern, source, re.MULTILINE) is None:
        raise ContractError(message)


def _trigger_entries(source: str, field: str) -> list[tuple[int, str, str]]:
    block_match = re.search(r"(?ms)^on:\s*\n(.*?)(?=^[A-Za-z][\w-]*:|\Z)", source)
    if block_match is None:
        raise ContractError(f"{field} workflow is missing an on block")
    entries: list[tuple[int, str, str]] = []
    for raw_line in block_match.group(1).splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        if "\t" in raw_line:
            raise ContractError(f"{field} trigger must not use tabs")
        indent = len(raw_line) - len(raw_line.lstrip(" "))
        key, separator, value = raw_line.strip().partition(":")
        if not separator or re.fullmatch(r"[A-Za-z_][\w-]*", key) is None:
            raise ContractError(f"{field} contains unsupported YAML: {raw_line.strip()}")
        entries.append((indent, key, value.strip()))
    return entries


def validate_workflow_trigger(source: str, expected: dict[str, Any], field: str) -> None:
    entries = _trigger_entries(source, field)
    event = _string(expected.get("event"), f"{field}.event")
    events = [(key, value) for indent, key, value in entries if indent == 2]
    if events != [(event, "")]:
        raise ContractError(f"{field} events must be exactly [{event}], found {events}")

    if event == "workflow_dispatch":
        inputs = _object(expected.get("inputs"), f"{field}.inputs")
        level_four = [(key, value) for indent, key, value in entries if indent == 4]
        if level_four != [("inputs", "")]:
            raise ContractError(
                f"{field} workflow_dispatch keys must be exactly [inputs], found {level_four}"
            )
        input_entries = [(key, value) for indent, key, value in entries if indent == 6]
        input_names = [key for key, _ in input_entries]
        if input_names != list(inputs) or any(value for _, value in input_entries):
            raise ContractError(
                f"{field} inputs must be exactly {list(inputs)}, found {input_entries}"
            )
        properties: dict[str, dict[str, str]] = {name: {} for name in input_names}
        current_input: str | None = None
        for indent, key, value in entries:
            if indent == 6:
                current_input = key
            elif indent == 8:
                if current_input is None:
                    raise ContractError(f"{field} input property has no parent input")
                if key in properties[current_input]:
                    raise ContractError(f"{field} input {current_input} repeats property {key}")
                properties[current_input][key] = value
            elif indent not in (2, 4):
                raise ContractError(f"{field} contains unsupported indentation {indent}")
        for name, contract_value in inputs.items():
            input_contract = _object(contract_value, f"{field}.inputs.{name}")
            required = input_contract.get("required")
            input_type = _string(input_contract.get("type"), f"{field}.inputs.{name}.type")
            if required is not True:
                raise ContractError(f"{field}.inputs.{name}.required must be true")
            actual = properties[name]
            unexpected = set(actual) - {"description", "required", "type"}
            if unexpected:
                raise ContractError(f"{field} input {name} has unexpected properties {sorted(unexpected)}")
            if actual.get("required") != "true":
                raise ContractError(f"{field} input {name} must be required")
            if actual.get("type") != input_type:
                raise ContractError(f"{field} input {name} type does not match")
    elif event == "push":
        tags = expected.get("tags")
        if not isinstance(tags, list) or not tags or not all(isinstance(tag, str) for tag in tags):
            raise ContractError(f"{field}.tags must be a non-empty string list")
        children = [(key, value) for indent, key, value in entries if indent == 4]
        if len(children) != 1 or children[0][0] != "tags":
            raise ContractError(f"{field} push keys must be exactly [tags], found {children}")
        if any(indent not in (2, 4) for indent, _, _ in entries):
            raise ContractError(f"{field} push trigger contains unsupported nested keys")
        try:
            actual_tags = json.loads(children[0][1])
        except json.JSONDecodeError as error:
            raise ContractError(f"{field} tags must use a JSON-compatible inline list") from error
        if actual_tags != tags:
            raise ContractError(f"{field} tags must be exactly {tags}, found {actual_tags}")
    else:
        raise ContractError(f"unsupported {field} event: {event}")


def _validate_guidance_references(root: Path, guidance: list[Any]) -> None:
    for index, value in enumerate(guidance):
        path = _repo_path(root, value, f"guidance[{index}]")
        source = path.read_text(encoding="utf-8")
        for code in INLINE_CODE.findall(source):
            if any(marker in code for marker in ("<", ">", "$")):
                continue
            match = SOURCE_REFERENCE.search(code)
            if match is None:
                continue
            reference = match.group(1).rstrip(".,:;)")
            if "*" in reference:
                if not list(root.glob(reference)):
                    raise ContractError(f"{path.relative_to(root)} references no files: {reference}")
            elif not (root / reference).exists():
                raise ContractError(f"{path.relative_to(root)} references missing path: {reference}")


def validate_contract(contract_path: Path = DEFAULT_CONTRACT, root: Path = ROOT) -> None:
    contract = _object(json.loads(contract_path.read_text(encoding="utf-8")), "contract")
    if contract.get("schemaVersion") != 1:
        raise ContractError("schemaVersion must be 1")

    candidate = _object(contract.get("candidate"), "candidate")
    publication = _object(contract.get("publication"), "publication")
    required_ci = _object(candidate.get("requiredCi"), "candidate.requiredCi")
    candidate_workflow = _repo_path(root, candidate.get("workflow"), "candidate.workflow")
    publication_workflow = _repo_path(
        root, publication.get("workflow"), "publication.workflow"
    )
    ci_workflow = _repo_path(root, required_ci.get("workflow"), "candidate.requiredCi.workflow")

    candidate_source = candidate_workflow.read_text(encoding="utf-8")
    validate_workflow_trigger(
        candidate_source,
        _object(candidate.get("trigger"), "candidate.trigger"),
        "candidate.trigger",
    )
    required_ref = _string(candidate.get("requiredRef"), "candidate.requiredRef")
    if required_ref not in candidate_source:
        raise ContractError("candidate workflow does not enforce candidate.requiredRef")
    for required_fragment in (
        "candidate-preflight:",
        "scripts/ci/require_successful_ci_run.py",
        "--contract quality/release-gates/release-contract.json",
        "needs: candidate-preflight",
        "scripts/ci/check_release_window.py",
        "RIPDPI_RELEASE_WINDOW_START_SHA",
        "RIPDPI_RELEASE_WINDOW_STARTED_AT",
    ):
        if required_fragment not in candidate_source:
            raise ContractError(
                f"candidate workflow does not enforce required CI fragment: {required_fragment}"
            )

    window = _object(contract.get("releaseWindow"), "releaseWindow")
    if set(window) != {
        "startShaVariable",
        "startedAtVariable",
        "maxAgeHours",
        "maxCommits",
        "maxCandidateRuns",
        "allowedSubjectPatterns",
    }:
        raise ContractError("releaseWindow fields do not match the supported contract")
    for variable_field in ("startShaVariable", "startedAtVariable"):
        variable = _string(window.get(variable_field), f"releaseWindow.{variable_field}")
        if variable not in candidate_source:
            raise ContractError(
                f"releaseWindow.{variable_field} is not consumed by candidate workflow"
            )
    for field, maximum in (("maxAgeHours", 168), ("maxCommits", 50), ("maxCandidateRuns", 10)):
        value = window.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= maximum:
            raise ContractError(f"releaseWindow.{field} must be 1..{maximum}")
    patterns = window.get("allowedSubjectPatterns")
    if not isinstance(patterns, list) or not patterns or not all(isinstance(item, str) for item in patterns):
        raise ContractError("releaseWindow.allowedSubjectPatterns must be a string list")
    try:
        for pattern in patterns:
            re.compile(pattern)
    except re.error as error:
        raise ContractError("releaseWindow contains an invalid regex") from error

    publication_source = publication_workflow.read_text(encoding="utf-8")
    validate_workflow_trigger(
        publication_source,
        _object(publication.get("trigger"), "publication.trigger"),
        "publication.trigger",
    )
    candidate_variable = _string(
        publication.get("candidateRunVariable"), "publication.candidateRunVariable"
    )
    if candidate_variable not in publication_source:
        raise ContractError("publication workflow does not consume candidateRunVariable")

    ci_source = ci_workflow.read_text(encoding="utf-8")
    aggregate_job = re.escape(_string(required_ci.get("aggregateJob"), "candidate.requiredCi.aggregateJob"))
    _require_pattern(ci_source, rf"^  {aggregate_job}:\s*$", "required CI aggregate job is missing")
    if required_ci.get("event") != "push":
        raise ContractError("candidate.requiredCi.event must be push")

    profiles = _object(contract.get("assuranceProfiles"), "assuranceProfiles")
    if set(profiles) != {"artifact-publish", "device-qualified", "owner-accepted"}:
        raise ContractError("assuranceProfiles must define the three supported profiles")
    if _object(profiles["artifact-publish"], "assuranceProfiles.artifact-publish").get("releaseBlocking") is not True:
        raise ContractError("artifact-publish must remain release blocking")

    guidance = contract.get("guidance")
    if not isinstance(guidance, list) or not guidance:
        raise ContractError("guidance must be a non-empty list")
    _validate_guidance_references(root, guidance)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    args = parser.parse_args()
    try:
        validate_contract(args.contract.resolve(), ROOT)
    except (ContractError, json.JSONDecodeError) as error:
        parser.error(str(error))
    print("release contract: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
