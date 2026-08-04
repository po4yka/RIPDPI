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

    if candidate.get("trigger") != "workflow_dispatch":
        raise ContractError("candidate.trigger must be workflow_dispatch")
    candidate_source = candidate_workflow.read_text(encoding="utf-8")
    _require_pattern(candidate_source, r"^  workflow_dispatch:\s*$", "candidate workflow must use workflow_dispatch")
    required_input = re.escape(_string(candidate.get("requiredInput"), "candidate.requiredInput"))
    _require_pattern(
        candidate_source,
        rf"^      {required_input}:\s*$",
        "candidate workflow is missing its required input",
    )
    required_ref = _string(candidate.get("requiredRef"), "candidate.requiredRef")
    if required_ref not in candidate_source:
        raise ContractError("candidate workflow does not enforce candidate.requiredRef")
    for required_fragment in (
        "candidate-preflight:",
        "scripts/ci/require_successful_ci_run.py",
        f"--workflow-path {required_ci['workflow']}",
        f"--event {required_ci['event']}",
        f"--aggregate-job {required_ci['aggregateJob']}",
        "needs: candidate-preflight",
    ):
        if required_fragment not in candidate_source:
            raise ContractError(
                f"candidate workflow does not enforce required CI fragment: {required_fragment}"
            )

    if publication.get("trigger") != "tag_push":
        raise ContractError("publication.trigger must be tag_push")
    publication_source = publication_workflow.read_text(encoding="utf-8")
    tag_pattern = re.escape(_string(publication.get("tagPattern"), "publication.tagPattern"))
    _require_pattern(publication_source, rf'^    tags: \["{tag_pattern}"\]\s*$', "publication workflow tag trigger does not match the contract")
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
