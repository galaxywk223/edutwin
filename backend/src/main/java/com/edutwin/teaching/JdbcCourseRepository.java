package com.edutwin.teaching;

import com.edutwin.api.model.CourseSummary;
import com.edutwin.api.model.UserRole;
import com.edutwin.identity.EduTwinPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCourseRepository implements CourseRepository {

    private final JdbcClient jdbcClient;

    public JdbcCourseRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public List<CourseSummary> findAccessible(EduTwinPrincipal principal) {
        String sql = principal.role() == UserRole.STUDENT
                ? """
                    SELECT c.id, c.code, c.title, c.term_label, c.college, c.department, c.credits,
                           (SELECT u.display_name FROM teaching_assignment ta JOIN user_account u ON u.id = ta.teacher_id WHERE ta.course_id = c.id LIMIT 1) instructor_name
                    FROM course c
                    JOIN course_enrollment e ON e.course_id = c.id
                    WHERE e.student_id = :userId AND e.status = 'ACTIVE' AND c.status = 'PUBLISHED'
                    ORDER BY c.code, c.term_label
                    """
                : """
                    SELECT c.id, c.code, c.title, c.term_label, c.college, c.department, c.credits,
                           (SELECT u.display_name FROM teaching_assignment tx JOIN user_account u ON u.id = tx.teacher_id WHERE tx.course_id = c.id LIMIT 1) instructor_name
                    FROM course c
                    JOIN teaching_assignment a ON a.course_id = c.id
                    WHERE a.teacher_id = :userId
                    ORDER BY c.code, c.term_label
                    """;
        return jdbcClient.sql(sql)
                .param("userId", principal.userId().toString())
                .query((resultSet, rowNum) -> new CourseSummary(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("code"),
                        resultSet.getString("title"),
                        resultSet.getBigDecimal("credits").doubleValue(),
                        principal.role())
                        .termLabel(resultSet.getString("term_label"))
                        .college(resultSet.getString("college"))
                        .department(resultSet.getString("department"))
                        .instructorName(resultSet.getString("instructor_name")))
                .list();
    }
}
