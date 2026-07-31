package com.edutwin.analysis;

import com.edutwin.api.model.ModelVersionRef;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record AnalysisInference(
        Map<UUID, SkillPrediction> skills,
        BigDecimal nextCorrectProbability,
        BigDecimal riskProbability,
        String riskBand,
        BigDecimal riskBaseValue,
        boolean calibrated,
        String knowledgeFeatureContractVersion,
        String riskFeatureContractVersion,
        Map<String, Double> riskFeatures,
        List<Evidence> evidence,
        Set<String> degradedStages,
        Set<ModelVersionRef> effectiveModels) {

    public AnalysisInference {
        skills = Collections.unmodifiableMap(new LinkedHashMap<>(skills));
        riskFeatures = Collections.unmodifiableMap(new LinkedHashMap<>(riskFeatures));
        evidence = List.copyOf(evidence);
        degradedStages = Collections.unmodifiableSet(new LinkedHashSet<>(degradedStages));
        effectiveModels = Collections.unmodifiableSet(new LinkedHashSet<>(effectiveModels));
    }

    public boolean degraded() {
        return !degradedStages.isEmpty();
    }

    public record SkillPrediction(
            UUID skillId,
            BigDecimal masteryProbability,
            BigDecimal nextCorrectProbability,
            String masteryModelVersion,
            String nextModelVersion,
            String estimator) {}

    public record Evidence(
            int rank,
            String featureName,
            double rawValue,
            String direction,
            BigDecimal contribution,
            BigDecimal baseValue,
            String outputUnit,
            String riskModelVersion) {}
}
