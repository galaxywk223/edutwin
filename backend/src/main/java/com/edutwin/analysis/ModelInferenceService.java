package com.edutwin.analysis;

import com.edutwin.analysis.AnalysisInference.Evidence;
import com.edutwin.analysis.AnalysisInference.SkillPrediction;
import com.edutwin.analysis.AnalysisWorkItemRepository.Interaction;
import com.edutwin.analysis.AnalysisWorkItemRepository.SkillState;
import com.edutwin.analysis.AnalysisWorkItemRepository.WorkItem;
import com.edutwin.api.model.ModelVersionRef;
import com.edutwin.api.model.TraceRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Service
public class ModelInferenceService {

    static final String KNOWLEDGE_CONTRACT = "assistments-event-v1";
    static final String RISK_CONTRACT = "oulad-d0-29-v1";
    private static final BigDecimal DEFAULT_MASTERY = new BigDecimal("0.20000000");
    private static final BigDecimal DEFAULT_RISK = new BigDecimal("0.70000000");

    private final RestClient restClient;
    private final boolean modelEnabled;

    public ModelInferenceService(
            @Value("${edutwin.model-service.enabled:true}") boolean modelEnabled,
            @Value("${edutwin.model-service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${edutwin.model-service.connect-timeout:PT1S}") Duration connectTimeout,
            @Value("${edutwin.model-service.response-timeout:PT2S}") Duration responseTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(responseTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.modelEnabled = modelEnabled;
    }

    public AnalysisInference infer(WorkItem workItem) {
        LinkedHashSet<String> degraded = new LinkedHashSet<>();
        LinkedHashSet<ModelVersionRef> effective =
                new LinkedHashSet<>(workItem.job().getTrace().getEffectiveModelVersions());

        KnowledgeResult knowledge;
        if (!modelEnabled) {
            degraded.add("MODEL_SERVICE_DISABLED");
            knowledge = fallbackKnowledge(workItem);
        } else if ("ONLINE_BKT".equals(workItem.answer().knowledgeModelMode())) {
            knowledge = onlineBkt(workItem);
        } else {
            try {
                knowledge = requestKnowledge(workItem);
            } catch (RestClientException | IllegalArgumentException | IllegalStateException exception) {
                degraded.add("KNOWLEDGE_MODEL_UNAVAILABLE");
                knowledge = fallbackKnowledge(workItem);
            }
        }
        replace(effective, ModelVersionRef.PurposeEnum.MASTERY, knowledge.masteryModel());
        replace(effective, ModelVersionRef.PurposeEnum.NEXT_CORRECT, knowledge.nextModel());

        RiskResult risk;
        try {
            risk = modelEnabled ? requestRisk(workItem) : fallbackRisk(workItem);
        } catch (RestClientException | IllegalArgumentException | IllegalStateException exception) {
            degraded.add("RISK_MODEL_UNAVAILABLE");
            risk = fallbackRisk(workItem);
        }
        replace(effective, ModelVersionRef.PurposeEnum.RISK, risk.model());

        List<Evidence> evidence;
        try {
            if (modelEnabled) {
                evidence = requestExplanation(workItem, risk);
            } else {
                ModelVersionRef fallback = fallbackModel(
                        ModelVersionRef.PurposeEnum.EXPLANATION,
                        "rule-evidence-fallback-v1");
                replace(effective, ModelVersionRef.PurposeEnum.EXPLANATION, fallback);
                evidence = fallbackEvidence(workItem, risk, fallback.getModelVersion());
            }
        } catch (RestClientException | IllegalArgumentException | IllegalStateException exception) {
            degraded.add("MODEL_CONTRACT_ERROR");
            ModelVersionRef fallback = fallbackModel(
                    ModelVersionRef.PurposeEnum.EXPLANATION,
                    "rule-evidence-fallback-v1");
            replace(effective, ModelVersionRef.PurposeEnum.EXPLANATION, fallback);
            evidence = fallbackEvidence(workItem, risk, fallback.getModelVersion());
        }

        LinkedHashMap<UUID, SkillPrediction> skills = new LinkedHashMap<>();
        for (MasteryEstimate estimate : knowledge.mastery()) {
            skills.put(estimate.skillId(), new SkillPrediction(
                    estimate.skillId(),
                    probability(estimate.probability()),
                    probability(knowledge.nextCorrectProbability()),
                    knowledge.masteryModel().getModelVersion(),
                    knowledge.nextModel().getModelVersion(),
                    knowledge.masteryModel().getFamily() == ModelVersionRef.FamilyEnum.BKT
                            ? "BKT"
                            : "RULE_FALLBACK"));
        }

        return new AnalysisInference(
                skills,
                probability(knowledge.nextCorrectProbability()),
                probability(risk.probability()),
                risk.riskBand(),
                evidence.isEmpty() ? risk.baseValue() : evidence.get(0).baseValue(),
                risk.calibrated(),
                KNOWLEDGE_CONTRACT,
                RISK_CONTRACT,
                workItem.riskFeatures(),
                evidence,
                degraded,
                effective);
    }

    public AnalysisInference restoreEvidence(
            WorkItem workItem, AnalysisInference persistedInference) {
        LinkedHashSet<String> degraded =
                new LinkedHashSet<>(persistedInference.degradedStages());
        LinkedHashSet<ModelVersionRef> effective =
                new LinkedHashSet<>(persistedInference.effectiveModels());
        ModelVersionRef riskModel = effective.stream()
                .filter(value -> value.getPurpose() == ModelVersionRef.PurposeEnum.RISK)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Persisted analysis is missing its risk model version."));
        RiskResult risk = new RiskResult(
                persistedInference.riskProbability(),
                persistedInference.riskBand(),
                persistedInference.riskBaseValue(),
                persistedInference.calibrated(),
                riskModel);
        WorkItem evidenceItem = new WorkItem(
                workItem.job(),
                workItem.aiConfigurationVersionId(),
                workItem.answer(),
                workItem.targetSkillIds(),
                workItem.currentTwin(),
                workItem.currentSkills(),
                workItem.interactions(),
                workItem.questions(),
                persistedInference.riskFeatures());

        List<Evidence> evidence;
        if (!modelEnabled || degraded.contains("MODEL_CONTRACT_ERROR")) {
            String persistedExplainer = effective.stream()
                    .filter(value -> value.getPurpose() == ModelVersionRef.PurposeEnum.EXPLANATION)
                    .map(ModelVersionRef::getModelVersion)
                    .findFirst()
                    .orElse("rule-evidence-fallback-v1");
            evidence = fallbackEvidence(evidenceItem, risk, persistedExplainer);
        } else {
            try {
                evidence = requestExplanation(evidenceItem, risk);
            } catch (RestClientException | IllegalArgumentException | IllegalStateException exception) {
                degraded.add("MODEL_CONTRACT_ERROR");
                ModelVersionRef fallback = fallbackModel(
                        ModelVersionRef.PurposeEnum.EXPLANATION,
                        "rule-evidence-fallback-v1");
                replace(effective, ModelVersionRef.PurposeEnum.EXPLANATION, fallback);
                evidence = fallbackEvidence(evidenceItem, risk, fallback.getModelVersion());
            }
        }

        return new AnalysisInference(
                persistedInference.skills(),
                persistedInference.nextCorrectProbability(),
                persistedInference.riskProbability(),
                persistedInference.riskBand(),
                persistedInference.riskBaseValue(),
                persistedInference.calibrated(),
                persistedInference.knowledgeFeatureContractVersion(),
                persistedInference.riskFeatureContractVersion(),
                persistedInference.riskFeatures(),
                evidence,
                degraded,
                effective);
    }

    private KnowledgeResult requestKnowledge(WorkItem workItem) {
        UUID targetQuestion = workItem.questions().stream()
                .filter(candidate -> !candidate.questionId().equals(workItem.answer().questionId()))
                .filter(candidate -> workItem.targetSkillIds().contains(candidate.skillId()))
                .map(AnalysisWorkItemRepository.CandidateQuestion::questionId)
                .findFirst()
                .orElse(workItem.answer().questionId());
        List<KnowledgeInteractionRequest> interactions = workItem.interactions().stream()
                .map(value -> new KnowledgeInteractionRequest(
                        value.eventId(),
                        value.questionId(),
                        value.skillIds(),
                        value.correct(),
                        value.occurredAt(),
                        value.sequence()))
                .toList();
        KnowledgePredictRequest request = new KnowledgePredictRequest(
                workItem.job().getTrace(),
                workItem.job().getStudentId(),
                workItem.job().getCourseId(),
                interactions,
                targetQuestion,
                workItem.targetSkillIds(),
                KNOWLEDGE_CONTRACT);
        KnowledgePredictResponse response = restClient.post()
                .uri("/v1/knowledge/predict")
                .body(request)
                .retrieve()
                .body(KnowledgePredictResponse.class);
        if (response == null) {
            throw new IllegalStateException("The knowledge service returned no body.");
        }
        validateTrace(workItem, response.trace());
        requirePurpose(response.masteryModel(), ModelVersionRef.PurposeEnum.MASTERY);
        requirePurpose(response.nextPredictorModel(), ModelVersionRef.PurposeEnum.NEXT_CORRECT);
        if (!response.selectedByValidation()
                || !KNOWLEDGE_CONTRACT.equals(response.featureContractVersion())
                || response.mastery() == null
                || response.mastery().isEmpty()) {
            throw new IllegalStateException("The knowledge response violates the frozen contract.");
        }
        Set<UUID> returned = response.mastery().stream()
                .map(MasteryEstimateResponse::skillId)
                .collect(java.util.stream.Collectors.toSet());
        if (!returned.containsAll(workItem.targetSkillIds())) {
            throw new IllegalStateException("The knowledge response omitted a target skill.");
        }
        List<MasteryEstimate> mastery = response.mastery().stream()
                .map(value -> new MasteryEstimate(value.skillId(), value.probability()))
                .toList();
        return new KnowledgeResult(
                mastery,
                response.nextCorrectProbability(),
                response.masteryModel(),
                response.nextPredictorModel());
    }

    private RiskResult requestRisk(WorkItem workItem) {
        List<RiskFeatureRequest> features = workItem.riskFeatures().entrySet().stream()
                .map(entry -> new RiskFeatureRequest(entry.getKey(), entry.getValue()))
                .toList();
        RiskPredictRequest request = new RiskPredictRequest(
                workItem.job().getTrace(),
                workItem.job().getStudentId(),
                workItem.job().getCourseId(),
                new ObservationWindow(0, 29),
                RISK_CONTRACT,
                features);
        RiskPredictResponse response = restClient.post()
                .uri("/v1/risk/predict")
                .body(request)
                .retrieve()
                .body(RiskPredictResponse.class);
        if (response == null) {
            throw new IllegalStateException("The risk service returned no body.");
        }
        validateTrace(workItem, response.trace());
        requirePurpose(response.model(), ModelVersionRef.PurposeEnum.RISK);
        if (!response.calibrated()
                || !RISK_CONTRACT.equals(response.featureContractVersion())
                || response.calibratorVersion() == null
                || response.calibratorVersion().isBlank()) {
            throw new IllegalStateException("The risk response violates the frozen contract.");
        }
        probability(response.probability());
        return new RiskResult(
                response.probability(),
                response.riskBand(),
                decimal(logit(response.probability().doubleValue())),
                true,
                response.model());
    }

    private List<Evidence> requestExplanation(WorkItem workItem, RiskResult risk) {
        List<RiskFeatureRequest> features = workItem.riskFeatures().entrySet().stream()
                .map(entry -> new RiskFeatureRequest(entry.getKey(), entry.getValue()))
                .toList();
        ExplainRequest request = new ExplainRequest(
                workItem.job().getTrace(),
                workItem.job().getStudentId(),
                workItem.job().getCourseId(),
                risk.model().getModelVersion(),
                RISK_CONTRACT,
                features);
        ExplainResponse response = restClient.post()
                .uri("/v1/explain")
                .body(request)
                .retrieve()
                .body(ExplainResponse.class);
        if (response == null) {
            throw new IllegalStateException("The explanation service returned no body.");
        }
        validateTrace(workItem, response.trace());
        if (!risk.model().getModelVersion().equals(response.riskModelVersion())
                || response.factors() == null
                || response.factors().size() != 5) {
            throw new IllegalStateException("The explanation response violates the frozen contract.");
        }
        List<Evidence> result = new ArrayList<>(5);
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < response.factors().size(); index++) {
            EvidenceResponse factor = response.factors().get(index);
            if (factor.rank() != index + 1
                    || !names.add(factor.featureName())
                    || !workItem.riskFeatures().containsKey(factor.featureName())
                    || !risk.model().getModelVersion().equals(factor.riskModelVersion())) {
                throw new IllegalStateException("SHAP evidence is not ordered or source-bounded.");
            }
            result.add(new Evidence(
                    factor.rank(),
                    factor.featureName(),
                    factor.rawValue(),
                    factor.direction(),
                    factor.contribution().setScale(10, RoundingMode.HALF_UP),
                    factor.baseValue().setScale(10, RoundingMode.HALF_UP),
                    factor.outputUnit(),
                    factor.riskModelVersion()));
        }
        return List.copyOf(result);
    }

    private static KnowledgeResult fallbackKnowledge(WorkItem workItem) {
        ModelVersionRef masteryModel = fallbackModel(
                ModelVersionRef.PurposeEnum.MASTERY, "rule-bkt-fallback-v1");
        ModelVersionRef nextModel = fallbackModel(
                ModelVersionRef.PurposeEnum.NEXT_CORRECT, "rule-next-fallback-v1");
        List<MasteryEstimate> mastery = new ArrayList<>();
        for (UUID skillId : workItem.targetSkillIds()) {
            SkillState previous = workItem.currentSkills().get(skillId);
            BigDecimal prior = previous == null ? DEFAULT_MASTERY : previous.masteryProbability();
            BigDecimal updated = workItem.answer().correct()
                    ? prior.add(BigDecimal.ONE.subtract(prior).multiply(new BigDecimal("0.18000000")))
                    : prior.multiply(new BigDecimal("0.88000000"));
            mastery.add(new MasteryEstimate(skillId, probability(updated)));
        }
        int recentSize = Math.min(10, workItem.interactions().size());
        double recent = recentSize == 0
                ? (workItem.answer().correct() ? 1.0 : 0.0)
                : workItem.interactions()
                        .subList(workItem.interactions().size() - recentSize, workItem.interactions().size())
                        .stream()
                        .filter(Interaction::correct)
                        .count() / (double) recentSize;
        double meanMastery = mastery.stream()
                .mapToDouble(value -> value.probability().doubleValue())
                .average()
                .orElse(0.2);
        BigDecimal next = probability(0.75 * meanMastery + 0.25 * recent);
        return new KnowledgeResult(mastery, next, masteryModel, nextModel);
    }

    static KnowledgeResult onlineBkt(WorkItem workItem) {
        final double learn = 0.10;
        final double slip = 0.10;
        final double guess = 0.20;
        List<MasteryEstimate> mastery = new ArrayList<>();
        for (UUID skillId : workItem.targetSkillIds()) {
            SkillState previous = workItem.currentSkills().get(skillId);
            double prior = previous == null ? 0.20 : previous.masteryProbability().doubleValue();
            double observed = workItem.answer().correct()
                    ? (prior * (1 - slip)) / (prior * (1 - slip) + (1 - prior) * guess)
                    : (prior * slip) / (prior * slip + (1 - prior) * (1 - guess));
            double updated = observed + (1 - observed) * learn;
            mastery.add(new MasteryEstimate(skillId, probability(updated)));
        }
        double mean = mastery.stream().mapToDouble(item -> item.probability().doubleValue())
                .average().orElse(0.20);
        BigDecimal next = probability(mean * (1 - slip) + (1 - mean) * guess);
        ModelVersionRef masteryModel = new ModelVersionRef(
                ModelVersionRef.PurposeEnum.MASTERY, ModelVersionRef.FamilyEnum.BKT,
                "online-bkt-course", "online-bkt-course-v1",
                sha256Artifact("online-bkt-course-v1"), "none");
        ModelVersionRef nextModel = new ModelVersionRef(
                ModelVersionRef.PurposeEnum.NEXT_CORRECT, ModelVersionRef.FamilyEnum.BKT,
                "online-bkt-next", "online-bkt-next-v1",
                sha256Artifact("online-bkt-next-v1"), "none");
        return new KnowledgeResult(mastery, next, masteryModel, nextModel);
    }

    private static RiskResult fallbackRisk(WorkItem workItem) {
        BigDecimal prior = workItem.currentTwin() == null
                ? DEFAULT_RISK
                : workItem.currentTwin().riskProbability();
        BigDecimal delta = workItem.answer().correct()
                ? new BigDecimal("-0.04000000")
                : new BigDecimal("0.05000000");
        BigDecimal updated = probability(prior.add(delta));
        String band = updated.compareTo(new BigDecimal("0.65000000")) >= 0
                ? "HIGH"
                : updated.compareTo(new BigDecimal("0.35000000")) >= 0 ? "MEDIUM" : "LOW";
        return new RiskResult(
                updated,
                band,
                decimal(logit(updated.doubleValue())),
                false,
                fallbackModel(ModelVersionRef.PurposeEnum.RISK, "rule-risk-fallback-v1"));
    }

    private static List<Evidence> fallbackEvidence(
            WorkItem workItem, RiskResult risk, String modelVersion) {
        Map<String, Double> weights = Map.of(
                "vle_total_clicks", -0.01,
                "vle_interaction_count", -0.01,
                "vle_active_days", -0.08,
                "vle_active_weeks", -0.10,
                "vle_resource_count", -0.03,
                "assessment_count", -0.01,
                "assessment_scored_count", -0.01,
                "assessment_mean_score", -0.04);
        List<Map.Entry<Map.Entry<String, Double>, Double>> ranked = workItem.riskFeatures().entrySet().stream()
                .map(entry -> Map.entry(
                        entry,
                        weights.getOrDefault(entry.getKey(), 0.0) * entry.getValue()))
                .sorted(Comparator.<Map.Entry<Map.Entry<String, Double>, Double>>comparingDouble(
                                value -> Math.abs(value.getValue()))
                        .reversed()
                        .thenComparing(value -> value.getKey().getKey()))
                .limit(5)
                .toList();
        List<Evidence> result = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            Map.Entry<Map.Entry<String, Double>, Double> value = ranked.get(index);
            double contribution = value.getValue();
            result.add(new Evidence(
                    index + 1,
                    value.getKey().getKey(),
                    value.getKey().getValue(),
                    contribution >= 0 ? "INCREASES_RISK" : "DECREASES_RISK",
                    decimal(contribution),
                    risk.baseValue(),
                    "LOG_ODDS",
                    modelVersion));
        }
        return List.copyOf(result);
    }

    private static void validateTrace(WorkItem workItem, TraceRef trace) {
        if (trace == null
                || !workItem.job().getJobId().equals(trace.getAnalysisJobId())
                || !workItem.answer().id().equals(trace.getAnswerEventId())
                || !workItem.job().getTrace().getSnapshotId().equals(trace.getSnapshotId())
                || !workItem.job().getTrace().getCorrelationId().equals(trace.getCorrelationId())) {
            throw new IllegalStateException("The model response trace does not match the analysis job.");
        }
    }

    private static void requirePurpose(ModelVersionRef model, ModelVersionRef.PurposeEnum purpose) {
        if (model == null || model.getPurpose() != purpose) {
            throw new IllegalStateException("The model response uses an unexpected purpose.");
        }
    }

    private static void replace(
            Set<ModelVersionRef> models,
            ModelVersionRef.PurposeEnum purpose,
            ModelVersionRef replacement) {
        models.removeIf(model -> model.getPurpose() == purpose);
        models.add(replacement);
    }

    private static ModelVersionRef fallbackModel(
            ModelVersionRef.PurposeEnum purpose, String version) {
        return new ModelVersionRef(
                purpose,
                ModelVersionRef.FamilyEnum.RULE,
                version,
                version,
                sha256Artifact(version),
                "none");
    }

    private static BigDecimal probability(double value) {
        return probability(BigDecimal.valueOf(value));
    }

    private static BigDecimal probability(BigDecimal value) {
        BigDecimal bounded = value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return bounded.setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(10, RoundingMode.HALF_UP);
    }

    private static double logit(double probability) {
        double bounded = Math.max(0.000001, Math.min(0.999999, probability));
        return Math.log(bounded / (1.0 - bounded));
    }

    static String sha256Artifact(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record KnowledgePredictRequest(
            TraceRef trace,
            UUID studentId,
            UUID courseId,
            List<KnowledgeInteractionRequest> interactions,
            UUID targetQuestionId,
            List<UUID> targetSkillIds,
            String featureContractVersion) {}

    private record KnowledgeInteractionRequest(
            UUID eventId,
            UUID questionId,
            List<UUID> skillIds,
            boolean correct,
            OffsetDateTime occurredAt,
            long sequenceNumber) {}

    private record KnowledgePredictResponse(
            TraceRef trace,
            List<MasteryEstimateResponse> mastery,
            UUID nextQuestionId,
            BigDecimal nextCorrectProbability,
            ModelVersionRef masteryModel,
            ModelVersionRef nextPredictorModel,
            boolean selectedByValidation,
            String featureContractVersion,
            double inferenceLatencyMs) {}

    private record MasteryEstimateResponse(
            UUID skillId, BigDecimal probability, ModelVersionRef estimatorModel) {}

    private record RiskPredictRequest(
            TraceRef trace,
            UUID studentId,
            UUID courseId,
            ObservationWindow observationWindow,
            String featureContractVersion,
            List<RiskFeatureRequest> features) {}

    private record ObservationWindow(int startDayInclusive, int endDayInclusive) {}

    private record RiskFeatureRequest(String name, double value) {}

    private record RiskPredictResponse(
            TraceRef trace,
            BigDecimal probability,
            boolean calibrated,
            String riskBand,
            BigDecimal mediumThreshold,
            BigDecimal highThreshold,
            ModelVersionRef model,
            String calibratorVersion,
            String featureContractVersion,
            double inferenceLatencyMs) {}

    private record ExplainRequest(
            TraceRef trace,
            UUID studentId,
            UUID courseId,
            String riskModelVersion,
            String featureContractVersion,
            List<RiskFeatureRequest> features) {}

    private record ExplainResponse(
            TraceRef trace,
            String riskModelVersion,
            BigDecimal baseValue,
            BigDecimal outputValue,
            String outputUnit,
            List<EvidenceResponse> factors,
            OffsetDateTime generatedAt,
            double inferenceLatencyMs) {}

    private record EvidenceResponse(
            TraceRef trace,
            int rank,
            String featureName,
            double rawValue,
            String direction,
            BigDecimal contribution,
            BigDecimal baseValue,
            String outputUnit,
            String riskModelVersion) {}

    record KnowledgeResult(
            List<MasteryEstimate> mastery,
            BigDecimal nextCorrectProbability,
            ModelVersionRef masteryModel,
            ModelVersionRef nextModel) {}

    record MasteryEstimate(UUID skillId, BigDecimal probability) {}

    private record RiskResult(
            BigDecimal probability,
            String riskBand,
            BigDecimal baseValue,
            boolean calibrated,
            ModelVersionRef model) {}
}
