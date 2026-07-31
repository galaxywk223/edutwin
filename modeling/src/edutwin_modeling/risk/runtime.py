"""Frozen calibrated risk inference and model-native SHAP explanations."""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.risk.model import (
    FEATURE_COLUMNS,
    RiskCalibrator,
    RiskCandidate,
    RiskPreprocessor,
)
from edutwin_modeling.risk.pipeline import (
    _load_candidate,
    _resolve_entry,
    _validate_frozen_selection,
)


@dataclass(frozen=True)
class FrozenRiskRuntime:
    preprocessor: RiskPreprocessor
    candidate: RiskCandidate
    calibrator: RiskCalibrator
    risk_reference: dict[str, Any]
    explanation_reference: dict[str, Any]
    feature_contract_version: str
    medium_threshold: float
    high_threshold: float

    def predict(self, features: Mapping[str, float]) -> tuple[float, str]:
        values = self.preprocessor.vector(features)
        raw = self.candidate.raw_probabilities(values)
        probability = float(self.calibrator.transform(raw)[0])
        band = (
            "HIGH"
            if probability >= self.high_threshold
            else "MEDIUM" if probability >= self.medium_threshold else "LOW"
        )
        return probability, band

    def explain(self, features: Mapping[str, float]) -> tuple[float, float, list[dict[str, Any]]]:
        values = self.preprocessor.vector(features)
        base_value, contributions = self.candidate.shap_values(values)
        output_value = float(base_value + contributions.sum())
        ranked = sorted(
            range(len(FEATURE_COLUMNS)),
            key=lambda index: (-abs(float(contributions[index])), FEATURE_COLUMNS[index]),
        )[:5]
        factors = [
            {
                "rank": rank,
                "featureName": FEATURE_COLUMNS[index],
                "rawValue": float(features[FEATURE_COLUMNS[index]]),
                "direction": (
                    "INCREASES_RISK"
                    if float(contributions[index]) >= 0.0
                    else "DECREASES_RISK"
                ),
                "contribution": float(contributions[index]),
                "baseValue": float(base_value),
                "outputUnit": "LOG_ODDS",
                "riskModelVersion": str(self.risk_reference["modelVersion"]),
            }
            for rank, index in enumerate(ranked, start=1)
        ]
        return float(base_value), output_value, factors


def load_frozen_risk_runtime(
    freeze_manifest_path: Path,
    paths: ProjectPaths,
    *,
    deployment_mode: str = "active",
) -> FrozenRiskRuntime:
    try:
        freeze = json.loads(freeze_manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DataQualityError("failed to read risk freeze manifest") from exc
    if not isinstance(freeze, dict) or freeze.get("status") != "FROZEN":
        raise DataQualityError("risk freeze manifest is not frozen")
    if freeze.get("featureContractVersion") != "oulad-d0-29-v1":
        raise DataQualityError("risk freeze feature contract differs")
    selection = freeze.get("selection")
    candidates = freeze.get("candidates")
    thresholds = freeze.get("thresholds")
    if (
        not isinstance(selection, dict)
        or not isinstance(candidates, dict)
        or not isinstance(thresholds, dict)
    ):
        raise DataQualityError("risk freeze serving references are incomplete")
    winner = _validate_frozen_selection(freeze)
    risk_reference = selection.get("riskModel")
    explanation_reference = selection.get("explainer")
    if (
        not isinstance(risk_reference, dict)
        or not isinstance(explanation_reference, dict)
    ):
        raise DataQualityError("risk freeze selection is invalid")
    winner = _deployment_family(winner, deployment_mode)
    if deployment_mode == "rollback":
        rollback_entry = candidates.get(winner)
        if not isinstance(rollback_entry, dict):
            raise DataQualityError("risk rollback candidate reference is missing")
        try:
            risk_reference = {
                "purpose": "RISK",
                "family": rollback_entry["family"],
                "modelName": rollback_entry["modelName"],
                "modelVersion": rollback_entry["modelVersion"],
                "artifactSha256": rollback_entry["modelArtifact"]["sha256"],
                "calibratorVersion": rollback_entry["calibratorVersion"],
            }
            explanation_reference = {
                "purpose": "EXPLANATION",
                "family": "SHAP",
                "modelName": f"{rollback_entry['modelName']}-native-shap",
                "modelVersion": f"shap-{rollback_entry['modelVersion']}",
                "artifactSha256": rollback_entry["modelArtifact"]["sha256"],
                "calibratorVersion": "none",
            }
        except (KeyError, TypeError) as exc:
            raise DataQualityError("risk rollback model reference is invalid") from exc
    candidate_entry = candidates.get(winner)
    if not isinstance(candidate_entry, dict):
        raise DataQualityError("risk winner artifact is missing")
    candidate, calibrator = _load_candidate(candidate_entry, paths)
    preprocessor_entry = freeze.get("preprocessor")
    if not isinstance(preprocessor_entry, dict):
        raise DataQualityError("risk preprocessor reference is missing")
    preprocessor_path = _resolve_entry(
        preprocessor_entry, paths, "risk preprocessor"
    )
    preprocessor_value = json.loads(preprocessor_path.read_text(encoding="utf-8"))
    if not isinstance(preprocessor_value, dict):
        raise DataQualityError("risk preprocessor artifact is invalid")
    return FrozenRiskRuntime(
        preprocessor=RiskPreprocessor.from_dict(preprocessor_value),
        candidate=candidate,
        calibrator=calibrator,
        risk_reference=risk_reference,
        explanation_reference=explanation_reference,
        feature_contract_version="oulad-d0-29-v1",
        medium_threshold=float(thresholds["medium"]),
        high_threshold=float(thresholds["high"]),
    )


def _deployment_family(winner: str, deployment_mode: str) -> str:
    if deployment_mode == "active":
        return winner
    if deployment_mode != "rollback":
        raise DataQualityError("risk deployment mode must be active or rollback")
    return next(
        (
            family
            for family in ("LOGISTIC_REGRESSION", "LIGHTGBM", "CATBOOST")
            if family != winner
        ),
        "",
    )
