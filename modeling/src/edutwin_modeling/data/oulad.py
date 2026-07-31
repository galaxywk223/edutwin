"""Leakage-safe OULAD day 0-29 risk-unit preparation."""

from __future__ import annotations

from collections.abc import Mapping
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd

from edutwin_modeling.config import ProjectPaths, resolve_repository_path
from edutwin_modeling.data.quality import (
    reject_forbidden_features,
    require_columns,
    require_unique,
)
from edutwin_modeling.data.splitting import assert_no_student_leakage, assign_student_splits
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import canonical_json_bytes, sha256_file
from edutwin_modeling.manifest import repository_relative

UNIT_KEYS = ("code_module", "code_presentation", "id_student")
FEATURE_COLUMNS = (
    "vle_total_clicks",
    "vle_interaction_count",
    "vle_active_days",
    "vle_active_weeks",
    "vle_resource_count",
    "assessment_count",
    "assessment_scored_count",
    "assessment_mean_score",
)
DIMENSION_COLUMNS = ("performance", "activity", "persistence")
FORBIDDEN_FEATURES = ("final_result", "date_unregistration")
FINAL_RESULTS = frozenset({"Fail", "Pass", "Distinction", "Withdrawn"})


def _section(config: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    value = config.get(name, {})
    if not isinstance(value, Mapping):
        raise DataQualityError(f"configuration section {name!r} must be an object")
    return value


def _window(config: Mapping[str, Any]) -> tuple[int, int]:
    oulad_config = _section(config, "oulad")
    start = int(oulad_config.get("feature_window_start_day", 0))
    end = int(oulad_config.get("feature_window_end_day", 29))
    withdrawal_after = int(oulad_config.get("positive_withdrawal_after_day", 29))
    if (start, end, withdrawal_after) != (0, 29, 29):
        raise DataQualityError("OULAD feature and withdrawal boundary must remain day 0..29")
    return start, end


def _normalize_keys(frame: pd.DataFrame, *, dataset: str) -> pd.DataFrame:
    result = frame.copy()
    for column in UNIT_KEYS:
        if result[column].isna().any():
            raise DataQualityError(f"{dataset}.{column} contains null keys")
        result[column] = result[column].astype(str)
    return result


def _numeric(
    values: pd.Series,
    *,
    dataset: str,
    column: str,
    allow_null: bool = False,
) -> pd.Series:
    converted = pd.to_numeric(values, errors="coerce")
    invalid = values.notna() & converted.isna()
    if invalid.any() or (not allow_null and converted.isna().any()):
        raise DataQualityError(f"{dataset}.{column} contains non-numeric or missing values")
    return converted


def _aggregate_vle(
    student_vle: pd.DataFrame,
    *,
    start_day: int,
    end_day: int,
) -> pd.DataFrame:
    require_columns(
        student_vle,
        [*UNIT_KEYS, "id_site", "date", "sum_click"],
        "OULAD studentVle",
    )
    vle = _normalize_keys(student_vle, dataset="OULAD studentVle")
    vle["date"] = _numeric(vle["date"], dataset="OULAD studentVle", column="date")
    vle["sum_click"] = _numeric(
        vle["sum_click"], dataset="OULAD studentVle", column="sum_click"
    )
    if (vle["sum_click"] < 0).any():
        raise DataQualityError("OULAD studentVle.sum_click contains negative values")
    vle = vle.loc[vle["date"].between(start_day, end_day, inclusive="both")].copy()
    if vle.empty:
        return pd.DataFrame(columns=[*UNIT_KEYS, *FEATURE_COLUMNS[:5]])
    vle["active_week"] = ((vle["date"] - start_day) // 7).astype("int64")
    return (
        vle.groupby(list(UNIT_KEYS), sort=True, as_index=False)
        .agg(
            vle_total_clicks=("sum_click", "sum"),
            vle_interaction_count=("id_site", "size"),
            vle_active_days=("date", "nunique"),
            vle_active_weeks=("active_week", "nunique"),
            vle_resource_count=("id_site", "nunique"),
        )
        .reset_index(drop=True)
    )


def _aggregate_assessments(
    assessments: pd.DataFrame,
    student_assessment: pd.DataFrame,
    *,
    start_day: int,
    end_day: int,
) -> pd.DataFrame:
    require_columns(
        assessments,
        ["id_assessment", "code_module", "code_presentation"],
        "OULAD assessments",
    )
    require_columns(
        student_assessment,
        ["id_assessment", "id_student", "date_submitted", "score"],
        "OULAD studentAssessment",
    )
    assessment_map = assessments[
        ["id_assessment", "code_module", "code_presentation"]
    ].copy()
    require_unique(assessment_map, ["id_assessment"], "OULAD assessments")
    submissions = student_assessment[
        ["id_assessment", "id_student", "date_submitted", "score"]
    ].copy()
    submissions["date_submitted"] = _numeric(
        submissions["date_submitted"],
        dataset="OULAD studentAssessment",
        column="date_submitted",
        allow_null=True,
    )
    submissions["score"] = _numeric(
        submissions["score"],
        dataset="OULAD studentAssessment",
        column="score",
        allow_null=True,
    )
    submissions = submissions.loc[
        submissions["date_submitted"].between(
            start_day, end_day, inclusive="both"
        )
    ].copy()
    if submissions.empty:
        return pd.DataFrame(columns=[*UNIT_KEYS, *FEATURE_COLUMNS[5:]])
    submissions = submissions.merge(
        assessment_map,
        on="id_assessment",
        how="left",
        validate="many_to_one",
    )
    if submissions[["code_module", "code_presentation"]].isna().any().any():
        raise DataQualityError("OULAD studentAssessment references unknown assessments")
    submissions = _normalize_keys(submissions, dataset="OULAD studentAssessment")
    return (
        submissions.groupby(list(UNIT_KEYS), sort=True, as_index=False)
        .agg(
            assessment_count=("id_assessment", "size"),
            assessment_scored_count=("score", "count"),
            assessment_mean_score=("score", "mean"),
        )
        .reset_index(drop=True)
    )


def build_oulad_risk_units(
    student_info: pd.DataFrame,
    student_registration: pd.DataFrame,
    student_vle: pd.DataFrame,
    assessments: pd.DataFrame,
    student_assessment: pd.DataFrame,
    config: Mapping[str, Any],
) -> pd.DataFrame:
    """Build one leakage-safe row per eligible student-course presentation."""

    start_day, end_day = _window(config)
    oulad_config = _section(config, "oulad")
    split_config = _section(config, "split")
    seed = int(config.get("seed", 42))
    namespace = str(split_config.get("namespace", "edutwin-student-split-v1"))
    if seed != 42:
        raise DataQualityError("OULAD split seed must remain 42")
    split_boundaries = (
        int(split_config.get("train_upper_exclusive", 7000)),
        int(split_config.get("validation_upper_exclusive", 8500)),
        int(split_config.get("test_upper_exclusive", 10_000)),
    )
    if split_boundaries != (7000, 8500, 10_000):
        raise DataQualityError("OULAD split proportions must remain 70/15/15")

    require_columns(student_info, [*UNIT_KEYS, "final_result"], "OULAD studentInfo")
    require_columns(
        student_registration,
        [*UNIT_KEYS, "date_unregistration"],
        "OULAD studentRegistration",
    )
    info = _normalize_keys(
        student_info[[*UNIT_KEYS, "final_result"]], dataset="OULAD studentInfo"
    )
    invalid_final_result = info["final_result"].isna() | ~info["final_result"].isin(
        FINAL_RESULTS
    )
    if invalid_final_result.any():
        sample = info.loc[invalid_final_result, "final_result"].head(10).map(repr).tolist()
        raise DataQualityError(
            "OULAD studentInfo.final_result must be exactly one of "
            f"{sorted(FINAL_RESULTS)}; invalid values: {sample}"
        )
    registration = _normalize_keys(
        student_registration[[*UNIT_KEYS, "date_unregistration"]],
        dataset="OULAD studentRegistration",
    )
    require_unique(info, list(UNIT_KEYS), "OULAD studentInfo")
    require_unique(registration, list(UNIT_KEYS), "OULAD studentRegistration")
    registration["date_unregistration"] = _numeric(
        registration["date_unregistration"],
        dataset="OULAD studentRegistration",
        column="date_unregistration",
        allow_null=True,
    )
    units = info.merge(registration, on=list(UNIT_KEYS), how="left", validate="one_to_one")
    withdrawn = units["final_result"].eq("Withdrawn")
    missing_withdrawal_date = withdrawn & units["date_unregistration"].isna()

    positive_results = set(oulad_config.get("positive_final_results", ["Fail"]))
    negative_results = set(
        oulad_config.get("negative_final_results", ["Pass", "Distinction"])
    )
    if positive_results != {"Fail"} or negative_results != {"Pass", "Distinction"}:
        raise DataQualityError("OULAD label classes must remain Fail versus Pass/Distinction")
    final_result = units["final_result"]
    unregistration_day = units["date_unregistration"]
    early_withdrawal = final_result.eq("Withdrawn") & unregistration_day.le(end_day)
    late_withdrawal = final_result.eq("Withdrawn") & unregistration_day.gt(end_day)
    positive = final_result.isin(positive_results) | late_withdrawal
    negative = final_result.isin(negative_results)
    eligible = ~missing_withdrawal_date & ~early_withdrawal & (positive | negative)
    label_audit = {
        "source_units": len(units),
        "withdrawn_missing_date_excluded": int(missing_withdrawal_date.sum()),
        "withdrawn_day_0_29_excluded": int(early_withdrawal.sum()),
        "withdrawn_after_day_29_positive": int(late_withdrawal.sum()),
        "fail_positive": int(final_result.eq("Fail").sum()),
        "pass_or_distinction_negative": int(negative.sum()),
        "eligible_units": int(eligible.sum()),
    }
    units = units.loc[eligible].copy()
    units["risk_label"] = positive.loc[eligible].astype("int8")

    vle_features = _aggregate_vle(student_vle, start_day=start_day, end_day=end_day)
    assessment_features = _aggregate_assessments(
        assessments,
        student_assessment,
        start_day=start_day,
        end_day=end_day,
    )
    units = units.merge(vle_features, on=list(UNIT_KEYS), how="left", validate="one_to_one")
    units = units.merge(
        assessment_features,
        on=list(UNIT_KEYS),
        how="left",
        validate="one_to_one",
    )
    for column in FEATURE_COLUMNS:
        units[column] = pd.to_numeric(units[column], errors="coerce").fillna(0.0)

    window_days = end_day - start_day + 1
    units["performance"] = (units["assessment_mean_score"] / 100.0).clip(0.0, 1.0)
    units["activity"] = np.log1p(units["vle_total_clicks"].clip(lower=0.0))
    units["persistence"] = (units["vle_active_days"] / window_days).clip(0.0, 1.0)
    units["oulad_person_key"] = units["id_student"]
    units["oulad_student_key"] = (
        units["code_module"]
        + "|"
        + units["code_presentation"]
        + "|"
        + units["id_student"]
    )
    units = assign_student_splits(
        units,
        student_column="id_student",
        namespace=namespace,
        seed=seed,
    )
    assert_no_student_leakage(units, student_column="id_student")
    require_unique(units, ["oulad_student_key"], "OULAD risk units")
    reject_forbidden_features(FEATURE_COLUMNS, FORBIDDEN_FEATURES)

    output_columns = [
        "oulad_person_key",
        "oulad_student_key",
        *UNIT_KEYS,
        "split",
        "risk_label",
        *FEATURE_COLUMNS,
        *DIMENSION_COLUMNS,
    ]
    result = units[output_columns].sort_values(
        ["code_module", "code_presentation", "id_student"], kind="mergesort"
    )
    result = result.reset_index(drop=True)
    result.attrs["label_audit"] = label_audit
    return result


def _read_vle_window(path: Path, *, start_day: int, end_day: int) -> pd.DataFrame:
    required = [*UNIT_KEYS, "id_site", "date", "sum_click"]
    chunks: list[pd.DataFrame] = []
    for chunk in pd.read_csv(path, usecols=required, chunksize=500_000):
        dates = pd.to_numeric(chunk["date"], errors="coerce")
        if (chunk["date"].notna() & dates.isna()).any():
            raise DataQualityError("OULAD studentVle.date contains non-numeric values")
        chunks.append(chunk.loc[dates.between(start_day, end_day, inclusive="both")].copy())
    if not chunks:
        return pd.DataFrame(columns=required)
    return pd.concat(chunks, ignore_index=True)


def _write_parquet(frame: pd.DataFrame, path: Path) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    frame.to_parquet(temporary, index=False, compression="zstd")
    temporary.replace(path)


def prepare_oulad(config: Mapping[str, Any], paths: ProjectPaths) -> dict[str, Any]:
    """Read extracted OULAD CSV files and write deterministic risk artifacts."""

    start_day, end_day = _window(config)
    path_config = _section(config, "paths")
    extracted_value = path_config.get("oulad_extracted")
    output_value = path_config.get("oulad_output")
    if not isinstance(extracted_value, str) or not isinstance(output_value, str):
        raise DataQualityError("paths.oulad_extracted and paths.oulad_output are required")
    extracted_dir = resolve_repository_path(extracted_value, paths)
    output_dir = resolve_repository_path(output_value, paths)
    source_paths = {
        "student_info": extracted_dir / "studentInfo.csv",
        "student_registration": extracted_dir / "studentRegistration.csv",
        "student_vle": extracted_dir / "studentVle.csv",
        "assessments": extracted_dir / "assessments.csv",
        "student_assessment": extracted_dir / "studentAssessment.csv",
    }
    missing = [str(path) for path in source_paths.values() if not path.is_file()]
    if missing:
        raise DataQualityError(f"OULAD extracted CSV files are missing: {missing}")

    units = build_oulad_risk_units(
        pd.read_csv(source_paths["student_info"]),
        pd.read_csv(source_paths["student_registration"]),
        _read_vle_window(
            source_paths["student_vle"], start_day=start_day, end_day=end_day
        ),
        pd.read_csv(source_paths["assessments"]),
        pd.read_csv(source_paths["student_assessment"]),
        config,
    )
    dimensions = units[
        [
            "oulad_person_key",
            "oulad_student_key",
            "id_student",
            "code_module",
            "code_presentation",
            "split",
            "risk_label",
            *DIMENSION_COLUMNS,
        ]
    ].copy()
    output_dir.mkdir(parents=True, exist_ok=True)
    units_path = output_dir / "risk_units.parquet"
    dimensions_path = output_dir / "dimensions.parquet"
    manifest_path = output_dir / "manifest.json"
    _write_parquet(units, units_path)
    _write_parquet(dimensions, dimensions_path)
    manifest: dict[str, Any] = {
        "dataset": "OULAD",
        "feature_contract_version": str(
            _section(config, "oulad").get("feature_contract_version", "oulad-d0-29-v1")
        ),
        "feature_window": {"start_day": start_day, "end_day": end_day},
        "label": "Fail or Withdrawn with date_unregistration > 29",
        "excluded": [
            "Withdrawn with date_unregistration <= 29",
            "Withdrawn with missing date_unregistration",
        ],
        "label_audit": units.attrs.get("label_audit", {}),
        "rows": len(units),
        "split_counts": {
            str(key): int(value)
            for key, value in units["split"].value_counts().sort_index().items()
        },
        "feature_columns": list(FEATURE_COLUMNS),
        "dimension_columns": list(DIMENSION_COLUMNS),
        "source_sha256": {
            name: sha256_file(path) for name, path in sorted(source_paths.items())
        },
        "outputs": {
            "risk_units": {
                "path": repository_relative(units_path, paths),
                "sha256": sha256_file(units_path),
            },
            "dimensions": {
                "path": repository_relative(dimensions_path, paths),
                "sha256": sha256_file(dimensions_path),
            },
        },
    }
    manifest_path.write_bytes(canonical_json_bytes(manifest) + b"\n")
    manifest["manifest_path"] = repository_relative(manifest_path, paths)
    manifest["manifest_sha256"] = sha256_file(manifest_path)
    return manifest
