package com.edutwin.analysis;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    private final JdbcClient jdbcClient;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String streamName;
    private final int batchSize;

    public OutboxRelay(
            JdbcClient jdbcClient,
            StringRedisTemplate redisTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${edutwin.analysis.stream:analysis-events}") String streamName,
            @Value("${edutwin.analysis.outbox-batch-size:100}") int batchSize) {
        this.jdbcClient = jdbcClient;
        this.redisTemplate = redisTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.streamName = streamName;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${edutwin.analysis.outbox-poll-delay:PT0.1S}")
    public void relayAvailable() {
        List<OutboxMessage> messages = transactionTemplate.execute(status -> claimBatch());
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (OutboxMessage message : messages) {
            publish(message);
        }
    }

    private List<OutboxMessage> claimBatch() {
        List<OutboxMessage> selected = jdbcClient.sql("""
                        SELECT id, aggregate_id, event_type, schema_version, payload, attempts
                        FROM outbox_event
                        WHERE status = 'PENDING'
                          AND available_at <= CURRENT_TIMESTAMP(6)
                        ORDER BY created_at, id
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("batchSize", batchSize)
                    .query((resultSet, rowNumber) -> new OutboxMessage(
                            UUID.fromString(resultSet.getString("id")),
                            UUID.fromString(resultSet.getString("aggregate_id")),
                            resultSet.getString("event_type"),
                        resultSet.getString("schema_version"),
                        resultSet.getString("payload"),
                        resultSet.getInt("attempts") + 1))
                .list();

        Instant leaseUntil = Instant.now().plus(CLAIM_LEASE);
        List<OutboxMessage> claimed = new ArrayList<>(selected.size());
        for (OutboxMessage message : selected) {
            int updated = jdbcClient.sql("""
                            UPDATE outbox_event
                            SET attempts = :attempts, available_at = :leaseUntil
                            WHERE id = :id AND status = 'PENDING'
                            """)
                    .param("attempts", message.deliveryAttempt())
                    .param("leaseUntil", Timestamp.from(leaseUntil))
                    .param("id", message.eventId().toString())
                    .update();
            if (updated == 1) {
                claimed.add(message);
            }
        }
        return claimed;
    }

    private void publish(OutboxMessage message) {
        OffsetDateTime publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event_id", message.eventId().toString());
        fields.put("event_type", message.eventType());
        fields.put("event_version", message.schemaVersion());
        fields.put("payload", message.payload());
        fields.put("published_at", publishedAt.toString());
        fields.put("delivery_attempt", Integer.toString(message.deliveryAttempt()));

        try {
            MapRecord<String, String, String> record =
                    StreamRecords.string(fields).withStreamKey(streamName);
            RecordId streamId = redisTemplate.opsForStream().add(record);
            if (streamId == null) {
                throw new IllegalStateException("Redis returned no Stream entry identifier.");
            }
            markPublished(message, streamId.getValue(), publishedAt.toInstant());
        } catch (RuntimeException exception) {
            reschedule(message);
            LOGGER.warn(
                    "Outbox event {} could not be published on attempt {}.",
                    message.eventId(),
                    message.deliveryAttempt(),
                    exception);
        }
    }

    private void markPublished(OutboxMessage message, String streamId, Instant publishedAt) {
        transactionTemplate.executeWithoutResult(status -> {
            jdbcClient.sql("""
                        UPDATE outbox_event
                        SET status = 'PUBLISHED',
                            published_at = :publishedAt,
                            stream_message_id = :streamId
                        WHERE id = :eventId AND status = 'PENDING'
                        """)
                .param("publishedAt", Timestamp.from(publishedAt))
                .param("streamId", streamId)
                    .param("eventId", message.eventId().toString())
                    .update();
            if (message.deliveryAttempt() > 1) {
                jdbcClient.sql("""
                                UPDATE analysis_job
                                SET degraded = TRUE,
                                    degraded_stages = CASE
                                        WHEN JSON_CONTAINS(
                                            COALESCE(degraded_stages, JSON_ARRAY()),
                                            JSON_QUOTE('REDIS_RETRY'))
                                        THEN COALESCE(degraded_stages, JSON_ARRAY())
                                        ELSE JSON_ARRAY_APPEND(
                                            COALESCE(degraded_stages, JSON_ARRAY()),
                                            '$', 'REDIS_RETRY')
                                    END
                                WHERE id = :jobId
                                """)
                        .param("jobId", message.jobId().toString())
                        .update();
            }
        });
    }

    private void reschedule(OutboxMessage message) {
        long exponent = Math.min(8, Math.max(0, message.deliveryAttempt() - 1));
        long delaySeconds = Math.min(300, 1L << exponent);
        Instant availableAt = Instant.now().plusSeconds(delaySeconds);
        transactionTemplate.executeWithoutResult(status -> jdbcClient.sql("""
                        UPDATE outbox_event
                        SET available_at = :availableAt
                        WHERE id = :eventId AND status = 'PENDING'
                        """)
                .param("availableAt", Timestamp.from(availableAt))
                .param("eventId", message.eventId().toString())
                .update());
    }

    private record OutboxMessage(
            UUID eventId,
            UUID jobId,
            String eventType,
            String schemaVersion,
            String payload,
            int deliveryAttempt) {}
}
