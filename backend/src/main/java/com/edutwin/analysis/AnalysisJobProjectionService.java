package com.edutwin.analysis;

import com.edutwin.api.model.AnalysisJob;
import com.edutwin.api.model.AnalysisJobStatus;
import com.edutwin.shared.web.DomainException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AnalysisJobProjectionService {

    private static final Duration ACTIVE_TTL = Duration.ofMinutes(5);
    private static final Duration TERMINAL_TTL = Duration.ofHours(24);

    private final AnalysisJobRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisJobProjectionService(
            AnalysisJobRepository repository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public AnalysisJob get(UUID jobId) {
        String key = cacheKey(jobId);
        AnalysisJob cached = readCache(key);
        if (cached != null) {
            return cached;
        }

        AnalysisJob persisted = repository.findById(jobId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND,
                        "ANALYSIS_JOB_NOT_FOUND",
                        "The requested analysis job does not exist."));
        writeCache(persisted);
        return persisted;
    }

    public void refresh(UUID jobId) {
        repository.findById(jobId).ifPresent(this::writeCache);
    }

    public void evict(UUID jobId) {
        try {
            redisTemplate.delete(cacheKey(jobId));
        } catch (DataAccessException ignored) {
            // MySQL remains authoritative when the projection store is unavailable.
        }
    }

    private AnalysisJob readCache(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            return json == null ? null : objectMapper.readValue(json, AnalysisJob.class);
        } catch (DataAccessException | JsonProcessingException ignored) {
            return null;
        }
    }

    private void writeCache(AnalysisJob job) {
        try {
            Duration ttl = isTerminal(job.getStatus()) ? TERMINAL_TTL : ACTIVE_TTL;
            redisTemplate.opsForValue().set(
                    cacheKey(job.getJobId()), objectMapper.writeValueAsString(job), ttl);
        } catch (DataAccessException | JsonProcessingException ignored) {
            // Projection failures never change the persisted task result.
        }
    }

    private static boolean isTerminal(AnalysisJobStatus status) {
        return status == AnalysisJobStatus.COMPLETED || status == AnalysisJobStatus.FAILED;
    }

    private static String cacheKey(UUID jobId) {
        return "job:" + jobId;
    }
}
