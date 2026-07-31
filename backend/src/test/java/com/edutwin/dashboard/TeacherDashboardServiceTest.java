package com.edutwin.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.edutwin.twin.TwinReadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class TeacherDashboardServiceTest {

    @Test
    void evictsEveryAnalyticsPeriodAndTheLegacyRiskKey() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        TeacherDashboardService service = new TeacherDashboardService(
                mock(JdbcClient.class),
                new ObjectMapper(),
                redisTemplate,
                mock(TwinReadService.class));
        UUID courseId = UUID.randomUUID();

        service.evict(courseId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> keys = ArgumentCaptor.forClass(Collection.class);
        verify(redisTemplate).delete(keys.capture());
        assertEquals(Set.of(
                        "course:" + courseId + ":7D:analytics",
                        "course:" + courseId + ":30D:analytics",
                        "course:" + courseId + ":TERM:analytics",
                        "course:" + courseId + ":risk"),
                Set.copyOf(keys.getValue()));
    }
}
