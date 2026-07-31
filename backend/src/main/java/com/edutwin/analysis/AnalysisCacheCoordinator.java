package com.edutwin.analysis;

import com.edutwin.dashboard.TeacherDashboardService;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AnalysisCacheCoordinator {

    private final StringRedisTemplate redisTemplate;
    private final AnalysisJobProjectionService jobProjectionService;
    private final TeacherDashboardService teacherDashboardService;

    public AnalysisCacheCoordinator(
            StringRedisTemplate redisTemplate,
            AnalysisJobProjectionService jobProjectionService,
            TeacherDashboardService teacherDashboardService) {
        this.redisTemplate = redisTemplate;
        this.jobProjectionService = jobProjectionService;
        this.teacherDashboardService = teacherDashboardService;
    }

    public void refreshTerminalProjections(UUID jobId, UUID courseId, UUID studentId) {
        try {
            redisTemplate.opsForHash().delete(
                    "twin:" + studentId + ":current", courseId.toString());
        } catch (DataAccessException ignored) {
            // MySQL rebuilds the twin projection on the next read.
        }
        teacherDashboardService.evict(courseId);
        jobProjectionService.evict(jobId);
        jobProjectionService.refresh(jobId);
    }
}
