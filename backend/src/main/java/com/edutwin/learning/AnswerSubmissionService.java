package com.edutwin.learning;

import com.edutwin.ai.AiRuntimeConfigurationService;
import com.edutwin.api.model.AnalysisJob;
import com.edutwin.api.model.AnswerSubmission;
import com.edutwin.api.model.AnswerHistoryItem;
import com.edutwin.api.model.AnswerHistoryPage;
import com.edutwin.api.model.AnswerHistorySkill;
import com.edutwin.api.model.PracticeChoice;
import com.edutwin.api.model.PracticeQuestion;
import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.learning.JdbcAnswerSubmissionRepository.AnswerHistoryFact;
import com.edutwin.learning.JdbcAnswerSubmissionRepository.AnswerHistorySkillFact;
import com.edutwin.learning.JdbcAnswerSubmissionRepository.IdempotencyFact;
import com.edutwin.learning.JdbcAnswerSubmissionRepository.QuestionFact;
import com.edutwin.provenance.ActiveVersionCatalog;
import com.edutwin.shared.web.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AnswerSubmissionService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final int MAX_ATTEMPT_NUMBER = 65_535;

    private final JdbcAnswerSubmissionRepository repository;
    private final ActiveVersionCatalog versionCatalog;
    private final AiRuntimeConfigurationService aiConfiguration;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AnswerSubmissionService(
            JdbcAnswerSubmissionRepository repository,
            ActiveVersionCatalog versionCatalog,
            AiRuntimeConfigurationService aiConfiguration,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.versionCatalog = versionCatalog;
        this.aiConfiguration = aiConfiguration;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public AnswerSubmissionResult submit(
            EduTwinPrincipal principal,
            UUID courseId,
            String idempotencyKey,
            AnswerSubmission submission,
            UUID correlationId) {
        requireStudent(principal);
        requireSubmission(submission);

        String resourcePath = "/api/v1/courses/" + courseId + "/answers";
        String keyHash = sha256(idempotencyKey);
        String requestHash = canonicalRequestHash(submission);

        Optional<AnswerSubmissionResult> existing = resolveReplay(
                repository.findIdempotency(
                        principal.userId(), resourcePath, keyHash, false),
                requestHash);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        try {
            AnswerSubmissionResult result = transactionTemplate.execute(status -> createSubmission(
                    principal,
                    courseId,
                    resourcePath,
                    keyHash,
                    requestHash,
                    submission,
                    correlationId));
            if (result == null) {
                throw new IllegalStateException("The answer transaction returned no result.");
            }
            return result;
        } catch (DataIntegrityViolationException exception) {
            Optional<AnswerSubmissionResult> winner = resolveReplay(
                    repository.findIdempotency(
                            principal.userId(), resourcePath, keyHash, false),
                    requestHash);
            if (winner.isPresent()) {
                return winner.orElseThrow();
            }
            throw exception;
        }
    }

    public PracticeQuestion nextQuestion(EduTwinPrincipal principal, UUID courseId) {
        requireStudent(principal);
        AnalysisJob sourceJob = repository.findCurrentSnapshotJob(courseId, principal.userId())
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND,
                        "TWIN_SNAPSHOT_NOT_FOUND",
                        "No current twin snapshot is available for practice selection."));
        QuestionFact question = repository.findNextPracticeQuestion(courseId, principal.userId())
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND,
                        "PRACTICE_QUESTION_NOT_FOUND",
                        "No active practice question is available for the course."));
        List<PracticeChoice> choices = parseChoices(question);
        requireSkills(question);
        return new PracticeQuestion(
                question.id(),
                courseId,
                question.prompt(),
                PracticeQuestion.QuestionTypeEnum.SINGLE_CHOICE,
                choices,
                new LinkedHashSet<>(question.skillIds()))
                .trace(sourceJob.getTrace());
    }

    public AnswerHistoryPage history(
            EduTwinPrincipal principal, UUID courseId, int page, int size) {
        requireStudent(principal);
        if (page < 0 || size < 1 || size > 100) {
            throw new DomainException(
                    HttpStatus.BAD_REQUEST,
                    "ANSWER_HISTORY_PAGE_INVALID",
                    "Page must be non-negative and size must be between 1 and 100.");
        }
        int total = repository.countAnswerHistory(courseId, principal.userId());
        List<AnswerHistoryFact> facts = repository.findAnswerHistory(
                courseId, principal.userId(), page, size);
        Map<UUID, List<AnswerHistorySkillFact>> skillFacts =
                repository.findAnswerHistorySkills(
                        facts.stream().map(AnswerHistoryFact::answerEventId).toList());
        List<AnswerHistoryItem> items = facts.stream()
                .map(fact -> historyItem(fact, skillFacts.getOrDefault(
                        fact.answerEventId(), List.of())))
                .toList();
        return new AnswerHistoryPage(items, total, page, size);
    }

    private AnswerSubmissionResult createSubmission(
            EduTwinPrincipal principal,
            UUID courseId,
            String resourcePath,
            String keyHash,
            String requestHash,
            AnswerSubmission submission,
            UUID correlationId) {
        if (!repository.lockActiveEnrollment(courseId, principal.userId())) {
            throw new DomainException(
                    HttpStatus.FORBIDDEN,
                    "COURSE_ACCESS_DENIED",
                    "An active student enrollment is required for answer submission.");
        }

        Optional<AnswerSubmissionResult> existing = resolveReplay(
                repository.findIdempotency(
                        principal.userId(), resourcePath, keyHash, true),
                requestHash);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }

        QuestionFact question = repository.findQuestion(courseId, submission.getQuestionId())
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND,
                        "QUESTION_NOT_FOUND",
                        "The question is not active in the requested course."));
        List<PracticeChoice> choices = parseChoices(question);
        requireSkills(question);
        boolean selectedChoiceExists = choices.stream()
                .anyMatch(choice -> choice.getChoiceId().equals(submission.getSelectedChoiceId()));
        if (!selectedChoiceExists) {
            throw new DomainException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_CHOICE",
                    "The selected choice is not defined for the question.");
        }

        ActiveVersionCatalog.Catalog catalog = versionCatalog.loadForCourse(courseId);
        Instant acceptedAt = Instant.now();
        long responseTimeMs = Math.max(
                0L,
                Math.min(
                        Integer.MAX_VALUE,
                        Duration.between(submission.getOccurredAt().toInstant(), acceptedAt).toMillis()));
        long eventSequence = repository.nextEventSequence(courseId, principal.userId());
        int attemptNumber = repository.nextAttemptNumber(
                courseId, principal.userId(), submission.getQuestionId());
        if (attemptNumber > MAX_ATTEMPT_NUMBER) {
            throw new DomainException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "ATTEMPT_LIMIT_REACHED",
                    "The question attempt number exceeds the persisted event contract.");
        }

        UUID answerEventId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        boolean correct = question.correctAnswer().equals(submission.getSelectedChoiceId());
        String envelope = requestedEnvelope(
                eventId,
                correlationId,
                answerEventId,
                jobId,
                snapshotId,
                principal.userId(),
                courseId,
                acceptedAt,
                eventSequence,
                correct,
                responseTimeMs,
                question.skillIds(),
                keyHash,
                catalog);

        repository.insertAnswerEvent(
                answerEventId,
                courseId,
                principal.userId(),
                question,
                submission.getSelectedChoiceId(),
                correct,
                responseTimeMs,
                attemptNumber,
                eventSequence,
                submission.getOccurredAt().toInstant());
        repository.insertAnalysisJob(
                jobId,
                answerEventId,
                courseId,
                principal.userId(),
                snapshotId,
                correlationId,
                catalog,
                aiConfiguration.currentVersionId());
        repository.insertJobEvent(eventId, jobId, envelope);
        repository.insertOutbox(eventId, jobId, envelope);
        repository.insertIdempotency(
                UUID.randomUUID(),
                principal.userId(),
                resourcePath,
                keyHash,
                requestHash,
                jobId,
                acceptedAt.plus(IDEMPOTENCY_TTL));

        return new AnswerSubmissionResult(repository.getJob(jobId), false);
    }

    private Optional<AnswerSubmissionResult> resolveReplay(
            Optional<IdempotencyFact> fact, String requestHash) {
        if (fact.isEmpty()) {
            return Optional.empty();
        }
        IdempotencyFact existing = fact.orElseThrow();
        if (!MessageDigest.isEqual(
                existing.requestSha256().getBytes(StandardCharsets.US_ASCII),
                requestHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "The Idempotency-Key is already bound to a different canonical request.");
        }
        return Optional.of(new AnswerSubmissionResult(
                repository.getJob(existing.analysisJobId()), true));
    }

    private String requestedEnvelope(
            UUID eventId,
            UUID correlationId,
            UUID answerEventId,
            UUID jobId,
            UUID snapshotId,
            UUID studentId,
            UUID courseId,
            Instant acceptedAt,
            long eventSequence,
            boolean correct,
            long responseTimeMs,
            List<UUID> skillIds,
            String keyHash,
            ActiveVersionCatalog.Catalog catalog) {
        String timestamp = OffsetDateTime.ofInstant(acceptedAt, ZoneOffset.UTC).toString();
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", "analysis.requested.v1");
        envelope.put("eventVersion", 1);
        envelope.put("occurredAt", timestamp);
        envelope.put("producedAt", timestamp);
        envelope.put("traceId", correlationId.toString().replace("-", ""));
        envelope.put("correlationId", correlationId.toString());
        envelope.put("causationId", answerEventId.toString());
        envelope.put("producer", "business-backend");
        envelope.put("aggregateType", "ANALYSIS_JOB");
        envelope.put("aggregateId", jobId.toString());
        envelope.put("aggregateVersion", 1);
        envelope.put("studentId", studentId.toString());
        envelope.put("courseId", courseId.toString());
        envelope.put("answerEventId", answerEventId.toString());
        envelope.put("answerEventVersion", 1);
        envelope.put("analysisJobId", jobId.toString());
        envelope.put("analysisJobVersion", 1);
        envelope.put("snapshotId", snapshotId.toString());
        envelope.putNull("snapshotVersion");
        envelope.putNull("learningPlanId");
        envelope.putNull("learningPlanVersion");
        envelope.set("dataVersionIds", objectMapper.valueToTree(catalog.dataVersionIds()));
        envelope.set("modelVersionIds", objectMapper.valueToTree(catalog.modelVersionIds()));

        ObjectNode payload = envelope.putObject("payload");
        payload.put("requestedAt", timestamp);
        payload.put("submissionSource", "PUBLIC_API");
        payload.put("idempotencyKeyHash", keyHash);
        payload.put("answerSequence", eventSequence);
        payload.put("answerCorrect", correct);
        payload.put("responseTimeMs", responseTimeMs);
        ArrayNode knowledgeComponents = payload.putArray("knowledgeComponentIds");
        skillIds.forEach(skillId -> knowledgeComponents.add(skillId.toString()));

        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The analysis.requested.v1 envelope could not be serialized.", exception);
        }
    }

    private AnswerHistoryItem historyItem(
            AnswerHistoryFact fact, List<AnswerHistorySkillFact> skills) {
        List<PracticeChoice> choices = parseChoices(fact.answerType(), fact.optionsJson());
        List<AnswerHistorySkill> historySkills = skills.stream()
                .map(skill -> new AnswerHistorySkill(skill.skillId(), skill.name()))
                .toList();
        return new AnswerHistoryItem(
                fact.answerEventId(),
                fact.questionId(),
                fact.prompt(),
                resolveChoice(choices, fact.selectedAnswer()),
                resolveChoice(choices, fact.correctAnswer()),
                fact.correct(),
                historySkills,
                fact.attemptNumber(),
                fact.eventSequence(),
                fact.responseTimeMs(),
                fact.occurredAt(),
                fact.imported()
                        ? AnswerHistoryItem.SourceTypeEnum.IMPORTED
                        : AnswerHistoryItem.SourceTypeEnum.ONLINE);
    }

    private static PracticeChoice resolveChoice(List<PracticeChoice> choices, String choiceId) {
        return choices.stream()
                .filter(choice -> choice.getChoiceId().equals(choiceId))
                .findFirst()
                .orElseGet(() -> new PracticeChoice(choiceId, choiceId));
    }

    private List<PracticeChoice> parseChoices(QuestionFact question) {
        return parseChoices(question.answerType(), question.optionsJson());
    }

    private List<PracticeChoice> parseChoices(String answerType, String optionsJson) {
        if (!"SINGLE_CHOICE".equals(answerType)) {
            throw new DomainException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "UNSUPPORTED_QUESTION_TYPE",
                    "The public practice contract accepts only SINGLE_CHOICE questions.");
        }
        try {
            JsonNode root = objectMapper.readTree(optionsJson);
            JsonNode choicesNode = root != null && root.isObject() && root.has("choices")
                    ? root.get("choices")
                    : root;
            List<PracticeChoice> choices = new ArrayList<>();
            if (choicesNode != null && choicesNode.isArray()) {
                for (JsonNode choice : choicesNode) {
                    if (choice.isObject()) {
                        String id = firstText(choice, "choiceId", "id", "value");
                        String label = firstText(choice, "label", "text");
                        choices.add(new PracticeChoice(id, label));
                    } else if (choice.isTextual()) {
                        choices.add(new PracticeChoice(choice.asText(), choice.asText()));
                    }
                }
            } else if (choicesNode != null && choicesNode.isObject()) {
                choicesNode.fields().forEachRemaining(entry -> choices.add(
                        new PracticeChoice(entry.getKey(), entry.getValue().asText())));
            }
            validateChoices(choices);
            return List.copyOf(choices);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new DomainException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "QUESTION_OPTIONS_INVALID",
                    "The question choices do not satisfy the practice contract.");
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        throw new IllegalArgumentException("Choice field is missing.");
    }

    private static void validateChoices(List<PracticeChoice> choices) {
        if (choices.size() < 2) {
            throw new IllegalArgumentException("At least two choices are required.");
        }
        Set<String> ids = new HashSet<>();
        for (PracticeChoice choice : choices) {
            if (choice.getChoiceId() == null
                    || choice.getChoiceId().isBlank()
                    || choice.getLabel() == null
                    || choice.getLabel().isBlank()
                    || !ids.add(choice.getChoiceId())) {
                throw new IllegalArgumentException("Choice identifiers and labels must be non-empty and unique.");
            }
        }
    }

    private static void requireSkills(QuestionFact question) {
        if (question.skillIds().isEmpty()) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "QUESTION_SKILLS_MISSING",
                    "The question has no persisted knowledge-component association.");
        }
    }

    private static void requireStudent(EduTwinPrincipal principal) {
        if (principal == null || !principal.enabled() || principal.role() != UserRole.STUDENT) {
            throw new DomainException(
                    HttpStatus.FORBIDDEN,
                    "STUDENT_ROLE_REQUIRED",
                    "Only an enabled student may use the practice endpoint.");
        }
    }

    private static void requireSubmission(AnswerSubmission submission) {
        if (submission == null
                || submission.getQuestionId() == null
                || submission.getSelectedChoiceId() == null
                || submission.getSelectedChoiceId().isBlank()
                || submission.getOccurredAt() == null) {
            throw new DomainException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_ANSWER_SUBMISSION",
                    "Question, selected choice, and occurrence time are required.");
        }
    }

    private String canonicalRequestHash(AnswerSubmission submission) {
        ObjectNode canonical = objectMapper.createObjectNode();
        canonical.put("occurredAt", submission.getOccurredAt().toInstant().toString());
        canonical.put("questionId", submission.getQuestionId().toString());
        canonical.put("selectedChoiceId", submission.getSelectedChoiceId());
        try {
            return sha256(objectMapper.writeValueAsString(canonical));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("The canonical answer request could not be serialized.", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}
