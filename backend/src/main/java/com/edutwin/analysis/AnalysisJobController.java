package com.edutwin.analysis;

import com.edutwin.api.model.AnalysisJob;
import com.edutwin.api.model.AnalysisJobStatus;
import com.edutwin.api.model.SseJobEvent;
import com.edutwin.shared.web.DomainException;
import com.edutwin.shared.web.PublicPayloadSanitizer;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class AnalysisJobController {

    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(2).toMillis();
    private static final long POLL_MILLIS = 100;
    private static final long HEARTBEAT_MILLIS = Duration.ofSeconds(15).toMillis();

    private final AnalysisJobProjectionService projectionService;
    private final AnalysisJobRepository repository;
    private final TaskExecutor taskExecutor;
    private final PublicPayloadSanitizer sanitizer;

    public AnalysisJobController(
            AnalysisJobProjectionService projectionService,
            AnalysisJobRepository repository,
            @Qualifier("sseTaskExecutor") TaskExecutor taskExecutor,
            PublicPayloadSanitizer sanitizer) {
        this.projectionService = projectionService;
        this.repository = repository;
        this.taskExecutor = taskExecutor;
        this.sanitizer = sanitizer;
    }

    @GetMapping(value = "/api/v1/analysis/jobs/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@scopeAuthorization.canAccessJob(authentication, #jobId)")
    public ResponseEntity<AnalysisJob> getAnalysisJob(@PathVariable UUID jobId) {
        return ResponseEntity.ok(projectionService.get(jobId));
    }

    @GetMapping(
            value = "/api/v1/analysis/jobs/{jobId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@scopeAuthorization.canAccessJob(authentication, #jobId)")
    public SseEmitter streamAnalysisJob(
            @PathVariable UUID jobId,
            @RequestHeader(name = "Last-Event-ID", required = false) @Nullable String lastEventId) {
        long resumeAfter = parseLastEventId(lastEventId);
        projectionService.get(jobId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        taskExecutor.execute(() -> emitPersistedEvents(jobId, resumeAfter, emitter, closed));
        return emitter;
    }

    private void emitPersistedEvents(
            UUID jobId, long resumeAfter, SseEmitter emitter, AtomicBoolean closed) {
        long sequence = resumeAfter;
        long lastHeartbeat = System.currentTimeMillis();
        long deadline = System.currentTimeMillis() + SSE_TIMEOUT_MILLIS;
        try {
            while (!closed.get() && System.currentTimeMillis() < deadline) {
                List<AnalysisJobRepository.JobEvent> events =
                        repository.findEventsAfter(jobId, sequence, 100);
                for (AnalysisJobRepository.JobEvent event : events) {
                    AnalysisJob eventJob = persistedJob(jobId);
                    SseJobEvent.EventTypeEnum publicEventType = publicEventType(event.eventType());
                    SseJobEvent data = new SseJobEvent(
                            event.sequence(),
                            publicEventType,
                            event.occurredAt(),
                            eventJob);
                    emitter.send(SseEmitter.event()
                            .id(Long.toString(event.sequence()))
                            .name(publicEventType.getValue())
                            .data(sanitizer.sanitize(data), MediaType.APPLICATION_JSON));
                    sequence = event.sequence();
                }

                AnalysisJob job = persistedJob(jobId);
                if (isTerminal(job) && sequence >= job.getLastEventSequence()) {
                    emitter.complete();
                    return;
                }

                long now = System.currentTimeMillis();
                if (now - lastHeartbeat >= HEARTBEAT_MILLIS) {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                    lastHeartbeat = now;
                }
                Thread.sleep(POLL_MILLIS);
            }
            if (!closed.get()) {
                emitter.complete();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(exception);
        } catch (IOException | RuntimeException exception) {
            if (!closed.get()) {
                emitter.completeWithError(exception);
            }
        }
    }

    private static boolean isTerminal(AnalysisJob job) {
        return job.getStatus() == AnalysisJobStatus.COMPLETED
                || job.getStatus() == AnalysisJobStatus.FAILED;
    }

    private AnalysisJob persistedJob(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new DomainException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "ANALYSIS_JOB_NOT_FOUND",
                        "The requested analysis job does not exist."));
    }

    static SseJobEvent.EventTypeEnum publicEventType(String internalEventType) {
        String value = switch (internalEventType) {
            case "analysis.requested.v1", "job.queued" -> "job.queued";
            case "analysis.started.v1", "job.processing" -> "job.processing";
            case "predictions.computed.v1", "predictions.computed" ->
                    "predictions.computed";
            case "twin.snapshot-created.v1", "twin.snapshot-created" ->
                    "twin.snapshot-created";
            case "learning-plan.created.v1", "learning-plan.created" ->
                    "learning-plan.created";
            case "analysis.completed.v1", "job.completed" -> "job.completed";
            case "analysis.failed.v1", "job.failed" -> "job.failed";
            default -> throw new IllegalStateException(
                    "Unsupported public SSE event type: " + internalEventType);
        };
        return SseJobEvent.EventTypeEnum.fromValue(value);
    }

    private static long parseLastEventId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new DomainException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_LAST_EVENT_ID",
                    "Last-Event-ID must be a non-negative integer.");
        }
    }
}
