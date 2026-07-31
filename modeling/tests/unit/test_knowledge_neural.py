from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest
import torch

from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.knowledge.data import KnowledgeSequence, SequenceWindow
from edutwin_modeling.knowledge.neural import (
    AKTNetwork,
    MonotonicMultiheadAttention,
    NeuralKnowledgeModel,
    _network,
    _resolve_training_device,
    _window_batch,
)


def _model_configs() -> tuple[tuple[str, dict[str, Any]], ...]:
    common: dict[str, Any] = {
        "sequence_context_contract": "rolling-prefix-max-history-v1",
        "latency_contract": "single-target-predict-next-v1",
        "problem_count": 6,
        "skill_count": 5,
        "embedding_size": 4,
        "hidden_size": 8,
        "layers": 1,
        "dropout": 0.0,
        "max_sequence_length": 8,
    }
    return (
        ("DKT", {**common, "architecture_version": "dkt-lstm-v1"}),
        (
            "AKT",
            {
                **common,
                "architecture_version": "akt-question-interaction-monotonic-v2",
                "heads": 2,
                "feedforward_size": 16,
            },
        ),
    )


def test_neural_artifact_is_byte_stable_and_round_trips(tmp_path: Path) -> None:
    for family, model_config in _model_configs():
        torch.manual_seed(42)
        model = NeuralKnowledgeModel(
            family=family,
            network=_network(family, model_config),
            model_config=model_config,
            training_log=[],
        )
        first = tmp_path / f"{family.lower()}-first.pt"
        second = tmp_path / f"{family.lower()}-second.pt"

        model.save(first)
        model.save(second)

        assert first.read_bytes() == second.read_bytes()
        loaded = NeuralKnowledgeModel.load(family, first, model_config)
        for name, expected in model.network.state_dict().items():
            assert torch.equal(loaded.network.state_dict()[name], expected)


def test_window_batch_preserves_history_and_target_problem_streams() -> None:
    batch = _window_batch(
        [
            SequenceWindow(
                event_keys=("event-1", "event-2", "event-3"),
                problem_indices=(2, 3, 4),
                skill_indices=((2,), (3, 4), (2,)),
                correct=(1, 0, 1),
            )
        ]
    )

    assert batch.history_problems.tolist() == [[2, 3]]
    assert batch.target_problems.tolist() == [[3, 4]]


def test_cuda_training_requires_an_available_cuda_runtime(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(torch.cuda, "is_available", lambda: False)

    with pytest.raises(DataQualityError, match="CUDA is unavailable"):
        _resolve_training_device("cuda")


def test_window_batch_moves_every_tensor_to_the_training_device() -> None:
    batch = _window_batch(
        [
            SequenceWindow(
                event_keys=("event-1", "event-2"),
                problem_indices=(2, 3),
                skill_indices=((2,), (3,)),
                correct=(1, 0),
            )
        ]
    ).to(torch.device("cpu"))

    assert all(
        getattr(batch, field).device.type == "cpu"
        for field in batch.__dataclass_fields__
    )


def test_monotonic_attention_is_causal_and_penalizes_older_equal_content() -> None:
    attention = MonotonicMultiheadAttention(hidden_size=4, heads=1, dropout=0.0)
    with torch.no_grad():
        attention.query_projection.weight.zero_()
        attention.key_projection.weight.zero_()
    values = torch.zeros((1, 3, 4), dtype=torch.float32)
    result = attention(
        values,
        values,
        values,
        padding_mask=torch.zeros((1, 3), dtype=torch.bool),
        need_weights=True,
    )

    assert isinstance(result, tuple)
    _, weights = result
    assert torch.equal(weights[0, 0].triu(diagonal=1), torch.zeros((3, 3)).triu(1))
    final_query = weights[0, 0, 2]
    assert final_query[0] < final_query[1] < final_query[2]


def test_akt_output_is_conditioned_by_target_question_and_skill() -> None:
    torch.manual_seed(42)
    network = AKTNetwork(
        problem_count=6,
        skill_count=5,
        embedding_size=4,
        hidden_size=8,
        heads=2,
        layers=1,
        feedforward_size=16,
        dropout=0.0,
        max_sequence_length=4,
    )
    network.eval()
    with torch.no_grad():
        network.problem_difficulty.weight[2].zero_()
        network.problem_difficulty.weight[3].fill_(1.0)
        network.skill_difficulty_variation.weight[2].fill_(1.0)
        outputs = network(
            torch.tensor([[2], [2], [2]]),
            torch.tensor([[[2]], [[2]], [[2]]]),
            torch.tensor([[1], [1], [1]]),
            torch.tensor([[2], [3], [2]]),
            torch.tensor([[[2]], [[2]], [[3]]]),
            torch.zeros((3, 1), dtype=torch.bool),
        )

    assert not torch.isclose(outputs[0, 0], outputs[1, 0])
    assert not torch.isclose(outputs[0, 0], outputs[2, 0])


def _long_sequence() -> KnowledgeSequence:
    return KnowledgeSequence(
        student_key="student-1",
        split="validation",
        role="validation",
        event_keys=tuple(f"event-{index}" for index in range(6)),
        problem_indices=(2, 3, 4, 2, 3, 4),
        skill_indices=((2,), (3,), (2, 3), (2,), (3,), (2, 3)),
        correct=(1, 0, 1, 1, 0, 1),
    )


def test_offline_prediction_uses_the_same_rolling_context_as_online_serving() -> None:
    torch.manual_seed(42)
    family, model_config = _model_configs()[0]
    model_config = {**model_config, "max_sequence_length": 4}
    model = NeuralKnowledgeModel(
        family=family,
        network=_network(family, model_config),
        model_config=model_config,
        training_log=[],
    )
    sequence = _long_sequence()
    windows = list(model._serving_windows((sequence,), max_sequence_length=4))
    assert [len(window.correct) for _, _, window in windows] == [2, 3, 4, 4, 4]
    assert windows[-1][2].event_keys == ("event-2", "event-3", "event-4", "event-5")

    offline = model.predict(
        (sequence,),
        max_sequence_length=4,
        warmup_repeats=0,
        timed_repeats=1,
    )
    online = [
        model.predict_next(
            sequence.problem_indices[:position],
            sequence.skill_indices[:position],
            sequence.correct[:position],
            sequence.problem_indices[position],
            sequence.skill_indices[position],
        )
        for position in range(1, len(sequence.correct))
    ]

    assert offline.event_keys == sequence.event_keys[1:]
    assert offline.probabilities.tolist() == pytest.approx(online)


def test_offline_latency_times_each_serving_equivalent_target(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    family, model_config = _model_configs()[0]
    model = NeuralKnowledgeModel(
        family=family,
        network=_network(family, model_config),
        model_config=model_config,
        training_log=[],
    )
    clock = iter(
        (
            0,
            2_000_000,
            2_000_000,
            6_000_000,
            6_000_000,
            12_000_000,
            12_000_000,
            20_000_000,
            20_000_000,
            30_000_000,
        )
    )
    monkeypatch.setattr(
        "edutwin_modeling.knowledge.neural.time.perf_counter_ns",
        lambda: next(clock),
    )
    monkeypatch.setattr(model, "_predict_serving_window", lambda window: 0.5)

    prediction = model.predict(
        (_long_sequence(),),
        max_sequence_length=8,
        warmup_repeats=0,
        timed_repeats=1,
    )

    assert prediction.latency_ms.tolist() == [2.0, 4.0, 6.0, 8.0, 10.0]


def test_evaluation_rejects_a_different_maximum_sequence_contract() -> None:
    family, model_config = _model_configs()[0]
    model = NeuralKnowledgeModel(
        family=family,
        network=_network(family, model_config),
        model_config=model_config,
        training_log=[],
    )

    with pytest.raises(DataQualityError, match="frozen contract"):
        model.predict(
            (_long_sequence(),),
            max_sequence_length=7,
            warmup_repeats=0,
            timed_repeats=1,
        )
