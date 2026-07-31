package com.edutwin.identity;

import com.edutwin.api.model.UserRole;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository implements AccountRepository {

    private static final String ACCOUNT_SQL = """
            SELECT u.id, u.username, u.display_name, u.password_hash, u.enabled,
                   u.must_change_password, u.token_version, u.last_active_role
            FROM user_account u
            WHERE u.username = :username
            """;

    private final JdbcClient jdbcClient;

    public JdbcAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<EduTwinPrincipal> findByUsername(String username) {
        return jdbcClient.sql(ACCOUNT_SQL)
                .param("username", username)
                .query(this::mapPrincipal)
                .optional();
    }

    @Override
    public Optional<EduTwinPrincipal> findByUserId(UUID userId) {
        return jdbcClient.sql(ACCOUNT_SQL.replace("u.username = :username", "u.id = :userId"))
                .param("userId", userId.toString())
                .query(this::mapPrincipal)
                .optional();
    }

    @Override
    public boolean isTokenCurrent(UUID userId, long tokenVersion, String activeRole) {
        Integer matches = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                WHERE u.id = :userId AND u.enabled = TRUE AND u.token_version = :tokenVersion
                  AND ur.role_code = :activeRole
                """)
                .param("userId", userId.toString())
                .param("tokenVersion", tokenVersion)
                .param("activeRole", activeRole)
                .query(Integer.class).single();
        return matches == 1;
    }

    private EduTwinPrincipal mapPrincipal(java.sql.ResultSet resultSet, int rowNum)
            throws java.sql.SQLException {
        UUID userId = UUID.fromString(resultSet.getString("id"));
        Set<UserRole> roles = findRoles(userId);
        if (roles.isEmpty()) {
            throw new org.springframework.dao.DataIntegrityViolationException(
                    "An enabled account must have at least one role.");
        }
        UserRole role = chooseRole(resultSet.getString("last_active_role"), roles);
        return new EduTwinPrincipal(
                userId,
                resultSet.getString("username"),
                resultSet.getString("display_name"),
                resultSet.getString("password_hash"),
                role,
                roles,
                findAccessibleCourses(userId, role),
                resultSet.getBoolean("enabled"),
                resultSet.getBoolean("must_change_password"),
                resultSet.getLong("token_version"));
    }

    private Set<UserRole> findRoles(UUID userId) {
        return jdbcClient.sql("SELECT role_code FROM user_role WHERE user_id = :userId ORDER BY role_code")
                .param("userId", userId.toString())
                .query((rs, row) -> UserRole.fromValue(rs.getString("role_code")))
                .stream()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    static UserRole chooseRole(String lastActiveRole, Set<UserRole> roles) {
        if (lastActiveRole != null) {
            UserRole remembered = UserRole.fromValue(lastActiveRole);
            if (roles.contains(remembered)) return remembered;
        }
        return java.util.List.of(UserRole.STUDENT, UserRole.COUNSELOR, UserRole.TEACHER, UserRole.ADMIN)
                .stream().filter(roles::contains).findFirst().orElseThrow();
    }

    private Set<UUID> findAccessibleCourses(UUID userId, UserRole role) {
        if (role == UserRole.ADMIN || "COUNSELOR".equals(role.getValue())) {
            return Set.of();
        }
        String sql = role == UserRole.STUDENT
                ? """
                    SELECT course_id FROM course_enrollment
                    WHERE student_id = :userId AND status = 'ACTIVE'
                    ORDER BY course_id
                    """
                : """
                    SELECT course_id FROM teaching_assignment
                    WHERE teacher_id = :userId
                    ORDER BY course_id
                    """;
        return jdbcClient.sql(sql)
                .param("userId", userId.toString())
                .query((resultSet, rowNum) -> UUID.fromString(resultSet.getString("course_id")))
                .stream()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
