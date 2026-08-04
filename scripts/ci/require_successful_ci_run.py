#!/usr/bin/env python3
"""Require a successful exact-SHA GitHub Actions workflow and aggregate job."""

from __future__ import annotations

import argparse
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
SHA = re.compile(r"^[0-9a-f]{40}$")


class ProvenanceError(ValueError):
    """Raised when an exact successful CI run cannot be proven."""


def fetch_json(url: str, token: str) -> dict[str, Any]:
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as error:
        raise ProvenanceError(f"GitHub API request failed: {error}") from error
    if not isinstance(payload, dict):
        raise ProvenanceError("GitHub API response must be an object")
    return payload


def require_successful_ci_run(
    *,
    repository: str,
    sha: str,
    token: str,
    api_url: str = "https://api.github.com",
    workflow: str = "ci.yml",
    workflow_path: str = ".github/workflows/ci.yml",
    event: str = "push",
    branch: str = "main",
    aggregate_job: str = "ci-required",
) -> dict[str, Any]:
    if REPOSITORY.fullmatch(repository) is None:
        raise ProvenanceError("repository must use owner/name syntax")
    if SHA.fullmatch(sha) is None:
        raise ProvenanceError("sha must be a 40-character lowercase Git object ID")
    if not token:
        raise ProvenanceError("GitHub token is required")

    query = urllib.parse.urlencode(
        {
            "branch": branch,
            "event": event,
            "head_sha": sha,
            "per_page": 100,
            "status": "success",
        }
    )
    workflow_id = urllib.parse.quote(workflow, safe="")
    runs_url = (
        f"{api_url.rstrip('/')}/repos/{repository}/actions/workflows/"
        f"{workflow_id}/runs?{query}"
    )
    runs_payload = fetch_json(runs_url, token)
    runs = runs_payload.get("workflow_runs")
    if not isinstance(runs, list):
        raise ProvenanceError("workflow-runs response is missing workflow_runs")
    matches = [
        run
        for run in runs
        if isinstance(run, dict)
        and run.get("conclusion") == "success"
        and run.get("event") == event
        and run.get("head_branch") == branch
        and run.get("head_sha") == sha
        and run.get("path") == workflow_path
        and isinstance(run.get("id"), int)
    ]
    if not matches:
        raise ProvenanceError(
            f"no successful {workflow_path} {event} run exists for {branch}@{sha}"
        )
    run = max(matches, key=lambda item: (item.get("run_attempt", 0), item["id"]))

    jobs: list[Any] = []
    for page in range(1, 11):
        jobs_url = (
            f"{api_url.rstrip('/')}/repos/{repository}/actions/runs/{run['id']}/jobs"
            f"?filter=latest&per_page=100&page={page}"
        )
        jobs_payload = fetch_json(jobs_url, token)
        page_jobs = jobs_payload.get("jobs")
        if not isinstance(page_jobs, list):
            raise ProvenanceError("workflow-jobs response is missing jobs")
        jobs.extend(page_jobs)
        if len(page_jobs) < 100:
            break
    else:
        raise ProvenanceError("workflow job listing exceeded the pagination limit")

    aggregate_matches = [
        job
        for job in jobs
        if isinstance(job, dict) and job.get("name") == aggregate_job
    ]
    if len(aggregate_matches) != 1:
        raise ProvenanceError(
            f"expected exactly one {aggregate_job} job, found {len(aggregate_matches)}"
        )
    aggregate = aggregate_matches[0]
    if aggregate.get("conclusion") != "success":
        raise ProvenanceError(
            f"{aggregate_job} conclusion is {aggregate.get('conclusion')!r}, not success"
        )

    return {
        "aggregateJob": aggregate_job,
        "event": event,
        "headSha": sha,
        "runAttempt": run.get("run_attempt"),
        "runId": run["id"],
        "workflowPath": workflow_path,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--token-env", default="GH_TOKEN")
    parser.add_argument("--api-url", default=os.environ.get("GITHUB_API_URL", "https://api.github.com"))
    parser.add_argument("--workflow", default="ci.yml")
    parser.add_argument("--workflow-path", default=".github/workflows/ci.yml")
    parser.add_argument("--event", default="push")
    parser.add_argument("--branch", default="main")
    parser.add_argument("--aggregate-job", default="ci-required")
    args = parser.parse_args()
    try:
        result = require_successful_ci_run(
            repository=args.repository,
            sha=args.sha,
            token=os.environ.get(args.token_env, ""),
            api_url=args.api_url,
            workflow=args.workflow,
            workflow_path=args.workflow_path,
            event=args.event,
            branch=args.branch,
            aggregate_job=args.aggregate_job,
        )
    except ProvenanceError as error:
        parser.error(str(error))
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
