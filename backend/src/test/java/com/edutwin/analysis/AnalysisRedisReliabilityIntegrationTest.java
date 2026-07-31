package com.edutwin.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.edutwin.dashboard.TeacherDashboardService;
import com.edutwin.twin.TwinReadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class AnalysisRedisReliabilityIntegrationTest {

    private static final String STREAM = "analysis-events-reliability-test";
    private static final String GROUP = "edutwin-analysis-v1-reliability-test";
    private static final String RECOVERY_CONSUMER = "recovery-consumer";

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void connectToIntegrationRedis() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 36379);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        redisTemplate.delete(STREAM);
    }

    @AfterEach
    void closeIntegrationRedis() {
        if (redisTemplate != null) {
            redisTemplate.delete(STREAM);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void xautoClaimRecoversAnIdlePendingMessageForTheActiveConsumer() {
        MapRecord<String, String, String> added = MapRecord.create(
                STREAM,
                Map.of(
                        "event_id", UUID.randomUUID().toString(),
                        "event_type", "analysis.requested.v1",
                        "payload", "{}"));
        var recordId = redisTemplate.opsForStream().add(added);
        redisTemplate.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);

        List<MapRecord<String, Object, Object>> delivered = redisTemplate.opsForStream().read(
                Consumer.from(GROUP, "crashed-consumer"),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(STREAM, ReadOffset.lastConsumed()));
        assertEquals(1, delivered.size());

        AnalysisStreamRecovery recovery = new AnalysisStreamRecovery(
                redisTemplate,
                STREAM,
                GROUP,
                RECOVERY_CONSUMER,
                Duration.ZERO,
                10);
        List<MapRecord<String, Object, Object>> claimed = recovery.claimStale();

        assertEquals(1, claimed.size());
        assertEquals(recordId, claimed.get(0).getId());
        assertEquals("analysis.requested.v1", claimed.get(0).getValue().get("event_type"));
        var pending = redisTemplate.opsForStream().pending(
                STREAM,
                Consumer.from(GROUP, RECOVERY_CONSUMER),
                Range.unbounded(),
                10);
        assertEquals(1, pending.size());
        assertEquals(RECOVERY_CONSUMER, pending.get(0).getConsumerName());
        assertTrue(pending.get(0).getTotalDeliveryCount() >= 2);
    }

    @Test
    void terminalRefreshInvalidatesTwinAndDashboardCachesAndRefreshesTheJob() {
        UUID jobId = UUID.randomUUID();
        UUID courseId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        String twinKey = "twin:" + studentId + ":current";
        String dashboardKey = "course:" + courseId + ":risk";
        redisTemplate.opsForHash().put(twinKey, courseId.toString(), "stale-twin");
        redisTemplate.opsForValue().set(dashboardKey, "stale-dashboard");
        AnalysisJobProjectionService projectionService = mock(AnalysisJobProjectionService.class);
        TeacherDashboardService dashboardService = new TeacherDashboardService(
                mock(JdbcClient.class), new ObjectMapper(), redisTemplate,
                mock(TwinReadService.class));
        AnalysisCacheCoordinator coordinator =
                new AnalysisCacheCoordinator(redisTemplate, projectionService, dashboardService);

        coordinator.refreshTerminalProjections(jobId, courseId, studentId);

        assertNull(redisTemplate.opsForHash().get(twinKey, courseId.toString()));
        assertNull(redisTemplate.opsForValue().get(dashboardKey));
        verify(projectionService).evict(jobId);
        verify(projectionService).refresh(jobId);
        redisTemplate.delete(twinKey);
    }
}
