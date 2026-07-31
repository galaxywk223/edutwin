from __future__ import annotations

from pathlib import Path

import pandas as pd
import pytest

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.data.splitting import split_name
from edutwin_modeling.errors import DataQualityError, ModelSelectionError
from edutwin_modeling.hashing import sha256_file
from edutwin_modeling.manifest import write_canonical_json
from edutwin_modeling.risk.model import (
    FEATURE_COLUMNS,
    RiskMetrics,
    select_candidate,
)
from edutwin_modeling.risk.pipeline import (
    DEPENDENCIES,
    RiskSettings,
    _claim_test,
    _load_units,
    _portable_platform,
    _validate_frozen_selection,
)
from edutwin_modeling.risk.runtime import _deployment_family


def test_portable_platform_omits_os_build(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("platform.system", lambda: "Windows")
    monkeypatch.setattr("platform.machine", lambda: "AMD64")

    assert _portable_platform() == "windows-amd64"


def _settings(root: Path, risk_units: Path, manifest: Path) -> RiskSettings:
    artifacts = root / "modeling" / "artifacts" / "risk"
    manifests = root / "modeling" / "artifacts" / "manifests"
    return RiskSettings(
        seed=42,
        feature_contract_version="oulad-d0-29-v1",
        risk_units=risk_units,
        oulad_manifest=manifest,
        artifacts_root=artifacts,
        freeze_manifest=manifests / "risk-freeze.json",
        test_state=manifests / "risk-test-state.json",
        test_metrics=manifests / "risk-test-metrics.json",
        test_lock=manifests / "risk-test-evaluation.lock",
        calibration_fraction=0.2,
        calibration_namespace="edutwin-risk-calibration-students-v1",
        latency_sample_size=16,
        latency_warmup_repeats=0,
        latency_timed_repeats=1,
        pr_auc_tolerance=0.005,
        medium_threshold=0.35,
        high_threshold=0.65,
        model_configs={},
    )


def _student_ids_by_split(count_per_split: int = 2) -> dict[str, list[str]]:
    result = {"train": [], "validation": [], "test": []}
    candidate = 1
    while any(len(values) < count_per_split for values in result.values()):
        student = str(candidate)
        split = split_name(
            student,
            namespace="edutwin-student-split-v1",
            seed=42,
        )
        if len(result[split]) < count_per_split:
            result[split].append(student)
        candidate += 1
    return result


def _write_units(root: Path, *, tamper_split: bool) -> tuple[Path, Path]:
    identifiers = _student_ids_by_split()
    rows: list[dict[str, object]] = []
    index = 0
    for split, students in identifiers.items():
        for student in students:
            row: dict[str, object] = {
                "oulad_student_key": f"AAA|2025J|{student}",
                "id_student": student,
                "split": split,
                "risk_label": index % 2,
            }
            row.update({name: float(index + 1) for name in FEATURE_COLUMNS})
            row["assessment_mean_score"] = float(20 + index)
            rows.append(row)
            index += 1
    if tamper_split:
        rows[0]["split"] = "validation"

    risk_units = root / "data" / "processed" / "risk" / "risk_units.parquet"
    risk_units.parent.mkdir(parents=True)
    pd.DataFrame.from_records(rows).to_parquet(risk_units, index=False)
    manifest = risk_units.parent / "manifest.json"
    write_canonical_json(
        {
            "feature_contract_version": "oulad-d0-29-v1",
            "feature_columns": list(FEATURE_COLUMNS),
            "feature_window": {"start_day": 0, "end_day": 29},
            "outputs": {
                "risk_units": {
                    "path": risk_units.relative_to(root).as_posix(),
                    "bytes": risk_units.stat().st_size,
                    "sha256": sha256_file(risk_units),
                }
            },
        },
        manifest,
    )
    return risk_units, manifest


def test_risk_dependencies_include_scipy() -> None:
    assert "scipy" in DEPENDENCIES


def test_risk_rollback_uses_fixed_distinct_runner_up() -> None:
    assert _deployment_family("LOGISTIC_REGRESSION", "active") == "LOGISTIC_REGRESSION"
    assert _deployment_family("LOGISTIC_REGRESSION", "rollback") == "LIGHTGBM"
    assert _deployment_family("LIGHTGBM", "rollback") == "LOGISTIC_REGRESSION"
    with pytest.raises(DataQualityError, match="active or rollback"):
        _deployment_family("LIGHTGBM", "invalid")


def test_risk_units_reject_split_that_differs_from_stable_hash(tmp_path: Path) -> None:
    risk_units, manifest = _write_units(tmp_path, tamper_split=True)
    paths = ProjectPaths(repository_root=tmp_path, modeling_root=tmp_path / "modeling")

    with pytest.raises(DataQualityError, match="stable hash contract"):
        _load_units(_settings(tmp_path, risk_units, manifest), paths)


def test_risk_test_cannot_be_reclaimed_after_lock_deletion(tmp_path: Path) -> None:
    risk_units = tmp_path / "risk_units.parquet"
    manifest = tmp_path / "manifest.json"
    settings = _settings(tmp_path, risk_units, manifest)
    settings.test_state.parent.mkdir(parents=True)
    freeze_sha256 = "a" * 64
    write_canonical_json(
        {
            "schemaVersion": 2,
            "status": "SEALED",
            "attempts": 0,
            "freezeManifestSha256": freeze_sha256,
        },
        settings.test_state,
    )

    _claim_test(settings, freeze_sha256)
    settings.test_lock.unlink()

    with pytest.raises(ModelSelectionError, match="unclaimed freeze seal"):
        _claim_test(settings, freeze_sha256)


def test_frozen_selection_rejects_tampered_winner() -> None:
    metrics = {
        "LOGISTIC_REGRESSION": RiskMetrics(0.80, 0.20, 0.2, 100),
        "LIGHTGBM": RiskMetrics(0.797, 0.15, 0.3, 100),
        "CATBOOST": RiskMetrics(0.78, 0.10, 0.1, 100),
    }
    candidates: dict[str, dict[str, object]] = {}
    for family, values in metrics.items():
        model_sha = (family[0].lower() * 64)[:64]
        calibrator_sha = (family[-1].lower() * 64)[:64]
        candidates[family] = {
            "family": family,
            "modelName": family.lower(),
            "modelVersion": f"risk-{family.lower()}-test",
            "modelArtifact": {"sha256": model_sha},
            "calibratorArtifact": {"sha256": calibrator_sha},
            "calibratorVersion": f"sha256:{calibrator_sha}",
            "validationMetrics": values.to_dict(),
        }
    decision = select_candidate(metrics, tolerance=0.005)
    winner = str(decision["winner"])
    winner_entry = candidates[winner]
    freeze = {
        "candidates": candidates,
        "selection": {
            **decision,
            "riskModel": {
                "purpose": "RISK",
                "family": winner,
                "modelName": winner.lower(),
                "modelVersion": winner_entry["modelVersion"],
                "artifactSha256": winner_entry["modelArtifact"]["sha256"],
                "calibratorVersion": winner_entry["calibratorVersion"],
            },
            "explainer": {
                "purpose": "EXPLANATION",
                "family": "SHAP",
                "modelName": f"{winner.lower()}-native-shap",
                "modelVersion": f"shap-{winner_entry['modelVersion']}",
                "artifactSha256": winner_entry["modelArtifact"]["sha256"],
                "calibratorVersion": "none",
            },
        },
        "thresholds": {"medium": 0.35, "high": 0.65},
    }

    assert _validate_frozen_selection(freeze) == winner
    freeze["selection"]["winner"] = "CATBOOST"

    with pytest.raises(DataQualityError, match="does not match"):
        _validate_frozen_selection(freeze)
