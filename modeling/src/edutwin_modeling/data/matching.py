"""Deterministic, training-fitted cross-source nearest-neighbor matching."""

from __future__ import annotations

import hashlib
from collections.abc import Mapping, Sequence
from typing import Any

import numpy as np
import pandas as pd

from edutwin_modeling.data.quality import require_columns, require_unique
from edutwin_modeling.data.splitting import SPLIT_ORDER
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.hashing import stable_rank

DIMENSIONS = ("performance", "activity", "persistence")
CANONICAL_TARGET_COUNTS = {"train": 1400, "validation": 300, "test": 300}
SAFE_OULAD_CONTEXT = ("code_module", "code_presentation", "risk_label")
OULAD_UNIT_SELECTION_NAMESPACE = "edutwin-oulad-unit-selection-v1"


def _section(config: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    value = config.get(name, {})
    if not isinstance(value, Mapping):
        raise DataQualityError(f"configuration section {name!r} must be an object")
    return value


def _validate_numeric_dimensions(
    frame: pd.DataFrame,
    *,
    dimensions: Sequence[str],
    dataset: str,
) -> pd.DataFrame:
    result = frame.copy()
    for dimension in dimensions:
        result[dimension] = pd.to_numeric(result[dimension], errors="coerce")
        values = result[dimension].to_numpy(dtype="float64")
        if not np.isfinite(values).all():
            raise DataQualityError(f"{dataset}.{dimension} contains missing or non-finite values")
    return result


def select_one_oulad_unit_per_person(
    frame: pd.DataFrame,
    *,
    seed: int = 42,
) -> pd.DataFrame:
    """Select one deterministic course unit for each real OULAD person."""

    required = ["oulad_person_key", "oulad_student_key", "split"]
    require_columns(frame, required, "OULAD unit selection")
    if seed != 42:
        raise DataQualityError("OULAD unit selection seed must remain 42")

    result = frame.copy()
    if result[required].isna().any().any():
        raise DataQualityError("OULAD unit selection contains null person, unit, or split keys")
    result["oulad_person_key"] = result["oulad_person_key"].astype(str)
    result["oulad_student_key"] = result["oulad_student_key"].astype(str)
    if result[required].astype(str).apply(lambda values: values.str.strip().eq("")).any().any():
        raise DataQualityError("OULAD unit selection contains blank person, unit, or split keys")
    require_unique(result, ["oulad_student_key"], "OULAD unit selection")

    split_counts = result.groupby("oulad_person_key", sort=True)["split"].nunique()
    cross_split_people = split_counts.loc[split_counts.gt(1)]
    if not cross_split_people.empty:
        sample = cross_split_people.index.astype(str).tolist()[:10]
        raise DataQualityError(
            f"OULAD people span multiple splits before unit selection: {sample}"
        )

    result["_unit_selection_rank"] = [
        stable_rank(
            f"{person_key}\x1f{unit_key}",
            OULAD_UNIT_SELECTION_NAMESPACE,
            seed,
        )
        for person_key, unit_key in zip(
            result["oulad_person_key"],
            result["oulad_student_key"],
            strict=True,
        )
    ]
    selected = (
        result.sort_values(
            ["oulad_person_key", "_unit_selection_rank", "oulad_student_key"],
            kind="mergesort",
        )
        .drop_duplicates("oulad_person_key", keep="first")
        .drop(columns="_unit_selection_rank")
        .reset_index(drop=True)
    )
    require_unique(selected, ["oulad_person_key"], "selected OULAD people")
    require_unique(selected, ["oulad_student_key"], "selected OULAD units")
    return selected


def fit_source_transform(
    frame: pd.DataFrame,
    *,
    key_column: str,
    dimensions: Sequence[str] = DIMENSIONS,
    fit_split: str = "train",
    lower_quantile: float = 0.01,
    upper_quantile: float = 0.99,
) -> tuple[pd.DataFrame, dict[str, Any]]:
    """Fit winsorization and z-score parameters on one source's training rows."""

    require_columns(frame, [key_column, "split", *dimensions], f"matching {key_column}")
    require_unique(frame, [key_column], f"matching {key_column}")
    if not 0.0 <= lower_quantile < upper_quantile <= 1.0:
        raise DataQualityError("matching winsor quantiles must satisfy 0 <= lower < upper <= 1")
    result = _validate_numeric_dimensions(
        frame,
        dimensions=dimensions,
        dataset=f"matching {key_column}",
    )
    fit_rows = result.loc[result["split"].eq(fit_split)]
    if fit_rows.empty:
        raise DataQualityError(f"matching {key_column} has no rows in fit split {fit_split!r}")

    statistics: dict[str, dict[str, float]] = {}
    for dimension in dimensions:
        lower = float(fit_rows[dimension].quantile(lower_quantile, interpolation="linear"))
        upper = float(fit_rows[dimension].quantile(upper_quantile, interpolation="linear"))
        clipped_train = fit_rows[dimension].clip(lower=lower, upper=upper)
        mean = float(clipped_train.mean())
        standard_deviation = float(clipped_train.std(ddof=0))
        if not np.isfinite(standard_deviation) or standard_deviation <= 0.0:
            standard_deviation = 1.0
        clipped_all = result[dimension].clip(lower=lower, upper=upper)
        result[f"{dimension}_z"] = (clipped_all - mean) / standard_deviation
        statistics[dimension] = {
            "winsor_lower": lower,
            "winsor_upper": upper,
            "mean": mean,
            "standard_deviation": standard_deviation,
        }
    metadata: dict[str, Any] = {
        "key_column": key_column,
        "fit_split": fit_split,
        "fit_row_count": len(fit_rows),
        "lower_quantile": lower_quantile,
        "upper_quantile": upper_quantile,
        "dimensions": statistics,
    }
    return result, metadata


def greedy_match_split(
    assist_rows: pd.DataFrame,
    oulad_rows: pd.DataFrame,
    *,
    split: str,
    count: int,
    seed: int,
    dimensions: Sequence[str] = DIMENSIONS,
) -> pd.DataFrame:
    """Match seeded ASSIST anchors to exact nearest remaining OULAD rows."""

    z_columns = [f"{dimension}_z" for dimension in dimensions]
    require_columns(
        assist_rows,
        ["assistments_user_key", "split", *dimensions, *z_columns],
        "ASSISTments matching rows",
    )
    require_columns(
        oulad_rows,
        ["oulad_person_key", "oulad_student_key", "split", *dimensions, *z_columns],
        "OULAD matching rows",
    )
    require_unique(assist_rows, ["assistments_user_key"], "ASSISTments matching rows")
    require_unique(oulad_rows, ["oulad_person_key"], "OULAD matching people")
    require_unique(oulad_rows, ["oulad_student_key"], "OULAD matching rows")
    if count < 1:
        raise DataQualityError("matching count must be positive")

    assist_split = assist_rows.loc[assist_rows["split"].eq(split)].copy()
    oulad_split = oulad_rows.loc[oulad_rows["split"].eq(split)].copy()
    if len(assist_split) < count or len(oulad_split) < count:
        raise DataQualityError(
            f"matching split {split!r} requires {count} rows; "
            f"ASSISTments={len(assist_split)}, OULAD={len(oulad_split)}"
        )

    assist_split["_stable_rank"] = assist_split["assistments_user_key"].astype(str).map(
        lambda key: stable_rank(key, f"edutwin-match-anchor-{split}-v1", seed)
    )
    anchors = assist_split.sort_values(
        ["_stable_rank", "assistments_user_key"], kind="mergesort"
    ).head(count)
    candidates = oulad_split.sort_values("oulad_student_key", kind="mergesort").reset_index(
        drop=True
    )
    candidate_vectors = candidates[z_columns].to_numpy(dtype="float64")
    available = np.ones(len(candidates), dtype=bool)
    candidate_keys = candidates["oulad_student_key"].astype(str).to_numpy()
    records: list[dict[str, Any]] = []

    for local_order, (_, anchor) in enumerate(anchors.iterrows(), start=1):
        anchor_vector = anchor[z_columns].to_numpy(dtype="float64")
        available_indices = np.flatnonzero(available)
        differences = candidate_vectors[available_indices] - anchor_vector
        squared_distances = np.einsum("ij,ij->i", differences, differences)
        minimum = float(squared_distances.min())
        nearest_positions = np.flatnonzero(squared_distances == minimum)
        tied_indices = available_indices[nearest_positions]
        chosen_index = min(tied_indices.tolist(), key=lambda index: candidate_keys[index])
        available[chosen_index] = False
        candidate = candidates.iloc[chosen_index]
        record: dict[str, Any] = {
            "split": split,
            "split_match_order": local_order,
            "assistments_user_key": str(anchor["assistments_user_key"]),
            "oulad_person_key": str(candidate["oulad_person_key"]),
            "oulad_student_key": str(candidate["oulad_student_key"]),
            "distance": float(np.sqrt(minimum)),
        }
        for dimension in dimensions:
            record[f"assist_{dimension}"] = float(anchor[dimension])
            record[f"assist_{dimension}_z"] = float(anchor[f"{dimension}_z"])
            record[f"oulad_{dimension}"] = float(candidate[dimension])
            record[f"oulad_{dimension}_z"] = float(candidate[f"{dimension}_z"])
        for context_column in SAFE_OULAD_CONTEXT:
            if context_column in candidate.index:
                record[f"oulad_{context_column}"] = candidate[context_column]
        records.append(record)
    result = pd.DataFrame.from_records(records)
    require_unique(result, ["assistments_user_key"], f"ASSISTments matches {split}")
    require_unique(result, ["oulad_person_key"], f"OULAD person matches {split}")
    require_unique(result, ["oulad_student_key"], f"OULAD matches {split}")
    return result


def match_students(
    assist_dimensions: pd.DataFrame,
    oulad_dimensions: pd.DataFrame,
    config: Mapping[str, Any],
) -> tuple[pd.DataFrame, dict[str, Any]]:
    """Create the fixed 1,400/300/300 cross-source match without replacement."""

    matching_config = _section(config, "matching")
    seed = int(config.get("seed", 42))
    if seed != 42:
        raise DataQualityError("matching seed must remain 42")
    dimensions = tuple(matching_config.get("dimensions", DIMENSIONS))
    if dimensions != DIMENSIONS:
        raise DataQualityError(f"matching dimensions must remain {list(DIMENSIONS)}")
    target_value = matching_config.get("target_counts", CANONICAL_TARGET_COUNTS)
    if not isinstance(target_value, Mapping):
        raise DataQualityError("matching.target_counts must be an object")
    target_counts = {split: int(target_value.get(split, -1)) for split in SPLIT_ORDER}
    if target_counts != CANONICAL_TARGET_COUNTS:
        raise DataQualityError(f"matching target counts must remain {CANONICAL_TARGET_COUNTS}")
    minimum_events = int(matching_config.get("assistments_min_events", 20))
    if minimum_events != 20:
        raise DataQualityError("matching ASSISTments minimum event count must remain 20")
    fit_split = str(matching_config.get("fit_split", "train"))
    if fit_split != "train":
        raise DataQualityError("matching transforms must be fitted on train only")
    lower = float(matching_config.get("winsor_lower_quantile", 0.01))
    upper = float(matching_config.get("winsor_upper_quantile", 0.99))
    if (lower, upper) != (0.01, 0.99):
        raise DataQualityError("matching winsor quantiles must remain 0.01 and 0.99")
    if matching_config.get("without_replacement", True) is not True:
        raise DataQualityError("matching must remain without replacement")
    if str(matching_config.get("tie_break", "stable_source_key")) != "stable_source_key":
        raise DataQualityError("matching tie break must remain stable_source_key")

    require_columns(
        assist_dimensions,
        ["assistments_user_key", "split", "event_count", *DIMENSIONS],
        "ASSISTments dimensions",
    )
    event_counts = pd.to_numeric(assist_dimensions["event_count"], errors="coerce")
    if event_counts.isna().any():
        raise DataQualityError("ASSISTments dimensions.event_count contains invalid values")
    eligible_assist = assist_dimensions.loc[event_counts.ge(minimum_events)].copy()
    selected_oulad = select_one_oulad_unit_per_person(oulad_dimensions, seed=seed)
    assist_transformed, assist_statistics = fit_source_transform(
        eligible_assist,
        key_column="assistments_user_key",
        fit_split=fit_split,
        lower_quantile=lower,
        upper_quantile=upper,
    )
    oulad_transformed, oulad_statistics = fit_source_transform(
        selected_oulad,
        key_column="oulad_student_key",
        fit_split=fit_split,
        lower_quantile=lower,
        upper_quantile=upper,
    )

    matched_parts = [
        greedy_match_split(
            assist_transformed,
            oulad_transformed,
            split=split,
            count=target_counts[split],
            seed=seed,
        )
        for split in SPLIT_ORDER
    ]
    matches = pd.concat(matched_parts, ignore_index=True)
    matches.insert(0, "match_order", np.arange(1, len(matches) + 1, dtype="int64"))
    require_unique(matches, ["assistments_user_key"], "ASSISTments matched students")
    require_unique(matches, ["oulad_person_key"], "OULAD matched people")
    require_unique(matches, ["oulad_student_key"], "OULAD matched students")
    if len(matches) != sum(CANONICAL_TARGET_COUNTS.values()):
        raise DataQualityError("matching did not produce exactly 2,000 pairs")

    serialized = matches.to_json(orient="records", double_precision=15, force_ascii=True)
    metadata: dict[str, Any] = {
        "algorithm": "seeded-person-deduplicated-greedy-exact-euclidean-v2",
        "seed": seed,
        "dimensions": list(DIMENSIONS),
        "fit_split": fit_split,
        "winsor_quantiles": {"lower": lower, "upper": upper},
        "target_counts": target_counts,
        "assistments_min_events": minimum_events,
        "without_replacement": True,
        "tie_break": "lexicographic_oulad_student_key",
        "oulad_unit_selection": {
            "algorithm": "stable-rank-one-unit-per-person-v1",
            "namespace": OULAD_UNIT_SELECTION_NAMESPACE,
            "input_unit_count": len(oulad_dimensions),
            "selected_person_count": len(selected_oulad),
        },
        "source_statistics": {
            "assistments": assist_statistics,
            "oulad": oulad_statistics,
        },
        "eligible_counts": {
            "assistments": {
                split: int(assist_transformed["split"].eq(split).sum())
                for split in SPLIT_ORDER
            },
            "oulad": {
                split: int(oulad_transformed["split"].eq(split).sum())
                for split in SPLIT_ORDER
            },
        },
        "matched_counts": {
            split: int(matches["split"].eq(split).sum()) for split in SPLIT_ORDER
        },
        "matches_sha256": hashlib.sha256(serialized.encode("utf-8")).hexdigest(),
    }
    return matches, metadata
