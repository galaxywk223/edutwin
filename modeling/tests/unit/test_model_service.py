"""Contract tests for the frozen FastAPI inference boundary."""

from __future__ import annotations

from dataclasses import replace
from types import SimpleNamespace
from typing import Any, cast

from fastapi.testclient import TestClient

from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.serving.app import create_app
from edutwin_modeling.serving.registry import ArtifactRegistry
from edutwin_modeling.serving.schemas import (
    LoadedArtifact,
    ModelFamily,
    ModelPurpose,
    ModelVersionRef,
)

CORRELATION_ID = "00000000-0000-4000-8000-000000000001"
ANSWER_EVENT_ID = "00000000-0000-4000-8000-000000000002"
ANALYSIS_JOB_ID = "00000000-0000-4000-8000-000000000003"
SNAPSHOT_ID = "00000000-0000-4000-8000-000000000004"
STUDENT_ID = "00000000-0000-4000-8000-000000000005"
COURSE_ID = "00000000-0000-4000-8000-000000000006"
EVENT_ID = "00000000-0000-4000-8000-000000000007"
QUESTION_ID = "00000000-0000-4000-8000-000000000008"
TARGET_QUESTION_ID = "00000000-0000-4000-8000-000000000009"
SKILL_ID = "00000000-0000-4000-8000-000000000010"
ARTIFACT_SHA = "a" * 64
MANIFEST_SHA = "b" * 64
FEATURE_NAMES = (
    "vle_total_clicks",
    "vle_interaction_count",
    "vle_active_days",
    "vle_active_weeks",
    "vle_resource_count",
    "assessment_count",
    "assessment_scored_count",
    "assessment_mean_score",
)


def _model(
    purpose: ModelPurpose,
    family: ModelFamily,
    version: str,
    calibrator: str | None,
) -> ModelVersionRef:
    return ModelVersionRef(
        purpose=purpose,
        family=family,
        model_name=family.value.lower(),
        model_version=version,
        artifact_sha256=ARTIFACT_SHA,
        calibrator_version=calibrator,
    )


MASTERY_MODEL = _model(ModelPurpose.MASTERY, ModelFamily.BKT, "bkt-v1", "bkt-cal-v1")
NEXT_MODEL = _model(ModelPurpose.NEXT_CORRECT, ModelFamily.AKT, "akt-v1", "akt-cal-v1")
RISK_MODEL = _model(
    ModelPurpose.RISK,
    ModelFamily.LIGHTGBM,
    "risk-lightgbm-v1",
    "risk-cal-v1",
)
EXPLANATION_MODEL = _model(
    ModelPurpose.EXPLANATION,
    ModelFamily.SHAP,
    "shap-risk-lightgbm-v1",
    "none",
)


class FakeKnowledgeRuntime:
    def predict(
        self,
        interactions: tuple[tuple[str, tuple[str, ...], int], ...],
        target_question_key: str,
        target_skill_keys: tuple[str, ...],
    ) -> SimpleNamespace:
        assert interactions == ((QUESTION_ID, (SKILL_ID,), 1),)
        assert target_question_key == TARGET_QUESTION_ID
        return SimpleNamespace(
            mastery={target_skill_keys[0]: 0.71},
            next_correct_probability=0.66,
            mastery_model_version=MASTERY_MODEL.model_version,
            next_model_version=NEXT_MODEL.model_version,
            next_calibrator_version=NEXT_MODEL.calibrator_version,
        )


class FakeRiskRuntime:
    medium_threshold = 0.35
    high_threshold = 0.70

    def predict(self, features: dict[str, float]) -> tuple[float, str]:
        self._validate(features)
        return 0.74, "HIGH"

    def explain(
        self, features: dict[str, float]
    ) -> tuple[float, float, list[dict[str, Any]]]:
        self._validate(features)
        base_value = -0.25
        factors = []
        for rank, name in enumerate(FEATURE_NAMES[:5], start=1):
            contribution = float(6 - rank) / 10.0
            factors.append(
                {
                    "rank": rank,
                    "featureName": name,
                    "rawValue": features[name],
                    "direction": "INCREASES_RISK",
                    "contribution": contribution,
                    "baseValue": base_value,
                    "outputUnit": "LOG_ODDS",
                    "riskModelVersion": RISK_MODEL.model_version,
                }
            )
        return base_value, 1.25, factors

    @staticmethod
    def _validate(features: dict[str, float]) -> None:
        if set(features) != set(FEATURE_NAMES):
            raise DataQualityError("risk feature contract mismatch")


class FakeSourceLineage:
    def skill_key(self, skill_id: object) -> str:
        return str(skill_id)

    def problem_key(self, question_id: object) -> str:
        return str(question_id)


class RejectingSourceLineage(FakeSourceLineage):
    def problem_key(self, question_id: object) -> str:
        raise DataQualityError(f"unmapped question: {question_id}")


def _registry() -> ArtifactRegistry:
    models = (MASTERY_MODEL, NEXT_MODEL, RISK_MODEL, EXPLANATION_MODEL)
    return ArtifactRegistry(
        service_version="1.0.0-test",
        knowledge_contract_version="assistments-event-v1",
        risk_contract_version="oulad-d0-29-v1",
        knowledge_runtime=cast(Any, FakeKnowledgeRuntime()),
        risk_runtime=cast(Any, FakeRiskRuntime()),
        mastery_model=MASTERY_MODEL,
        next_model=NEXT_MODEL,
        risk_model=RISK_MODEL,
        explanation_model=EXPLANATION_MODEL,
        loaded_artifacts=tuple(
            LoadedArtifact(model=model, manifest_sha256=MANIFEST_SHA, loaded=True)
            for model in models
        ),
        source_lineage=cast(Any, FakeSourceLineage()),
        lineage_manifest_sha256="c" * 64,
        knowledge_lineage_sha256="d" * 64,
    )


def _trace() -> dict[str, Any]:
    models = [
        model.model_dump(mode="json", by_alias=True)
        for model in (MASTERY_MODEL, NEXT_MODEL, RISK_MODEL, EXPLANATION_MODEL)
    ]
    return {
        "correlationId": CORRELATION_ID,
        "answerEventId": ANSWER_EVENT_ID,
        "analysisJobId": ANALYSIS_JOB_ID,
        "snapshotId": SNAPSHOT_ID,
        "dataVersions": [
            {
                "sourceId": "ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED",
                "version": "assistments-v1",
                "manifestSha256": "c" * 64,
            },
            {
                "sourceId": "OULAD",
                "version": "oulad-v1",
                "manifestSha256": "d" * 64,
            },
        ],
        "requestedModelVersions": models,
        "effectiveModelVersions": [dict(model) for model in models],
    }


def _knowledge_request() -> dict[str, Any]:
    return {
        "trace": _trace(),
        "studentId": STUDENT_ID,
        "courseId": COURSE_ID,
        "interactions": [
            {
                "eventId": EVENT_ID,
                "questionId": QUESTION_ID,
                "skillIds": [SKILL_ID],
                "correct": True,
                "occurredAt": "2026-07-11T01:00:00Z",
                "sequenceNumber": 1,
            }
        ],
        "targetQuestionId": TARGET_QUESTION_ID,
        "targetSkillIds": [SKILL_ID],
        "featureContractVersion": "assistments-event-v1",
    }


def _features() -> list[dict[str, Any]]:
    return [
        {"name": name, "value": float(index + 1)}
        for index, name in enumerate(FEATURE_NAMES)
    ]


def _risk_request() -> dict[str, Any]:
    return {
        "trace": _trace(),
        "studentId": STUDENT_ID,
        "courseId": COURSE_ID,
        "observationWindow": {"startDayInclusive": 0, "endDayInclusive": 29},
        "featureContractVersion": "oulad-d0-29-v1",
        "features": _features(),
    }


def _explain_request() -> dict[str, Any]:
    return {
        "trace": _trace(),
        "studentId": STUDENT_ID,
        "courseId": COURSE_ID,
        "riskModelVersion": RISK_MODEL.model_version,
        "featureContractVersion": "oulad-d0-29-v1",
        "features": _features(),
    }


def test_health_reports_verified_artifacts() -> None:
    with TestClient(create_app(_registry)) as client:
        response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["serviceVersion"] == "1.0.0-test"
    assert len(body["loadedArtifacts"]) == 4
    assert all(artifact["loaded"] for artifact in body["loadedArtifacts"])
    assert body["featureContractVersions"] == [
        "assistments-event-v1",
        "oulad-d0-29-v1",
    ]


def test_health_reports_down_when_artifact_loading_fails() -> None:
    def fail() -> ArtifactRegistry:
        raise DataQualityError("freeze manifest missing")

    with TestClient(create_app(fail)) as client:
        response = client.get("/health")

    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"
    assert not any(
        artifact["loaded"] for artifact in response.json()["loadedArtifacts"]
    )


def test_health_rejects_unsupported_accept_header() -> None:
    with TestClient(create_app(_registry)) as client:
        response = client.get("/health", headers={"Accept": "text/plain"})

    assert response.status_code == 406
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "NOT_ACCEPTABLE"


def test_knowledge_prediction_propagates_trace_and_versions() -> None:
    with TestClient(create_app(_registry)) as client:
        response = client.post("/v1/knowledge/predict", json=_knowledge_request())

    assert response.status_code == 200
    body = response.json()
    assert body["trace"]["correlationId"] == CORRELATION_ID
    assert body["trace"]["analysisJobId"] == ANALYSIS_JOB_ID
    assert body["mastery"] == [
        {
            "skillId": SKILL_ID,
            "probability": 0.71,
            "estimatorModel": MASTERY_MODEL.model_dump(mode="json", by_alias=True),
        }
    ]
    assert body["nextQuestionId"] == TARGET_QUESTION_ID
    assert body["nextCorrectProbability"] == 0.66
    assert body["masteryModel"]["modelVersion"] == "bkt-v1"
    assert body["nextPredictorModel"]["modelVersion"] == "akt-v1"
    assert response.headers["x-correlation-id"] == CORRELATION_ID


def test_knowledge_prediction_replaces_effective_versions_with_loaded_artifacts() -> None:
    payload = _knowledge_request()
    effective = payload["trace"]["effectiveModelVersions"]
    mastery = next(model for model in effective if model["purpose"] == "MASTERY")
    mastery["modelVersion"] = "rule-mastery-fallback-v1"
    mastery["family"] = "RULE"
    with TestClient(create_app(_registry)) as client:
        response = client.post("/v1/knowledge/predict", json=payload)

    assert response.status_code == 200
    returned = response.json()["trace"]["effectiveModelVersions"]
    returned_mastery = next(model for model in returned if model["purpose"] == "MASTERY")
    assert returned_mastery == MASTERY_MODEL.model_dump(mode="json", by_alias=True)
    assert response.json()["trace"]["dataVersions"] == payload["trace"]["dataVersions"]


def test_risk_prediction_uses_frozen_thresholds() -> None:
    with TestClient(create_app(_registry)) as client:
        response = client.post("/v1/risk/predict", json=_risk_request())

    assert response.status_code == 200
    body = response.json()
    assert body["probability"] == 0.74
    assert body["calibrated"] is True
    assert body["riskBand"] == "HIGH"
    assert body["mediumThreshold"] == 0.35
    assert body["highThreshold"] == 0.70
    assert body["model"]["modelVersion"] == RISK_MODEL.model_version
    assert body["calibratorVersion"] == RISK_MODEL.calibrator_version


def test_explanation_returns_exactly_five_model_factors() -> None:
    with TestClient(create_app(_registry)) as client:
        response = client.post("/v1/explain", json=_explain_request())

    assert response.status_code == 200
    body = response.json()
    assert body["riskModelVersion"] == RISK_MODEL.model_version
    assert body["outputUnit"] == "LOG_ODDS"
    assert [factor["rank"] for factor in body["factors"]] == [1, 2, 3, 4, 5]
    assert all(factor["trace"] == body["trace"] for factor in body["factors"])
    assert all(
        factor["riskModelVersion"] == RISK_MODEL.model_version
        for factor in body["factors"]
    )


def test_explanation_rejects_a_stale_risk_model_version() -> None:
    payload = _explain_request()
    payload["riskModelVersion"] = "stale-risk-v0"
    with TestClient(create_app(_registry)) as client:
        response = client.post("/v1/explain", json=payload)

    assert response.status_code == 409
    assert response.json()["code"] == "RISK_MODEL_VERSION_CONFLICT"
    assert response.json()["traceId"] == CORRELATION_ID


def test_missing_risk_feature_returns_unprocessable_problem() -> None:
    payload = _risk_request()
    payload["features"] = payload["features"][:-1]
    with TestClient(create_app(_registry)) as client:
        response = client.post("/v1/risk/predict", json=payload)

    assert response.status_code == 422
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "RISK_FEATURE_CONTRACT_INVALID"


def test_unmapped_knowledge_lineage_returns_unprocessable_problem() -> None:
    registry = replace(
        _registry(), source_lineage=cast(Any, RejectingSourceLineage())
    )
    with TestClient(create_app(lambda: registry)) as client:
        response = client.post("/v1/knowledge/predict", json=_knowledge_request())

    assert response.status_code == 422
    assert response.json()["code"] == "KNOWLEDGE_INPUT_INVALID"


def test_extra_request_property_returns_validation_problem() -> None:
    payload = _knowledge_request()
    payload["unexpected"] = True
    with TestClient(create_app(_registry)) as client:
        response = client.post("/v1/knowledge/predict", json=payload)

    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "REQUEST_VALIDATION_FAILED"
    assert body["type"].endswith("/request-validation-failed")
    assert "problem_type" not in body
    assert body["traceId"] == CORRELATION_ID
    assert {violation["field"] for violation in body["violations"]} == {"unexpected"}


def test_malformed_json_returns_bad_request_problem() -> None:
    with TestClient(create_app(_registry)) as client:
        response = client.post(
            "/v1/risk/predict",
            content=b'{"trace":',
            headers={"Content-Type": "application/json"},
        )

    assert response.status_code == 400
    assert response.json()["code"] == "MALFORMED_JSON"
    assert response.headers["content-type"].startswith("application/problem+json")


def test_empty_json_body_returns_bad_request_problem() -> None:
    with TestClient(create_app(_registry)) as client:
        response = client.post(
            "/v1/risk/predict",
            content=b"",
            headers={"Content-Type": "application/json"},
        )

    assert response.status_code == 400
    assert response.json()["code"] == "MALFORMED_JSON"


def test_non_json_request_media_type_returns_bad_request() -> None:
    with TestClient(create_app(_registry)) as client:
        response = client.post(
            "/v1/risk/predict",
            content=b"features=none",
            headers={"Content-Type": "application/x-www-form-urlencoded"},
        )

    assert response.status_code == 400
    assert response.json()["code"] == "UNSUPPORTED_REQUEST_MEDIA_TYPE"


def test_requested_model_mismatch_is_rejected_before_inference() -> None:
    payload = _risk_request()
    requested = payload["trace"]["requestedModelVersions"]
    risk = next(model for model in requested if model["purpose"] == "RISK")
    risk["modelVersion"] = "stale-risk-v0"
    with TestClient(create_app(_registry)) as client:
        response = client.post("/v1/risk/predict", json=payload)

    assert response.status_code == 422
    assert response.json()["code"] == "TRACE_MODEL_VERSION_MISMATCH"


def test_loaded_rollback_registry_is_selected_by_requested_version() -> None:
    rollback_risk = _model(
        ModelPurpose.RISK, ModelFamily.CATBOOST, "risk-catboost-rollback-v1", "risk-cal-v2"
    )
    rollback = replace(_registry(), risk_model=rollback_risk)
    payload = _risk_request()
    requested = payload["trace"]["requestedModelVersions"]
    risk = next(model for model in requested if model["purpose"] == "RISK")
    risk.update(rollback_risk.model_dump(mode="json", by_alias=True))

    with TestClient(create_app(lambda: (_registry(), rollback))) as client:
        response = client.post("/v1/risk/predict", json=payload)

    assert response.status_code == 200
    assert response.json()["model"]["modelVersion"] == rollback_risk.model_version


def test_inference_returns_service_unavailable_until_artifacts_are_ready() -> None:
    def fail() -> ArtifactRegistry:
        raise DataQualityError("freeze manifest missing")

    with TestClient(create_app(fail)) as client:
        response = client.post("/v1/risk/predict", json=_risk_request())

    assert response.status_code == 503
    assert response.json()["code"] == "MODEL_ARTIFACTS_NOT_READY"
