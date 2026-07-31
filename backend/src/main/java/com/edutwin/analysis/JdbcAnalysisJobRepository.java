package com.edutwin.analysis;

import com.edutwin.api.model.AnalysisJob;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnalysisJobRepository implements AnalysisJobRepository {

    private static final String JOB_SELECT = """
            SELECT aj.*,
                   COALESCE((
                       SELECT MAX(aje.sequence_no)
                       FROM analysis_job_event aje
                       WHERE aje.analysis_job_id = aj.id
                   ), 0) AS last_event_sequence
            FROM analysis_job aj
            WHERE aj.id = :jobId
            """;

    private final JdbcClient jdbcClient;
    private final AnalysisJobMapper mapper;

    public JdbcAnalysisJobRepository(JdbcClient jdbcClient, AnalysisJobMapper mapper) {
        this.jdbcClient = jdbcClient;
        this.mapper = mapper;
    }

    @Override
    public Optional<AnalysisJob> findById(UUID jobId) {
        return jdbcClient.sql(JOB_SELECT)
                .param("jobId", jobId.toString())
                .query(mapper)
                .optional();
    }

    @Override
    public List<JobEvent> findEventsAfter(UUID jobId, long sequenceExclusive, int limit) {
        return jdbcClient.sql("""
                        SELECT sequence_no, event_type, payload, occurred_at
                        FROM analysis_job_event
                        WHERE analysis_job_id = :jobId
                          AND sequence_no > :sequenceExclusive
                        ORDER BY sequence_no
                        LIMIT :limit
                        """)
                .param("jobId", jobId.toString())
                .param("sequenceExclusive", sequenceExclusive)
                .param("limit", limit)
                .query((resultSet, rowNumber) -> {
                    Timestamp timestamp = resultSet.getTimestamp("occurred_at");
                    return new JobEvent(
                            resultSet.getLong("sequence_no"),
                            resultSet.getString("event_type"),
                            resultSet.getString("payload"),
                            timestamp.toInstant().atOffset(ZoneOffset.UTC));
                })
                .list();
    }
}
