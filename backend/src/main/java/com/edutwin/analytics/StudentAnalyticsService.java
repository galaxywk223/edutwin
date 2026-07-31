package com.edutwin.analytics;

import com.edutwin.api.model.ActivityHeatmapPoint;
import com.edutwin.api.model.ActivityPoint;
import com.edutwin.api.model.BehaviorDistributionItem;
import com.edutwin.api.model.StudentAnalytics;
import com.edutwin.api.model.TwinState;
import com.edutwin.shared.web.DomainException;
import com.edutwin.twin.TwinReadService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class StudentAnalyticsService {
    private final JdbcClient jdbc;
    private final TwinReadService twins;

    public StudentAnalyticsService(JdbcClient jdbc, TwinReadService twins) {
        this.jdbc = jdbc;
        this.twins = twins;
    }

    public StudentAnalytics get(UUID courseId, UUID studentId, String requestedPeriod) {
        AnalyticsPeriod period = AnalyticsPeriod.parse(requestedPeriod);
        LocalDate termStart = jdbc.sql("""
                        SELECT c.starts_on FROM course c
                        JOIN course_enrollment ce ON ce.course_id = c.id
                        WHERE c.id = :courseId AND ce.student_id = :studentId
                          AND ce.status = 'ACTIVE'
                        """)
                .param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .query(LocalDate.class).optional()
                .orElseThrow(() -> new DomainException(HttpStatus.FORBIDDEN,
                        "STUDENT_ANALYTICS_SCOPE_DENIED",
                        "The course is outside the student's active enrollments."));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime since = period.since(now, termStart);
        TwinState current = twins.current(courseId, studentId);
        List<TwinState> snapshotTrend = twins.history(courseId, studentId, null, 100).getItems()
                .stream().sorted(java.util.Comparator.comparingLong(TwinState::getSnapshotVersion))
                .toList();
        Summary summary = jdbc.sql("""
                        SELECT COUNT(*) answer_count, COALESCE(AVG(correct), 0) correct_rate
                        FROM answer_event
                        WHERE course_id = :courseId AND student_id = :studentId
                          AND occurred_at >= :since
                        """)
                .param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .param("since", since)
                .query((rs, row) -> new Summary(rs.getInt("answer_count"),
                        rs.getBigDecimal("correct_rate"))).single();
        ActivitySummary activitySummary = jdbc.sql("""
                        SELECT COUNT(DISTINCT DATE(occurred_at)) active_days,
                               COALESCE(SUM(duration_seconds), 0) duration_seconds
                        FROM learning_activity_event
                        WHERE course_id = :courseId AND student_id = :studentId
                          AND occurred_at >= :since
                        """)
                .param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .param("since", since)
                .query((rs, row) -> new ActivitySummary(rs.getInt("active_days"),
                        rs.getLong("duration_seconds"))).single();
        BigDecimal mastery = current.getMastery().stream().map(value -> value.getProbability())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(current.getMastery().size()), 8, RoundingMode.HALF_UP);
        return new StudentAnalytics()
                .courseId(courseId).studentId(studentId)
                .period(StudentAnalytics.PeriodEnum.fromValue(period.value()))
                .generatedAt(now).answerCount(summary.answerCount())
                .correctRate(summary.correctRate()).activeDays(activitySummary.activeDays())
                .totalDurationMinutes((int) (activitySummary.durationSeconds() / 60))
                .currentMastery(mastery).currentRisk(current.getRisk().getProbability())
                .currentEngagement(current.getEngagementScore())
                .snapshotTrend(snapshotTrend).activityTrend(activity(courseId, studentId, since))
                .behaviorDistribution(behavior(courseId, studentId, since))
                .activityHeatmap(heatmap(courseId, studentId, since));
    }

    private List<ActivityPoint> activity(UUID courseId, UUID studentId, OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT DATE(occurred_at) activity_date, COUNT(*) answer_count,
                               AVG(correct) correct_rate
                        FROM answer_event
                        WHERE course_id = :courseId AND student_id = :studentId
                          AND occurred_at >= :since
                        GROUP BY DATE(occurred_at) ORDER BY activity_date
                        """)
                .param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .param("since", since)
                .query((rs, row) -> new ActivityPoint(
                        rs.getObject("activity_date", LocalDate.class), rs.getInt("answer_count"),
                        1, rs.getBigDecimal("correct_rate"))).list();
    }

    private List<BehaviorDistributionItem> behavior(
            UUID courseId, UUID studentId, OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT event_type, COUNT(*) event_count,
                               COUNT(*) / SUM(COUNT(*)) OVER () event_share
                        FROM learning_activity_event
                        WHERE course_id = :courseId AND student_id = :studentId
                          AND occurred_at >= :since
                        GROUP BY event_type ORDER BY event_count DESC
                        """)
                .param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .param("since", since)
                .query((rs, row) -> new BehaviorDistributionItem(rs.getString("event_type"),
                        rs.getInt("event_count"), 1, rs.getBigDecimal("event_share"))).list();
    }

    private List<ActivityHeatmapPoint> heatmap(
            UUID courseId, UUID studentId, OffsetDateTime since) {
        return jdbc.sql("""
                        SELECT WEEKDAY(occurred_at) + 1 weekday, HOUR(occurred_at) activity_hour,
                               COUNT(*) event_count
                        FROM learning_activity_event
                        WHERE course_id = :courseId AND student_id = :studentId
                          AND occurred_at >= :since
                        GROUP BY WEEKDAY(occurred_at) + 1, HOUR(occurred_at)
                        ORDER BY weekday, activity_hour
                        """)
                .param("courseId", courseId.toString()).param("studentId", studentId.toString())
                .param("since", since)
                .query((rs, row) -> new ActivityHeatmapPoint(rs.getInt("weekday"),
                        rs.getInt("activity_hour"), rs.getInt("event_count"))).list();
    }

    private record Summary(int answerCount, BigDecimal correctRate) {}
    private record ActivitySummary(int activeDays, long durationSeconds) {}
}
