package com.edutwin.diagnosis;

import com.edutwin.ai.AiRuntimeConfigurationService;
import com.edutwin.ai.AiRuntimeConfigurationService.Snapshot;
import com.edutwin.analysis.AnalysisInference;
import com.edutwin.analysis.AnalysisWorkItemRepository.WorkItem;
import com.edutwin.analysis.AnalysisWorkflowStore.DiagnosisContent;
import com.edutwin.analysis.AnalysisWorkflowStore.SnapshotResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class DeepSeekDiagnosisService {

    private final AiRuntimeConfigurationService aiConfiguration;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbcClient;
    private final boolean forceTimeout;
    private final Duration timeout;

    public DeepSeekDiagnosisService(
            AiRuntimeConfigurationService aiConfiguration,
            ObjectMapper objectMapper,
            JdbcClient jdbcClient,
            @Value("${edutwin.ai.force-timeout:false}") boolean forceTimeout,
            @Value("${edutwin.ai.timeout:PT8S}") Duration timeout) {
        this.aiConfiguration = aiConfiguration;
        this.objectMapper = objectMapper;
        this.jdbcClient = jdbcClient;
        this.forceTimeout = forceTimeout;
        this.timeout = timeout;
    }

    public DiagnosisContent diagnose(
            WorkItem workItem, AnalysisInference inference, SnapshotResult snapshot) {
        Snapshot configuration;
        try {
            configuration = aiConfiguration.configuration(workItem.aiConfigurationVersionId());
        } catch (RuntimeException exception) {
            return fallback(workItem, inference, snapshot, null, "LLM_UNAVAILABLE");
        }
        if (!configuration.enabled()) {
            return fallback(workItem, inference, snapshot, configuration, "LLM_UNAVAILABLE");
        }

        UUID invocationId = UUID.randomUUID();
        String requestJson = requestJson(workItem, inference, snapshot);
        Instant startedAt = Instant.now();
        if (forceTimeout) {
            recordInvocation(
                    invocationId,
                    workItem.job().getJobId(),
                    "TIMED_OUT",
                    requestJson,
                    null,
                    startedAt,
                    configuration,
                    "LLM_TIMEOUT");
            return fallback(workItem, inference, snapshot, configuration, "LLM_TIMEOUT");
        }
        try {
            TwinEvidenceTool tool = new TwinEvidenceTool(requestJson);
            DiagnosisResponse response = invokeWithTimeout(() -> aiConfiguration.client(configuration)
                    .prompt()
                    .system("""
                            返回结构化学习风险诊断，并且只调用一次 getTwinEvidence。
                            仅使用工具返回的学情画像和证据，不得虚构原因、修改任务或补充证据。
                            summary、strengths、concerns 和 recommendedActions 必须使用简洁的简体中文。
                            必要的模型名和技术专名可以保留原文，其余自然语言不得使用英文句子。
                            """)
                    .user("为给定的学习分析任务生成诊断。")
                    .tools(tool)
                    .call()
                    .entity(DiagnosisResponse.class));
            if (response == null
                    || !tool.called()
                    || blank(response.summary())
                    || response.strengths() == null
                    || response.concerns() == null
                    || response.recommendedActions() == null
                    || response.strengths().isEmpty()
                    || response.concerns().isEmpty()
                    || response.recommendedActions().isEmpty()
                    || !ChineseDiagnosisText.containsChinese(response.summary())
                    || !ChineseDiagnosisText.allChinese(response.strengths())
                    || !ChineseDiagnosisText.allChinese(response.concerns())
                    || !ChineseDiagnosisText.allChinese(response.recommendedActions())) {
                throw new IllegalStateException("The configured model returned an invalid structured diagnosis.");
            }
            recordInvocation(
                    invocationId,
                    workItem.job().getJobId(),
                    "SUCCEEDED",
                    requestJson,
                    json(response),
                    startedAt,
                    configuration,
                    null);
            return new DiagnosisContent(
                    response.summary(),
                    response.strengths(),
                    response.concerns(),
                    response.recommendedActions(),
                    true,
                    false,
                    "OPENAI_COMPATIBLE",
                    configuration.model(),
                    configuration.model(),
                    configuration.versionId(),
                    null);
        } catch (TimeoutException exception) {
            recordInvocation(
                    invocationId,
                    workItem.job().getJobId(),
                    "TIMED_OUT",
                    requestJson,
                    null,
                    startedAt,
                    configuration,
                    "LLM_TIMEOUT");
            return fallback(workItem, inference, snapshot, configuration, "LLM_TIMEOUT");
        } catch (RuntimeException exception) {
            recordInvocation(
                    invocationId,
                    workItem.job().getJobId(),
                    "FAILED",
                    requestJson,
                    null,
                    startedAt,
                    configuration,
                    "LLM_CALL_FAILED");
            return fallback(workItem, inference, snapshot, configuration, "LLM_UNAVAILABLE");
        }
    }

    private DiagnosisContent fallback(
            WorkItem workItem,
            AnalysisInference inference,
            SnapshotResult snapshot,
            Snapshot configuration,
            String degradationReason) {
        String strongest = inference.evidence().isEmpty()
                ? "当前暂无可用的模型影响因素。"
                : "当前影响最大的模型指标为“"
                        + ChineseDiagnosisText.featureLabel(inference.evidence().get(0).featureName())
                        + "”，主要"
                        + ChineseDiagnosisText.directionLabel(inference.evidence().get(0).direction())
                        + "。";
        String weakSkill = snapshot.skills().values().stream()
                .min(java.util.Comparator.comparing(
                        com.edutwin.analysis.AnalysisWorkItemRepository.SkillState::masteryProbability))
                .map(com.edutwin.analysis.AnalysisWorkItemRepository.SkillState::skillName)
                .orElse("当前薄弱知识点");
        return new DiagnosisContent(
                "当前风险等级为" + ChineseDiagnosisText.riskLabel(inference.riskBand())
                        + "，风险概率为" + ChineseDiagnosisText.percent(inference.riskProbability()) + "。",
                List.of(workItem.answer().correct()
                        ? "最近一道针对性练习回答正确。"
                        : "最近一道练习已纳入完整学习记录，可继续复盘。"),
                List.of(strongest),
                List.of("完成“" + weakSkill + "”相关的规则生成练习任务。"),
                false,
                true,
                "TEMPLATE",
                configuration == null ? "unconfigured" : configuration.model(),
                "template-diagnosis-v1",
                configuration == null ? null : configuration.versionId(),
                degradationReason);
    }

    private String requestJson(
            WorkItem workItem, AnalysisInference inference, SnapshotResult snapshot) {
        Map<String, Object> value = Map.of(
                "analysisJobId", workItem.job().getJobId(),
                "snapshotId", snapshot.snapshotId(),
                "snapshotVersion", snapshot.snapshotVersion(),
                "riskProbability", inference.riskProbability(),
                "riskBand", inference.riskBand(),
                "mastery", snapshot.skills(),
                "evidence", inference.evidence());
        return json(value);
    }

    private <T> T invokeWithTimeout(Callable<T> callable) throws TimeoutException {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<T> future = executor.submit(callable);
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Model invocation was interrupted.", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Model invocation failed.", cause);
            } catch (TimeoutException exception) {
                future.cancel(true);
                throw exception;
            }
        }
    }

    private void recordInvocation(
            UUID invocationId,
            UUID jobId,
            String status,
            String request,
            String response,
            Instant startedAt,
            Snapshot configuration,
            String errorCode) {
        jdbcClient.sql("""
                        INSERT INTO ai_invocation(
                            id, analysis_job_id, ai_configuration_version_id,
                            provider, model_name, tool_name,
                            request_sha256, response_sha256, status, latency_ms, error_code)
                        VALUES (
                            :id, :jobId, :configurationVersionId,
                            'OPENAI_COMPATIBLE', :modelName, 'getTwinEvidence',
                            :requestSha, :responseSha, :status, :latencyMs, :errorCode)
                        """)
                .param("id", invocationId.toString())
                .param("jobId", jobId.toString())
                .param("configurationVersionId", configuration == null || configuration.versionId() == null
                        ? null : configuration.versionId().toString())
                .param("modelName", configuration == null ? "unavailable" : configuration.model())
                .param("requestSha", sha256(request))
                .param("responseSha", response == null ? null : sha256(response))
                .param("status", status)
                .param("latencyMs", Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis()))
                .param("errorCode", errorCode)
                .update();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Diagnosis JSON serialization failed.", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static final class TwinEvidenceTool {
        private final String evidenceJson;
        private volatile boolean called;

        TwinEvidenceTool(String evidenceJson) {
            this.evidenceJson = evidenceJson;
        }

        @org.springframework.ai.tool.annotation.Tool(
                name = "getTwinEvidence",
                description = "Return the authoritative twin snapshot and model-computed evidence.")
        public String getTwinEvidence() {
            called = true;
            return evidenceJson;
        }

        boolean called() {
            return called;
        }
    }

    private record DiagnosisResponse(
            String summary,
            List<String> strengths,
            List<String> concerns,
            List<String> recommendedActions) {}
}
