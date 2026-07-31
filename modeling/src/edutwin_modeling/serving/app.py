"""FastAPI application for frozen EduTwin CPU inference."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator, Callable, Mapping
from contextlib import asynccontextmanager
from dataclasses import dataclass
from time import perf_counter
from typing import Any, cast
from uuid import UUID, uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import ValidationError
from starlette.exceptions import HTTPException as StarletteHttpException
from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.responses import Response

from edutwin_modeling.errors import DataQualityError
from edutwin_modeling.serving.registry import ArtifactRegistry
from edutwin_modeling.serving.schemas import (
    DataSourceId,
    DiagnosisEvidence,
    ExplainRequest,
    ExplainResponse,
    FieldViolation,
    HealthResponse,
    HealthStatus,
    KnowledgePrediction,
    KnowledgePredictRequest,
    LoadedArtifact,
    MasteryEstimate,
    ModelFamily,
    ModelPurpose,
    ModelVersionRef,
    ProblemDetail,
    RiskPrediction,
    RiskPredictRequest,
    TraceRef,
    utc_now,
)

LOGGER = logging.getLogger(__name__)
PROBLEM_BASE = "https://edutwin.local/problems"
JSON_ENDPOINTS = frozenset(
    {"/v1/knowledge/predict", "/v1/risk/predict", "/v1/explain"}
)


@dataclass
class RuntimeState:
    registry: ArtifactRegistry | None = None
    registries: tuple[ArtifactRegistry, ...] = ()
    readiness_error: str | None = None
    service_version: str = "1.0.0"


class ServiceProblemError(Exception):
    """A bounded client-facing service failure."""

    def __init__(
        self,
        status: int,
        code: str,
        title: str,
        detail: str,
        *,
        violations: tuple[FieldViolation, ...] = (),
        trace_id: str | None = None,
    ) -> None:
        super().__init__(detail)
        self.status = status
        self.code = code
        self.title = title
        self.detail = detail
        self.violations = violations
        self.trace_id = trace_id


class RequestContractMiddleware(BaseHTTPMiddleware):
    """Apply media-type checks and correlation IDs before body validation."""

    async def dispatch(
        self, request: Request, call_next: RequestResponseEndpoint
    ) -> Response:
        request.state.trace_id = _header_trace_id(request)
        response: Response
        if request.method == "POST" and request.url.path in JSON_ENDPOINTS:
            media_type = request.headers.get("content-type", "").split(";", 1)[0].strip()
            if media_type.lower() != "application/json":
                response = _problem_response(
                    request,
                    status=400,
                    code="UNSUPPORTED_REQUEST_MEDIA_TYPE",
                    title="Malformed request",
                    detail="The request Content-Type must be application/json.",
                )
            else:
                response = await call_next(request)
        else:
            response = await call_next(request)
        response.headers["X-Correlation-ID"] = str(request.state.trace_id)
        return response


def create_app(
    registry_loader: Callable[[], ArtifactRegistry | tuple[ArtifactRegistry, ...]] =
        lambda: (ArtifactRegistry.load("active"), ArtifactRegistry.load("rollback")),
) -> FastAPI:
    """Create an application with an injectable artifact loader for tests."""

    state = RuntimeState()

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        try:
            loaded = registry_loader()
            state.registries = loaded if isinstance(loaded, tuple) else (loaded,)
            state.registry = state.registries[0]
            state.service_version = state.registry.service_version
            state.readiness_error = None
        except Exception as exc:  # Startup must remain observable through /health.
            state.registry = None
            state.readiness_error = str(exc)
            LOGGER.exception("model artifacts failed readiness validation")
        yield

    application = FastAPI(
        title="EduTwin Model Service API",
        version="1.0.0",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        lifespan=lifespan,
    )
    application.state.runtime_state = state
    application.add_middleware(RequestContractMiddleware)
    _install_exception_handlers(application)
    _install_routes(application)
    return application


def _install_routes(application: FastAPI) -> None:
    @application.post(
        "/v1/knowledge/predict",
        response_model=KnowledgePrediction,
        response_model_by_alias=True,
    )
    def predict_knowledge(
        payload: KnowledgePredictRequest, request: Request
    ) -> KnowledgePrediction:
        _set_trace_id(request, payload.trace.correlation_id)
        registry = _ready_registry(
            request, payload.trace, (ModelPurpose.MASTERY, ModelPurpose.NEXT_CORRECT)
        )
        _require_contract(
            payload.feature_contract_version,
            registry.knowledge_contract_version,
            payload.trace,
        )
        _require_data_source(payload.trace, DataSourceId.ASSISTMENTS)
        _require_requested_model(payload.trace, registry.mastery_model)
        _require_requested_model(payload.trace, registry.next_model)
        started = perf_counter()
        try:
            interactions = tuple(
                (
                    registry.source_lineage.problem_key(interaction.question_id),
                    tuple(
                        registry.source_lineage.skill_key(skill_id)
                        for skill_id in interaction.skill_ids
                    ),
                    int(interaction.correct),
                )
                for interaction in payload.interactions
            )
            prediction = registry.knowledge_runtime.predict(
                interactions,
                registry.source_lineage.problem_key(payload.target_question_id),
                tuple(
                    registry.source_lineage.skill_key(skill_id)
                    for skill_id in payload.target_skill_ids
                ),
            )
            if (
                prediction.mastery_model_version != registry.mastery_model.model_version
                or prediction.next_model_version != registry.next_model.model_version
                or prediction.next_calibrator_version
                != registry.next_model.calibrator_version
            ):
                raise _artifact_inconsistent(payload.trace, "knowledge")
            mastery = tuple(
                MasteryEstimate(
                    skill_id=skill_id,
                    probability=prediction.mastery[
                        registry.source_lineage.skill_key(skill_id)
                    ],
                    estimator_model=registry.mastery_model,
                )
                for skill_id in payload.target_skill_ids
            )
            trace = _effective_trace(
                payload.trace, (registry.mastery_model, registry.next_model)
            )
            return KnowledgePrediction(
                trace=trace,
                mastery=mastery,
                next_question_id=payload.target_question_id,
                next_correct_probability=prediction.next_correct_probability,
                mastery_model=registry.mastery_model,
                next_predictor_model=registry.next_model,
                selected_by_validation=True,
                feature_contract_version=registry.knowledge_contract_version,
                inference_latency_ms=_elapsed_ms(started),
            )
        except DataQualityError as exc:
            raise _unprocessable(payload.trace, "KNOWLEDGE_INPUT_INVALID", str(exc)) from exc
        except ServiceProblemError:
            raise
        except (KeyError, ValidationError) as exc:
            raise _artifact_inconsistent(payload.trace, "knowledge") from exc
        except Exception as exc:
            raise _inference_unavailable(payload.trace, "knowledge", exc) from exc

    @application.post(
        "/v1/risk/predict",
        response_model=RiskPrediction,
        response_model_by_alias=True,
    )
    def predict_risk(payload: RiskPredictRequest, request: Request) -> RiskPrediction:
        _set_trace_id(request, payload.trace.correlation_id)
        registry = _ready_registry(request, payload.trace, (ModelPurpose.RISK,))
        _require_contract(
            payload.feature_contract_version,
            registry.risk_contract_version,
            payload.trace,
        )
        _require_data_source(payload.trace, DataSourceId.OULAD)
        _require_requested_model(payload.trace, registry.risk_model)
        features = {feature.name: feature.value for feature in payload.features}
        started = perf_counter()
        try:
            probability, band = registry.risk_runtime.predict(features)
            return RiskPrediction(
                trace=_effective_trace(payload.trace, (registry.risk_model,)),
                probability=probability,
                calibrated=True,
                risk_band=band,
                medium_threshold=registry.risk_runtime.medium_threshold,
                high_threshold=registry.risk_runtime.high_threshold,
                model=registry.risk_model,
                calibrator_version=cast(str, registry.risk_model.calibrator_version),
                feature_contract_version=registry.risk_contract_version,
                inference_latency_ms=_elapsed_ms(started),
            )
        except DataQualityError as exc:
            raise _unprocessable(payload.trace, "RISK_FEATURE_CONTRACT_INVALID", str(exc)) from exc
        except ValidationError as exc:
            raise _artifact_inconsistent(payload.trace, "risk") from exc
        except Exception as exc:
            raise _inference_unavailable(payload.trace, "risk", exc) from exc

    @application.post(
        "/v1/explain",
        response_model=ExplainResponse,
        response_model_by_alias=True,
    )
    def explain_risk(payload: ExplainRequest, request: Request) -> ExplainResponse:
        _set_trace_id(request, payload.trace.correlation_id)
        registry = _ready_registry(
            request, payload.trace, (ModelPurpose.RISK, ModelPurpose.EXPLANATION)
        )
        _require_contract(
            payload.feature_contract_version,
            registry.risk_contract_version,
            payload.trace,
        )
        _require_data_source(payload.trace, DataSourceId.OULAD)
        _require_requested_model(payload.trace, registry.risk_model)
        _require_requested_model(payload.trace, registry.explanation_model)
        if payload.risk_model_version != registry.risk_model.model_version:
            raise ServiceProblemError(
                409,
                "RISK_MODEL_VERSION_CONFLICT",
                "Model version conflict",
                "riskModelVersion does not identify the active frozen risk artifact.",
                trace_id=str(payload.trace.correlation_id),
            )
        features = {feature.name: feature.value for feature in payload.features}
        started = perf_counter()
        try:
            base_value, output_value, raw_factors = registry.risk_runtime.explain(features)
            _validate_raw_factors(
                raw_factors,
                features,
                registry.risk_model.model_version,
                payload.trace,
            )
            trace = _effective_trace(payload.trace, (registry.explanation_model,))
            factors = tuple(
                DiagnosisEvidence(
                    trace=trace,
                    rank=int(factor["rank"]),
                    feature_name=str(factor["featureName"]),
                    raw_value=float(factor["rawValue"]),
                    direction=str(factor["direction"]),
                    contribution=float(factor["contribution"]),
                    base_value=float(factor["baseValue"]),
                    output_unit=str(factor["outputUnit"]),
                    risk_model_version=str(factor["riskModelVersion"]),
                )
                for factor in raw_factors
            )
            return ExplainResponse(
                trace=trace,
                risk_model_version=registry.risk_model.model_version,
                base_value=base_value,
                output_value=output_value,
                output_unit="LOG_ODDS",
                factors=factors,
                generated_at=utc_now(),
                inference_latency_ms=_elapsed_ms(started),
            )
        except DataQualityError as exc:
            raise _unprocessable(payload.trace, "RISK_FEATURE_CONTRACT_INVALID", str(exc)) from exc
        except ServiceProblemError:
            raise
        except ValidationError as exc:
            raise _artifact_inconsistent(payload.trace, "explanation") from exc
        except Exception as exc:
            raise _inference_unavailable(payload.trace, "explanation", exc) from exc

    @application.get(
        "/health",
        response_model=HealthResponse,
        response_model_by_alias=True,
    )
    def health(request: Request) -> JSONResponse:
        if not _accepts_json(request.headers.get("accept")):
            raise ServiceProblemError(
                406,
                "NOT_ACCEPTABLE",
                "Representation not acceptable",
                "The health endpoint supports application/json responses.",
            )
        state = _runtime_state(request)
        if state.registry is None:
            response = HealthResponse(
                status=HealthStatus.DOWN,
                service_version=state.service_version,
                checked_at=utc_now(),
                loaded_artifacts=_unavailable_artifacts(),
                feature_contract_versions=(
                    "assistments-event-v1",
                    "oulad-d0-29-v1",
                ),
            )
            return JSONResponse(
                status_code=503,
                content=response.model_dump(mode="json", by_alias=True),
            )
        response = HealthResponse(
            status=HealthStatus.UP,
            service_version=state.registry.service_version,
            checked_at=utc_now(),
            loaded_artifacts=state.registry.loaded_artifacts,
            feature_contract_versions=state.registry.feature_contract_versions,
        )
        return JSONResponse(content=response.model_dump(mode="json", by_alias=True))


def _install_exception_handlers(application: FastAPI) -> None:
    @application.exception_handler(ServiceProblemError)
    async def service_problem_handler(
        request: Request, exception: ServiceProblemError
    ) -> JSONResponse:
        return _problem_response(
            request,
            status=exception.status,
            code=exception.code,
            title=exception.title,
            detail=exception.detail,
            violations=exception.violations,
            trace_id=exception.trace_id,
        )

    @application.exception_handler(RequestValidationError)
    async def validation_handler(
        request: Request, exception: RequestValidationError
    ) -> JSONResponse:
        _set_validation_trace(request, exception.body)
        malformed = any(
            error.get("type") == "json_invalid"
            or (
                error.get("type") == "missing"
                and tuple(error.get("loc", ())) == ("body",)
            )
            for error in exception.errors()
        )
        status = 400 if malformed else 422
        code = "MALFORMED_JSON" if malformed else "REQUEST_VALIDATION_FAILED"
        title = "Malformed request" if malformed else "Request validation failed"
        violations = tuple(
            FieldViolation(
                field=_field_path(error.get("loc", ())),
                message=str(error.get("msg", "Invalid value")),
            )
            for error in exception.errors()
        )
        return _problem_response(
            request,
            status=status,
            code=code,
            title=title,
            detail=(
                "The request body is not valid JSON."
                if malformed
                else "The request violates the model service contract."
            ),
            violations=violations,
        )

    @application.exception_handler(StarletteHttpException)
    async def http_exception_handler(
        request: Request, exception: StarletteHttpException
    ) -> JSONResponse:
        return _problem_response(
            request,
            status=exception.status_code,
            code="HTTP_REQUEST_REJECTED",
            title="HTTP request rejected",
            detail=str(exception.detail),
        )

    @application.exception_handler(Exception)
    async def unexpected_exception_handler(
        request: Request, exception: Exception
    ) -> JSONResponse:
        LOGGER.exception("unhandled model service request failure", exc_info=exception)
        return _problem_response(
            request,
            status=500,
            code="INTERNAL_SERVICE_ERROR",
            title="Internal service error",
            detail="The model service could not complete the request.",
        )


def _ready_registry(
    request: Request, trace: TraceRef, purposes: tuple[ModelPurpose, ...]
) -> ArtifactRegistry:
    state = _runtime_state(request)
    if not state.registries:
        raise ServiceProblemError(
            503,
            "MODEL_ARTIFACTS_NOT_READY",
            "Model artifacts unavailable",
            "The required frozen model artifacts are not ready for inference.",
        )
    requested = {model.purpose: model for model in trace.requested_model_versions}
    for registry in state.registries:
        available = {
            ModelPurpose.MASTERY: registry.mastery_model,
            ModelPurpose.NEXT_CORRECT: registry.next_model,
            ModelPurpose.RISK: registry.risk_model,
            ModelPurpose.EXPLANATION: registry.explanation_model,
        }
        if all(requested.get(purpose) == available[purpose] for purpose in purposes):
            return registry
    raise ServiceProblemError(
        422,
        "TRACE_MODEL_VERSION_MISMATCH",
        "Inference request rejected",
        "The requested model version is not a loaded frozen artifact.",
        trace_id=str(trace.correlation_id),
    )


def _runtime_state(request: Request) -> RuntimeState:
    return cast(RuntimeState, request.app.state.runtime_state)


def _require_contract(actual: str, expected: str, trace: TraceRef) -> None:
    if actual != expected:
        raise _unprocessable(
            trace,
            "FEATURE_CONTRACT_VERSION_MISMATCH",
            f"featureContractVersion must be {expected}.",
        )


def _require_data_source(trace: TraceRef, source: DataSourceId) -> None:
    if not any(version.source_id == source for version in trace.data_versions):
        raise _unprocessable(
            trace,
            "TRACE_DATA_VERSION_MISSING",
            f"trace.dataVersions must include {source.value}.",
        )


def _require_requested_model(trace: TraceRef, active: ModelVersionRef) -> None:
    requested = next(
        (
            model
            for model in trace.requested_model_versions
            if model.purpose == active.purpose
        ),
        None,
    )
    if requested is None:
        raise _unprocessable(
            trace,
            "TRACE_MODEL_VERSION_MISSING",
            f"trace.requestedModelVersions must include {active.purpose.value}.",
        )
    if requested != active:
        raise _unprocessable(
            trace,
            "TRACE_MODEL_VERSION_MISMATCH",
            f"The requested {active.purpose.value} model is not the loaded frozen artifact.",
        )


def _effective_trace(
    trace: TraceRef, replacements: tuple[ModelVersionRef, ...]
) -> TraceRef:
    replacement_by_purpose = {model.purpose: model for model in replacements}
    effective: list[ModelVersionRef] = []
    applied: set[ModelPurpose] = set()
    for model in trace.effective_model_versions:
        replacement = replacement_by_purpose.get(model.purpose)
        if replacement is None:
            effective.append(model)
        elif model.purpose not in applied:
            effective.append(replacement)
            applied.add(model.purpose)
    for model in replacements:
        if model.purpose not in applied:
            effective.append(model)
            applied.add(model.purpose)
    return TraceRef(
        correlation_id=trace.correlation_id,
        answer_event_id=trace.answer_event_id,
        analysis_job_id=trace.analysis_job_id,
        snapshot_id=trace.snapshot_id,
        data_versions=trace.data_versions,
        requested_model_versions=trace.requested_model_versions,
        effective_model_versions=tuple(effective),
    )


def _unprocessable(trace: TraceRef, code: str, detail: str) -> ServiceProblemError:
    return ServiceProblemError(
        422,
        code,
        "Inference request rejected",
        detail,
        trace_id=str(trace.correlation_id),
    )


def _artifact_inconsistent(trace: TraceRef, stage: str) -> ServiceProblemError:
    return ServiceProblemError(
        503,
        "MODEL_ARTIFACT_INCONSISTENT",
        "Model artifact unavailable",
        f"The loaded {stage} artifact returned an inconsistent result.",
        trace_id=str(trace.correlation_id),
    )


def _inference_unavailable(
    trace: TraceRef, stage: str, exception: Exception
) -> ServiceProblemError:
    LOGGER.exception("%s inference failed", stage, exc_info=exception)
    return ServiceProblemError(
        503,
        "MODEL_INFERENCE_UNAVAILABLE",
        "Model inference unavailable",
        f"The frozen {stage} runtime could not complete inference.",
        trace_id=str(trace.correlation_id),
    )


def _validate_raw_factors(
    factors: list[dict[str, Any]],
    features: Mapping[str, float],
    risk_model_version: str,
    trace: TraceRef,
) -> None:
    if len(factors) != 5:
        raise _artifact_inconsistent(trace, "explanation")
    ranking: list[tuple[float, str]] = []
    names: set[str] = set()
    for expected_rank, factor in enumerate(factors, start=1):
        name = factor.get("featureName")
        contribution = factor.get("contribution")
        raw_value = factor.get("rawValue")
        if (
            factor.get("rank") != expected_rank
            or not isinstance(name, str)
            or name in names
            or name not in features
            or not isinstance(contribution, int | float)
            or not isinstance(raw_value, int | float)
            or float(raw_value) != features[name]
            or factor.get("riskModelVersion") != risk_model_version
        ):
            raise _artifact_inconsistent(trace, "explanation")
        names.add(name)
        ranking.append((-abs(float(contribution)), name))
    if ranking != sorted(ranking):
        raise _artifact_inconsistent(trace, "explanation")


def _elapsed_ms(started: float) -> float:
    return max(0.0, (perf_counter() - started) * 1_000.0)


def _set_trace_id(request: Request, trace_id: UUID) -> None:
    request.state.trace_id = str(trace_id)


def _set_validation_trace(request: Request, body: Any) -> None:
    if not isinstance(body, Mapping):
        return
    trace = body.get("trace")
    if not isinstance(trace, Mapping):
        return
    correlation_id = trace.get("correlationId")
    try:
        request.state.trace_id = str(UUID(str(correlation_id)))
    except (TypeError, ValueError):
        return


def _header_trace_id(request: Request) -> str:
    value = request.headers.get("x-correlation-id")
    try:
        return str(UUID(value)) if value else str(uuid4())
    except ValueError:
        return str(uuid4())


def _field_path(location: Any) -> str:
    if not isinstance(location, tuple | list):
        return "body"
    parts = [str(part) for part in location if part not in {"body"}]
    return ".".join(parts) or "body"


def _problem_response(
    request: Request,
    *,
    status: int,
    code: str,
    title: str,
    detail: str,
    violations: tuple[FieldViolation, ...] = (),
    trace_id: str | None = None,
) -> JSONResponse:
    resolved_trace = trace_id or str(getattr(request.state, "trace_id", uuid4()))
    request.state.trace_id = resolved_trace
    problem = ProblemDetail(
        problem_type=f"{PROBLEM_BASE}/{code.lower().replace('_', '-')}",
        title=title,
        status=status,
        detail=detail,
        instance=request.url.path,
        code=code,
        trace_id=resolved_trace,
        violations=violations,
    )
    return JSONResponse(
        status_code=status,
        content=problem.model_dump(mode="json", by_alias=True),
        media_type="application/problem+json",
    )


def _accepts_json(value: str | None) -> bool:
    if value is None or not value.strip():
        return True
    for part in value.split(","):
        segments = [segment.strip().lower() for segment in part.split(";")]
        media_range = segments[0]
        quality = 1.0
        for parameter in segments[1:]:
            if parameter.startswith("q="):
                try:
                    quality = float(parameter.removeprefix("q="))
                except ValueError:
                    quality = 0.0
        if quality > 0.0 and media_range in {"*/*", "application/*", "application/json"}:
            return True
    return False


def _unavailable_artifacts() -> tuple[LoadedArtifact, ...]:
    unavailable_hash = "0" * 64
    models = (
        ModelVersionRef(
            purpose=ModelPurpose.MASTERY,
            family=ModelFamily.BKT,
            model_name="unavailable",
            model_version="unavailable",
            artifact_sha256=unavailable_hash,
            calibrator_version=None,
        ),
        ModelVersionRef(
            purpose=ModelPurpose.NEXT_CORRECT,
            family=ModelFamily.AKT,
            model_name="unavailable",
            model_version="unavailable",
            artifact_sha256=unavailable_hash,
            calibrator_version=None,
        ),
        ModelVersionRef(
            purpose=ModelPurpose.RISK,
            family=ModelFamily.LIGHTGBM,
            model_name="unavailable",
            model_version="unavailable",
            artifact_sha256=unavailable_hash,
            calibrator_version=None,
        ),
        ModelVersionRef(
            purpose=ModelPurpose.EXPLANATION,
            family=ModelFamily.SHAP,
            model_name="unavailable",
            model_version="unavailable",
            artifact_sha256=unavailable_hash,
            calibrator_version=None,
        ),
    )
    return tuple(
        LoadedArtifact(model=model, manifest_sha256=unavailable_hash, loaded=False)
        for model in models
    )


app = create_app()
