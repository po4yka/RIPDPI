from __future__ import annotations

import argparse
from pathlib import Path

from .bless import bless_candidate_artifacts
from .cluster import run_cluster
from .common import (
    DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG,
    DEFAULT_BLESSED_WINNER_MAPPING_CATALOG,
    ROOT,
    load_json,
    write_json,
)
from .extract import run_extract
from .publish import publish_outputs


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Offline diagnostics analytics pipeline.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    extract_parser = subparsers.add_parser("extract", help="Normalize diagnostics archives into offline records.")
    add_input_arguments(extract_parser)
    extract_parser.add_argument("--output", required=True, help="Output JSON path for extracted records.")

    cluster_parser = subparsers.add_parser("cluster", help="Cluster extracted offline records.")
    cluster_parser.add_argument("--input", required=True, help="Extracted records JSON path.")
    cluster_parser.add_argument("--output", required=True, help="Cluster output JSON path.")

    publish_parser = subparsers.add_parser("publish", help="Emit reports and candidate artifacts from extracted data.")
    publish_parser.add_argument("--extracted", required=True, help="Extracted records JSON path.")
    publish_parser.add_argument("--clustered", required=True, help="Cluster output JSON path.")
    publish_parser.add_argument("--output-dir", required=True, help="Directory for report and candidate outputs.")
    publish_parser.add_argument("--corpus-name", default="offline_analytics", help="Logical corpus label.")
    publish_parser.add_argument(
        "--blessed-device-catalog",
        default=str(DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG),
        help="Reviewed device fingerprint catalog used for drift comparison.",
    )
    publish_parser.add_argument(
        "--blessed-winner-catalog",
        default=str(DEFAULT_BLESSED_WINNER_MAPPING_CATALOG),
        help="Reviewed winner mapping catalog path.",
    )

    run_all_parser = subparsers.add_parser("run-all", help="Run extract, cluster, and publish in one command.")
    add_input_arguments(run_all_parser)
    run_all_parser.add_argument("--output-dir", required=True, help="Directory for extracted, cluster, and report outputs.")
    run_all_parser.add_argument("--corpus-name", default=None, help="Override corpus label.")
    run_all_parser.add_argument(
        "--blessed-device-catalog",
        default=str(DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG),
        help="Reviewed device fingerprint catalog used for drift comparison.",
    )
    run_all_parser.add_argument(
        "--blessed-winner-catalog",
        default=str(DEFAULT_BLESSED_WINNER_MAPPING_CATALOG),
        help="Reviewed winner mapping catalog path.",
    )

    bless_parser = subparsers.add_parser("bless", help="Promote candidate artifacts into reviewed repo assets.")
    bless_parser.add_argument("--device-catalog", required=True, help="Candidate device catalog JSON.")
    bless_parser.add_argument("--winner-catalog", required=True, help="Candidate winner mapping JSON.")
    bless_parser.add_argument(
        "--blessed-device-catalog",
        default=str(DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG),
        help="Destination reviewed device catalog path.",
    )
    bless_parser.add_argument(
        "--blessed-winner-catalog",
        default=str(DEFAULT_BLESSED_WINNER_MAPPING_CATALOG),
        help="Destination reviewed winner mapping path.",
    )
    return parser.parse_args()


def add_input_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--inputs",
        nargs="*",
        default=[],
        help="Diagnostics archive directories or zip files.",
    )
    parser.add_argument(
        "--manifest",
        default=None,
        help="Optional corpus manifest JSON with `inputs` and optional blessed asset paths.",
    )


def load_manifest_inputs(args: argparse.Namespace) -> tuple[list[Path], str, Path, Path]:
    blessed_device = Path(args.blessed_device_catalog).resolve() if hasattr(args, "blessed_device_catalog") else DEFAULT_BLESSED_DEVICE_FINGERPRINT_CATALOG
    blessed_winner = Path(args.blessed_winner_catalog).resolve() if hasattr(args, "blessed_winner_catalog") else DEFAULT_BLESSED_WINNER_MAPPING_CATALOG
    corpus_name = getattr(args, "corpus_name", None) or "offline_analytics"
    inputs = [Path(path).resolve() for path in getattr(args, "inputs", [])]
    if args.manifest:
        manifest = load_json(Path(args.manifest).resolve())
        inputs = [ROOT / path for path in manifest.get("inputs", [])]
        corpus_name = manifest.get("corpusName") or corpus_name
        if manifest.get("blessedDeviceCatalog"):
            blessed_device = (ROOT / manifest["blessedDeviceCatalog"]).resolve()
        if manifest.get("blessedWinnerCatalog"):
            blessed_winner = (ROOT / manifest["blessedWinnerCatalog"]).resolve()
    if not inputs:
        raise ValueError("No analytics inputs were provided.")
    return inputs, corpus_name, blessed_device, blessed_winner


def main() -> int:
    args = parse_args()
    if args.command == "extract":
        inputs, _corpus_name, _blessed_device, _blessed_winner = load_manifest_inputs(args)
        extracted = run_extract(inputs)
        write_json(Path(args.output).resolve(), extracted)
        return 0
    if args.command == "cluster":
        extracted = load_json(Path(args.input).resolve())
        clustered = run_cluster(extracted)
        write_json(Path(args.output).resolve(), clustered)
        return 0
    if args.command == "publish":
        extracted = load_json(Path(args.extracted).resolve())
        clustered = load_json(Path(args.clustered).resolve())
        publish_outputs(
            extracted_payload=extracted,
            clustered_payload=clustered,
            output_dir=Path(args.output_dir).resolve(),
            corpus_name=args.corpus_name,
            blessed_device_catalog_path=Path(args.blessed_device_catalog).resolve(),
            blessed_winner_mapping_path=Path(args.blessed_winner_catalog).resolve(),
        )
        return 0
    if args.command == "run-all":
        inputs, corpus_name, blessed_device, blessed_winner = load_manifest_inputs(args)
        output_dir = Path(args.output_dir).resolve()
        output_dir.mkdir(parents=True, exist_ok=True)
        extracted = run_extract(inputs)
        extracted_path = output_dir / "offline-records.json"
        write_json(extracted_path, extracted)
        clustered = run_cluster(extracted)
        clustered_path = output_dir / "clustered-records.json"
        write_json(clustered_path, clustered)
        publish_outputs(
            extracted_payload=extracted,
            clustered_payload=clustered,
            output_dir=output_dir,
            corpus_name=corpus_name,
            blessed_device_catalog_path=blessed_device,
            blessed_winner_mapping_path=blessed_winner,
        )
        return 0
    if args.command == "bless":
        bless_candidate_artifacts(
            device_catalog_candidate=Path(args.device_catalog).resolve(),
            winner_catalog_candidate=Path(args.winner_catalog).resolve(),
            blessed_device_catalog_path=Path(args.blessed_device_catalog).resolve(),
            blessed_winner_mapping_path=Path(args.blessed_winner_catalog).resolve(),
        )
        return 0
    raise ValueError(f"Unsupported command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
