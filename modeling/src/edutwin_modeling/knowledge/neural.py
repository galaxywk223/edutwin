"""Trainable DKT and attentive knowledge-tracing candidates."""

from __future__ import annotations

import hashlib
import io
import json
import os
import time
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from zipfile import ZIP_STORED, BadZipFile, ZipFile, ZipInfo

import numpy as np
import torch
from torch import nn
from torch.nn import functional as functional

from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.knowledge.data import (
    KnowledgeSequence,
    KnowledgeVocabulary,
    SequenceWindow,
    sequence_windows,
)
from edutwin_modeling.knowledge.metrics import PredictionResult

_STATE_ARCHIVE_FORMAT = "edutwin-torch-state-npy-v1"
_STATE_ARCHIVE_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def _write_archive_entry(archive: ZipFile, name: str, payload: bytes) -> None:
    entry = ZipInfo(name, date_time=_STATE_ARCHIVE_TIMESTAMP)
    entry.compress_type = ZIP_STORED
    entry.create_system = 3
    entry.external_attr = 0o600 << 16
    archive.writestr(entry, payload)


def _save_state_archive(state_dict: dict[str, torch.Tensor], destination: Path) -> None:
    tensors: list[tuple[dict[str, Any], np.ndarray]] = []
    for index, (name, tensor) in enumerate(sorted(state_dict.items())):
        array = tensor.detach().cpu().contiguous().numpy()
        tensors.append(
            (
                {
                    "name": name,
                    "path": f"tensors/{index:06d}.npy",
                    "dtype": array.dtype.str,
                    "shape": list(array.shape),
                },
                array,
            )
        )
    manifest = {
        "schemaVersion": 1,
        "format": _STATE_ARCHIVE_FORMAT,
        "tensors": [metadata for metadata, _ in tensors],
    }
    manifest_bytes = (
        json.dumps(
            manifest,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")
    with ZipFile(destination, mode="w", compression=ZIP_STORED) as archive:
        _write_archive_entry(archive, "manifest.json", manifest_bytes)
        for metadata, array in tensors:
            buffer = io.BytesIO()
            np.save(buffer, array, allow_pickle=False)
            _write_archive_entry(archive, str(metadata["path"]), buffer.getvalue())


def _load_state_archive(source: Path) -> dict[str, torch.Tensor]:
    try:
        with ZipFile(source, mode="r") as archive:
            archive_names = archive.namelist()
            if len(archive_names) != len(set(archive_names)):
                raise DataQualityError("neural artifact contains duplicate archive entries")
            manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
            if (
                not isinstance(manifest, dict)
                or manifest.get("format") != _STATE_ARCHIVE_FORMAT
                or manifest.get("schemaVersion") != 1
                or not isinstance(manifest.get("tensors"), list)
            ):
                raise DataQualityError("neural artifact state manifest is invalid")
            tensors: dict[str, torch.Tensor] = {}
            expected_entries = {"manifest.json"}
            for index, metadata in enumerate(manifest["tensors"]):
                if not isinstance(metadata, dict):
                    raise DataQualityError("neural artifact tensor metadata is invalid")
                name = metadata.get("name")
                tensor_path = metadata.get("path")
                expected_path = f"tensors/{index:06d}.npy"
                if (
                    not isinstance(name, str)
                    or not name
                    or name in tensors
                    or tensor_path != expected_path
                    or not isinstance(metadata.get("dtype"), str)
                    or not isinstance(metadata.get("shape"), list)
                ):
                    raise DataQualityError("neural artifact tensor metadata is invalid")
                expected_entries.add(expected_path)
                array = np.load(
                    io.BytesIO(archive.read(expected_path)), allow_pickle=False
                )
                if (
                    not isinstance(array, np.ndarray)
                    or array.dtype.str != metadata["dtype"]
                    or list(array.shape) != metadata["shape"]
                ):
                    raise DataQualityError("neural artifact tensor payload is invalid")
                tensors[name] = torch.from_numpy(array.copy())
            if set(archive_names) != expected_entries:
                raise DataQualityError("neural artifact contains unexpected archive entries")
            return tensors
    except DataQualityError:
        raise
    except (
        BadZipFile,
        KeyError,
        OSError,
        TypeError,
        UnicodeDecodeError,
        ValueError,
        json.JSONDecodeError,
    ) as exc:
        raise DataQualityError(f"failed to read neural artifact: {source}") from exc


def _mean_skill_embedding(
    embedding: nn.Embedding, skill_indices: torch.Tensor
) -> torch.Tensor:
    values = embedding(skill_indices)
    mask = skill_indices.ne(0).unsqueeze(-1)
    summed = (values * mask).sum(dim=-2)
    denominator = mask.sum(dim=-2).clamp_min(1)
    return summed / denominator


class DKTNetwork(nn.Module):
    """LSTM DKT predicting each next event's mean target-skill logit."""

    def __init__(
        self,
        *,
        skill_count: int,
        embedding_size: int,
        hidden_size: int,
        layers: int,
        dropout: float,
    ) -> None:
        super().__init__()
        self.skill_embedding = nn.Embedding(
            skill_count, embedding_size, padding_idx=0
        )
        self.outcome_embedding = nn.Embedding(2, embedding_size)
        self.recurrent = nn.LSTM(
            input_size=embedding_size * 2,
            hidden_size=hidden_size,
            num_layers=layers,
            dropout=dropout if layers > 1 else 0.0,
            batch_first=True,
        )
        self.output = nn.Linear(hidden_size, skill_count)

    def forward(
        self,
        history_problems: torch.Tensor,
        history_skills: torch.Tensor,
        history_correct: torch.Tensor,
        target_problems: torch.Tensor,
        target_skills: torch.Tensor,
        padding_mask: torch.Tensor,
    ) -> torch.Tensor:
        del history_problems, target_problems, padding_mask
        skill_values = _mean_skill_embedding(self.skill_embedding, history_skills)
        outcome_values = self.outcome_embedding(history_correct)
        hidden, _ = self.recurrent(torch.cat([skill_values, outcome_values], dim=-1))
        all_skill_logits = self.output(hidden)
        target_mask = target_skills.ne(0)
        selected = all_skill_logits.gather(dim=-1, index=target_skills)
        return (selected * target_mask).sum(dim=-1) / target_mask.sum(dim=-1).clamp_min(1)


class MonotonicMultiheadAttention(nn.Module):
    """Causal attention with a learned nonnegative distance penalty per head."""

    def __init__(
        self,
        *,
        hidden_size: int,
        heads: int,
        dropout: float,
    ) -> None:
        super().__init__()
        if hidden_size < 1 or heads < 1 or hidden_size % heads != 0:
            raise DataQualityError(
                "AKT hidden size must be positive and divisible by attention heads"
            )
        self.hidden_size = hidden_size
        self.heads = heads
        self.head_size = hidden_size // heads
        self.dropout = dropout
        self.query_projection = nn.Linear(hidden_size, hidden_size, bias=False)
        self.key_projection = nn.Linear(hidden_size, hidden_size, bias=False)
        self.value_projection = nn.Linear(hidden_size, hidden_size, bias=False)
        self.output_projection = nn.Linear(hidden_size, hidden_size, bias=False)
        self.raw_distance_decay = nn.Parameter(torch.zeros(heads))

    def forward(
        self,
        query: torch.Tensor,
        key: torch.Tensor,
        value: torch.Tensor,
        *,
        padding_mask: torch.Tensor,
        need_weights: bool = False,
    ) -> torch.Tensor | tuple[torch.Tensor, torch.Tensor]:
        if query.ndim != 3 or key.shape != query.shape or value.shape != query.shape:
            raise DataQualityError("AKT attention streams must have equal three-dimensional shapes")
        batch_size, sequence_length, hidden_size = query.shape
        if hidden_size != self.hidden_size or padding_mask.shape != (
            batch_size,
            sequence_length,
        ):
            raise DataQualityError("AKT attention shape does not match its architecture contract")

        def project(values: torch.Tensor, projection: nn.Linear) -> torch.Tensor:
            return (
                projection(values)
                .reshape(batch_size, sequence_length, self.heads, self.head_size)
                .transpose(1, 2)
            )

        queries = project(query, self.query_projection)
        keys = project(key, self.key_projection)
        values = project(value, self.value_projection)
        scores = torch.matmul(queries, keys.transpose(-2, -1)) / self.head_size**0.5

        positions = torch.arange(sequence_length, device=query.device)
        distance = (positions[:, None] - positions[None, :]).clamp_min(0).to(
            dtype=scores.dtype
        )
        decay = functional.softplus(self.raw_distance_decay).view(1, self.heads, 1, 1)
        scores = scores - decay * distance.view(1, 1, sequence_length, sequence_length)
        future = positions[None, :] > positions[:, None]
        scores = scores.masked_fill(future.view(1, 1, sequence_length, sequence_length), -torch.inf)
        scores = scores.masked_fill(padding_mask[:, None, None, :], -torch.inf)

        weights = torch.softmax(scores, dim=-1)
        weights = torch.nan_to_num(weights, nan=0.0)
        dropped_weights = functional.dropout(
            weights,
            p=self.dropout,
            training=self.training,
        )
        attended = torch.matmul(dropped_weights, values)
        attended = attended.transpose(1, 2).reshape(
            batch_size, sequence_length, hidden_size
        )
        result = self.output_projection(attended)
        result = result.masked_fill(padding_mask.unsqueeze(-1), 0.0)
        if need_weights:
            return result, weights
        return result


class _AKTStreamBlock(nn.Module):
    def __init__(
        self,
        *,
        hidden_size: int,
        heads: int,
        feedforward_size: int,
        dropout: float,
    ) -> None:
        super().__init__()
        self.attention_norm = nn.LayerNorm(hidden_size)
        self.attention = MonotonicMultiheadAttention(
            hidden_size=hidden_size,
            heads=heads,
            dropout=dropout,
        )
        self.attention_dropout = nn.Dropout(dropout)
        self.feedforward_norm = nn.LayerNorm(hidden_size)
        self.feedforward = nn.Sequential(
            nn.Linear(hidden_size, feedforward_size),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(feedforward_size, hidden_size),
        )
        self.feedforward_dropout = nn.Dropout(dropout)

    def forward(
        self,
        values: torch.Tensor,
        *,
        padding_mask: torch.Tensor,
    ) -> torch.Tensor:
        normalized = self.attention_norm(values)
        attended = self.attention(
            normalized,
            normalized,
            normalized,
            padding_mask=padding_mask,
        )
        if not isinstance(attended, torch.Tensor):
            raise DataQualityError("AKT stream attention returned unexpected weights")
        values = values + self.attention_dropout(attended)
        values = values + self.feedforward_dropout(
            self.feedforward(self.feedforward_norm(values))
        )
        return values.masked_fill(padding_mask.unsqueeze(-1), 0.0)


class _AKTRetrievalBlock(nn.Module):
    def __init__(
        self,
        *,
        hidden_size: int,
        heads: int,
        feedforward_size: int,
        dropout: float,
    ) -> None:
        super().__init__()
        self.query_norm = nn.LayerNorm(hidden_size)
        self.memory_norm = nn.LayerNorm(hidden_size)
        self.attention = MonotonicMultiheadAttention(
            hidden_size=hidden_size,
            heads=heads,
            dropout=dropout,
        )
        self.attention_dropout = nn.Dropout(dropout)
        self.feedforward_norm = nn.LayerNorm(hidden_size)
        self.feedforward = nn.Sequential(
            nn.Linear(hidden_size, feedforward_size),
            nn.GELU(),
            nn.Dropout(dropout),
            nn.Linear(feedforward_size, hidden_size),
        )
        self.feedforward_dropout = nn.Dropout(dropout)

    def forward(
        self,
        questions: torch.Tensor,
        interactions: torch.Tensor,
        *,
        padding_mask: torch.Tensor,
    ) -> torch.Tensor:
        retrieved = self.attention(
            self.query_norm(questions),
            self.memory_norm(interactions),
            self.memory_norm(interactions),
            padding_mask=padding_mask,
        )
        if not isinstance(retrieved, torch.Tensor):
            raise DataQualityError("AKT retrieval attention returned unexpected weights")
        questions = questions + self.attention_dropout(retrieved)
        questions = questions + self.feedforward_dropout(
            self.feedforward(self.feedforward_norm(questions))
        )
        return questions.masked_fill(padding_mask.unsqueeze(-1), 0.0)


class AKTNetwork(nn.Module):
    """Target-conditioned AKT with question, interaction, and retrieval streams."""

    def __init__(
        self,
        *,
        problem_count: int,
        skill_count: int,
        embedding_size: int,
        hidden_size: int,
        heads: int,
        layers: int,
        feedforward_size: int,
        dropout: float,
        max_sequence_length: int,
    ) -> None:
        super().__init__()
        if (
            problem_count < 2
            or skill_count < 2
            or min(embedding_size, hidden_size, heads, layers, feedforward_size) < 1
            or hidden_size % heads != 0
            or not 0.0 <= dropout < 1.0
            or max_sequence_length < 2
        ):
            raise DataQualityError("AKT vocabulary and maximum sequence contracts are invalid")
        self.problem_count = problem_count
        self.skill_count = skill_count
        self.max_sequence_length = max_sequence_length
        self.skill_embedding = nn.Embedding(
            skill_count, embedding_size, padding_idx=0
        )
        self.skill_difficulty_variation = nn.Embedding(
            skill_count, embedding_size, padding_idx=0
        )
        self.outcome_embedding = nn.Embedding(2, embedding_size)
        self.outcome_difficulty_variation = nn.Embedding(2, embedding_size)
        self.problem_difficulty = nn.Embedding(problem_count, 1, padding_idx=0)
        self.question_projection = nn.Linear(embedding_size, hidden_size)
        self.interaction_projection = nn.Linear(embedding_size, hidden_size)
        self.question_blocks = nn.ModuleList(
            _AKTStreamBlock(
                hidden_size=hidden_size,
                heads=heads,
                feedforward_size=feedforward_size,
                dropout=dropout,
            )
            for _ in range(layers)
        )
        self.interaction_blocks = nn.ModuleList(
            _AKTStreamBlock(
                hidden_size=hidden_size,
                heads=heads,
                feedforward_size=feedforward_size,
                dropout=dropout,
            )
            for _ in range(layers)
        )
        self.retrieval_blocks = nn.ModuleList(
            _AKTRetrievalBlock(
                hidden_size=hidden_size,
                heads=heads,
                feedforward_size=feedforward_size,
                dropout=dropout,
            )
            for _ in range(layers)
        )
        self.output_norm = nn.LayerNorm(hidden_size)
        self.output = nn.Linear(hidden_size, 1)
        nn.init.zeros_(self.problem_difficulty.weight)

    def _question_values(
        self,
        problem_indices: torch.Tensor,
        skill_indices: torch.Tensor,
    ) -> torch.Tensor:
        base = _mean_skill_embedding(self.skill_embedding, skill_indices)
        variation = _mean_skill_embedding(
            self.skill_difficulty_variation,
            skill_indices,
        )
        difficulty = self.problem_difficulty(problem_indices)
        return self.question_projection(base + difficulty * variation)

    def forward(
        self,
        history_problems: torch.Tensor,
        history_skills: torch.Tensor,
        history_correct: torch.Tensor,
        target_problems: torch.Tensor,
        target_skills: torch.Tensor,
        padding_mask: torch.Tensor,
    ) -> torch.Tensor:
        if history_correct.shape[1] > self.max_sequence_length - 1:
            raise DataQualityError("AKT input exceeds the frozen maximum sequence length")
        history_questions = self._question_values(history_problems, history_skills)
        target_questions = self._question_values(target_problems, target_skills)
        history_difficulty = self.problem_difficulty(history_problems)
        outcome_values = self.outcome_embedding(history_correct)
        outcome_variation = self.outcome_difficulty_variation(history_correct)
        interactions = history_questions + self.interaction_projection(
            outcome_values + history_difficulty * outcome_variation
        )
        questions = target_questions
        for question_block, interaction_block, retrieval_block in zip(
            self.question_blocks,
            self.interaction_blocks,
            self.retrieval_blocks,
            strict=True,
        ):
            questions = question_block(questions, padding_mask=padding_mask)
            interactions = interaction_block(interactions, padding_mask=padding_mask)
            questions = retrieval_block(
                questions,
                interactions,
                padding_mask=padding_mask,
            )
        return self.output(self.output_norm(questions)).squeeze(-1)


@dataclass(frozen=True)
class _WindowBatch:
    history_problems: torch.Tensor
    history_skills: torch.Tensor
    history_correct: torch.Tensor
    target_problems: torch.Tensor
    target_skills: torch.Tensor
    target_correct: torch.Tensor
    valid_mask: torch.Tensor
    padding_mask: torch.Tensor

    def to(self, device: torch.device) -> _WindowBatch:
        return _WindowBatch(
            **{
                field: getattr(self, field).to(device)
                for field in self.__dataclass_fields__
            }
        )


def _window_batch(windows: list[SequenceWindow]) -> _WindowBatch:
    if not windows:
        raise DataQualityError("neural knowledge batch is empty")
    for window in windows:
        lengths = {
            len(window.event_keys),
            len(window.problem_indices),
            len(window.skill_indices),
            len(window.correct),
        }
        if len(lengths) != 1 or len(window.correct) < 2:
            raise DataQualityError("neural knowledge window is empty or misaligned")
        if any(not skills for skills in window.skill_indices):
            raise DataQualityError("neural knowledge window contains an event without skills")
    max_targets = max(len(window.correct) - 1 for window in windows)
    max_skills = max(
        len(skills)
        for window in windows
        for skills in window.skill_indices
    )
    history_skills = torch.zeros(
        (len(windows), max_targets, max_skills), dtype=torch.long
    )
    target_skills = torch.zeros_like(history_skills)
    history_problems = torch.zeros((len(windows), max_targets), dtype=torch.long)
    target_problems = torch.zeros_like(history_problems)
    history_correct = torch.zeros((len(windows), max_targets), dtype=torch.long)
    target_correct = torch.zeros((len(windows), max_targets), dtype=torch.float32)
    valid_mask = torch.zeros((len(windows), max_targets), dtype=torch.bool)
    for row, window in enumerate(windows):
        targets = len(window.correct) - 1
        valid_mask[row, :targets] = True
        history_problems[row, :targets] = torch.tensor(
            window.problem_indices[:-1], dtype=torch.long
        )
        target_problems[row, :targets] = torch.tensor(
            window.problem_indices[1:], dtype=torch.long
        )
        history_correct[row, :targets] = torch.tensor(
            window.correct[:-1], dtype=torch.long
        )
        target_correct[row, :targets] = torch.tensor(
            window.correct[1:], dtype=torch.float32
        )
        for column, skills in enumerate(window.skill_indices[:-1]):
            history_skills[row, column, : len(skills)] = torch.tensor(
                skills, dtype=torch.long
            )
        for column, skills in enumerate(window.skill_indices[1:]):
            target_skills[row, column, : len(skills)] = torch.tensor(
                skills, dtype=torch.long
            )
    return _WindowBatch(
        history_problems=history_problems,
        history_skills=history_skills,
        history_correct=history_correct,
        target_problems=target_problems,
        target_skills=target_skills,
        target_correct=target_correct,
        valid_mask=valid_mask,
        padding_mask=~valid_mask,
    )


def _network(family: str, model_config: dict[str, Any]) -> nn.Module:
    expected_architecture = {
        "DKT": "dkt-lstm-v1",
        "AKT": "akt-question-interaction-monotonic-v2",
    }.get(family)
    if expected_architecture is None:
        raise DataQualityError(f"unknown neural knowledge family: {family}")
    if (
        model_config.get("architecture_version") != expected_architecture
        or model_config.get("sequence_context_contract")
        != "rolling-prefix-max-history-v1"
        or model_config.get("latency_contract") != "single-target-predict-next-v1"
    ):
        raise DataQualityError(f"{family} frozen neural contract is invalid")
    problem_count = int(model_config["problem_count"])
    skill_count = int(model_config["skill_count"])
    embedding_size = int(model_config["embedding_size"])
    hidden_size = int(model_config["hidden_size"])
    layers = int(model_config["layers"])
    dropout = float(model_config["dropout"])
    max_sequence_length = int(model_config["max_sequence_length"])
    if (
        problem_count < 2
        or skill_count < 2
        or min(embedding_size, hidden_size, layers) < 1
        or not 0.0 <= dropout < 1.0
        or max_sequence_length < 2
    ):
        raise DataQualityError("neural architecture contract is invalid")
    common: dict[str, Any] = {
        "skill_count": skill_count,
        "embedding_size": embedding_size,
        "hidden_size": hidden_size,
        "layers": layers,
        "dropout": dropout,
    }
    if family == "DKT":
        return DKTNetwork(**common)
    if family == "AKT":
        return AKTNetwork(
            **common,
            problem_count=problem_count,
            heads=int(model_config["heads"]),
            feedforward_size=int(model_config["feedforward_size"]),
            max_sequence_length=max_sequence_length,
        )
    raise AssertionError("validated neural family did not construct a network")


def _resolve_training_device(requested: str) -> torch.device:
    if requested not in {"cpu", "cuda"}:
        raise DataQualityError("neural training device must be cpu or cuda")
    if requested == "cuda" and not torch.cuda.is_available():
        raise DataQualityError("CUDA training was requested but CUDA is unavailable")
    return torch.device(requested)


@dataclass
class NeuralKnowledgeModel:
    """Serializable DKT or AKT network plus its fixed architecture contract."""

    family: str
    network: nn.Module
    model_config: dict[str, Any]
    training_log: list[dict[str, float]]

    @classmethod
    def fit(
        cls,
        family: str,
        sequences: tuple[KnowledgeSequence, ...],
        vocabulary: KnowledgeVocabulary,
        config: dict[str, Any],
        *,
        max_sequence_length: int,
        seed: int,
        training_device: str,
    ) -> NeuralKnowledgeModel:
        if family not in {"DKT", "AKT"}:
            raise DataQualityError(f"unsupported neural knowledge family: {family}")
        if seed != 42:
            raise DataQualityError(f"{family} seed must remain 42")
        if max_sequence_length < 2:
            raise DataQualityError("neural maximum sequence length must be at least two")
        model_config: dict[str, Any] = {
            "architecture_version": (
                "akt-question-interaction-monotonic-v2"
                if family == "AKT"
                else "dkt-lstm-v1"
            ),
            "sequence_context_contract": "rolling-prefix-max-history-v1",
            "latency_contract": "single-target-predict-next-v1",
            "problem_count": vocabulary.problem_count,
            "skill_count": vocabulary.skill_count,
            "embedding_size": int(config["embedding_size"]),
            "hidden_size": int(config["hidden_size"]),
            "layers": int(config["layers"]),
            "dropout": float(config["dropout"]),
            "max_sequence_length": max_sequence_length,
        }
        if family == "AKT":
            model_config.update(
                {
                    "heads": int(config["heads"]),
                    "feedforward_size": int(config["feedforward_size"]),
                }
            )
            if (
                model_config["heads"] < 1
                or model_config["feedforward_size"] < 1
                or model_config["hidden_size"] % model_config["heads"] != 0
            ):
                raise DataQualityError("AKT attention and feedforward configuration is invalid")
        epochs = int(config["epochs"])
        batch_size = int(config["batch_size"])
        learning_rate = float(config["learning_rate"])
        weight_decay = float(config["weight_decay"])
        gradient_clip = float(config["gradient_clip"])
        if min(epochs, batch_size) < 1 or learning_rate <= 0.0:
            raise DataQualityError(f"{family} training hyperparameters are invalid")
        if weight_decay < 0.0 or gradient_clip <= 0.0:
            raise DataQualityError(f"{family} regularization is invalid")
        windows = sequence_windows(sequences, max_events=max_sequence_length)
        if not windows:
            raise DataQualityError(f"{family} fit has no sequence windows")

        device = _resolve_training_device(training_device)
        torch.manual_seed(seed)
        torch.set_num_threads(1)
        torch.use_deterministic_algorithms(True)
        if device.type == "cuda":
            os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
            torch.cuda.manual_seed_all(seed)
            torch.backends.cudnn.benchmark = False
            torch.backends.cudnn.deterministic = True
        model_config["training_device"] = device.type
        network = _network(family, model_config).to(device)
        optimizer = torch.optim.AdamW(
            network.parameters(), lr=learning_rate, weight_decay=weight_decay
        )
        generator = torch.Generator().manual_seed(seed)
        training_log: list[dict[str, float]] = []
        network.train()
        for epoch in range(epochs):
            order = torch.randperm(len(windows), generator=generator).tolist()
            loss_sum = 0.0
            event_count = 0
            for start in range(0, len(order), batch_size):
                selected = [windows[index] for index in order[start : start + batch_size]]
                batch = _window_batch(selected).to(device)
                logits = network(
                    batch.history_problems,
                    batch.history_skills,
                    batch.history_correct,
                    batch.target_problems,
                    batch.target_skills,
                    batch.padding_mask,
                )
                loss = functional.binary_cross_entropy_with_logits(
                    logits[batch.valid_mask], batch.target_correct[batch.valid_mask]
                )
                optimizer.zero_grad(set_to_none=True)
                loss.backward()
                nn.utils.clip_grad_norm_(network.parameters(), gradient_clip)
                optimizer.step()
                selected_events = int(batch.valid_mask.sum())
                loss_sum += float(loss.detach()) * selected_events
                event_count += selected_events
            training_log.append(
                {"epoch": float(epoch + 1), "loss": loss_sum / event_count}
            )
        network = network.to("cpu")
        network.eval()
        return cls(
            family=family,
            network=network,
            model_config=model_config,
            training_log=training_log,
        )

    def _predict_window(self, window: SequenceWindow) -> np.ndarray:
        maximum = int(self.model_config["max_sequence_length"])
        if len(window.correct) > maximum:
            raise DataQualityError("neural inference exceeds the frozen maximum sequence length")
        batch = _window_batch([window])
        with torch.inference_mode():
            logits = self.network(
                batch.history_problems,
                batch.history_skills,
                batch.history_correct,
                batch.target_problems,
                batch.target_skills,
                batch.padding_mask,
            )
            return torch.sigmoid(logits[batch.valid_mask]).cpu().numpy().astype("float64")

    def predict_next(
        self,
        history_problem_indices: tuple[int, ...],
        history_skill_indices: tuple[tuple[int, ...], ...],
        history_correct: tuple[int, ...],
        target_problem_index: int,
        target_skill_indices: tuple[int, ...],
    ) -> float:
        """Predict one target event from observed history for online serving."""

        if (
            not history_skill_indices
            or len(history_problem_indices) != len(history_skill_indices)
            or len(history_skill_indices) != len(history_correct)
        ):
            raise DataQualityError("neural knowledge inference history is empty or misaligned")
        problem_count = int(self.model_config["problem_count"])
        skill_count = int(self.model_config["skill_count"])
        if not 1 <= target_problem_index < problem_count or not target_skill_indices:
            raise DataQualityError("neural knowledge inference target is invalid")
        if any(not 1 <= index < problem_count for index in history_problem_indices):
            raise DataQualityError("neural knowledge inference history has invalid problems")
        if any(
            not skills or any(not 1 <= index < skill_count for index in skills)
            for skills in history_skill_indices
        ):
            raise DataQualityError("neural knowledge inference history has invalid skills")
        if any(not 1 <= index < skill_count for index in target_skill_indices):
            raise DataQualityError("neural knowledge inference target has invalid skills")
        if any(outcome not in {0, 1} for outcome in history_correct):
            raise DataQualityError("neural knowledge inference history has invalid outcomes")
        maximum_history = int(self.model_config["max_sequence_length"]) - 1
        retained_problems = history_problem_indices[-maximum_history:]
        retained_skills = history_skill_indices[-maximum_history:]
        retained_correct = history_correct[-maximum_history:]
        event_count = len(retained_correct) + 1
        window = SequenceWindow(
            event_keys=tuple(str(index) for index in range(event_count)),
            problem_indices=(*retained_problems, target_problem_index),
            skill_indices=(*retained_skills, target_skill_indices),
            correct=(*retained_correct, 0),
        )
        return self._predict_target(window)

    def _predict_target(self, window: SequenceWindow) -> float:
        probabilities = self._predict_window(window)
        if len(probabilities) != len(window.correct) - 1:
            raise DataQualityError("neural knowledge inference produced an invalid sequence")
        return float(probabilities[-1])

    def _predict_serving_window(self, window: SequenceWindow) -> float:
        return self.predict_next(
            window.problem_indices[:-1],
            window.skill_indices[:-1],
            window.correct[:-1],
            window.problem_indices[-1],
            window.skill_indices[-1],
        )

    @staticmethod
    def _serving_windows(
        sequences: tuple[KnowledgeSequence, ...],
        *,
        max_sequence_length: int,
    ) -> Iterator[tuple[str, int, SequenceWindow]]:
        maximum_history = max_sequence_length - 1
        for sequence in sequences:
            for target_position in range(1, len(sequence.correct)):
                start = max(0, target_position - maximum_history)
                end = target_position + 1
                yield (
                    sequence.event_keys[target_position],
                    sequence.correct[target_position],
                    SequenceWindow(
                        event_keys=sequence.event_keys[start:end],
                        problem_indices=sequence.problem_indices[start:end],
                        skill_indices=sequence.skill_indices[start:end],
                        correct=sequence.correct[start:end],
                    ),
                )

    def predict(
        self,
        sequences: tuple[KnowledgeSequence, ...],
        *,
        max_sequence_length: int,
        warmup_repeats: int,
        timed_repeats: int,
        evaluation_batch_size: int = 64,
        latency_sample_events: int = 2048,
    ) -> PredictionResult:
        frozen_maximum = int(self.model_config["max_sequence_length"])
        if max_sequence_length != frozen_maximum:
            raise DataQualityError(
                "neural evaluation maximum sequence length differs from the frozen contract"
            )
        if (
            warmup_repeats < 0
            or timed_repeats < 1
            or evaluation_batch_size < 1
            or latency_sample_events < 1
        ):
            raise DataQualityError("neural latency measurement configuration is invalid")
        serving_items = list(
            self._serving_windows(
                sequences,
                max_sequence_length=max_sequence_length,
            )
        )
        if not serving_items:
            raise DataQualityError("neural evaluation has no target events")
        probabilities: list[float] = []
        self.network.eval()
        with torch.inference_mode():
            for start in range(0, len(serving_items), evaluation_batch_size):
                selected = serving_items[start : start + evaluation_batch_size]
                batch = _window_batch([window for _, _, window in selected])
                logits = self.network(
                    batch.history_problems,
                    batch.history_skills,
                    batch.history_correct,
                    batch.target_problems,
                    batch.target_skills,
                    batch.padding_mask,
                )
                final_positions = batch.valid_mask.sum(dim=1).sub(1)
                final_logits = logits.gather(
                    1, final_positions.unsqueeze(1)
                ).squeeze(1)
                probabilities.extend(
                    torch.sigmoid(final_logits)
                    .cpu()
                    .numpy()
                    .astype("float64")
                    .tolist()
                )

        sample_count = min(latency_sample_events, len(serving_items))
        sampled_indices = sorted(
            range(len(serving_items)),
            key=lambda index: (
                hashlib.sha256(serving_items[index][0].encode()).digest(),
                serving_items[index][0],
            ),
        )[:sample_count]
        latencies: list[float] = []
        for index in sampled_indices:
            window = serving_items[index][2]
            for _ in range(warmup_repeats):
                self._predict_serving_window(window)
            started = time.perf_counter_ns()
            for _ in range(timed_repeats):
                self._predict_serving_window(window)
            elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000.0
            latencies.append(elapsed_ms / timed_repeats)
        return PredictionResult(
            event_keys=tuple(item[0] for item in serving_items),
            labels=np.asarray([item[1] for item in serving_items], dtype="int8"),
            probabilities=np.asarray(probabilities, dtype="float64"),
            latency_ms=np.asarray(latencies, dtype="float64"),
        )

    def save(self, destination: Path) -> None:
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(f".{destination.name}.tmp")
        if temporary.exists():
            temporary.unlink()
        try:
            _save_state_archive(dict(self.network.state_dict()), temporary)
            temporary.replace(destination)
        finally:
            if temporary.exists():
                temporary.unlink()

    @classmethod
    def load(
        cls,
        family: str,
        source: Path,
        model_config: dict[str, Any],
    ) -> NeuralKnowledgeModel:
        network = _network(family, model_config)
        state_dict = _load_state_archive(source)
        expected_state = network.state_dict()
        if set(state_dict) != set(expected_state):
            raise DataQualityError(f"{family} artifact state dictionary is incompatible")
        for name, tensor in state_dict.items():
            expected = expected_state[name]
            if tensor.dtype != expected.dtype or tensor.shape != expected.shape:
                raise DataQualityError(f"{family} artifact tensor {name!r} is incompatible")
        network.load_state_dict(state_dict, strict=True)
        network.eval()
        return cls(
            family=family,
            network=network,
            model_config=model_config,
            training_log=[],
        )
