package com.edutwin.counselor;

import com.edutwin.analytics.AnalyticsPeriod;
import com.edutwin.shared.web.DomainException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class CounselorService {
    private static final String SCOPE_SQL = """
            EXISTS (
                SELECT 1 FROM counselor_scope cs
                WHERE cs.counselor_id = :counselorId
                  AND cs.college = sp.college
                  AND cs.major = sp.major
                  AND cs.cohort_year = sp.cohort_year
                  AND (cs.scope_type = 'COHORT'
                       OR (cs.scope_type = 'CLASS' AND cs.class_name = sp.class_name))
            )
            """;

    private static final String SUMMARY_COLUMNS = """
            u.id, u.username, u.display_name, sp.student_number, sp.college,
            sp.major, sp.cohort_year, sp.class_name,
            (SELECT COUNT(*) FROM course_enrollment e
             WHERE e.student_id = u.id AND e.status IN ('ACTIVE', 'COMPLETED')) course_count,
            (SELECT COALESCE(SUM(c.credits), 0) FROM course_enrollment e
             JOIN course c ON c.id = e.course_id
             WHERE e.student_id = u.id AND e.status IN ('ACTIVE', 'COMPLETED')) total_credits,
            (SELECT 100 * SUM(ls.score) / NULLIF(SUM(ls.max_score), 0)
             FROM lms_submission ls
             JOIN lms_assessment la ON la.id = ls.assessment_id
             JOIN course_enrollment e ON e.course_id = la.course_id AND e.student_id = ls.student_id
             WHERE ls.student_id = u.id AND e.status IN ('ACTIVE', 'COMPLETED')) average_score,
            (SELECT AVG(tss.mastery_probability)
             FROM twin_current_pointer tcp
             JOIN twin_snapshot_skill tss ON tss.snapshot_id = tcp.snapshot_id
             WHERE tcp.student_id = u.id) average_mastery,
            (SELECT COUNT(*) FROM twin_current_pointer tcp
             JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
             JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
             WHERE tcp.student_id = u.id AND rp.risk_band = 'HIGH') high_risk_count,
            (SELECT MAX(lae.occurred_at) FROM learning_activity_event lae
             WHERE lae.student_id = u.id) last_activity_at
            """;

    private final JdbcClient jdbc;

    public CounselorService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CounselorDtos.StudentPage students(
            UUID counselorId,
            String query,
            Integer cohortYear,
            String className,
            String riskBand,
            int page,
            int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw badRequest("COUNSELOR_PAGE_INVALID", "Page must be non-negative and size must be between 1 and 100.");
        }
        String filters = filters(query, cohortYear, className, riskBand);
        String from = """
                FROM user_account u
                JOIN student_profile sp ON sp.user_id = u.id
                WHERE u.enabled = TRUE AND %s %s
                """.formatted(SCOPE_SQL, filters);
        int total = bind(jdbc.sql("SELECT COUNT(*) " + from), counselorId, query, cohortYear, className, riskBand)
                .query(Integer.class).single();
        List<CounselorDtos.StudentSummary> items = bind(jdbc.sql("""
                        SELECT %s %s
                        ORDER BY sp.cohort_year DESC, sp.class_name, u.display_name, u.username
                        LIMIT :limit OFFSET :offset
                        """.formatted(SUMMARY_COLUMNS, from)),
                        counselorId, query, cohortYear, className, riskBand)
                .param("limit", size)
                .param("offset", page * size)
                .query((rs, row) -> summary(rs))
                .list();
        return new CounselorDtos.StudentPage(items, total, page, size);
    }

    public CounselorDtos.StudentOverview overview(UUID counselorId, UUID studentId) {
        CounselorDtos.StudentSummary student = jdbc.sql("""
                        SELECT %s
                        FROM user_account u
                        JOIN student_profile sp ON sp.user_id = u.id
                        WHERE u.id = :studentId AND u.enabled = TRUE AND %s
                        """.formatted(SUMMARY_COLUMNS, SCOPE_SQL))
                .param("studentId", studentId.toString())
                .param("counselorId", counselorId.toString())
                .query((rs, row) -> summary(rs))
                .optional()
                .orElseThrow(() -> new DomainException(
                        HttpStatus.FORBIDDEN,
                        "COUNSELOR_STUDENT_SCOPE_DENIED",
                        "The student is outside the counselor's assigned academic groups."));

        List<CounselorDtos.CourseStatistics> courses = jdbc.sql("""
                        SELECT c.id, c.code, c.title, c.term_label, c.credits, e.status,
                               (SELECT GROUP_CONCAT(DISTINCT u.display_name ORDER BY u.display_name SEPARATOR '||')
                                FROM teaching_assignment ta JOIN user_account u ON u.id = ta.teacher_id
                                WHERE ta.course_id = c.id) instructors,
                               (SELECT COUNT(*) FROM lms_lesson_progress lc
                                JOIN lms_lesson l ON l.id = lc.lesson_id
                                JOIN lms_section sec ON sec.id = l.section_id
                                WHERE lc.student_id = e.student_id AND sec.course_id = c.id) completed_lessons,
                               (SELECT COUNT(*) FROM lms_lesson l JOIN lms_section sec ON sec.id = l.section_id
                                WHERE sec.course_id = c.id AND sec.status = 'PUBLISHED' AND l.status = 'PUBLISHED') total_lessons,
                               (SELECT COUNT(*) FROM lms_submission ls JOIN lms_assessment a ON a.id = ls.assessment_id
                                WHERE ls.student_id = e.student_id AND a.course_id = c.id) submitted_assessments,
                               (SELECT COUNT(*) FROM lms_assessment a
                                WHERE a.course_id = c.id AND a.status = 'PUBLISHED') published_assessments,
                               (SELECT 100 * SUM(ls.score) / NULLIF(SUM(ls.max_score), 0)
                                FROM lms_submission ls JOIN lms_assessment a ON a.id = ls.assessment_id
                                WHERE ls.student_id = e.student_id AND a.course_id = c.id) score_percentage,
                               (SELECT AVG(tss.mastery_probability) FROM twin_current_pointer tcp
                                JOIN twin_snapshot_skill tss ON tss.snapshot_id = tcp.snapshot_id
                                WHERE tcp.student_id = e.student_id AND tcp.course_id = c.id) average_mastery,
                               (SELECT rp.risk_band FROM twin_current_pointer tcp
                                JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                                JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id
                                WHERE tcp.student_id = e.student_id AND tcp.course_id = c.id) risk_band,
                               (SELECT ts.risk_probability FROM twin_current_pointer tcp
                                JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id
                                WHERE tcp.student_id = e.student_id AND tcp.course_id = c.id) risk_probability,
                               (SELECT MAX(lae.occurred_at) FROM learning_activity_event lae
                                WHERE lae.student_id = e.student_id AND lae.course_id = c.id) last_activity_at
                        FROM course_enrollment e JOIN course c ON c.id = e.course_id
                        WHERE e.student_id = :studentId AND e.status IN ('ACTIVE', 'COMPLETED')
                        ORDER BY c.starts_on DESC, c.code
                        """)
                .param("studentId", studentId.toString())
                .query((rs, row) -> new CounselorDtos.CourseStatistics(
                        UUID.fromString(rs.getString("id")), rs.getString("code"), rs.getString("title"),
                        rs.getString("term_label"), rs.getBigDecimal("credits"), instructors(rs.getString("instructors")),
                        rs.getString("status"), rs.getInt("completed_lessons"), rs.getInt("total_lessons"),
                        rs.getInt("submitted_assessments"), rs.getInt("published_assessments"),
                        rs.getBigDecimal("score_percentage"), rs.getBigDecimal("average_mastery"),
                        rs.getString("risk_band"), rs.getBigDecimal("risk_probability"),
                        offsetDateTime(rs.getTimestamp("last_activity_at"))))
                .list();
        return new CounselorDtos.StudentOverview(student, courses);
    }

    public CounselorDtos.ClassComparisonResult compareClasses(
            UUID counselorId, List<String> classNames, String requestedPeriod) {
        AnalyticsPeriod period = AnalyticsPeriod.parse(requestedPeriod);
        List<String> normalized = classNames == null ? List.of() : classNames.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        if (normalized.isEmpty() || normalized.size() > 4) {
            throw badRequest("COUNSELOR_CLASS_COMPARISON_INVALID",
                    "Between one and four distinct classes are required.");
        }
        int scoped = jdbc.sql("""
                        SELECT COUNT(DISTINCT cs.class_name) FROM counselor_scope cs
                        WHERE cs.counselor_id = :counselorId AND cs.scope_type = 'CLASS'
                          AND cs.class_name IN (:classNames)
                        """).param("counselorId", counselorId.toString()).param("classNames", normalized)
                .query(Integer.class).single();
        if (scoped != normalized.size()) {
            throw new DomainException(
                    HttpStatus.FORBIDDEN,
                    "COUNSELOR_CLASS_SCOPE_DENIED",
                    "At least one requested class is outside the counselor's class scope.");
        }
        List<CounselorDtos.ClassComparison> classes = jdbc.sql("""
                        SELECT requested.class_name,
                               COUNT(DISTINCT sp.user_id) student_count,
                               COUNT(DISTINCT CASE WHEN enrollment.status = 'ACTIVE'
                                   THEN CONCAT(enrollment.course_id, ':', enrollment.student_id) END)
                                   active_course_enrollments,
                               AVG(score.score_percentage) average_score,
                               AVG(twin.average_mastery) average_mastery,
                               AVG(twin.risk_probability) average_risk,
                                COUNT(CASE WHEN twin.risk_band = 'LOW' THEN 1 END) low_risk_count,
                                COUNT(CASE WHEN twin.risk_band = 'MEDIUM' THEN 1 END) medium_risk_count,
                               COUNT(CASE WHEN twin.risk_band = 'HIGH' THEN 1 END) high_risk_count,
                               AVG(plan.completion_rate) plan_completion_rate
                        FROM (SELECT DISTINCT class_name FROM counselor_scope
                              WHERE counselor_id = :counselorId AND scope_type = 'CLASS'
                                AND class_name IN (:classNames)) requested
                        LEFT JOIN student_profile sp ON sp.class_name = requested.class_name
                        LEFT JOIN course_enrollment enrollment ON enrollment.student_id = sp.user_id
                          AND enrollment.status IN ('ACTIVE', 'COMPLETED')
                        LEFT JOIN (
                            SELECT submission.student_id, assessment.course_id,
                                   100 * SUM(submission.score) / NULLIF(SUM(submission.max_score), 0)
                                       score_percentage
                            FROM lms_submission submission
                            JOIN lms_assessment assessment ON assessment.id = submission.assessment_id
                            GROUP BY submission.student_id, assessment.course_id
                        ) score ON score.student_id = enrollment.student_id
                              AND score.course_id = enrollment.course_id
                        LEFT JOIN (
                            SELECT pointer.student_id, pointer.course_id,
                                   AVG(skill.mastery_probability) average_mastery,
                                   snapshot.risk_probability,
                                   prediction.risk_band
                            FROM twin_current_pointer pointer
                            JOIN twin_snapshot snapshot ON snapshot.id = pointer.snapshot_id
                            JOIN risk_prediction prediction
                              ON prediction.analysis_job_id = snapshot.analysis_job_id
                            LEFT JOIN twin_snapshot_skill skill ON skill.snapshot_id = snapshot.id
                            GROUP BY pointer.student_id, pointer.course_id,
                                     snapshot.risk_probability, prediction.risk_band
                        ) twin ON twin.student_id = enrollment.student_id
                              AND twin.course_id = enrollment.course_id
                        LEFT JOIN (
                            SELECT pointer.student_id, pointer.course_id,
                                   SUM(item.completed_count) / NULLIF(SUM(item.target_count), 0)
                                       completion_rate
                            FROM learning_plan_current_pointer pointer
                            JOIN learning_plan_item item ON item.learning_plan_id = pointer.learning_plan_id
                            GROUP BY pointer.student_id, pointer.course_id
                        ) plan ON plan.student_id = enrollment.student_id
                              AND plan.course_id = enrollment.course_id
                        GROUP BY requested.class_name
                        ORDER BY requested.class_name
                        """).param("counselorId", counselorId.toString()).param("classNames", normalized)
                .query((rs, row) -> new CounselorDtos.ClassComparison(
                        rs.getString("class_name"), rs.getInt("student_count"),
                        rs.getInt("active_course_enrollments"), rs.getBigDecimal("average_score"),
                        rs.getBigDecimal("average_mastery"), rs.getBigDecimal("average_risk"),
                        rs.getInt("low_risk_count"),
                        rs.getInt("medium_risk_count"), rs.getInt("high_risk_count"),
                        rs.getBigDecimal("plan_completion_rate")))
                .list();
        LocalDate termStart = jdbc.sql("""
                        SELECT MIN(c.starts_on) FROM course c
                        JOIN course_enrollment ce ON ce.course_id = c.id
                        JOIN student_profile sp ON sp.user_id = ce.student_id
                        WHERE sp.class_name IN (:classNames)
                        """).param("classNames", normalized).query(LocalDate.class).single();
        OffsetDateTime since = period.since(OffsetDateTime.now(ZoneOffset.UTC), termStart);
        List<CounselorDtos.ClassRiskTrendPoint> riskTrend = jdbc.sql("""
                        SELECT sp.class_name,
                               DATE_SUB(DATE(ts.created_at), INTERVAL WEEKDAY(ts.created_at) DAY)
                                   week_start,
                               AVG(ts.risk_probability) average_risk
                        FROM twin_snapshot ts
                        JOIN student_profile sp ON sp.user_id = ts.student_id
                        WHERE sp.class_name IN (:classNames) AND ts.created_at >= :since
                        GROUP BY sp.class_name, week_start
                        ORDER BY week_start, sp.class_name
                        """).param("classNames", normalized).param("since", since)
                .query((rs, row) -> new CounselorDtos.ClassRiskTrendPoint(
                        rs.getString("class_name"), rs.getObject("week_start", LocalDate.class),
                        rs.getBigDecimal("average_risk"))).list();
        return new CounselorDtos.ClassComparisonResult(classes, period.value(), riskTrend);
    }

    public CounselorDtos.ClassComparisonResult compareClasses(
            UUID counselorId, List<String> classNames) {
        return compareClasses(counselorId, classNames, "30D");
    }

    private static String filters(String query, Integer cohortYear, String className, String riskBand) {
        StringBuilder sql = new StringBuilder();
        if (query != null && !query.isBlank()) {
            sql.append(" AND (u.username LIKE :query OR u.display_name LIKE :query OR sp.student_number LIKE :query)");
        }
        if (cohortYear != null) sql.append(" AND sp.cohort_year = :cohortYear");
        if (className != null && !className.isBlank()) sql.append(" AND sp.class_name = :className");
        if (riskBand != null && !riskBand.isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM twin_current_pointer tcp JOIN twin_snapshot ts ON ts.id = tcp.snapshot_id")
                    .append(" JOIN risk_prediction rp ON rp.analysis_job_id = ts.analysis_job_id")
                    .append(" WHERE tcp.student_id = u.id AND rp.risk_band = :riskBand)");
        }
        return sql.toString();
    }

    private static JdbcClient.StatementSpec bind(
            JdbcClient.StatementSpec spec,
            UUID counselorId,
            String query,
            Integer cohortYear,
            String className,
            String riskBand) {
        spec = spec.param("counselorId", counselorId.toString());
        if (query != null && !query.isBlank()) spec = spec.param("query", "%" + query.trim() + "%");
        if (cohortYear != null) spec = spec.param("cohortYear", cohortYear);
        if (className != null && !className.isBlank()) spec = spec.param("className", className.trim());
        if (riskBand != null && !riskBand.isBlank()) spec = spec.param("riskBand", riskBand.trim().toUpperCase());
        return spec;
    }

    private static CounselorDtos.StudentSummary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CounselorDtos.StudentSummary(
                UUID.fromString(rs.getString("id")), rs.getString("username"), rs.getString("display_name"),
                rs.getString("student_number"), rs.getString("college"), rs.getString("major"),
                rs.getInt("cohort_year"), rs.getString("class_name"), rs.getInt("course_count"),
                rs.getBigDecimal("total_credits"), rs.getBigDecimal("average_score"),
                rs.getBigDecimal("average_mastery"), rs.getInt("high_risk_count"),
                offsetDateTime(rs.getTimestamp("last_activity_at")));
    }

    private static List<String> instructors(String value) {
        return value == null || value.isBlank() ? List.of() : Arrays.asList(value.split("\\|\\|"));
    }

    private static OffsetDateTime offsetDateTime(Timestamp value) {
        return value == null ? null : OffsetDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private static DomainException badRequest(String code, String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, detail);
    }
}
