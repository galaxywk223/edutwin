"""Risk candidate training, validation selection, and one-shot test finalization."""

from __future__ import annotations

import json
import platform
from collections.abc import Mapping
from dataclasses import dataclass
from importlib import metadata
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd

from edutwin_modeling.config import ProjectPaths, resolve_repository_path
from edutwin_modeling.data.splitting import split_name
from edutwin_modeling.errors import DataQualityError, ModelSelectionError
from edutwin_modeling.hashing import sha256_file, sha256_object, stable_bucket
from edutwin_modeling.manifest import file_entry, write_canonical_json
from edutwin_modeling.risk.model import (
    FAMILIES,
    FEATURE_COLUMNS,
    RiskCalibrator,
    RiskCandidate,
    RiskMetrics,
    RiskPreprocessor,
    build_candidate,
    evaluate_candidate,
    select_candidate,
)

DEPENDENCIES = (
    "catboost",
    "joblib",
    "lightgbm",
    "numpy",
    "pandas",
    "pyarrow",
    "scikit-learn",
    "scipy",
)


def _portable_platform() -> str:
    machine = platform.machine().lower()
    architecture = {
        "amd64": "amd64",
        "x86_64": "amd64",
        "aarch64": "arm64",
        "arm64": "arm64",
    }.get(machine, machine or "unknown")
    return f"{platform.system().lower()}-{architecture}"


def _section(config: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    value = config.get(name)
    if not isinstance(value, Mapping):
        raise DataQualityError(f"risk configuration section {name!r} must be an object")
    return value


def _path(config: Mapping[str, Any], name: str, paths: ProjectPaths) -> Path:
    value = config.get(name)
    if not isinstance(value, str) or not value:
        raise DataQualityError(f"risk paths.{name} must be a non-empty string")
    return resolve_repository_path(value, paths)


@dataclass(frozen=True)
class RiskSettings:
    seed: int
    feature_contract_version: str
    risk_units: Path
    oulad_manifest: Path
    artifacts_root: Path
    freeze_manifest: Path
    test_state: Path
    test_metrics: Path
    test_lock: Path
    calibration_fraction: float
    calibration_namespace: str
    latency_sample_size: int
    latency_warmup_repeats: int
    latency_timed_repeats: int
    pr_auc_tolerance: float
    medium_threshold: float
    high_threshold: float
    model_configs: dict[str, dict[str, Any]]


def _settings(config: Mapping[str, Any], paths: ProjectPaths) -> RiskSettings:
    if int(config.get("schema_version", -1)) != 1 or int(config.get("seed", -1)) != 42:
        raise DataQualityError("risk schema version and seed must remain 1 and 42")
    contract = config.get("feature_contract_version")
    if contract != "oulad-d0-29-v1":
        raise DataQualityError("risk feature contract must remain oulad-d0-29-v1")
    path_config = _section(config, "paths")
    data = _section(config, "data")
    evaluation = _section(config, "evaluation")
    thresholds = _section(config, "thresholds")
    models = _section(config, "models")
    fixed_data = {
        "fit_split": "train",
        "selection_split": "validation",
        "final_split": "test",
        "split_algorithm": "sha256-u64-mod-10000-v1",
        "split_namespace": "edutwin-student-split-v1",
        "train_upper_exclusive": 7000,
        "validation_upper_exclusive": 8500,
        "test_upper_exclusive": 10000,
        "observation_window_start_day": 0,
        "observation_window_end_day": 29,
        "calibration_namespace": "edutwin-risk-calibration-students-v1",
    }
    for key, expected in fixed_data.items():
        if data.get(key) != expected:
            raise DataQualityError(f"risk data.{key} must remain {expected!r}")
    if evaluation.get("primary_metric") != "PR_AUC" or evaluation.get("device") != "cpu":
        raise DataQualityError("risk evaluation must use CPU validation PR-AUC")
    tolerance = float(evaluation.get("pr_auc_tolerance", -1.0))
    if tolerance != 0.005:
        raise DataQualityError("risk PR-AUC tolerance must remain 0.005")
    calibration_fraction = float(data.get("calibration_fraction", -1.0))
    if not 0.05 <= calibration_fraction <= 0.4:
        raise DataQualityError("risk calibration fraction is invalid")
    latency_sample_size = int(evaluation.get("latency_sample_size", -1))
    latency_warmup_repeats = int(evaluation.get("latency_warmup_repeats", -1))
    latency_timed_repeats = int(evaluation.get("latency_timed_repeats", -1))
    if latency_sample_size < 1 or latency_warmup_repeats < 0 or latency_timed_repeats < 1:
        raise DataQualityError("risk latency evaluation settings are invalid")
    model_configs = {
        family: dict(_section(models, family)) for family in FAMILIES
    }
    if set(models) != set(FAMILIES):
        raise DataQualityError(f"risk models must be exactly {list(FAMILIES)}")
    medium = float(thresholds.get("medium", -1.0))
    high = float(thresholds.get("high", -1.0))
    if not 0.0 < medium < high < 1.0:
        raise DataQualityError("risk band thresholds are invalid")
    return RiskSettings(
        seed=42,
        feature_contract_version=str(contract),
        risk_units=_path(path_config, "risk_units", paths),
        oulad_manifest=_path(path_config, "oulad_manifest", paths),
        artifacts_root=_path(path_config, "artifacts_root", paths),
        freeze_manifest=_path(path_config, "freeze_manifest", paths),
        test_state=_path(path_config, "test_state", paths),
        test_metrics=_path(path_config, "test_metrics", paths),
        test_lock=_path(path_config, "test_lock", paths),
        calibration_fraction=calibration_fraction,
        calibration_namespace=str(data.get("calibration_namespace")),
        latency_sample_size=latency_sample_size,
        latency_warmup_repeats=latency_warmup_repeats,
        latency_timed_repeats=latency_timed_repeats,
        pr_auc_tolerance=tolerance,
        medium_threshold=medium,
        high_threshold=high,
        model_configs=model_configs,
    )


def _load_json(path: Path, context: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DataQualityError(f"failed to read {context}: {path}") from exc
    if not isinstance(value, dict):
        raise DataQualityError(f"{context} must contain an object")
    return value


def _load_units(settings: RiskSettings, paths: ProjectPaths) -> pd.DataFrame:
    if not settings.risk_units.is_file() or not settings.oulad_manifest.is_file():
        raise DataQualityError("risk training inputs are missing; run data prepare first")
    units = pd.read_parquet(settings.risk_units)
    required = {
        "oulad_student_key",
        "id_student",
        "split",
        "risk_label",
        *FEATURE_COLUMNS,
    }
    missing = sorted(required.difference(units.columns))
    if missing:
        raise DataQualityError(f"risk units omit required columns: {missing}")
    forbidden = {"final_result", "date_unregistration"}.intersection(units.columns)
    if forbidden:
        raise DataQualityError(f"risk units expose forbidden features: {sorted(forbidden)}")
    if not units["oulad_student_key"].is_unique:
        raise DataQualityError("risk unit keys are not unique")
    if units[["oulad_student_key", "id_student", "split"]].isna().any().any():
        raise DataQualityError("risk unit identity or split contains null values")
    if set(units["split"].astype(str).unique()) != {"train", "validation", "test"}:
        raise DataQualityError("risk units do not contain the frozen three splits")
    units = units.copy()
    units["split"] = units["split"].astype(str)
    split_counts = units.groupby("id_student", dropna=False)["split"].nunique(
        dropna=False
    )
    if (split_counts != 1).any():
        raise DataQualityError("risk students are assigned to multiple splits")
    expected_splits = units["id_student"].astype(str).map(
        lambda student: split_name(
            student,
            namespace="edutwin-student-split-v1",
            seed=42,
            train_upper_exclusive=7000,
            validation_upper_exclusive=8500,
            test_upper_exclusive=10_000,
        )
    )
    if not units["split"].eq(expected_splits).all():
        raise DataQualityError("risk student split differs from the stable hash contract")
    labels = pd.to_numeric(units["risk_label"], errors="coerce")
    if labels.isna().any() or not labels.isin([0, 1]).all():
        raise DataQualityError("risk labels are not binary")
    units["risk_label"] = labels.astype("int8")
    for column in FEATURE_COLUMNS:
        units[column] = pd.to_numeric(units[column], errors="coerce")
    if not np.isfinite(units[list(FEATURE_COLUMNS)].to_numpy(dtype="float64")).all():
        raise DataQualityError("risk features contain non-finite values")
    if (units[list(FEATURE_COLUMNS)] < 0.0).any().any():
        raise DataQualityError("risk features contain negative values")
    if (units["assessment_mean_score"] > 100.0).any():
        raise DataQualityError("risk assessment mean score exceeds 100")
    manifest = _load_json(settings.oulad_manifest, "OULAD processing manifest")
    if manifest.get("feature_contract_version") != settings.feature_contract_version:
        raise DataQualityError("OULAD manifest feature contract differs")
    if tuple(manifest.get("feature_columns", ())) != FEATURE_COLUMNS:
        raise DataQualityError("OULAD manifest feature order differs")
    if manifest.get("feature_window") != {"start_day": 0, "end_day": 29}:
        raise DataQualityError("OULAD manifest feature window differs")
    outputs = _section(manifest, "outputs")
    risk_units_entry = _section(outputs, "risk_units")
    output_path = risk_units_entry.get("path")
    if not isinstance(output_path, str):
        raise DataQualityError("OULAD manifest risk-unit path is missing")
    manifest_output = (paths.repository_root / output_path).resolve()
    if (
        manifest_output != settings.risk_units.resolve()
        or risk_units_entry.get("sha256") != sha256_file(settings.risk_units)
    ):
        raise DataQualityError("OULAD manifest risk-unit identity differs")
    return units.sort_values("oulad_student_key", kind="mergesort").reset_index(drop=True)


def _training_roles(
    units: pd.DataFrame, settings: RiskSettings
) -> tuple[pd.DataFrame, pd.DataFrame]:
    train = units.loc[units["split"].eq("train")].copy()
    calibration_cutoff = int(settings.calibration_fraction * 10_000)
    role_by_student = {
        student: stable_bucket(
            str(student), settings.calibration_namespace, settings.seed
        ) < calibration_cutoff
        for student in sorted(train["id_student"].astype(str).unique())
    }
    calibration_mask = train["id_student"].astype(str).map(role_by_student)
    fit_rows = train.loc[~calibration_mask].copy()
    calibration_rows = train.loc[calibration_mask].copy()
    for name, frame in (("fit", fit_rows), ("calibration", calibration_rows)):
        if frame.empty or set(frame["risk_label"].unique()) != {0, 1}:
            raise ModelSelectionError(f"risk train-{name} role requires both classes")
    fit_students = set(fit_rows["id_student"].astype(str))
    calibration_students = set(calibration_rows["id_student"].astype(str))
    if fit_students.intersection(calibration_students):
        raise DataQualityError("risk fit and calibration students overlap")
    return fit_rows, calibration_rows


def _matrix(frame: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    return (
        frame[list(FEATURE_COLUMNS)].to_numpy(dtype="float64"),
        frame["risk_label"].to_numpy(dtype="int8"),
    )


def _dependency_versions() -> dict[str, str]:
    return {name: metadata.version(name) for name in DEPENDENCIES}


def _resolve_entry(entry: Mapping[str, Any], paths: ProjectPaths, context: str) -> Path:
    value = entry.get("path")
    if not isinstance(value, str) or not value:
        raise DataQualityError(f"{context} path is missing")
    path = (paths.repository_root / value).resolve()
    try:
        path.relative_to(paths.repository_root.resolve())
    except ValueError as exc:
        raise DataQualityError(f"{context} artifact path escapes the repository") from exc
    if not path.is_file() or sha256_file(path) != entry.get("sha256"):
        raise DataQualityError(f"{context} artifact hash differs")
    if path.stat().st_size != int(entry.get("bytes", -1)):
        raise DataQualityError(f"{context} artifact size differs")
    return path


def _load_candidate(
    entry: Mapping[str, Any], paths: ProjectPaths
) -> tuple[RiskCandidate, RiskCalibrator]:
    model_path = _resolve_entry(_section(entry, "modelArtifact"), paths, "risk model")
    calibrator_path = _resolve_entry(
        _section(entry, "calibratorArtifact"), paths, "risk calibrator"
    )
    try:
        candidate = joblib.load(model_path)
    except Exception as exc:
        raise DataQualityError("risk model artifact cannot be loaded") from exc
    if not isinstance(candidate, RiskCandidate) or candidate.family != entry.get("family"):
        raise DataQualityError("risk model artifact type differs")
    candidate.validate()
    calibrator = RiskCalibrator.from_dict(_load_json(calibrator_path, "risk calibrator"))
    return candidate, calibrator


def _verify_frozen_inputs(
    freeze: Mapping[str, Any], settings: RiskSettings, paths: ProjectPaths
) -> None:
    inputs = _section(freeze, "inputs")
    if set(inputs) != {"riskUnits", "ouladManifest"}:
        raise DataQualityError("risk freeze input references are incomplete")
    expected = {
        "riskUnits": settings.risk_units.resolve(),
        "ouladManifest": settings.oulad_manifest.resolve(),
    }
    for name, expected_path in expected.items():
        actual = _resolve_entry(_section(inputs, name), paths, f"risk input {name}")
        if actual != expected_path:
            raise DataQualityError(f"risk input {name} path changed after freeze")


def _validate_frozen_selection(
    freeze: Mapping[str, Any],
    *,
    expected_medium_threshold: float | None = None,
    expected_high_threshold: float | None = None,
) -> str:
    candidates = _section(freeze, "candidates")
    if set(candidates) != set(FAMILIES):
        raise DataQualityError("risk freeze does not contain exactly three candidates")
    metrics: dict[str, RiskMetrics] = {}
    versions: set[str] = set()
    for family in FAMILIES:
        entry = _section(candidates, family)
        if entry.get("family") != family or entry.get("modelName") != family.lower():
            raise DataQualityError(f"risk {family} candidate identity differs")
        version = entry.get("modelVersion")
        if not isinstance(version, str) or not version or version in versions:
            raise DataQualityError("risk candidate model versions are invalid")
        versions.add(version)
        model_artifact = _section(entry, "modelArtifact")
        calibrator_artifact = _section(entry, "calibratorArtifact")
        if entry.get("calibratorVersion") != f"sha256:{calibrator_artifact.get('sha256')}":
            raise DataQualityError(f"risk {family} calibrator version differs")
        if not isinstance(model_artifact.get("sha256"), str):
            raise DataQualityError(f"risk {family} model hash is missing")
        metrics[family] = RiskMetrics.from_dict(_section(entry, "validationMetrics"))

    decision = select_candidate(metrics, tolerance=0.005)
    selection = _section(freeze, "selection")
    for key in (
        "winner",
        "primaryMetric",
        "primaryValue",
        "prAucTolerance",
        "contenders",
        "tieBreakOrder",
    ):
        if selection.get(key) != decision[key]:
            raise DataQualityError("risk frozen selection does not match validation metrics")
    winner = str(decision["winner"])
    winner_entry = _section(candidates, winner)
    expected_risk_reference = {
        "purpose": "RISK",
        "family": winner_entry["family"],
        "modelName": winner_entry["modelName"],
        "modelVersion": winner_entry["modelVersion"],
        "artifactSha256": _section(winner_entry, "modelArtifact")["sha256"],
        "calibratorVersion": winner_entry["calibratorVersion"],
    }
    expected_explainer_reference = {
        "purpose": "EXPLANATION",
        "family": "SHAP",
        "modelName": f"{winner_entry['modelName']}-native-shap",
        "modelVersion": f"shap-{winner_entry['modelVersion']}",
        "artifactSha256": _section(winner_entry, "modelArtifact")["sha256"],
        "calibratorVersion": "none",
    }
    if dict(_section(selection, "riskModel")) != expected_risk_reference:
        raise DataQualityError("risk serving reference does not match the selected artifact")
    if dict(_section(selection, "explainer")) != expected_explainer_reference:
        raise DataQualityError("risk explainer reference does not match the selected artifact")
    thresholds = _section(freeze, "thresholds")
    try:
        medium = float(thresholds["medium"])
        high = float(thresholds["high"])
    except (KeyError, TypeError, ValueError) as exc:
        raise DataQualityError("risk frozen thresholds are invalid") from exc
    if not 0.0 < medium < high < 1.0:
        raise DataQualityError("risk frozen thresholds are invalid")
    if (
        expected_medium_threshold is not None
        and medium != expected_medium_threshold
    ) or (
        expected_high_threshold is not None
        and high != expected_high_threshold
    ):
        raise DataQualityError("risk frozen thresholds differ from the serving contract")
    return winner


def _candidate_bindings(freeze: Mapping[str, Any]) -> dict[str, dict[str, str]]:
    candidates = _section(freeze, "candidates")
    result: dict[str, dict[str, str]] = {}
    for family in FAMILIES:
        entry = _section(candidates, family)
        result[family] = {
            "modelVersion": str(entry.get("modelVersion")),
            "modelArtifactSha256": str(
                _section(entry, "modelArtifact").get("sha256")
            ),
            "calibratorArtifactSha256": str(
                _section(entry, "calibratorArtifact").get("sha256")
            ),
        }
        if any(
            not value or value == "None" for value in result[family].values()
        ):
            raise DataQualityError(f"risk {family} test binding is incomplete")
    return result


def _test_cohort_hashes(test_rows: pd.DataFrame) -> tuple[str, str]:
    if test_rows.empty:
        raise DataQualityError("risk test split is empty")
    students = sorted(test_rows["id_student"].astype(str).unique().tolist())
    units = sorted(test_rows["oulad_student_key"].astype(str).tolist())
    if len(units) != len(set(units)):
        raise DataQualityError("risk test unit keys are not unique")
    return sha256_object(students), sha256_object(units)


def train_risk_models(
    config: Mapping[str, Any], paths: ProjectPaths
) -> dict[str, Any]:
    settings = _settings(config, paths)
    if any(
        path.exists()
        for path in (
            settings.freeze_manifest,
            settings.test_state,
            settings.test_metrics,
            settings.test_lock,
        )
    ):
        raise ModelSelectionError("risk artifacts are already frozen or test-claimed")
    units = _load_units(settings, paths)
    fit_rows, calibration_rows = _training_roles(units, settings)
    validation_rows = units.loc[units["split"].eq("validation")].copy()
    if set(validation_rows["risk_label"].unique()) != {0, 1}:
        raise ModelSelectionError("risk validation split requires both classes")
    fit_values, fit_labels = _matrix(fit_rows)
    calibration_values, calibration_labels = _matrix(calibration_rows)
    validation_values, validation_labels = _matrix(validation_rows)
    preprocessor = RiskPreprocessor.fit(fit_values, FEATURE_COLUMNS)
    fit_values = preprocessor.transform(fit_values)
    calibration_values = preprocessor.transform(calibration_values)
    validation_values = preprocessor.transform(validation_values)

    settings.artifacts_root.mkdir(parents=True, exist_ok=True)
    preprocessor_path = settings.artifacts_root / "preprocessor.json"
    write_canonical_json(preprocessor.to_dict(), preprocessor_path)
    candidate_entries: dict[str, dict[str, Any]] = {}
    metrics_by_family: dict[str, RiskMetrics] = {}
    for family in FAMILIES:
        candidate = build_candidate(
            family, settings.model_configs[family], seed=settings.seed
        )
        candidate.estimator.fit(fit_values, fit_labels)
        calibrator = RiskCalibrator.fit(
            calibration_labels,
            candidate.raw_probabilities(calibration_values),
            seed=settings.seed,
        )
        metrics = evaluate_candidate(
            candidate,
            calibrator,
            validation_values,
            validation_labels,
            latency_sample_size=settings.latency_sample_size,
            warmup_repeats=settings.latency_warmup_repeats,
            timed_repeats=settings.latency_timed_repeats,
        )
        family_root = settings.artifacts_root / family.lower()
        family_root.mkdir(parents=True, exist_ok=True)
        model_path = family_root / "model.joblib"
        calibrator_path = family_root / "calibrator.json"
        joblib.dump(candidate, model_path, compress=0, protocol=5)
        write_canonical_json(calibrator.to_dict(), calibrator_path)
        model_artifact = file_entry(model_path, paths)
        calibrator_artifact = file_entry(calibrator_path, paths)
        version_seed = {
            "family": family,
            "modelSha256": model_artifact["sha256"],
            "calibratorSha256": calibrator_artifact["sha256"],
            "config": settings.model_configs[family],
            "seed": settings.seed,
        }
        model_version = f"risk-{family.lower()}-{sha256_object(version_seed)[:16]}"
        candidate_entries[family] = {
            "family": family,
            "modelName": family.lower(),
            "modelVersion": model_version,
            "modelArtifact": model_artifact,
            "calibratorArtifact": calibrator_artifact,
            "calibratorVersion": f"sha256:{calibrator_artifact['sha256']}",
            "configuration": settings.model_configs[family],
            "validationMetrics": metrics.to_dict(),
        }
        metrics_by_family[family] = metrics

    selection = select_candidate(
        metrics_by_family, tolerance=settings.pr_auc_tolerance
    )
    winner_entry = candidate_entries[str(selection["winner"])]
    freeze: dict[str, Any] = {
        "schemaVersion": 1,
        "status": "FROZEN",
        "seed": settings.seed,
        "featureContractVersion": settings.feature_contract_version,
        "observationWindow": {"startDayInclusive": 0, "endDayInclusive": 29},
        "configurationSha256": sha256_object(config),
        "dependencies": _dependency_versions(),
        "runtime": {"python": platform.python_version(), "platform": _portable_platform()},
        "inputs": {
            "riskUnits": file_entry(settings.risk_units, paths),
            "ouladManifest": file_entry(settings.oulad_manifest, paths),
        },
        "training": {
            "fitRows": len(fit_rows),
            "calibrationRows": len(calibration_rows),
            "validationRows": len(validation_rows),
            "fitStudents": int(fit_rows["id_student"].nunique()),
            "calibrationStudents": int(calibration_rows["id_student"].nunique()),
            "testEvaluated": False,
        },
        "preprocessor": file_entry(preprocessor_path, paths),
        "candidates": candidate_entries,
        "selection": {
            **selection,
            "riskModel": {
                "purpose": "RISK",
                "family": winner_entry["family"],
                "modelName": winner_entry["modelName"],
                "modelVersion": winner_entry["modelVersion"],
                "artifactSha256": winner_entry["modelArtifact"]["sha256"],
                "calibratorVersion": winner_entry["calibratorVersion"],
            },
            "explainer": {
                "purpose": "EXPLANATION",
                "family": "SHAP",
                "modelName": f"{winner_entry['modelName']}-native-shap",
                "modelVersion": f"shap-{winner_entry['modelVersion']}",
                "artifactSha256": winner_entry["modelArtifact"]["sha256"],
                "calibratorVersion": "none",
            },
        },
        "thresholds": {
            "medium": settings.medium_threshold,
            "high": settings.high_threshold,
        },
    }
    write_canonical_json(freeze, settings.freeze_manifest)
    freeze_sha256 = sha256_file(settings.freeze_manifest)
    write_canonical_json(
        {
            "schemaVersion": 2,
            "status": "SEALED",
            "attempts": 0,
            "freezeManifestSha256": freeze_sha256,
        },
        settings.test_state,
    )
    return {
        "freeze_manifest": file_entry(settings.freeze_manifest, paths),
        "test_state": file_entry(settings.test_state, paths),
        "winner": selection["winner"],
        "validation_metrics": {
            family: metrics.to_dict() for family, metrics in metrics_by_family.items()
        },
    }


def _claim_test(settings: RiskSettings, freeze_sha256: str) -> None:
    if settings.test_lock.exists() or settings.test_metrics.exists():
        raise ModelSelectionError("risk test evaluation was already claimed")
    state = _load_json(settings.test_state, "risk test state")
    if (
        state.get("schemaVersion") != 2
        or state.get("status") != "SEALED"
        or state.get("attempts") != 0
        or state.get("freezeManifestSha256") != freeze_sha256
    ):
        raise ModelSelectionError("risk test state is not an unclaimed freeze seal")
    settings.test_lock.parent.mkdir(parents=True, exist_ok=True)
    try:
        with settings.test_lock.open("x", encoding="utf-8") as handle:
            handle.write(f"freeze_sha256={freeze_sha256}\nattempt=1\n")
    except FileExistsError as exc:
        raise ModelSelectionError("risk test evaluation was already claimed") from exc
    write_canonical_json(
        {
            "schemaVersion": 2,
            "status": "CLAIMED",
            "attempts": 1,
            "freezeManifestSha256": freeze_sha256,
        },
        settings.test_state,
    )


def finalize_risk_test(
    config: Mapping[str, Any], paths: ProjectPaths
) -> dict[str, Any]:
    settings = _settings(config, paths)
    freeze = _load_json(settings.freeze_manifest, "risk freeze manifest")
    if (
        freeze.get("status") != "FROZEN"
        or freeze.get("configurationSha256") != sha256_object(config)
    ):
        raise ModelSelectionError("risk freeze manifest is absent or configuration changed")
    _verify_frozen_inputs(freeze, settings, paths)
    _validate_frozen_selection(
        freeze,
        expected_medium_threshold=settings.medium_threshold,
        expected_high_threshold=settings.high_threshold,
    )
    freeze_sha256 = sha256_file(settings.freeze_manifest)
    _claim_test(settings, freeze_sha256)
    test_student_set_sha256: str | None = None
    test_unit_set_sha256: str | None = None
    try:
        units = _load_units(settings, paths)
        test_rows = units.loc[units["split"].eq("test")].copy()
        test_student_set_sha256, test_unit_set_sha256 = _test_cohort_hashes(
            test_rows
        )
        raw_values, labels = _matrix(test_rows)
        preprocessor_path = _resolve_entry(
            _section(freeze, "preprocessor"), paths, "risk preprocessor"
        )
        preprocessor = RiskPreprocessor.from_dict(
            _load_json(preprocessor_path, "risk preprocessor")
        )
        values = preprocessor.transform(raw_values)
        candidates = _section(freeze, "candidates")
        metrics: dict[str, Any] = {}
        for family in FAMILIES:
            candidate, calibrator = _load_candidate(
                _section(candidates, family), paths
            )
            result = evaluate_candidate(
                candidate,
                calibrator,
                values,
                labels,
                latency_sample_size=settings.latency_sample_size,
                warmup_repeats=settings.latency_warmup_repeats,
                timed_repeats=settings.latency_timed_repeats,
            )
            metrics[family] = result.to_dict()
        test_result = {
            "schemaVersion": 2,
            "status": "COMPLETED",
            "attempts": 1,
            "configurationSha256": sha256_object(config),
            "freezeManifestSha256": freeze_sha256,
            "testStudentSetSha256": test_student_set_sha256,
            "testUnitSetSha256": test_unit_set_sha256,
            "testRows": len(test_rows),
            "candidateBindings": _candidate_bindings(freeze),
            "metrics": metrics,
        }
        write_canonical_json(test_result, settings.test_metrics)
        write_canonical_json(
            {
                "schemaVersion": 2,
                "status": "COMPLETED",
                "attempts": 1,
                "freezeManifestSha256": freeze_sha256,
                "metricsSha256": sha256_file(settings.test_metrics),
                "testStudentSetSha256": test_student_set_sha256,
                "testUnitSetSha256": test_unit_set_sha256,
            },
            settings.test_state,
        )
        return {
            "test_state": file_entry(settings.test_state, paths),
            "test_metrics": file_entry(settings.test_metrics, paths),
        }
    except Exception:
        write_canonical_json(
            {
                "schemaVersion": 2,
                "status": "FAILED",
                "attempts": 1,
                "freezeManifestSha256": freeze_sha256,
                "testStudentSetSha256": test_student_set_sha256,
                "testUnitSetSha256": test_unit_set_sha256,
            },
            settings.test_state,
        )
        raise


def verify_risk_delivery(
    config: Mapping[str, Any], paths: ProjectPaths
) -> dict[str, Any]:
    settings = _settings(config, paths)
    freeze = _load_json(settings.freeze_manifest, "risk freeze manifest")
    state = _load_json(settings.test_state, "risk test state")
    metrics = _load_json(settings.test_metrics, "risk test metrics")
    if (
        freeze.get("status") != "FROZEN"
        or freeze.get("configurationSha256") != sha256_object(config)
    ):
        raise DataQualityError("risk freeze manifest is invalid")
    _verify_frozen_inputs(freeze, settings, paths)
    winner = _validate_frozen_selection(
        freeze,
        expected_medium_threshold=settings.medium_threshold,
        expected_high_threshold=settings.high_threshold,
    )
    freeze_sha256 = sha256_file(settings.freeze_manifest)
    if (
        state.get("schemaVersion") != 2
        or state.get("status") != "COMPLETED"
        or state.get("attempts") != 1
        or state.get("freezeManifestSha256") != freeze_sha256
    ):
        raise DataQualityError("risk test evaluation is not exactly-once complete")
    if state.get("metricsSha256") != sha256_file(settings.test_metrics):
        raise DataQualityError("risk test metrics hash differs")
    units = _load_units(settings, paths)
    test_rows = units.loc[units["split"].eq("test")].copy()
    student_sha256, unit_sha256 = _test_cohort_hashes(test_rows)
    if (
        metrics.get("schemaVersion") != 2
        or metrics.get("status") != "COMPLETED"
        or metrics.get("attempts") != 1
        or metrics.get("configurationSha256") != sha256_object(config)
        or metrics.get("freezeManifestSha256") != freeze_sha256
        or metrics.get("testStudentSetSha256") != student_sha256
        or metrics.get("testUnitSetSha256") != unit_sha256
        or metrics.get("testRows") != len(test_rows)
        or state.get("testStudentSetSha256") != student_sha256
        or state.get("testUnitSetSha256") != unit_sha256
        or dict(_section(metrics, "candidateBindings"))
        != _candidate_bindings(freeze)
    ):
        raise DataQualityError("risk test evidence does not match the frozen delivery")
    if set(_section(metrics, "metrics")) != set(FAMILIES):
        raise DataQualityError("risk test metrics omit candidates")
    for family in FAMILIES:
        RiskMetrics.from_dict(_section(_section(metrics, "metrics"), family))
    try:
        lock_lines = settings.test_lock.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise DataQualityError("risk test claim lock is missing") from exc
    if lock_lines != [f"freeze_sha256={freeze_sha256}", "attempt=1"]:
        raise DataQualityError("risk test claim lock does not match the freeze")
    preprocessor_path = _resolve_entry(
        _section(freeze, "preprocessor"), paths, "risk preprocessor"
    )
    RiskPreprocessor.from_dict(_load_json(preprocessor_path, "risk preprocessor"))
    candidates = _section(freeze, "candidates")
    for family in FAMILIES:
        _load_candidate(_section(candidates, family), paths)
    return {
        "freeze_manifest": file_entry(settings.freeze_manifest, paths),
        "test_state": file_entry(settings.test_state, paths),
        "test_metrics": file_entry(settings.test_metrics, paths),
        "winner": winner,
        "candidates": list(FAMILIES),
    }
