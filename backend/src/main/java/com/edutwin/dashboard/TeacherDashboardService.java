package com.edutwin.dashboard;

import com.edutwin.analytics.AnalyticsPeriod;
import com.edutwin.api.model.ActivityHeatmapPoint;
import com.edutwin.api.model.ActivityPoint;
import com.edutwin.api.model.BehaviorDistributionItem;
import com.edutwin.api.model.RiskBand;
import com.edutwin.api.model.StudentRiskSummary;
import com.edutwin.api.model.StudentScatterPoint;
import com.edutwin.api.model.TeacherDashboard;
import com.edutwin.api.model.TraceRef;
import com.edutwin.api.model.WeakSkill;
import com.edutwin.shared.web.DomainException;
import com.edutwin.twin.TwinReadService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class TeacherDashboardService {

    private static final long CACHE_TTL_NANOS = Duration.ofSeconds(60).toNanos();

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final TwinReadService twinReadService;
    private final ConcurrentHashMap<String, Object> buildLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedDashboard> localCache = new ConcurrentHashMap<>();

    public TeacherDashboardService(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate,
            TwinReadService twinReadService) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.twinReadService = twinReadService;
    }

    public TeacherDashboard get(UUID courseId, String requestedPeriod) {
        AnalyticsPeriod period = AnalyticsPeriod.parse(requestedPeriod);
        String cacheId = courseId + ":" + period.value();
        TeacherDashboard cached = readLocal(cacheId);
        if (cached != null) {
            return cached;
        }
        String key = "course:" + cacheId + ":analytics";
        synchronized (buildLocks.computeIfAbsent(cacheId, ignored -> new Object())) {
            cached = readLocal(cacheId);
            if (cached != null) {
                return cached;
            }
            cached = readCached(key);
            if (cached != null) {
                writeLocal(cacheId, cached);
                return cached;
            }
            TeacherDashboard dashboard = build(courseId, period);
            writeCached(key, dashboard);
            writeLocal(cacheId, dashboard);
            return dashboard;
        }
    }

    public void evict(UUID courseId) {
        List<String> redisKeys = new ArrayList<>();
        for (AnalyticsPeriod period : AnalyticsPeriod.values()) {
            String cacheId = courseId + ":" + period.value();
            synchronized (buildLocks.computeIfAbsent(cacheId, ignored -> new Object())) {
                localCache.remove(cacheId);
            }
            redisKeys.add("course:" + cacheId + ":analytics");
        }
        redisKeys.add("course:" + courseId + ":risk");
        try {
            redisTemplate.delete(redisKeys);
        } catch (DataAccessException ignored) {
            // MySQL rebuilds the dashboard projection on the next read.
        }
    }

    private TeacherDashboard readLocal(String cacheId) {
        CachedDashboard cached = localCache.get(cacheId);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAtNanos() > System.nanoTime()) {
            return cached.dashboard();
        }
        localCache.remove(cacheId, cached);
        return null;
    }

    private void writeLocal(String cacheId, TeacherDashboard dashboard) {
        localCache.put(cacheId, new CachedDashboard(
                dashboard, System.nanoTime() + CACHE_TTL_NANOS));
    }

    private TeacherDashboard readCached(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                return objectMapper.readValue(value, TeacherDashboard.class);
            }
        } catch (DataAccessException | JsonProcessingException ignored) {
            // MySQL remains authoritative.
        }
        return null;
    }

    private void writeCached(String key, TeacherDashboard dashboard) {
        try {
            redisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(dashboard),
                    java.time.Duration.ofSeconds(60));
        } catch (DataAccessException | JsonProcessingException ignored) {
            // Cache write failures do not change the result.
        }
    }

    private TeacherDashboard build(UUID courseId, AnalyticsPeriod period) {
        OffsetDateTime generatedAt = OffsetDateTime.now(ZoneOffset.UTC);
        LocalDate termStart = jdbcClient.sql("SELECT starts_on FROM course WHERE id = :courseId")
                .param("courseId", courseId.toString())
                .query(LocalDate.class)
                .single();
        OffsetDateTime since = period.since(generatedAt, termStart);
        List<StudentRow> students = jdbcClient.sql("""
                        SELECT ua.id AS student_id, ua.display_name, ts.id AS snapshot_id,
                               ts.risk_probability, rp.risk_band,
                               COALESCE((SELECT MAX(lae.occurred_at)
                                 FROM learning_activity_event lae
                                 WHERE lae.course_id = ce.course_id
                                   AND lae.student_id = ce.student_id), ts.created_at) last_activity_at,
                               ts.analysis_job_id, ts.engagement_score,
                               (SELECT AVG(tss.mastery_probability)
                                  FROM twin_snapshot_skill tss
                                  WHERE tss.snapshot_id = ts.id) average_mastery,
                               (SELECT COUNT(*) FROM answer_event ae
                                  WHERE ae.course_id = ce.course_id
                                    AND ae.student_id = ce.student_id
                                    AND ae.occurred_at >= :since) answer_count,
                               COALESCE((SELECT JSON_UNQUOTE(JSON_EXTRACT(
                                         lae.metadata_json, '$.behaviorProfile'))
                                  FROM learning_activity_event lae
                                  WHERE lae.course_id = ce.course_id
                                    AND lae.student_id = ce.student_id
                                  ORDER BY lae.occurred_at DESC LIMIT 1), 'STEADY') behavior_profile
                        FROM course_enrollment ce
                        JOIN user_account ua ON ua.id = ce.student_id
                        JOIN twin_current_pointer tcp
                          ON tcp.course_id = ce.course_id AND tcp.student_id = ce.student_id
                        JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                        JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                        WHERE ce.course_id = :courseId AND ce.status = 'ACTIVE'
                        ORDER BY ts.risk_probability DESC, ua.display_name
                        """)
                .param("courseId", courseId.toString())
                .param("since", since)
                .query((resultSet, rowNumber) -> new StudentRow(
                        UUID.fromString(resultSet.getString("student_id")),
                        resultSet.getString("display_name"),
                        UUID.fromString(resultSet.getString("snapshot_id")),
                        resultSet.getBigDecimal("risk_probability"),
                        RiskBand.fromValue(resultSet.getString("risk_band")),
                        resultSet.getTimestamp("last_activity_at").toInstant()
                                .atOffset(ZoneOffset.UTC),
                        UUID.fromString(resultSet.getString("analysis_job_id")),
                        resultSet.getBigDecimal("average_mastery"),
                        resultSet.getBigDecimal("engagement_score"),
                        resultSet.getInt("answer_count"),
                        resultSet.getString("behavior_profile")))
                .list();
        if (students.isEmpty()) {
            throw new DomainException(
                    HttpStatus.NOT_FOUND,
                    "DASHBOARD_SNAPSHOT_NOT_FOUND",
                    "No current student snapshots exist for the requested course.");
        }

        int high = (int) students.stream().filter(value -> value.riskBand() == RiskBand.HIGH).count();
        int medium = (int) students.stream().filter(value -> value.riskBand() == RiskBand.MEDIUM).count();
        int low = (int) students.stream().filter(value -> value.riskBand() == RiskBand.LOW).count();
        BigDecimal average = BigDecimal.valueOf(students.stream()
                        .mapToDouble(value -> value.riskProbability().doubleValue())
                        .average()
                        .orElse(0.0))
                .setScale(8, RoundingMode.HALF_UP);
        StudentRow traceStudent = students.get(0);
        TraceRef trace = twinReadService.current(courseId, traceStudent.studentId()).getTrace();
        List<StudentRiskSummary> summaries = students.stream()
                .map(value -> new StudentRiskSummary()
                        .studentId(value.studentId())
                        .displayName(value.displayName())
                        .riskProbability(value.riskProbability())
                        .riskBand(value.riskBand())
                        .lastActivityAt(value.lastActivityAt())
                        .averageMastery(value.averageMastery())
                        .engagementScore(value.engagementScore())
                        .answerCount(value.answerCount())
                        .behaviorProfile(value.behaviorProfile())
                        .snapshotId(value.snapshotId()))
                .toList();
        List<WeakSkill> weakSkills = weakSkills(courseId);
        List<ActivityPoint> activity = activity(courseId, since);
        List<BehaviorDistributionItem> behavior = behavior(courseId, since);
        List<ActivityHeatmapPoint> heatmap = heatmap(courseId, since);
        List<StudentScatterPoint> scatter = students.stream()
                .map(value -> new StudentScatterPoint(value.studentId(), value.displayName(),
                        value.averageMastery(), value.riskProbability(), value.engagementScore(),
                        value.behaviorProfile()))
                .toList();
        int activeStudents = activity.stream().mapToInt(ActivityPoint::getActiveStudentCount)
                .max().orElse(0);
        int answerCount = students.stream().mapToInt(StudentRow::answerCount).sum();
        BigDecimal correctRate = jdbcClient.sql("""
                        SELECT COALESCE(AVG(correct), 0) FROM answer_event
                        WHERE course_id = :courseId AND occurred_at >= :since
                        """)
                .param("courseId", courseId.toString()).param("since", since)
                .query(BigDecimal.class).single();
        TeacherDashboard result = new TeacherDashboard()
                .courseId(courseId)
                .generatedAt(generatedAt)
                .studentCount(students.size())
                .highRiskCount(high)
                .mediumRiskCount(medium)
                .lowRiskCount(low)
                .averageRiskProbability(average)
                .period(TeacherDashboard.PeriodEnum.fromValue(period.value()))
                .activeStudentCount(activeStudents)
                .answerCount(answerCount)
                .averageCorrectRate(correctRate)
                .weakSkills(weakSkills)
                .activityTrend(activity)
                .students(summaries)
                .behaviorDistribution(behavior)
                .activityHeatmap(heatmap)
                .studentScatter(scatter)
                .trace(trace);
        persistAggregate(courseId, average, low, medium, high, students.size(), traceStudent.jobId());
        return result;
    }

    private List<WeakSkill> weakSkills(UUID courseId) {
        return jdbcClient.sql("""
                        SELECT tss.skill_id, ks.name,
                               AVG(tss.mastery_probability) AS average_mastery,
                               COUNT(DISTINCT tcp.student_id) AS affected_students
                        FROM twin_current_pointer tcp
                        JOIN twin_snapshot_skill tss ON tss.snapshot_id = tcp.snapshot_id
                        JOIN knowledge_skill ks ON ks.id = tss.skill_id
                        WHERE tcp.course_id = :courseId
                        GROUP BY tss.skill_id, ks.name
                        ORDER BY average_mastery, ks.name
                        LIMIT 5
                        """)
                .param("courseId", courseId.toString())
                .query((resultSet, rowNumber) -> new WeakSkill(
                        UUID.fromString(resultSet.getString("skill_id")),
                        resultSet.getString("name"),
                        resultSet.getBigDecimal("average_mastery"),
                        resultSet.getInt("affected_students")))
                .list();
    }

    private List<ActivityPoint> activity(UUID courseId, OffsetDateTime since) {
        return jdbcClient.sql("""
                        SELECT DATE(occurred_at) AS activity_date,
                               COUNT(*) AS answer_count,
                               COUNT(DISTINCT student_id) AS active_students,
                               AVG(correct) AS correct_rate
                        FROM answer_event
                        WHERE course_id = :courseId
                          AND occurred_at >= :since
                        GROUP BY DATE(occurred_at)
                        ORDER BY activity_date
                        """)
                .param("courseId", courseId.toString())
                .param("since", since)
                .query((resultSet, rowNumber) -> new ActivityPoint(
                        resultSet.getObject("activity_date", LocalDate.class),
                        resultSet.getInt("answer_count"),
                        resultSet.getInt("active_students"),
                        resultSet.getBigDecimal("correct_rate")))
                .list();
    }

    private List<BehaviorDistributionItem> behavior(UUID courseId, OffsetDateTime since) {
        return jdbcClient.sql("""
                        SELECT event_type, COUNT(*) event_count,
                               COUNT(DISTINCT student_id) student_count,
                               COUNT(*) / SUM(COUNT(*)) OVER () event_share
                        FROM learning_activity_event
                        WHERE course_id = :courseId AND occurred_at >= :since
                        GROUP BY event_type ORDER BY event_count DESC
                        """)
                .param("courseId", courseId.toString()).param("since", since)
                .query((rs, row) -> new BehaviorDistributionItem(
                        rs.getString("event_type"), rs.getInt("event_count"),
                        rs.getInt("student_count"), rs.getBigDecimal("event_share")))
                .list();
    }

    private List<ActivityHeatmapPoint> heatmap(UUID courseId, OffsetDateTime since) {
        return jdbcClient.sql("""
                        SELECT WEEKDAY(occurred_at) + 1 weekday, HOUR(occurred_at) activity_hour,
                               COUNT(*) event_count
                        FROM learning_activity_event
                        WHERE course_id = :courseId AND occurred_at >= :since
                        GROUP BY WEEKDAY(occurred_at) + 1, HOUR(occurred_at)
                        ORDER BY weekday, activity_hour
                        """)
                .param("courseId", courseId.toString()).param("since", since)
                .query((rs, row) -> new ActivityHeatmapPoint(
                        rs.getInt("weekday"), rs.getInt("activity_hour"),
                        rs.getInt("event_count")))
                .list();
    }

    private void persistAggregate(
            UUID courseId,
            BigDecimal mean,
            int low,
            int medium,
            int high,
            int total,
            UUID sourceJobId) {
        jdbcClient.sql("""
                        INSERT INTO course_risk_current(
                            course_id, mean_risk, low_count, medium_count, high_count,
                            student_count, aggregation_version, source_job_id)
                        VALUES (
                            :courseId, :meanRisk, :lowCount, :mediumCount, :highCount,
                            :studentCount, 1, :sourceJobId)
                        ON DUPLICATE KEY UPDATE
                            mean_risk = VALUES(mean_risk),
                            low_count = VALUES(low_count),
                            medium_count = VALUES(medium_count),
                            high_count = VALUES(high_count),
                            student_count = VALUES(student_count),
                            aggregation_version = aggregation_version + 1,
                            source_job_id = VALUES(source_job_id)
                        """)
                .param("courseId", courseId.toString())
                .param("meanRisk", mean)
                .param("lowCount", low)
                .param("mediumCount", medium)
                .param("highCount", high)
                .param("studentCount", total)
                .param("sourceJobId", sourceJobId.toString())
                .update();
    }

    private record StudentRow(
            UUID studentId,
            String displayName,
            UUID snapshotId,
            BigDecimal riskProbability,
            RiskBand riskBand,
            OffsetDateTime lastActivityAt,
            UUID jobId,
            BigDecimal averageMastery,
            BigDecimal engagementScore,
            int answerCount,
            String behaviorProfile) {}

    private record CachedDashboard(TeacherDashboard dashboard, long expiresAtNanos) {}
}
