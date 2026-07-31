from __future__ import annotations

import numpy as np
import pytest

from edutwin_modeling.knowledge.data import (
    KnowledgeSequence,
    KnowledgeVocabulary,
)
from edutwin_modeling.knowledge.statistical import BKTModel, IRTModel


def _vocabulary() -> KnowledgeVocabulary:
    return KnowledgeVocabulary(
        problem_to_index={"<PAD>": 0, "<UNKNOWN>": 1, "p1": 2, "p2": 3},
        skill_to_index={"<PAD>": 0, "<UNKNOWN>": 1, "s1": 2},
    )


def _sequences() -> tuple[KnowledgeSequence, ...]:
    outcomes = ((0, 1, 1, 0), (1, 0, 1, 0), (0, 0, 1, 1), (1, 1, 0, 0))
    return tuple(
        KnowledgeSequence(
            student_key=f"student-{index}",
            split="train",
            role="fit",
            event_keys=tuple(f"event-{index}-{position}" for position in range(4)),
            problem_indices=(2, 3, 2, 3),
            skill_indices=((2,), (2,), (2,), (2,)),
            correct=values,
        )
        for index, values in enumerate(outcomes)
    )


def test_bkt_em_trains_parameters_and_correct_history_increases_mastery() -> None:
    model = BKTModel.fit(
        _sequences(),
        _vocabulary(),
        {
            "em_iterations": 4,
            "tolerance": 1e-8,
            "minimum_skill_observations": 1,
            "shrinkage": 1.0,
        },
        seed=42,
    )
    assert model.training_log
    assert 2 in model.skill_parameters
    assert model.mastery_after(2, (1, 1)) > model.mastery_after(2, (0, 0))


def test_irt_fits_two_parameter_artifact_and_predicts_unseen_history() -> None:
    model = IRTModel.fit(
        _sequences(),
        _vocabulary(),
        {
            "epochs": 2,
            "batch_size": 8,
            "learning_rate": 0.02,
            "regularization": 0.0001,
            "ability_prior_variance": 1.0,
        },
        seed=42,
    )
    prediction = model.predict(
        _sequences()[:1], warmup_repeats=0, timed_repeats=1
    )
    assert model.training_log
    assert len(model.discrimination) == _vocabulary().problem_count
    assert np.isfinite(prediction.probabilities).all()
    assert len(prediction.probabilities) == 3


def test_vectorized_irt_ability_matches_scalar_newton_reference() -> None:
    model = IRTModel(
        discrimination=np.asarray([1.0, 1.0, 0.8, 1.4]),
        difficulty=np.asarray([0.0, 0.0, -0.3, 0.6]),
        ability_prior_variance=1.0,
        training_log=[],
    )
    problems = (2, 3, 2, 3)
    outcomes = (1, 0, 1, 1)
    ability = 0.0
    for _ in range(8):
        gradient = -ability
        hessian = -1.0
        for problem, outcome in zip(problems, outcomes, strict=True):
            discrimination, difficulty = model._item_parameters(problem)
            logit = discrimination * (ability - difficulty)
            probability = 1.0 / (1.0 + np.exp(-logit))
            gradient += discrimination * (outcome - probability)
            hessian -= discrimination**2 * probability * (1.0 - probability)
        step = gradient / hessian
        ability = float(np.clip(ability - step, -6.0, 6.0))
        if abs(step) < 1e-7:
            break

    assert model._estimate_ability(problems, outcomes) == pytest.approx(
        ability, abs=1e-12
    )
