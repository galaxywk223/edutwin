"""Load frozen mastery and next-correct artifacts for CPU serving."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np

from edutwin_modeling.config import ProjectPaths
from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.knowledge.data import KnowledgeVocabulary
from edutwin_modeling.knowledge.metrics import PlattCalibrator
from edutwin_modeling.knowledge.neural import NeuralKnowledgeModel
from edutwin_modeling.knowledge.pipeline import (
    _load_frozen_candidate,
    _resolve_entry,
)
from edutwin_modeling.knowledge.statistical import BKTModel


@dataclass(frozen=True)
class KnowledgeRuntimePrediction:
    mastery: dict[str, float]
    next_correct_probability: float
    mastery_model_version: str
    next_model_version: str
    next_calibrator_version: str


@dataclass(frozen=True)
class FrozenKnowledgeRuntime:
    """BKT mastery plus the validation-selected DKT or AKT next predictor."""

    vocabulary: KnowledgeVocabulary
    mastery_model: BKTModel
    next_model: NeuralKnowledgeModel
    next_calibrator: PlattCalibrator
    mastery_reference: dict[str, Any]
    next_reference: dict[str, Any]

    def predict(
        self,
        interactions: tuple[tuple[str, tuple[str, ...], int], ...],
        target_question_key: str,
        target_skill_keys: tuple[str, ...],
    ) -> KnowledgeRuntimePrediction:
        if not interactions:
            raise DataQualityError("knowledge inference requires observed interactions")
        if not target_question_key or not target_skill_keys:
            raise DataQualityError("knowledge inference requires a target question and skills")
        if any(
            not question or outcome not in {0, 1} or not skills
            for question, skills, outcome in interactions
        ):
            raise DataQualityError("knowledge inference interactions are invalid")
        encoded_problems = tuple(
            self.vocabulary.problem_index(question) for question, _, _ in interactions
        )
        encoded_history = tuple(
            tuple(self.vocabulary.skill_index(skill) for skill in skills)
            for _, skills, _ in interactions
        )
        outcomes = tuple(outcome for _, _, outcome in interactions)
        target_problem_index = self.vocabulary.problem_index(target_question_key)
        target_indices = tuple(
            self.vocabulary.skill_index(skill) for skill in target_skill_keys
        )
        mastery: dict[str, float] = {}
        for target_key, target_index in zip(
            target_skill_keys, target_indices, strict=True
        ):
            target_outcomes = tuple(
                outcome
                for encoded_skills, outcome in zip(
                    encoded_history, outcomes, strict=True
                )
                if target_index in encoded_skills
            )
            mastery[target_key] = self.mastery_model.mastery_after(
                target_index, target_outcomes
            )
        raw_probability = self.next_model.predict_next(
            encoded_problems,
            encoded_history,
            outcomes,
            target_problem_index,
            target_indices,
        )
        calibrated = float(
            self.next_calibrator.transform(
                np.asarray([raw_probability], dtype="float64")
            )[0]
        )
        return KnowledgeRuntimePrediction(
            mastery=mastery,
            next_correct_probability=calibrated,
            mastery_model_version=str(self.mastery_reference["modelVersion"]),
            next_model_version=str(self.next_reference["modelVersion"]),
            next_calibrator_version=str(self.next_reference["calibratorVersion"]),
        )


def load_frozen_knowledge_runtime(
    freeze_manifest_path: Path,
    paths: ProjectPaths,
    *,
    deployment_mode: str = "active",
) -> FrozenKnowledgeRuntime:
    """Verify and load the two serving roles from a frozen knowledge manifest."""

    try:
        freeze = json.loads(freeze_manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DataQualityError(
            f"failed to read knowledge freeze manifest: {freeze_manifest_path}"
        ) from exc
    if not isinstance(freeze, dict) or freeze.get("status") != "FROZEN":
        raise DataQualityError("knowledge freeze manifest is not frozen")
    candidates = freeze.get("candidates")
    selection = freeze.get("selection")
    vocabulary_entry = freeze.get("vocabulary")
    if (
        not isinstance(candidates, dict)
        or not isinstance(selection, dict)
        or not isinstance(vocabulary_entry, dict)
    ):
        raise DataQualityError("knowledge freeze serving references are incomplete")
    mastery_reference = selection.get("masteryEstimator")
    next_reference = selection.get("nextCorrectPredictor")
    if not isinstance(mastery_reference, dict) or not isinstance(next_reference, dict):
        raise DataQualityError("knowledge freeze selected model references are missing")
    if mastery_reference.get("family") != "BKT":
        raise DataQualityError("knowledge mastery estimator must be BKT")
    next_family = str(next_reference.get("family"))
    if next_family not in {"DKT", "AKT"}:
        raise DataQualityError("knowledge next predictor must be DKT or AKT")
    if deployment_mode == "rollback":
        next_family = "AKT" if next_family == "DKT" else "DKT"
        rollback_reference = candidates.get(next_family)
        if not isinstance(rollback_reference, dict):
            raise DataQualityError("knowledge rollback predictor reference is missing")
        next_reference = rollback_reference
    elif deployment_mode != "active":
        raise DataQualityError("knowledge deployment mode must be active or rollback")
    vocabulary_path = _resolve_entry(
        vocabulary_entry, paths, context="knowledge serving vocabulary"
    )
    vocabulary_value = json.loads(vocabulary_path.read_text(encoding="utf-8"))
    if not isinstance(vocabulary_value, dict):
        raise DataQualityError("knowledge serving vocabulary is invalid")
    vocabulary = KnowledgeVocabulary.from_dict(vocabulary_value)
    mastery_loaded, _ = _load_frozen_candidate(
        "BKT", candidates["BKT"], paths
    )
    next_loaded, next_calibrator = _load_frozen_candidate(
        next_family, candidates[next_family], paths
    )
    if not isinstance(mastery_loaded, BKTModel) or not isinstance(
        next_loaded, NeuralKnowledgeModel
    ):
        raise DataQualityError("knowledge serving artifact types are invalid")
    return FrozenKnowledgeRuntime(
        vocabulary=vocabulary,
        mastery_model=mastery_loaded,
        next_model=next_loaded,
        next_calibrator=next_calibrator,
        mastery_reference=mastery_reference,
        next_reference=next_reference,
    )
