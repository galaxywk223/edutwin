"""Trainable IRT and BKT knowledge candidates with online-history inference."""

from __future__ import annotations

import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import numpy as np
import torch
from torch import nn
from torch.nn import functional as functional

from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.knowledge.data import KnowledgeSequence, KnowledgeVocabulary
from edutwin_modeling.knowledge.metrics import PredictionResult
from edutwin_modeling.manifest import write_canonical_json


def _sigmoid(value: np.ndarray | float) -> np.ndarray | float:
    array = np.asarray(value, dtype="float64")
    result = np.empty_like(array)
    positive = array >= 0.0
    result[positive] = 1.0 / (1.0 + np.exp(-array[positive]))
    negative_exp = np.exp(array[~positive])
    result[~positive] = negative_exp / (1.0 + negative_exp)
    if np.isscalar(value):
        return float(result)
    return result


def _timed_prediction(
    function: Any,
    *,
    target_count: int,
    warmup_repeats: int,
    timed_repeats: int,
) -> tuple[np.ndarray, float]:
    if target_count < 1 or timed_repeats < 1 or warmup_repeats < 0:
        raise DataQualityError("knowledge latency measurement configuration is invalid")
    for _ in range(warmup_repeats):
        function()
    started = time.perf_counter_ns()
    probabilities = np.empty(0, dtype="float64")
    for _ in range(timed_repeats):
        probabilities = function()
    elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000.0
    return probabilities, elapsed_ms / (timed_repeats * target_count)


class _TorchIrt(nn.Module):
    def __init__(self, user_count: int, problem_count: int) -> None:
        super().__init__()
        self.ability = nn.Embedding(user_count, 1)
        self.difficulty = nn.Embedding(problem_count, 1)
        self.log_discrimination = nn.Embedding(problem_count, 1)
        nn.init.zeros_(self.ability.weight)
        nn.init.zeros_(self.difficulty.weight)
        nn.init.zeros_(self.log_discrimination.weight)

    def forward(self, users: torch.Tensor, problems: torch.Tensor) -> torch.Tensor:
        ability = self.ability(users).squeeze(-1)
        difficulty = self.difficulty(problems).squeeze(-1)
        discrimination = self.log_discrimination(problems).squeeze(-1).exp()
        return discrimination * (ability - difficulty)


@dataclass
class IRTModel:
    """Two-parameter logistic IRT with MAP ability inference for unseen students."""

    discrimination: np.ndarray
    difficulty: np.ndarray
    ability_prior_variance: float
    training_log: list[dict[str, float]]

    family = "IRT"

    @classmethod
    def fit(
        cls,
        sequences: tuple[KnowledgeSequence, ...],
        vocabulary: KnowledgeVocabulary,
        config: dict[str, Any],
        *,
        seed: int,
    ) -> IRTModel:
        if seed != 42:
            raise DataQualityError("IRT seed must remain 42")
        users = sorted(sequence.student_key for sequence in sequences)
        if not users:
            raise DataQualityError("IRT fit received no students")
        user_to_index = {user: index for index, user in enumerate(users)}
        user_indices: list[int] = []
        problem_indices: list[int] = []
        outcomes: list[float] = []
        for sequence in sequences:
            user_index = user_to_index[sequence.student_key]
            user_indices.extend([user_index] * len(sequence.correct))
            problem_indices.extend(sequence.problem_indices)
            outcomes.extend(float(value) for value in sequence.correct)
        if len(set(outcomes)) != 2:
            raise DataQualityError("IRT fit requires both correct and incorrect outcomes")

        epochs = int(config["epochs"])
        batch_size = int(config["batch_size"])
        learning_rate = float(config["learning_rate"])
        regularization = float(config["regularization"])
        prior_variance = float(config["ability_prior_variance"])
        if min(epochs, batch_size) < 1 or min(learning_rate, regularization) <= 0.0:
            raise DataQualityError("IRT training hyperparameters are invalid")
        if prior_variance <= 0.0:
            raise DataQualityError("IRT ability prior variance must be positive")

        torch.manual_seed(seed)
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)
        model = _TorchIrt(len(users), vocabulary.problem_count)
        optimizer = torch.optim.Adam(model.parameters(), lr=learning_rate)
        user_tensor = torch.tensor(user_indices, dtype=torch.long)
        problem_tensor = torch.tensor(problem_indices, dtype=torch.long)
        outcome_tensor = torch.tensor(outcomes, dtype=torch.float32)
        generator = torch.Generator().manual_seed(seed)
        training_log: list[dict[str, float]] = []
        for epoch in range(epochs):
            order = torch.randperm(len(outcome_tensor), generator=generator)
            epoch_loss = 0.0
            for start in range(0, len(order), batch_size):
                batch = order[start : start + batch_size]
                logits = model(user_tensor[batch], problem_tensor[batch])
                data_loss = functional.binary_cross_entropy_with_logits(
                    logits, outcome_tensor[batch]
                )
                penalty = regularization * (
                    model.ability.weight.square().mean()
                    + model.difficulty.weight.square().mean()
                    + model.log_discrimination.weight.square().mean()
                )
                loss = data_loss + penalty
                optimizer.zero_grad(set_to_none=True)
                loss.backward()
                optimizer.step()
                with torch.no_grad():
                    model.log_discrimination.weight.clamp_(-3.0, 3.0)
                    model.difficulty.weight.clamp_(-6.0, 6.0)
                    model.ability.weight.clamp_(-6.0, 6.0)
                epoch_loss += float(loss.detach()) * len(batch)
            training_log.append(
                {"epoch": float(epoch + 1), "loss": epoch_loss / len(outcome_tensor)}
            )
        return cls(
            discrimination=model.log_discrimination.weight.detach()
            .exp()
            .squeeze(-1)
            .cpu()
            .numpy()
            .astype("float64"),
            difficulty=model.difficulty.weight.detach()
            .squeeze(-1)
            .cpu()
            .numpy()
            .astype("float64"),
            ability_prior_variance=prior_variance,
            training_log=training_log,
        )

    def _item_parameters(self, problem_index: int) -> tuple[float, float]:
        if 0 <= problem_index < len(self.difficulty):
            return (
                float(self.discrimination[problem_index]),
                float(self.difficulty[problem_index]),
            )
        return 1.0, 0.0

    def _estimate_ability(
        self, problem_indices: tuple[int, ...], outcomes: tuple[int, ...]
    ) -> float:
        if len(problem_indices) != len(outcomes):
            raise DataQualityError("IRT ability history is misaligned")
        indices = np.asarray(problem_indices, dtype="int64")
        valid = (indices >= 0) & (indices < len(self.difficulty))
        discrimination = np.ones(len(indices), dtype="float64")
        difficulty = np.zeros(len(indices), dtype="float64")
        discrimination[valid] = self.discrimination[indices[valid]]
        difficulty[valid] = self.difficulty[indices[valid]]
        return self._estimate_ability_arrays(
            discrimination,
            difficulty,
            np.asarray(outcomes, dtype="float64"),
        )

    def _estimate_ability_arrays(
        self,
        discrimination: np.ndarray,
        difficulty: np.ndarray,
        outcomes: np.ndarray,
    ) -> float:
        ability = 0.0
        for _ in range(8):
            probability = np.asarray(
                _sigmoid(discrimination * (ability - difficulty)),
                dtype="float64",
            )
            gradient = float(
                -ability / self.ability_prior_variance
                + np.sum(discrimination * (outcomes - probability))
            )
            hessian = float(
                -1.0 / self.ability_prior_variance
                - np.sum(discrimination**2 * probability * (1.0 - probability))
            )
            if hessian == 0.0:
                break
            step = gradient / hessian
            ability = float(np.clip(ability - step, -6.0, 6.0))
            if abs(step) < 1e-7:
                break
        return ability

    def _predict_sequence(self, sequence: KnowledgeSequence) -> np.ndarray:
        if len(sequence.correct) < 2:
            return np.empty(0, dtype="float64")
        indices = np.asarray(sequence.problem_indices, dtype="int64")
        valid = (indices >= 0) & (indices < len(self.difficulty))
        discrimination = np.ones(len(indices), dtype="float64")
        difficulty = np.zeros(len(indices), dtype="float64")
        discrimination[valid] = self.discrimination[indices[valid]]
        difficulty[valid] = self.difficulty[indices[valid]]
        outcomes = np.asarray(sequence.correct, dtype="float64")
        ability = self._estimate_ability_arrays(
            discrimination[:1], difficulty[:1], outcomes[:1]
        )
        predictions: list[float] = []
        for position in range(1, len(sequence.correct)):
            predictions.append(
                float(
                    _sigmoid(
                        discrimination[position]
                        * (ability - difficulty[position])
                    )
                )
            )
            ability = self._estimate_ability_arrays(
                discrimination[: position + 1],
                difficulty[: position + 1],
                outcomes[: position + 1],
            )
        return np.asarray(predictions, dtype="float64")

    def predict(
        self,
        sequences: tuple[KnowledgeSequence, ...],
        *,
        warmup_repeats: int,
        timed_repeats: int,
    ) -> PredictionResult:
        event_keys: list[str] = []
        labels: list[int] = []
        probabilities: list[float] = []
        latencies: list[float] = []
        for sequence in sequences:
            target_count = max(0, len(sequence.correct) - 1)
            if target_count == 0:
                continue
            predicted, latency = _timed_prediction(
                lambda sequence=sequence: self._predict_sequence(sequence),
                target_count=target_count,
                warmup_repeats=warmup_repeats,
                timed_repeats=timed_repeats,
            )
            event_keys.extend(sequence.event_keys[1:])
            labels.extend(sequence.correct[1:])
            probabilities.extend(predicted.tolist())
            latencies.extend([latency] * target_count)
        return PredictionResult(
            event_keys=tuple(event_keys),
            labels=np.asarray(labels, dtype="int8"),
            probabilities=np.asarray(probabilities, dtype="float64"),
            latency_ms=np.asarray(latencies, dtype="float64"),
        )

    def save(self, destination: Path) -> None:
        write_canonical_json(
            {
                "schemaVersion": 1,
                "family": self.family,
                "abilityPriorVariance": self.ability_prior_variance,
                "discrimination": self.discrimination.tolist(),
                "difficulty": self.difficulty.tolist(),
            },
            destination,
        )

    @classmethod
    def load(cls, source: Path) -> IRTModel:
        import json

        value = json.loads(source.read_text(encoding="utf-8"))
        if value.get("family") != cls.family:
            raise DataQualityError("IRT artifact family is invalid")
        return cls(
            discrimination=np.asarray(value["discrimination"], dtype="float64"),
            difficulty=np.asarray(value["difficulty"], dtype="float64"),
            ability_prior_variance=float(value["abilityPriorVariance"]),
            training_log=[],
        )


@dataclass(frozen=True)
class BKTParameters:
    initial_mastery: float
    learn: float
    guess: float
    slip: float

    def clipped(self) -> BKTParameters:
        return BKTParameters(
            initial_mastery=float(np.clip(self.initial_mastery, 0.001, 0.999)),
            learn=float(np.clip(self.learn, 0.001, 0.5)),
            guess=float(np.clip(self.guess, 0.001, 0.499)),
            slip=float(np.clip(self.slip, 0.001, 0.499)),
        )


def _emission(outcomes: np.ndarray, parameters: BKTParameters) -> np.ndarray:
    unmastered = np.where(outcomes == 1, parameters.guess, 1.0 - parameters.guess)
    mastered = np.where(outcomes == 1, 1.0 - parameters.slip, parameters.slip)
    return np.column_stack([unmastered, mastered])


def _forward_backward(
    outcomes: np.ndarray, parameters: BKTParameters
) -> tuple[np.ndarray, np.ndarray, float]:
    transition = np.asarray(
        [[1.0 - parameters.learn, parameters.learn], [0.0, 1.0]],
        dtype="float64",
    )
    emissions = _emission(outcomes, parameters)
    alpha = np.zeros((len(outcomes), 2), dtype="float64")
    scales = np.zeros(len(outcomes), dtype="float64")
    alpha[0] = np.asarray(
        [1.0 - parameters.initial_mastery, parameters.initial_mastery]
    ) * emissions[0]
    scales[0] = max(float(alpha[0].sum()), 1e-12)
    alpha[0] /= scales[0]
    for index in range(1, len(outcomes)):
        alpha[index] = (alpha[index - 1] @ transition) * emissions[index]
        scales[index] = max(float(alpha[index].sum()), 1e-12)
        alpha[index] /= scales[index]
    beta = np.ones((len(outcomes), 2), dtype="float64")
    for index in range(len(outcomes) - 2, -1, -1):
        beta[index] = transition @ (emissions[index + 1] * beta[index + 1])
        beta[index] /= scales[index + 1]
    gamma = alpha * beta
    gamma /= np.maximum(gamma.sum(axis=1, keepdims=True), 1e-12)
    xi = np.zeros((max(0, len(outcomes) - 1), 2, 2), dtype="float64")
    for index in range(len(outcomes) - 1):
        value = (
            alpha[index, :, None]
            * transition
            * (emissions[index + 1] * beta[index + 1])[None, :]
        )
        xi[index] = value / max(float(value.sum()), 1e-12)
    return gamma, xi, float(np.log(scales).sum())


def _fit_bkt_em(
    sequences: list[np.ndarray],
    *,
    initial: BKTParameters,
    iterations: int,
    tolerance: float,
) -> tuple[BKTParameters, list[dict[str, float]]]:
    parameters = initial.clipped()
    history: list[dict[str, float]] = []
    previous_likelihood: float | None = None
    for iteration in range(iterations):
        initial_mastery = 0.0
        initial_count = 0
        learn_numerator = 0.0
        learn_denominator = 0.0
        guess_numerator = 0.0
        guess_denominator = 0.0
        slip_numerator = 0.0
        slip_denominator = 0.0
        likelihood = 0.0
        for outcomes in sequences:
            gamma, xi, sequence_likelihood = _forward_backward(outcomes, parameters)
            likelihood += sequence_likelihood
            initial_mastery += float(gamma[0, 1])
            initial_count += 1
            if len(outcomes) > 1:
                learn_numerator += float(xi[:, 0, 1].sum())
                learn_denominator += float(xi[:, 0, :].sum())
            guess_numerator += float((gamma[:, 0] * outcomes).sum())
            guess_denominator += float(gamma[:, 0].sum())
            slip_numerator += float((gamma[:, 1] * (1 - outcomes)).sum())
            slip_denominator += float(gamma[:, 1].sum())
        updated = BKTParameters(
            initial_mastery=initial_mastery / max(initial_count, 1),
            learn=learn_numerator / max(learn_denominator, 1e-12),
            guess=guess_numerator / max(guess_denominator, 1e-12),
            slip=slip_numerator / max(slip_denominator, 1e-12),
        ).clipped()
        history.append(
            {"iteration": float(iteration + 1), "logLikelihood": likelihood}
        )
        parameters = updated
        if previous_likelihood is not None and abs(likelihood - previous_likelihood) < tolerance:
            break
        previous_likelihood = likelihood
    return parameters, history


@dataclass
class BKTModel:
    """Per-skill Bayesian Knowledge Tracing with a trained global fallback."""

    fallback: BKTParameters
    skill_parameters: dict[int, BKTParameters]
    training_log: list[dict[str, float]]

    family = "BKT"

    @classmethod
    def fit(
        cls,
        sequences: tuple[KnowledgeSequence, ...],
        vocabulary: KnowledgeVocabulary,
        config: dict[str, Any],
        *,
        seed: int,
    ) -> BKTModel:
        if seed != 42:
            raise DataQualityError("BKT seed must remain 42")
        iterations = int(config["em_iterations"])
        tolerance = float(config["tolerance"])
        minimum_observations = int(config["minimum_skill_observations"])
        shrinkage = float(config["shrinkage"])
        if iterations < 1 or tolerance <= 0.0 or minimum_observations < 1:
            raise DataQualityError("BKT training hyperparameters are invalid")
        if shrinkage < 0.0:
            raise DataQualityError("BKT shrinkage must be non-negative")
        by_skill_student: dict[int, dict[str, list[int]]] = {}
        for sequence in sequences:
            for skills, outcome in zip(
                sequence.skill_indices, sequence.correct, strict=True
            ):
                for skill in skills:
                    by_skill_student.setdefault(skill, {}).setdefault(
                        sequence.student_key, []
                    ).append(outcome)
        all_sequences = [
            np.asarray(outcomes, dtype="int8")
            for students in by_skill_student.values()
            for outcomes in students.values()
            if outcomes
        ]
        if not all_sequences:
            raise DataQualityError("BKT fit received no skill sequences")
        initial = BKTParameters(0.2, 0.1, 0.2, 0.1)
        fallback, global_log = _fit_bkt_em(
            all_sequences,
            initial=initial,
            iterations=iterations,
            tolerance=tolerance,
        )
        skill_parameters: dict[int, BKTParameters] = {}
        training_log = [
            {**entry, "scope": 0.0} for entry in global_log
        ]
        for skill in range(2, vocabulary.skill_count):
            skill_sequences = [
                np.asarray(outcomes, dtype="int8")
                for outcomes in by_skill_student.get(skill, {}).values()
                if outcomes
            ]
            observation_count = sum(len(outcomes) for outcomes in skill_sequences)
            if observation_count < minimum_observations:
                skill_parameters[skill] = fallback
                continue
            fitted, skill_log = _fit_bkt_em(
                skill_sequences,
                initial=fallback,
                iterations=iterations,
                tolerance=tolerance,
            )
            weight = observation_count / (observation_count + shrinkage)
            skill_parameters[skill] = BKTParameters(
                initial_mastery=(
                    weight * fitted.initial_mastery
                    + (1.0 - weight) * fallback.initial_mastery
                ),
                learn=weight * fitted.learn + (1.0 - weight) * fallback.learn,
                guess=weight * fitted.guess + (1.0 - weight) * fallback.guess,
                slip=weight * fitted.slip + (1.0 - weight) * fallback.slip,
            ).clipped()
            training_log.extend(
                {**entry, "scope": float(skill)} for entry in skill_log
            )
        return cls(
            fallback=fallback,
            skill_parameters=skill_parameters,
            training_log=training_log,
        )

    def parameters_for(self, skill_index: int) -> BKTParameters:
        return self.skill_parameters.get(skill_index, self.fallback)

    @staticmethod
    def _correct_probability(mastery: float, parameters: BKTParameters) -> float:
        return mastery * (1.0 - parameters.slip) + (1.0 - mastery) * parameters.guess

    @staticmethod
    def _updated_mastery(
        mastery: float, outcome: int, parameters: BKTParameters
    ) -> float:
        if outcome == 1:
            numerator = mastery * (1.0 - parameters.slip)
            denominator = numerator + (1.0 - mastery) * parameters.guess
        else:
            numerator = mastery * parameters.slip
            denominator = numerator + (1.0 - mastery) * (1.0 - parameters.guess)
        posterior = numerator / max(denominator, 1e-12)
        return float(posterior + (1.0 - posterior) * parameters.learn)

    def mastery_after(
        self, skill_index: int, outcomes: tuple[int, ...]
    ) -> float:
        parameters = self.parameters_for(skill_index)
        mastery = parameters.initial_mastery
        for outcome in outcomes:
            mastery = self._updated_mastery(mastery, outcome, parameters)
        return mastery

    def _predict_sequence(self, sequence: KnowledgeSequence) -> np.ndarray:
        mastery: dict[int, float] = {}
        predictions: list[float] = []
        for position, (skills, outcome) in enumerate(
            zip(sequence.skill_indices, sequence.correct, strict=True)
        ):
            event_probabilities = []
            for skill in skills:
                parameters = self.parameters_for(skill)
                current = mastery.get(skill, parameters.initial_mastery)
                event_probabilities.append(
                    self._correct_probability(current, parameters)
                )
            if position > 0:
                predictions.append(float(np.mean(event_probabilities)))
            for skill in skills:
                parameters = self.parameters_for(skill)
                current = mastery.get(skill, parameters.initial_mastery)
                mastery[skill] = self._updated_mastery(current, outcome, parameters)
        return np.asarray(predictions, dtype="float64")

    def predict(
        self,
        sequences: tuple[KnowledgeSequence, ...],
        *,
        warmup_repeats: int,
        timed_repeats: int,
    ) -> PredictionResult:
        event_keys: list[str] = []
        labels: list[int] = []
        probabilities: list[float] = []
        latencies: list[float] = []
        for sequence in sequences:
            target_count = max(0, len(sequence.correct) - 1)
            if target_count == 0:
                continue
            predicted, latency = _timed_prediction(
                lambda sequence=sequence: self._predict_sequence(sequence),
                target_count=target_count,
                warmup_repeats=warmup_repeats,
                timed_repeats=timed_repeats,
            )
            event_keys.extend(sequence.event_keys[1:])
            labels.extend(sequence.correct[1:])
            probabilities.extend(predicted.tolist())
            latencies.extend([latency] * target_count)
        return PredictionResult(
            event_keys=tuple(event_keys),
            labels=np.asarray(labels, dtype="int8"),
            probabilities=np.asarray(probabilities, dtype="float64"),
            latency_ms=np.asarray(latencies, dtype="float64"),
        )

    def save(self, destination: Path) -> None:
        write_canonical_json(
            {
                "schemaVersion": 1,
                "family": self.family,
                "fallback": asdict(self.fallback),
                "skills": {
                    str(skill): asdict(parameters)
                    for skill, parameters in sorted(self.skill_parameters.items())
                },
            },
            destination,
        )

    @classmethod
    def load(cls, source: Path) -> BKTModel:
        import json

        value = json.loads(source.read_text(encoding="utf-8"))
        if value.get("family") != cls.family:
            raise DataQualityError("BKT artifact family is invalid")
        return cls(
            fallback=BKTParameters(**value["fallback"]).clipped(),
            skill_parameters={
                int(skill): BKTParameters(**parameters).clipped()
                for skill, parameters in value["skills"].items()
            },
            training_log=[],
        )
