"""Deterministic preparation of ASSISTments 2009-2010 Skill Builder data."""

from __future__ import annotations

import hashlib
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any, BinaryIO, cast
from zipfile import ZipFile

import numpy as np
import pandas as pd

from edutwin_modeling.config import ProjectPaths, resolve_repository_path
from edutwin_modeling.data.provenance import verify_source_lock
from edutwin_modeling.data.quality import (
    require_binary,
    require_columns,
    require_exact_column_count,
    require_no_unnamed_columns,
)
from edutwin_modeling.data.splitting import assert_no_student_leakage, split_name
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import canonical_json_bytes, sha256_file

ANSWER_EVENT_COLUMNS = [
    "order_id",
    "user_id",
    "problem_id",
    "assignment_id",
    "correct",
    "attempt_count",
    "ms_first_response",
    "hint_count",
    "skill_count",
    "event_sequence",
    "split",
]
ANSWER_EVENT_SKILL_COLUMNS = [
    "order_id",
    "skill_id",
    "skill_name",
    "opportunity",
    "opportunity_original",
    "ordinal",
]
STUDENT_DIMENSION_COLUMNS = [
    "assistments_user_key",
    "split",
    "performance",
    "activity",
    "persistence",
    "event_count",
]

__all__ = ["fold_order_events", "prepare_assistments"]


def _mapping(config: Mapping[str, Any], key: str) -> Mapping[str, Any]:
    value = config.get(key)
    if not isinstance(value, Mapping):
        raise DataQualityError(f"configuration section must be an object: {key}")
    return value


def _string(config: Mapping[str, Any], key: str, context: str) -> str:
    value = config.get(key)
    if not isinstance(value, str) or not value:
        raise DataQualityError(f"{context}.{key} must be a non-empty string")
    return value


def _integer(config: Mapping[str, Any], key: str, context: str) -> int:
    value = config.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise DataQualityError(f"{context}.{key} must be an integer")
    return value


def _string_list(config: Mapping[str, Any], key: str, context: str) -> list[str]:
    value = config.get(key)
    if not isinstance(value, Sequence) or isinstance(value, str | bytes):
        raise DataQualityError(f"{context}.{key} must be a string array")
    result = list(value)
    if not result or any(not isinstance(item, str) or not item for item in result):
        raise DataQualityError(f"{context}.{key} must contain non-empty strings")
    if len(result) != len(set(result)):
        raise DataQualityError(f"{context}.{key} contains duplicate fields")
    return result


def _normalize_frame(frame: pd.DataFrame, *, expected_columns: int, dataset: str) -> pd.DataFrame:
    result = frame.copy()
    columns = [str(column).lstrip("\ufeff").strip() for column in result.columns]
    if len(columns) != len(set(columns)):
        raise DataQualityError(f"{dataset} contains duplicate normalized column names")
    result.columns = columns
    require_exact_column_count(result, expected_columns, dataset)
    require_no_unnamed_columns(result, dataset)
    for column in result.columns:
        result[column] = (
            result[column]
            .astype("string")
            .fillna("")
            .str.replace("\r\n", "\n", regex=False)
            .str.replace("\r", "\n", regex=False)
        )
    return result


def _read_string_csv(source: Path | BinaryIO, *, encoding: str, dataset: str) -> pd.DataFrame:
    try:
        return pd.read_csv(
            source,
            dtype="string",
            encoding=encoding,
            keep_default_na=False,
            na_filter=False,
            low_memory=False,
            on_bad_lines="error",
        )
    except (OSError, UnicodeError, pd.errors.ParserError) as exc:
        raise DataQualityError(f"failed to read {dataset} as {encoding} CSV: {exc}") from exc


def _sha256_stream(handle: BinaryIO, chunk_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    for chunk in iter(lambda: handle.read(chunk_size), b""):
        digest.update(chunk)
    return digest.hexdigest()


def _load_corrected_mirror(
    path_config: Mapping[str, Any], paths: ProjectPaths, *, encoding: str
) -> tuple[pd.DataFrame, dict[str, Any]]:
    csv_value = _string(path_config, "assistments_mirror_csv", "paths")
    zip_value = _string(path_config, "assistments_mirror_zip", "paths")
    csv_path = resolve_repository_path(csv_value, paths)
    zip_path = resolve_repository_path(zip_value, paths)

    if csv_path.is_file():
        return (
            _read_string_csv(csv_path, encoding=encoding, dataset="assistments corrected mirror"),
            {"kind": "csv", "configuredPath": csv_value, "sha256": sha256_file(csv_path)},
        )
    if not zip_path.is_file():
        raise DataQualityError(
            "ASSISTments corrected mirror is missing at both configured CSV and ZIP paths"
        )

    with ZipFile(zip_path) as archive:
        csv_members = sorted(
            info.filename
            for info in archive.infolist()
            if not info.is_dir() and info.filename.lower().endswith(".csv")
        )
        preferred = [
            member for member in csv_members if Path(member).name.lower() == csv_path.name.lower()
        ]
        if len(preferred) == 1:
            member = preferred[0]
        elif len(csv_members) == 1:
            member = csv_members[0]
        else:
            raise DataQualityError(
                "corrected mirror ZIP must contain one CSV or one member matching "
                f"{csv_path.name}: {csv_members}"
            )
        with archive.open(member) as member_handle:
            member_sha256 = _sha256_stream(cast(BinaryIO, member_handle))
        with archive.open(member) as member_handle:
            frame = _read_string_csv(
                cast(BinaryIO, member_handle),
                encoding=encoding,
                dataset="assistments corrected mirror",
            )
    return frame, {
        "kind": "zip-member",
        "configuredPath": zip_value,
        "containerSha256": sha256_file(zip_path),
        "member": member,
        "memberSha256": member_sha256,
    }


def _drop_exact_duplicates(frame: pd.DataFrame) -> tuple[pd.DataFrame, int]:
    deduplicated = frame.drop_duplicates(keep="first").reset_index(drop=True)
    return deduplicated, len(frame) - len(deduplicated)


def _assert_same_normalized_rows(official: pd.DataFrame, mirror: pd.DataFrame) -> None:
    if official.columns.tolist() != mirror.columns.tolist():
        raise DataQualityError("official raw and corrected mirror column order differs")
    if len(official) != len(mirror):
        raise DataQualityError(
            "official exact-deduplicated and corrected mirror row counts differ: "
            f"{len(official)} != {len(mirror)}"
        )

    official_hash = pd.util.hash_pandas_object(official, index=False, categorize=True).to_numpy(
        dtype="uint64"
    )
    mirror_hash = pd.util.hash_pandas_object(mirror, index=False, categorize=True).to_numpy(
        dtype="uint64"
    )
    if not np.array_equal(np.sort(official_hash), np.sort(mirror_hash)):
        raise DataQualityError(
            "official exact-deduplicated rows do not match corrected mirror rows"
        )

    hash_collision = bool(pd.Series(official_hash).duplicated(keep=False).any()) or bool(
        pd.Series(mirror_hash).duplicated(keep=False).any()
    )
    if hash_collision:
        sort_columns = official.columns.tolist()
        official_ordered = official.sort_values(sort_columns, kind="mergesort").reset_index(
            drop=True
        )
        mirror_ordered = mirror.sort_values(sort_columns, kind="mergesort").reset_index(drop=True)
    else:
        official_ordered = official.iloc[np.argsort(official_hash, kind="stable")].reset_index(
            drop=True
        )
        mirror_ordered = mirror.iloc[np.argsort(mirror_hash, kind="stable")].reset_index(drop=True)
    if not official_ordered.equals(mirror_ordered):
        raise DataQualityError(
            "official exact-deduplicated rows do not match corrected mirror rows"
        )


def _require_nonblank(frame: pd.DataFrame, fields: Sequence[str], dataset: str) -> None:
    for field in fields:
        invalid = frame[field].str.strip().eq("")
        if invalid.any():
            raise DataQualityError(
                f"{dataset}.{field} contains {int(invalid.sum())} blank required values"
            )


def _integer_order_key(series: pd.Series, *, field: str) -> pd.Series:
    numeric = pd.to_numeric(series, errors="coerce")
    invalid = numeric.isna() | numeric.mod(1).ne(0)
    if invalid.any():
        sample = sorted(series.loc[invalid].astype(str).unique().tolist())[:10]
        raise DataQualityError(f"{field} contains non-integer keys: {sample}")
    return numeric.astype("int64")


def _nullable_integer(series: pd.Series, *, field: str) -> pd.Series:
    stripped = series.astype("string").fillna("").str.strip()
    numeric = pd.to_numeric(stripped.mask(stripped.eq("")), errors="coerce")
    invalid = stripped.ne("") & numeric.isna()
    fractional = numeric.notna() & numeric.mod(1).ne(0)
    if invalid.any() or fractional.any():
        bad = invalid | fractional
        sample = sorted(stripped.loc[bad].unique().tolist())[:10]
        raise DataQualityError(f"{field} contains non-integer values: {sample}")
    return numeric.astype("Int64")


def _validate_column_contract(
    frame: pd.DataFrame,
    *,
    event_key: str,
    multi_skill_fields: Sequence[str],
    core_fields: Sequence[str],
    expected_columns: int,
) -> None:
    configured = [event_key, *multi_skill_fields, *core_fields]
    if len(configured) != len(set(configured)):
        raise DataQualityError("ASSISTments event, multi-skill, and core fields overlap")
    if set(configured) != set(frame.columns) or len(configured) != expected_columns:
        missing = sorted(set(frame.columns).difference(configured))
        unknown = sorted(set(configured).difference(frame.columns))
        raise DataQualityError(
            "ASSISTments 30-column partition does not match the CSV schema; "
            f"unconfigured columns={missing}, missing configured columns={unknown}"
        )


def _assert_core_consistency(
    frame: pd.DataFrame, *, event_key: str, core_fields: Sequence[str]
) -> None:
    uniqueness = frame.groupby(event_key, sort=False, dropna=False)[list(core_fields)].nunique(
        dropna=False
    )
    conflict_mask = uniqueness.gt(1)
    if not conflict_mask.any(axis=None):
        return
    conflicting_orders = sorted(conflict_mask.index[conflict_mask.any(axis=1)].astype(str).tolist())
    sample = [
        {
            event_key: order_id,
            "fields": sorted(
                conflict_mask.columns[conflict_mask.loc[order_id].to_numpy()].astype(str).tolist()
            ),
        }
        for order_id in conflicting_orders[:10]
    ]
    raise DataQualityError(f"ASSISTments core consistency conflict: {sample}")


def _assign_splits(events: pd.DataFrame, config: Mapping[str, Any]) -> pd.DataFrame:
    split_config = _mapping(config, "split")
    namespace = _string(split_config, "namespace", "split")
    seed = _integer(config, "seed", "root")
    train_upper = _integer(split_config, "train_upper_exclusive", "split")
    validation_upper = _integer(split_config, "validation_upper_exclusive", "split")
    test_upper = _integer(split_config, "test_upper_exclusive", "split")
    user_splits = {
        user_id: split_name(
            user_id,
            namespace=namespace,
            seed=seed,
            train_upper_exclusive=train_upper,
            validation_upper_exclusive=validation_upper,
            test_upper_exclusive=test_upper,
        )
        for user_id in sorted(events["user_id"].astype(str).unique().tolist())
    }
    result = events.copy()
    result["split"] = result["user_id"].map(user_splits).astype("string")
    assert_no_student_leakage(result, student_column="user_id")
    return result


def fold_order_events(
    frame: pd.DataFrame, config: Mapping[str, Any]
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Fold corrected non-folded skill rows into events and skill associations."""

    assistments_config = _mapping(config, "assistments")
    expected_columns = _integer(assistments_config, "expected_columns", "assistments")
    event_key = _string(assistments_config, "event_key", "assistments")
    multi_skill_fields = _string_list(
        assistments_config, "multi_skill_fields", "assistments"
    )
    core_fields = _string_list(
        assistments_config, "core_consistency_fields", "assistments"
    )
    required_fields = _string_list(assistments_config, "required_fields", "assistments")
    missing_skill_token = _string(
        assistments_config, "missing_skill_token", "assistments"
    )

    normalized = _normalize_frame(
        frame, expected_columns=expected_columns, dataset="assistments corrected"
    )
    require_columns(
        normalized,
        [event_key, *multi_skill_fields, *core_fields, *required_fields],
        "assistments corrected",
    )
    _validate_column_contract(
        normalized,
        event_key=event_key,
        multi_skill_fields=multi_skill_fields,
        core_fields=core_fields,
        expected_columns=expected_columns,
    )
    _require_nonblank(normalized, required_fields, "assistments corrected")
    require_binary(normalized, "correct", "assistments corrected")
    normalized, _ = _drop_exact_duplicates(normalized)
    _assert_core_consistency(normalized, event_key=event_key, core_fields=core_fields)

    skill_id = normalized["skill_id"].astype("string").fillna("").str.strip()
    missing_skill = skill_id.eq("")
    if skill_id.eq(missing_skill_token).any():
        raise DataQualityError(
            "ASSISTments source skill_id collides with missing_skill_token"
        )
    mixed_skill_events = (
        pd.DataFrame({event_key: normalized[event_key], "missing": missing_skill})
        .groupby(event_key, sort=False)["missing"]
        .agg(["any", "all"])
    )
    mixed_skill_events = mixed_skill_events[
        mixed_skill_events["any"] & ~mixed_skill_events["all"]
    ]
    if not mixed_skill_events.empty:
        raise DataQualityError(
            "ASSISTments events cannot mix missing and identified skill rows: "
            f"{mixed_skill_events.index.astype(str).tolist()[:10]}"
        )
    if missing_skill.any():
        unexpected_missing_name = (
            normalized.loc[missing_skill, ["skill_name"]]
            .astype("string")
            .fillna("")
            .apply(lambda column: column.str.strip().ne(""))
            .any(axis=1)
        )
        if unexpected_missing_name.any():
            raise DataQualityError(
                "ASSISTments missing-skill rows contain an unexpected skill_name"
            )
    normalized.loc[missing_skill, "skill_id"] = missing_skill_token
    normalized.loc[missing_skill, "skill_name"] = missing_skill_token

    working = normalized.copy()
    working["_order_sort"] = _integer_order_key(working[event_key], field=event_key)
    working = working.sort_values(
        ["_order_sort", event_key, *multi_skill_fields], kind="mergesort"
    ).reset_index(drop=True)

    skills = working[[event_key, *multi_skill_fields]].drop_duplicates().reset_index(drop=True)
    skills["ordinal"] = skills.groupby(event_key, sort=False).cumcount().add(1).astype("int64")
    skill_counts = skills.groupby(event_key, sort=False).size().rename("skill_count")

    events = working.drop_duplicates(event_key, keep="first").copy()
    events["correct"] = pd.to_numeric(events["correct"], errors="raise").astype("int8")
    for field in ("attempt_count", "ms_first_response", "hint_count"):
        events[field] = _nullable_integer(events[field], field=field)
    events = events.merge(skill_counts, left_on=event_key, right_index=True, validate="one_to_one")
    events["skill_count"] = events["skill_count"].astype("int64")
    events = events.sort_values(["user_id", "_order_sort", event_key], kind="mergesort")
    events["event_sequence"] = (
        events.groupby("user_id", sort=False).cumcount().add(1).astype("int64")
    )
    events = _assign_splits(events, config)
    events = events[ANSWER_EVENT_COLUMNS].reset_index(drop=True)

    skills["_order_sort"] = _integer_order_key(skills[event_key], field=event_key)
    skills = skills.sort_values(["_order_sort", "ordinal"], kind="mergesort")
    skills = skills[ANSWER_EVENT_SKILL_COLUMNS].reset_index(drop=True)
    return events, skills


def _student_dimensions(events: pd.DataFrame, skills: pd.DataFrame) -> pd.DataFrame:
    opportunity_text = skills["opportunity_original"].where(
        skills["opportunity_original"].str.strip().ne(""), skills["opportunity"]
    )
    opportunity = pd.to_numeric(opportunity_text, errors="coerce").fillna(0.0).clip(lower=0.0)
    event_opportunity = (
        pd.DataFrame({"order_id": skills["order_id"], "opportunity": opportunity})
        .groupby("order_id", sort=False)["opportunity"]
        .max()
        .rename("opportunity")
    )
    event_features = events[["order_id", "user_id"]].merge(
        event_opportunity, left_on="order_id", right_index=True, how="left", validate="one_to_one"
    )
    event_features["opportunity"] = event_features["opportunity"].fillna(0.0)
    event_features["persistence_component"] = np.log1p(event_features["opportunity"])
    persistence = event_features.groupby("user_id", sort=True)["persistence_component"].mean()

    dimensions = events.groupby("user_id", sort=True).agg(
        split=("split", "first"),
        performance=("correct", "mean"),
        event_count=("order_id", "size"),
    )
    dimensions["activity"] = np.log1p(dimensions["event_count"].astype("float64"))
    dimensions["persistence"] = persistence
    dimensions = dimensions.reset_index().rename(columns={"user_id": "assistments_user_key"})
    dimensions["performance"] = dimensions["performance"].astype("float64")
    dimensions["persistence"] = dimensions["persistence"].astype("float64")
    dimensions["event_count"] = dimensions["event_count"].astype("int64")
    return dimensions[STUDENT_DIMENSION_COLUMNS].sort_values(
        "assistments_user_key", kind="mergesort", ignore_index=True
    )


def _write_parquet(frame: pd.DataFrame, destination: Path) -> None:
    temporary = destination.with_name(f".{destination.name}.tmp")
    if temporary.exists():
        temporary.unlink()
    try:
        frame.to_parquet(temporary, engine="pyarrow", compression="zstd", index=False)
        temporary.replace(destination)
    finally:
        if temporary.exists():
            temporary.unlink()


def _write_json(value: Mapping[str, Any], destination: Path) -> None:
    temporary = destination.with_name(f".{destination.name}.tmp")
    if temporary.exists():
        temporary.unlink()
    try:
        temporary.write_bytes(canonical_json_bytes(value) + b"\n")
        temporary.replace(destination)
    finally:
        if temporary.exists():
            temporary.unlink()


def prepare_assistments(
    config: Mapping[str, Any],
    paths: ProjectPaths,
    *,
    verified_source_lock: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    """Validate the source-locked canonical ASSISTments file and fold events."""

    path_config = _mapping(config, "paths")
    assistments_config = _mapping(config, "assistments")
    encoding = _string(assistments_config, "canonical_encoding", "assistments")
    expected_columns = _integer(assistments_config, "expected_columns", "assistments")
    expected_rows = _integer(assistments_config, "expected_corrected_rows", "assistments")
    expected_orders = _integer(
        assistments_config, "expected_unique_order_ids", "assistments"
    )
    missing_skill_token = _string(
        assistments_config, "missing_skill_token", "assistments"
    )
    event_key = _string(assistments_config, "event_key", "assistments")

    canonical_value = _string(path_config, "assistments_canonical", "paths")
    canonical_path = resolve_repository_path(canonical_value, paths)
    source_lock = (
        verified_source_lock
        if verified_source_lock is not None
        else verify_source_lock(config, paths)
    )
    verified_files = source_lock.get("verified_files")
    locked_canonical = (
        verified_files.get("assistments_canonical")
        if isinstance(verified_files, Mapping)
        else None
    )
    if not isinstance(locked_canonical, Path) or locked_canonical != canonical_path:
        raise DataQualityError(
            "ASSISTments canonical path does not match the verified source lock"
        )
    if not canonical_path.is_file():
        raise DataQualityError(
            "ASSISTments canonical CSV is missing; run "
            f"scripts/data/download-datasets.ps1: {canonical_path}"
        )
    canonical_raw = _read_string_csv(
        canonical_path, encoding=encoding, dataset="assistments canonical corrected"
    )
    corrected = _normalize_frame(
        canonical_raw,
        expected_columns=expected_columns,
        dataset="assistments canonical corrected",
    )
    corrected_deduplicated, corrected_duplicates = _drop_exact_duplicates(corrected)
    if corrected_duplicates != 0:
        raise DataQualityError(
            "ASSISTments canonical file contains exact duplicate rows: "
            f"{corrected_duplicates}"
        )

    if len(corrected_deduplicated) != expected_rows:
        raise DataQualityError(
            "ASSISTments corrected row count is "
            f"{len(corrected_deduplicated)}; expected {expected_rows}"
        )
    unique_orders = int(corrected_deduplicated[event_key].nunique(dropna=False))
    if unique_orders != expected_orders:
        raise DataQualityError(
            f"ASSISTments unique {event_key} count is {unique_orders}; expected {expected_orders}"
        )

    missing_skill_rows = int(
        corrected_deduplicated["skill_id"]
        .astype("string")
        .fillna("")
        .str.strip()
        .eq("")
        .sum()
    )
    events, skills = fold_order_events(corrected_deduplicated, config)
    if len(events) != expected_orders:
        raise DataQualityError(
            f"folded answer event count is {len(events)}; expected {expected_orders}"
        )
    dimensions = _student_dimensions(events, skills)
    if "answer_text" in events.columns or "answer_text" in skills.columns:
        raise DataQualityError("raw answer_text leaked into a processed interface artifact")

    output_value = _string(path_config, "assistments_output", "paths")
    output_directory = resolve_repository_path(output_value, paths)
    output_directory.mkdir(parents=True, exist_ok=True)
    event_path = output_directory / "answer_events.parquet"
    skill_path = output_directory / "answer_event_skills.parquet"
    dimension_path = output_directory / "student_dimensions.parquet"
    report_path = output_directory / "quality_report.json"
    _write_parquet(events, event_path)
    _write_parquet(skills, skill_path)
    _write_parquet(dimensions, dimension_path)

    report: dict[str, Any] = {
        "schemaVersion": 1,
        "dataset": "ASSISTments 2009-2010 Skill Builder corrected non-folded",
        "encoding": encoding,
        "source": {
            "canonical": {
                "configuredPath": canonical_value,
                "sha256": sha256_file(canonical_path),
            },
            "sourceLock": _string(path_config, "source_lock", "paths"),
            "sourceLockSha256": source_lock.get("source_lock_sha256"),
        },
        "expected": {
            "columns": expected_columns,
            "correctedRows": expected_rows,
            "uniqueOrderIds": expected_orders,
        },
        "observed": {
            "canonicalRowsBeforeValidation": len(corrected),
            "canonicalExactDuplicates": corrected_duplicates,
            "correctedRows": len(corrected_deduplicated),
            "uniqueOrderIds": unique_orders,
            "answerEvents": len(events),
            "answerEventSkills": len(skills),
            "missingSkillRows": missing_skill_rows,
            "missingSkillEvents": int(skills["skill_id"].eq(missing_skill_token).sum()),
            "missingSkillToken": missing_skill_token,
            "students": len(dimensions),
        },
        "checks": {
            "exactColumnCount": True,
            "noUnnamedColumns": True,
            "sourceLockVerifiedBeforeProcessing": True,
            "canonicalLineageVerified": True,
            "canonicalHasNoExactDuplicates": True,
            "missingSkillsRepresentedExplicitly": True,
            "coreFieldsConsistentWithinOrder": True,
            "studentSplitLeakage": False,
            "answerTextExcludedFromArtifacts": True,
        },
        "split": {
            "seed": _integer(config, "seed", "root"),
            "namespace": _string(_mapping(config, "split"), "namespace", "split"),
            "counts": {
                split: int(count)
                for split, count in (
                    dimensions["split"].value_counts(sort=False).sort_index().items()
                )
            },
        },
        "studentDimensions": {
            "performance": "mean(correct)",
            "activity": "log1p(event_count)",
            "persistence": "mean(log1p(max opportunity_original per order; opportunity fallback))",
        },
        "outputs": {
            "answerEvents": {
                "file": event_path.name,
                "rows": len(events),
                "sha256": sha256_file(event_path),
            },
            "answerEventSkills": {
                "file": skill_path.name,
                "rows": len(skills),
                "sha256": sha256_file(skill_path),
            },
            "studentDimensions": {
                "file": dimension_path.name,
                "rows": len(dimensions),
                "sha256": sha256_file(dimension_path),
            },
            "qualityReport": {"file": report_path.name},
        },
    }
    _write_json(report, report_path)
    return report
