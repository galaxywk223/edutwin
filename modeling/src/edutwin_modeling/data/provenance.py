"""Cryptographic verification of downloaded source data before processing."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any, cast

from edutwin_modeling.config import ProjectPaths, resolve_repository_path
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import sha256_file


def _object(value: Any, context: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise DataQualityError(f"source lock entry must be an object: {context}")
    return value


def _integer(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise DataQualityError(f"source lock entry must be an integer: {context}")
    return cast(int, value)


def _resolve_locked_file(
    entry: Mapping[str, Any], paths: ProjectPaths, context: str
) -> Path:
    configured_path = entry.get("path")
    if not isinstance(configured_path, str) or not configured_path:
        raise DataQualityError(f"source lock path is missing: {context}")
    candidate = (paths.repository_root / configured_path).resolve()
    try:
        candidate.relative_to(paths.repository_root)
    except ValueError as exc:
        raise DataQualityError(f"source lock path escapes repository: {configured_path}") from exc
    if not candidate.is_file():
        raise DataQualityError(f"source lock file is missing: {candidate}")
    expected_bytes = _integer(entry.get("bytes"), f"{context}.bytes")
    if candidate.stat().st_size != expected_bytes:
        raise DataQualityError(
            f"source size mismatch for {configured_path}: "
            f"{candidate.stat().st_size} != {expected_bytes}"
        )
    expected_sha256 = entry.get("sha256")
    actual_sha256 = sha256_file(candidate)
    if not isinstance(expected_sha256, str) or actual_sha256 != expected_sha256.lower():
        raise DataQualityError(f"source SHA-256 mismatch for {configured_path}")
    return candidate


def _md5_file(path: Path) -> str:
    digest = hashlib.md5(usedforsecurity=False)
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_source_lock(
    config: Mapping[str, Any], paths: ProjectPaths
) -> dict[str, Any]:
    path_config = _object(config.get("paths"), "paths")
    lock_value = path_config.get("source_lock")
    if not isinstance(lock_value, str) or not lock_value:
        raise DataQualityError("paths.source_lock is required")
    lock_path = resolve_repository_path(lock_value, paths)
    if not lock_path.is_file():
        raise DataQualityError(
            f"source lock is missing; run scripts/data/download-datasets.ps1: {lock_path}"
        )
    try:
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DataQualityError(f"source lock is not valid JSON: {lock_path}") from exc
    root = _object(lock, "root")
    if root.get("schema_version") != 2:
        raise DataQualityError("source lock schema_version must equal 2")

    assistments = _object(
        root.get("assistments_2009_2010_skill_builder"), "assistments"
    )
    official = _object(assistments.get("official_raw_nonfolded"), "assistments.official")
    if official.get("download_url_env") != "EDUTWIN_ASSISTMENTS_DOWNLOAD_URL":
        raise DataQualityError("ASSISTments download environment variable differs")
    landing_url = official.get("source_landing_url")
    if not isinstance(landing_url, str) or not landing_url.startswith("https://"):
        raise DataQualityError("ASSISTments source landing URL is invalid")
    if "download_url" in official:
        raise DataQualityError("ASSISTments source lock must not persist a runtime URL")
    mirror = _object(
        assistments.get("corrected_nonfolded_academic_mirror"), "assistments.mirror"
    )
    canonical = _object(
        assistments.get("canonical_corrected_nonfolded"), "assistments.canonical"
    )
    verified_files: dict[str, Path] = {
        "source_manifest": _resolve_locked_file(
            _object(root.get("source_manifest"), "source_manifest"),
            paths,
            "source_manifest",
        ),
        "generator": _resolve_locked_file(
            _object(root.get("generator"), "generator"), paths, "generator"
        ),
        "assistments_official": _resolve_locked_file(
            _object(official.get("file"), "assistments.official.file"),
            paths,
            "assistments.official.file",
        ),
        "assistments_mirror_archive": _resolve_locked_file(
            _object(mirror.get("archive"), "assistments.mirror.archive"),
            paths,
            "assistments.mirror.archive",
        ),
        "assistments_mirror_csv": _resolve_locked_file(
            _object(mirror.get("extracted"), "assistments.mirror.extracted"),
            paths,
            "assistments.mirror.extracted",
        ),
        "assistments_canonical": _resolve_locked_file(
            _object(canonical.get("file"), "assistments.canonical.file"),
            paths,
            "assistments.canonical.file",
        ),
    }
    expected_canonical = {
        "official_rows": 525_534,
        "official_repeated_lineage_rows": 123_778,
        "official_opportunity_variant_rows": 123_778,
        "rows": 401_756,
        "mirror_rows": 401_756,
        "unique_order_ids": 346_860,
        "mirror_answer_text_mismatches": 83,
        "mirror_retained_opportunity_mismatches": 0,
    }
    observed_canonical = {
        key: _integer(canonical.get(key), f"assistments.canonical.{key}")
        for key in expected_canonical
    }
    if observed_canonical != expected_canonical:
        raise DataQualityError(
            f"ASSISTments locked cardinality differs: {observed_canonical}"
        )
    if canonical.get("official_mirror_lineage_match") is not True:
        raise DataQualityError("ASSISTments canonical lineage was not mirror verified")
    if canonical.get("retained_training_fields_match") is not True:
        raise DataQualityError("ASSISTments retained training fields were not mirror verified")
    if canonical.get("lineage_key_excludes") != [
        "answer_text",
        "opportunity",
        "opportunity_original",
    ]:
        raise DataQualityError("ASSISTments lineage key exclusions differ")
    if canonical.get("semantic_hash_excludes") != ["answer_text"]:
        raise DataQualityError("ASSISTments semantic hash exclusions differ")
    semantic_hash = canonical.get("canonical_semantic_sha256")
    if not isinstance(semantic_hash, str) or len(semantic_hash) != 64:
        raise DataQualityError("ASSISTments canonical semantic SHA-256 is invalid")
    if canonical.get("answer_text_source") != "official_raw_nonfolded":
        raise DataQualityError("ASSISTments canonical answer text source differs")

    oulad = _object(root.get("oulad"), "oulad")
    oulad_archive_entry = _object(oulad.get("archive"), "oulad.archive")
    oulad_archive = _resolve_locked_file(oulad_archive_entry, paths, "oulad.archive")
    verified_files["oulad_archive"] = oulad_archive
    expected_md5 = "7412686fd77cf0e0ee1e8c3e9b354308"
    if oulad_archive_entry.get("md5") != expected_md5:
        raise DataQualityError("OULAD source lock MD5 does not match Figshare v1")
    if _md5_file(oulad_archive) != expected_md5:
        raise DataQualityError("OULAD archive MD5 does not match Figshare v1")
    extracted = oulad.get("extracted_files")
    if not isinstance(extracted, list) or len(extracted) != 7:
        raise DataQualityError("OULAD source lock must contain seven extracted files")
    for index, value in enumerate(extracted):
        verified_files[f"oulad_extracted_{index}"] = _resolve_locked_file(
            _object(value, f"oulad.extracted_files[{index}]"),
            paths,
            f"oulad.extracted_files[{index}]",
        )
    if oulad.get("license") != "CC-BY-4.0":
        raise DataQualityError("OULAD source lock license must equal CC-BY-4.0")
    if oulad.get("dataset_doi") != "10.6084/m9.figshare.5081998.v1":
        raise DataQualityError("OULAD source lock DOI differs from fixed Figshare v1")

    boundary = _object(root.get("deployment_boundary"), "deployment_boundary")
    if any(
        boundary.get(key) is not False
        for key in ("raw_data_in_git", "raw_data_in_images", "raw_data_on_server")
    ):
        raise DataQualityError("source lock deployment boundary permits raw data exposure")
    return {
        "source_lock_path": lock_path,
        "source_lock_sha256": sha256_file(lock_path),
        "verified_files": verified_files,
        "assistments": observed_canonical,
        "oulad_md5": expected_md5,
    }
