package com.edutwin.bootstrap;

import com.edutwin.bootstrap.CsvTable.Row;
import com.edutwin.bootstrap.DemoSeedRegistry.Artifact;
import com.edutwin.bootstrap.DemoSeedRegistry.Dataset;
import com.edutwin.bootstrap.DemoSeedRegistry.Deployment;
import com.edutwin.bootstrap.DemoSeedRegistry.Metric;
import com.edutwin.bootstrap.DemoSeedRegistry.Model;
import com.edutwin.bootstrap.DemoSeedRegistry.SourceFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(0)
@ConditionalOnProperty(prefix = "edutwin.demo-import", name = "enabled", havingValue = "true")
public class DemoDataImporter implements ApplicationRunner {
    private static final int MAX_ANSWER_EVENTS_PER_ENROLLMENT = 42;

    private static final Logger LOGGER = LoggerFactory.getLogger(DemoDataImporter.class);
    private static final String DEMO_VERSION = "synthetic-demo-v6";
    private static final String LEGACY_DEMO_VERSION = "synthetic-demo-v5";
    private static final OffsetDateTime DEMO_REFERENCE_TIME =
            OffsetDateTime.parse("2026-06-15T00:00:00Z");
    private static final long SEED_SOURCE_ORDER_BASE = 9_000_000_000_000L;
    private static final long SEED_SOURCE_ORDER_LIMIT = SEED_SOURCE_ORDER_BASE + 10_000_000L;
    private static final int BATCH_SIZE = 5_000;
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern EVENT_KEY = Pattern.compile("^DEMO-E-(\\d{5})-(\\d{3})$");
    private static final Set<String> REQUIRED_SOURCE_KEYS = Set.of(
            "ASSISTMENTS_2009_2010_SKILL_BUILDER_CORRECTED", "OULAD", "EDUTWIN_DEMO");
    private static final Set<String> REQUIRED_MODEL_FAMILIES = Set.of(
            "IRT", "BKT", "DKT", "AKT",
            "LOGISTIC_REGRESSION", "LIGHTGBM", "CATBOOST",
            "SHAP", "RULE", "DEEPSEEK");
    private static final Set<String> KNOWLEDGE_CANDIDATES = Set.of("IRT", "BKT", "DKT", "AKT");
    private static final Set<String> RISK_CANDIDATES = Set.of(
            "LOGISTIC_REGRESSION", "LIGHTGBM", "CATBOOST");
    private static final Set<String> REQUIRED_DEPLOYMENTS = Set.of(
            "MASTERY", "NEXT_CORRECT", "RISK", "EXPLANATION", "PLAN_RULES", "DIAGNOSIS");
    private static final Set<String> DATABASE_FILES = Set.of(
            "organization_units",
            "academic_terms",
            "courses",
            "teachers",
            "teaching_assignments",
            "knowledge_skills",
            "questions",
            "students",
            "enrollments",
            "course_questions",
            "lms_sections",
            "lms_lessons",
            "lms_assessments",
            "lms_assessment_questions",
            "lms_lesson_progress",
            "lms_submissions",
            "lms_submission_answers",
            "learning_activity_events",
            "answer_events",
            "answer_event_skills",
            "source_lineage",
            "knowledge_lineage");

    private static final List<String> COURSE_HEADER = List.of(
            "course_id", "course_code", "title", "presentation", "starts_on",
            "description", "college", "department", "organization_id", "academic_term_id",
            "credits", "data_version", "synthetic");
    private static final List<String> TEACHER_HEADER = List.of(
            "teacher_id", "username", "display_name", "staff_number", "staff_key",
            "college", "department", "organization_id", "academic_title", "synthetic");
    private static final List<String> TEACHING_ASSIGNMENT_HEADER = List.of("course_id", "teacher_id");
    private static final List<String> SKILL_HEADER = List.of(
            "knowledge_skill_id", "skill_code", "name", "data_version", "content_origin", "synthetic");
    private static final List<String> QUESTION_HEADER = List.of(
            "question_id", "question_key", "knowledge_skill_id", "prompt_text",
            "answer_type", "options_json", "correct_answer", "difficulty",
            "data_version", "knowledge_model_mode", "content_origin", "active", "synthetic");
    private static final List<String> STUDENT_HEADER = List.of(
            "student_id", "username", "display_name", "student_number", "college", "major",
            "cohort_year", "class_name", "organization_id", "synthetic_key",
            "synthetic", "split", "performance", "activity", "persistence",
            "initial_risk_probability");
    private static final List<String> ENROLLMENT_HEADER = List.of("course_id", "student_id", "status");
    private static final List<String> COURSE_QUESTION_HEADER = List.of("course_id", "question_id", "ordinal");
    private static final List<String> SECTION_HEADER = List.of("section_id", "course_id", "title", "description", "position", "status");
    private static final List<String> LESSON_HEADER = List.of("lesson_id", "section_id", "title", "summary", "body", "resource_url", "position", "status");
    private static final List<String> ASSESSMENT_HEADER = List.of("assessment_id", "course_id", "title", "description", "assessment_type", "status", "due_at", "published_at", "closed_at");
    private static final List<String> ASSESSMENT_QUESTION_HEADER = List.of("assessment_question_id", "assessment_id", "source_question_id", "prompt", "options_json", "correct_choice_id", "points", "position");
    private static final List<String> ORGANIZATION_HEADER = List.of("organization_id", "code", "display_name", "unit_type", "parent_id", "enabled");
    private static final List<String> ACADEMIC_TERM_HEADER = List.of("academic_term_id", "code", "display_name", "starts_on", "ends_on", "enabled");
    private static final List<String> LESSON_PROGRESS_HEADER = List.of("lesson_id", "student_id", "completed_at");
    private static final List<String> SUBMISSION_HEADER = List.of("submission_id", "assessment_id", "student_id", "attempt_number", "attempt_type", "idempotency_key_hash", "request_sha256", "score", "max_score", "valid_for_grade", "submitted_at");
    private static final List<String> SUBMISSION_ANSWER_HEADER = List.of("submission_id", "assessment_question_id", "selected_choice_id", "correct", "points_awarded");
    private static final List<String> ACTIVITY_HEADER = List.of(
            "learning_activity_event_id", "course_id", "student_id", "event_type",
            "occurred_at", "resource_type", "resource_id", "answer_event_id",
            "progress_percent", "duration_seconds", "metadata_json", "synthetic",
            "data_version");
    private static final List<String> EVENT_HEADER = List.of(
            "answer_event_id", "event_key", "student_id", "course_id", "question_id",
            "event_sequence", "selected_choice", "correct", "correct_probability",
            "response_time_ms", "attempt_number", "hint_count", "occurred_at",
            "split", "synthetic");
    private static final List<String> EVENT_SKILL_HEADER = List.of(
            "answer_event_id", "knowledge_skill_id", "ordinal", "synthetic");
    private static final List<String> SOURCE_LINEAGE_HEADER = List.of(
            "student_id", "assistments_user_sha256", "oulad_student_sha256", "split",
            "performance_z", "activity_z", "persistence_z", "match_distance", "random_seed");
    private static final List<String> KNOWLEDGE_LINEAGE_HEADER = List.of(
            "knowledge_skill_id", "source_skill_key", "question_id", "source_problem_key");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final Path root;
    private final String teacherPassword;
    private final String studentPassword;
    private final LocalDate demoAsOf;
    private final DemoInitialStateImporter initialStateImporter;

    public DemoDataImporter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PasswordEncoder passwordEncoder,
            @Value("${edutwin.demo-import.root:data/demo/generated}") String root,
            @Value("${edutwin.demo-import.teacher-password:}") String teacherPassword,
            @Value("${edutwin.demo-import.student-password:}") String studentPassword,
            @Value("${edutwin.demo-import.as-of:}") String demoAsOf) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.teacherPassword = teacherPassword;
        this.studentPassword = studentPassword;
        this.demoAsOf = demoAsOf == null || demoAsOf.isBlank()
                ? LocalDate.now(ZoneOffset.UTC)
                : LocalDate.parse(demoAsOf);
        this.initialStateImporter = new DemoInitialStateImporter(
                jdbcTemplate,
                objectMapper,
                this::businessTime,
                businessTime(DEMO_REFERENCE_TIME));
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        try {
            SeedPackage seed = loadSeedPackage();
            requirePasswords();
            if (isAlreadyImported(seed)) {
                synchronizeDemoAccountPasswords();
                LOGGER.info("EduTwin demo seed {} is already present and verified.",
                        seed.registry().processingRun().id());
                return;
            }
            String legacyProcessingRunId = prepareImportSlot();
            insertRegistry(seed.registry());
            insertTeachingData(seed);
            insertModelRegistry(seed.registry());
            initialStateImporter.insert(seed.initialStates(), seed.registry());
            if (legacyProcessingRunId != null) {
                finishLegacyUpgrade(legacyProcessingRunId);
            }
            verifyPersisted(seed);
            LOGGER.info(
                    "Imported deterministic EduTwin demo: students={}, answerEvents={}, manifestSha256={}.",
                    seed.students().size(), seed.events().size(), seed.demoManifestSha256());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read the EduTwin demo seed package.", exception);
        }
    }

    private SeedPackage loadSeedPackage() throws IOException {
        Path manifestPath = root.resolve("manifest.json");
        JsonNode manifest = objectMapper.readTree(manifestPath.toFile());
        if (manifest.path("schema_version").asInt(-1) != 7
                || !DEMO_VERSION.equals(manifest.path("data_version").asText())
                || !DEMO_REFERENCE_TIME.equals(
                        OffsetDateTime.parse(manifest.path("reference_time").asText()))
                || manifest.path("seed").asInt(-1) != 42
                || !manifest.path("synthetic").asBoolean(false)
                || manifest.path("student_count").asInt(-1) != 2_000
                || manifest.path("course_count").asInt(-1) != 20
                || manifest.path("teacher_count").asInt(-1) != 12
                || !"data/demo/question-bank.tsv".equals(
                        manifest.path("question_bank").path("path").asText())
                || !"TEACHER_AUTHORED".equals(
                        manifest.path("question_bank").path("content_origin").asText())
                || !SHA256.matcher(
                        manifest.path("question_bank").path("sha256").asText()).matches()
                || manifest.path("outputs").path("lms_assessments").path("rows").asInt(-1) != 100
                || manifest.path("outputs").path("lms_assessment_questions").path("rows").asInt(-1) != 500
                || manifest.path("answer_event_count").asInt(-1) < 150_000
                || manifest.path("answer_event_count").asInt(-1) > 360_000
                || manifest.path("learning_activity_event_count").asInt(-1) < 450_000
                || manifest.path("learning_activity_event_count").asInt(-1) > 1_500_000) {
            throw new IllegalStateException("The demo manifest violates the frozen seed contract.");
        }
        JsonNode databaseImport = manifest.path("database_import");
        if (databaseImport.path("schema_version").asInt(-1) != 3
                || !"rfc4180-csv-utf8-lf".equals(databaseImport.path("format").asText())
                || databaseImport.path("contains_source_behavior_rows").asBoolean(true)) {
            throw new IllegalStateException("The demo database interface is invalid.");
        }
        Map<String, ImportFile> files = loadFiles(databaseImport.path("outputs"));
        if (!files.keySet().equals(DATABASE_FILES)) {
            throw new IllegalStateException("The demo database file set differs: " + files.keySet());
        }
        String manifestSha256 = sha256(manifestPath);
        DemoSeedRegistry registry = objectMapper.readValue(
                root.resolve("database/registry.json").toFile(), DemoSeedRegistry.class);
        validateRegistry(registry, manifestSha256);

        List<OrganizationRow> organizations = read(
                files, "organization_units", ORGANIZATION_HEADER, this::organizationRow);
        List<AcademicTermRow> academicTerms = read(
                files, "academic_terms", ACADEMIC_TERM_HEADER, this::academicTermRow);
        List<CourseRow> courses = read(files, "courses", COURSE_HEADER, this::courseRow);
        List<TeacherRow> teachers = read(files, "teachers", TEACHER_HEADER, this::teacherRow);
        List<TeachingAssignmentRow> teachingAssignments = read(files, "teaching_assignments", TEACHING_ASSIGNMENT_HEADER, this::teachingAssignmentRow);
        List<SkillRow> skills = read(files, "knowledge_skills", SKILL_HEADER, this::skillRow);
        List<QuestionRow> questions = read(files, "questions", QUESTION_HEADER, this::questionRow);
        List<StudentRow> students = read(files, "students", STUDENT_HEADER, this::studentRow);
        List<EnrollmentRow> enrollments = read(files, "enrollments", ENROLLMENT_HEADER, this::enrollmentRow);
        List<CourseQuestionRow> courseQuestions = read(files, "course_questions", COURSE_QUESTION_HEADER, this::courseQuestionRow);
        List<SectionRow> sections = read(files, "lms_sections", SECTION_HEADER, this::sectionRow);
        List<LessonRow> lessons = read(files, "lms_lessons", LESSON_HEADER, this::lessonRow);
        List<AssessmentRow> assessments = read(files, "lms_assessments", ASSESSMENT_HEADER, this::assessmentRow);
        List<AssessmentQuestionRow> assessmentQuestions = read(files, "lms_assessment_questions", ASSESSMENT_QUESTION_HEADER, this::assessmentQuestionRow);
        List<LessonProgressRow> lessonProgress = read(
                files, "lms_lesson_progress", LESSON_PROGRESS_HEADER, this::lessonProgressRow);
        List<SubmissionRow> submissions = read(
                files, "lms_submissions", SUBMISSION_HEADER, this::submissionRow);
        List<SubmissionAnswerRow> submissionAnswers = read(
                files, "lms_submission_answers", SUBMISSION_ANSWER_HEADER,
                this::submissionAnswerRow);
        List<ActivityRow> activities = read(files, "learning_activity_events", ACTIVITY_HEADER, this::activityRow);
        List<EventRow> events = read(files, "answer_events", EVENT_HEADER, this::eventRow);
        List<EventSkillRow> eventSkills = read(
                files, "answer_event_skills", EVENT_SKILL_HEADER, this::eventSkillRow);
        List<SourceLineageRow> sourceLineage = read(
                files, "source_lineage", SOURCE_LINEAGE_HEADER, this::sourceLineageRow);
        List<KnowledgeLineageRow> knowledgeLineage = read(
                files, "knowledge_lineage", KNOWLEDGE_LINEAGE_HEADER,
                this::knowledgeLineageRow);
        Map<String, UUID> enrollmentCourses = new LinkedHashMap<>();
        for (EnrollmentRow enrollment : enrollments) {
            if ("ACTIVE".equals(enrollment.status())) {
                enrollmentCourses.put(enrollmentKey(enrollment.courseId(), enrollment.studentId()), enrollment.courseId());
            }
        }
        Map<String, Map<Long, UUID>> eventIdsBySequence = new LinkedHashMap<>();
        for (EventRow event : events) {
            String key = enrollmentKey(event.courseId(), event.studentId());
            UUID previous = eventIdsBySequence
                    .computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .put(event.sequence(), event.id());
            if (previous != null) {
                throw new IllegalStateException("Duplicate answer event sequence: " + key);
            }
        }
        DemoInitialStateImporter.Seed initialStates = initialStateImporter.load(
                root,
                registry,
                enrollmentCourses,
                eventIdsBySequence,
                skills.stream().map(SkillRow::id).collect(java.util.stream.Collectors.toSet()),
                questions.stream().map(QuestionRow::id)
                        .collect(java.util.stream.Collectors.toSet()),
                manifestSha256);
        SeedPackage seed = new SeedPackage(
                registry,
                manifestSha256,
                organizations,
                academicTerms,
                courses,
                teachers,
                teachingAssignments,
                skills,
                questions,
                students,
                enrollments,
                courseQuestions,
                sections,
                lessons,
                assessments,
                assessmentQuestions,
                lessonProgress,
                submissions,
                submissionAnswers,
                activities,
                events,
                eventSkills,
                sourceLineage,
                knowledgeLineage,
                initialStates);
        validateRows(seed, files);
        return seed;
    }

    private Map<String, ImportFile> loadFiles(JsonNode outputs) throws IOException {
        if (!outputs.isObject()) {
            throw new IllegalStateException("The demo database outputs must be an object.");
        }
        Map<String, ImportFile> result = new LinkedHashMap<>();
        outputs.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            Path path = root.resolve(value.path("path").asText()).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                throw new IllegalStateException("Demo import path is missing or escapes its root: " + path);
            }
            long rows = value.path("rows").asLong(-1);
            String expectedSha = value.path("sha256").asText();
            if (rows < 0 || !isSha256(expectedSha)) {
                throw new IllegalStateException("Demo import metadata is invalid for " + entry.getKey());
            }
            try {
                String actualSha = sha256(path);
                if (!actualSha.equals(expectedSha)) {
                    throw new IllegalStateException("Demo import SHA-256 differs for " + path);
                }
            } catch (IOException exception) {
                throw new SeedReadException(exception);
            }
            result.put(entry.getKey(), new ImportFile(path, rows, expectedSha));
        });
        return result;
    }

    private <T> List<T> read(
            Map<String, ImportFile> files,
            String name,
            List<String> header,
            Function<Row, T> mapper) throws IOException {
        ImportFile file = Objects.requireNonNull(files.get(name));
        List<T> rows = CsvTable.read(file.path(), header, mapper);
        if (rows.size() != file.rows()) {
            throw new IllegalStateException(
                    "Demo import row count differs for " + name + ": " + rows.size());
        }
        return rows;
    }

    private void validateRows(SeedPackage seed, Map<String, ImportFile> files) {
        requireSize(seed.courses(), 20, "courses");
        requireSize(seed.teachers(), 12, "teachers");
        requireSize(seed.teachingAssignments(), 20, "teaching assignments");
        requireSize(seed.skills(), 60, "knowledge skills");
        requireSize(seed.questions(), 600, "questions");
        requireSize(seed.assessments(), 100, "assessments");
        requireSize(seed.assessmentQuestions(), 500, "assessment questions");
        requireSize(seed.academicTerms(), 1, "academic terms");
        requireSize(seed.students(), 2_000, "students");
        if (seed.enrollments().size() < 8_400
                || seed.events().size() < 150_000 || seed.events().size() > 360_000
                || seed.activities().size() < 450_000
                || seed.activities().size() > 1_500_000) {
            throw new IllegalStateException("Demo enrollment or event cardinality is below the approved scale.");
        }
        requireSize(seed.eventSkills(), seed.events().size(), "answer event skills");
        requireSize(seed.sourceLineage(), 2_000, "source lineage");
        requireSize(seed.knowledgeLineage(), 600, "knowledge lineage");
        if (files.get("answer_events").rows() < 100_000) {
            throw new IllegalStateException("The demo seed contains fewer than 100,000 events.");
        }

        Set<UUID> courseIds = unique(seed.courses(), CourseRow::id, "course IDs");
        Set<UUID> organizationIds = unique(
                seed.organizations(), OrganizationRow::id, "organization IDs");
        unique(seed.organizations(), OrganizationRow::code, "organization codes");
        Set<UUID> academicTermIds = unique(
                seed.academicTerms(), AcademicTermRow::id, "academic term IDs");
        Set<UUID> skillIds = unique(seed.skills(), SkillRow::id, "skill IDs");
        Set<UUID> questionIds = unique(seed.questions(), QuestionRow::id, "question IDs");
        Set<UUID> studentIds = unique(seed.students(), StudentRow::id, "student IDs");
        Set<UUID> eventIds = unique(seed.events(), EventRow::id, "answer event IDs");
        unique(seed.lessons(), LessonRow::id, "lesson IDs");
        unique(seed.assessments(), AssessmentRow::id, "assessment IDs");
        unique(seed.assessmentQuestions(), AssessmentQuestionRow::id,
                "assessment question IDs");
        Set<UUID> submissionIds = unique(
                seed.submissions(), SubmissionRow::id, "submission IDs");
        unique(seed.students(), StudentRow::username, "student usernames");
        unique(seed.students(), StudentRow::syntheticKey, "synthetic student keys");
        unique(seed.events(), EventRow::eventKey, "answer event keys");
        unique(seed.lessonProgress(),
                row -> row.lessonId() + "|" + row.studentId(), "lesson progress keys");
        unique(seed.submissions(),
                row -> row.assessmentId() + "|" + row.studentId() + "|" + row.attemptNumber(),
                "assessment attempts");
        unique(seed.submissionAnswers(),
                row -> row.submissionId() + "|" + row.questionId(),
                "submission answer keys");
        unique(seed.sourceLineage(), SourceLineageRow::assistmentsUserSha256,
                "ASSISTments user hashes");
        unique(seed.sourceLineage(), SourceLineageRow::ouladStudentSha256,
                "OULAD student hashes");
        unique(seed.knowledgeLineage(), KnowledgeLineageRow::questionId,
                "knowledge lineage question IDs");
        unique(seed.knowledgeLineage(), KnowledgeLineageRow::sourceProblemKey,
                "ASSISTments problem keys");

        Map<UUID, StudentRow> students = index(seed.students(), StudentRow::id);
        Map<UUID, QuestionRow> questions = index(seed.questions(), QuestionRow::id);
        Map<UUID, EventRow> events = index(seed.events(), EventRow::id);
        Map<UUID, AssessmentRow> assessments = index(seed.assessments(), AssessmentRow::id);
        Map<UUID, AssessmentQuestionRow> assessmentQuestions =
                index(seed.assessmentQuestions(), AssessmentQuestionRow::id);
        Map<UUID, KnowledgeLineageRow> questionLineage =
                index(seed.knowledgeLineage(), KnowledgeLineageRow::questionId);
        Map<UUID, String> skillLineage = new HashMap<>();
        for (KnowledgeLineageRow row : seed.knowledgeLineage()) {
            String previous = skillLineage.putIfAbsent(row.skillId(), row.sourceSkillKey());
            if (previous != null && !previous.equals(row.sourceSkillKey())) {
                throw new IllegalStateException("A demo skill maps to multiple source skill keys.");
            }
        }
        if (!skillLineage.keySet().equals(skillIds)
                || !questionLineage.keySet().equals(questionIds)) {
            throw new IllegalStateException("Knowledge lineage does not cover the demo bank.");
        }
        if (new HashSet<>(skillLineage.values()).size() != skillLineage.size()) {
            throw new IllegalStateException("ASSISTments source skill keys are not unique.");
        }
        if (seed.organizations().stream().anyMatch(row -> row.parentId() != null
                        && !organizationIds.contains(row.parentId()))
                || seed.courses().stream().anyMatch(row -> !organizationIds.contains(row.organizationId())
                        || !academicTermIds.contains(row.academicTermId()))
                || seed.teachers().stream().anyMatch(
                        row -> !organizationIds.contains(row.organizationId()))
                || seed.students().stream().anyMatch(
                        row -> !organizationIds.contains(row.organizationId()))) {
            throw new IllegalStateException("Demo organization references are invalid.");
        }

        Map<String, Integer> splitCounts = new HashMap<>();
        Set<String> studentSequences = new HashSet<>(seed.events().size());
        for (StudentRow student : seed.students()) {
            if (!student.synthetic()) {
                throw new IllegalStateException("A demo student is not synthetic.");
            }
            splitCounts.merge(student.split(), 1, Integer::sum);
        }
        Set<String> activeEnrollments = new HashSet<>();
        for (EnrollmentRow enrollment : seed.enrollments()) {
            if (!courseIds.contains(enrollment.courseId()) || !studentIds.contains(enrollment.studentId())) {
                throw new IllegalStateException("An enrollment has invalid references.");
            }
            if ("ACTIVE".equals(enrollment.status())) {
                activeEnrollments.add(enrollmentKey(enrollment.courseId(), enrollment.studentId()));
            }
        }
        if (!splitCounts.equals(Map.of("train", 1_400, "validation", 300, "test", 300))) {
            throw new IllegalStateException("Demo student split counts differ: " + splitCounts);
        }
        Map<UUID, UUID> lessonCourses = new HashMap<>();
        Map<UUID, UUID> sectionCourses = seed.sections().stream().collect(
                java.util.stream.Collectors.toMap(SectionRow::id, SectionRow::courseId));
        for (LessonRow lesson : seed.lessons()) {
            UUID courseId = sectionCourses.get(lesson.sectionId());
            if (courseId == null) {
                throw new IllegalStateException("A lesson references an unknown section.");
            }
            lessonCourses.put(lesson.id(), courseId);
        }
        for (LessonProgressRow progress : seed.lessonProgress()) {
            UUID courseId = lessonCourses.get(progress.lessonId());
            if (courseId == null || !studentIds.contains(progress.studentId())
                    || !activeEnrollments.contains(enrollmentKey(courseId, progress.studentId()))) {
                throw new IllegalStateException("Lesson progress violates enrollment scope.");
            }
        }
        Map<UUID, List<SubmissionAnswerRow>> answersBySubmission =
                seed.submissionAnswers().stream().collect(
                        java.util.stream.Collectors.groupingBy(SubmissionAnswerRow::submissionId));
        if (!answersBySubmission.keySet().equals(submissionIds)
                || seed.submissions().stream().noneMatch(row -> row.attemptNumber() == 2)) {
            throw new IllegalStateException("Demo submission attempts are incomplete.");
        }
        Set<UUID> submittedAssessments = new HashSet<>();
        for (SubmissionRow submission : seed.submissions()) {
            AssessmentRow assessment = assessments.get(submission.assessmentId());
            List<SubmissionAnswerRow> answers = answersBySubmission.get(submission.id());
            BigDecimal answerScore = answers.stream()
                    .map(SubmissionAnswerRow::pointsAwarded)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (assessment == null
                    || !activeEnrollments.contains(
                            enrollmentKey(assessment.courseId(), submission.studentId()))
                    || answers.size() != 5
                    || submission.score().compareTo(answerScore) != 0
                    || submission.maxScore().compareTo(BigDecimal.valueOf(5)) != 0
                    || (assessment.dueAt() != null
                            && submission.submittedAt().isAfter(assessment.dueAt()))) {
                throw new IllegalStateException("A demo submission is inconsistent with behavior.");
            }
            for (SubmissionAnswerRow answer : answers) {
                AssessmentQuestionRow question = assessmentQuestions.get(answer.questionId());
                if (question == null || !question.assessmentId().equals(assessment.id())) {
                    throw new IllegalStateException("A submission answer references another assessment.");
                }
            }
            submittedAssessments.add(assessment.id());
        }
        for (UUID courseId : courseIds) {
            List<AssessmentRow> scoped = seed.assessments().stream()
                    .filter(row -> row.courseId().equals(courseId)).toList();
            boolean hasOpen = scoped.stream().anyMatch(row -> "PUBLISHED".equals(row.status())
                    && (row.dueAt() == null || row.dueAt().isAfter(DEMO_REFERENCE_TIME)));
            boolean hasSubmitted = scoped.stream().anyMatch(
                    row -> submittedAssessments.contains(row.id()));
            boolean hasClosedUnsubmitted = scoped.stream().anyMatch(row -> "CLOSED".equals(row.status())
                    && !submittedAssessments.contains(row.id()));
            if (!hasOpen || !hasSubmitted || !hasClosedUnsubmitted) {
                throw new IllegalStateException(
                        "Each demo course must contain open, submitted, and closed-unsubmitted work.");
            }
        }
        for (QuestionRow question : seed.questions()) {
            if (!question.synthetic() || !skillIds.contains(question.skillId())) {
                throw new IllegalStateException("A demo question has invalid skill lineage.");
            }
            validateJson(question.optionsJson(), "question options");
        }
        for (EventRow event : seed.events()) {
            StudentRow student = students.get(event.studentId());
            if (!event.synthetic()
                    || student == null
                    || !activeEnrollments.contains(enrollmentKey(event.courseId(), event.studentId()))
                    || !questions.containsKey(event.questionId())
                    || !event.split().equals(student.split())
                    || event.sequence() < 1
                    || event.sequence() > MAX_ANSWER_EVENTS_PER_ENROLLMENT
                    || !studentSequences.add(enrollmentKey(event.courseId(), event.studentId()) + ":" + event.sequence())) {
                throw new IllegalStateException("An answer event violates demo referential integrity.");
            }
        }
        Set<UUID> associatedEvents = new HashSet<>();
        for (EventSkillRow row : seed.eventSkills()) {
            EventRow event = events.get(row.eventId());
            if (!row.synthetic()
                    || row.ordinal() != 1
                    || event == null
                    || !skillIds.contains(row.skillId())
                    || !questions.get(event.questionId()).skillId().equals(row.skillId())
                    || !associatedEvents.add(row.eventId())) {
                throw new IllegalStateException("Answer event skill lineage is invalid.");
            }
        }
        if (!associatedEvents.equals(eventIds)) {
            throw new IllegalStateException("Answer event skill lineage is incomplete.");
        }
        Set<UUID> lineageStudents = unique(
                seed.sourceLineage(), SourceLineageRow::studentId, "source lineage students");
        if (!lineageStudents.equals(studentIds)
                || seed.sourceLineage().stream().anyMatch(row -> row.seed() != 42
                        || !row.split().equals(students.get(row.studentId()).split()))) {
            throw new IllegalStateException("Student source lineage differs from the demo students.");
        }
    }

    private void validateRegistry(DemoSeedRegistry registry, String demoManifestSha256) {
        if (registry == null
                || registry.schemaVersion() != 1
                || registry.processingRun() == null
                || registry.processingRun().randomSeed() != 42
                || !"SUCCEEDED".equals(registry.processingRun().status())
                || !isSha256(registry.processingRun().gitTreeSha256())
                || !isSha256(registry.processingRun().configSha256())
                || !isSha256(registry.processingRun().outputManifestSha256())) {
            throw new IllegalStateException("The demo registry processing run is invalid.");
        }
        UUID.fromString(registry.processingRun().id());
        requireText(registry.processingRun().startedAt(), "processing start time");
        requireText(registry.processingRun().completedAt(), "processing completion time");
        offset(registry.processingRun().startedAt());
        offset(registry.processingRun().completedAt());
        if (registry.datasets() == null || registry.datasets().size() != 3) {
            throw new IllegalStateException("The demo registry must contain three datasets.");
        }
        Set<String> sourceKeys = unique(
                registry.datasets(), value -> value.source().sourceKey(), "dataset source keys");
        if (!sourceKeys.equals(REQUIRED_SOURCE_KEYS)) {
            throw new IllegalStateException("Dataset source keys differ: " + sourceKeys);
        }
        Set<String> datasetVersions = unique(
                registry.datasets(), value -> value.version().versionId(), "dataset versions");
        Dataset demo = registry.datasets().stream()
                .filter(value -> "EDUTWIN_DEMO".equals(value.source().sourceKey()))
                .findFirst()
                .orElseThrow();
        if (!DEMO_VERSION.equals(demo.version().versionId())
                || demo.version().rowCount() < 150_000
                || demo.version().rowCount() > 360_000
                || !demoManifestSha256.equals(demo.version().manifestSha256())) {
            throw new IllegalStateException("The demo dataset registry does not match manifest.json.");
        }
        for (Dataset dataset : registry.datasets()) {
            UUID.fromString(dataset.source().id());
            requireText(dataset.source().name(), "dataset source name");
            requireSha(dataset.version().sourceSha256(), "dataset source hash");
            requireSha(dataset.version().schemaSha256(), "dataset schema hash");
            requireSha(dataset.version().processingConfigSha256(), "dataset config hash");
            requireSha(dataset.version().manifestSha256(), "dataset manifest hash");
            requireText(dataset.version().processedAt(), "dataset processing time");
            if (dataset.version().rowCount() < 1 || dataset.version().manifestJson() == null) {
                throw new IllegalStateException("A dataset registry entry is incomplete.");
            }
            offset(dataset.version().processedAt());
            if (dataset.files() == null || dataset.files().isEmpty()) {
                throw new IllegalStateException("A dataset registry entry has no source files.");
            }
            unique(dataset.files(), SourceFile::fileName, "dataset source file names");
            for (SourceFile file : dataset.files()) {
                requireSha(file.sha256(), "source file hash");
                if (file.sizeBytes() < 1) {
                    throw new IllegalStateException("A source file size is invalid.");
                }
            }
        }

        if (registry.models() == null || registry.models().size() < 10) {
            throw new IllegalStateException("The demo registry omits required models.");
        }
        Set<String> modelVersions = unique(registry.models(), Model::versionId, "model versions");
        Set<String> families = new HashSet<>();
        for (Model model : registry.models()) {
            families.add(model.modelFamily());
            if (!datasetVersions.contains(model.datasetVersionId())
                    || model.randomSeed() != 42
                    || model.configJson() == null
                    || !Set.of("CANDIDATE", "FROZEN", "ACTIVE", "ROLLED_BACK")
                            .contains(model.status())) {
                throw new IllegalStateException("A model registry entry is invalid: " + model.versionId());
            }
            requireSha(model.featureContractSha256(), "model feature contract hash");
            requireSha(model.manifestSha256(), "model manifest hash");
            requireText(model.frozenAt(), "model frozen time");
            if (model.calibratorSha256() != null) {
                requireSha(model.calibratorSha256(), "model calibrator hash");
            }
            offset(model.frozenAt());
            if (model.testEvaluatedAt() != null) {
                offset(model.testEvaluatedAt());
            }
            if (model.metrics() == null || model.metrics().isEmpty()
                    || model.artifacts() == null || model.artifacts().isEmpty()) {
                throw new IllegalStateException("A model has no metrics or artifacts: " + model.versionId());
            }
            unique(model.artifacts(), Artifact::artifactRole, "model artifact roles");
            unique(model.metrics(),
                    value -> value.splitName() + "\u001f" + value.metricName(),
                    "model metric keys");
            for (Metric metric : model.metrics()) {
                requireText(metric.measuredAt(), "model metric measurement time");
                offset(metric.measuredAt());
            }
            for (Artifact artifact : model.artifacts()) {
                requireSha(artifact.sha256(), "model artifact hash");
                requireText(artifact.artifactUri(), "model artifact URI");
                if (artifact.sizeBytes() < 1 || artifact.dependencyVersions() == null) {
                    throw new IllegalStateException("A model artifact is invalid.");
                }
            }
            Set<String> metricSplits = model.metrics().stream()
                    .map(Metric::splitName)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> metricNames = model.metrics().stream()
                    .map(Metric::metricName)
                    .collect(java.util.stream.Collectors.toSet());
            if (KNOWLEDGE_CANDIDATES.contains(model.modelFamily())
                    && (!metricSplits.containsAll(Set.of("validation", "test"))
                            || !metricNames.containsAll(
                                    Set.of("AUC", "LOG_LOSS", "ECE_15", "CPU_P95_MS"))
                            || model.testEvaluatedAt() == null)) {
                throw new IllegalStateException(
                        "A knowledge candidate omits frozen validation or test metrics.");
            }
            if (RISK_CANDIDATES.contains(model.modelFamily())
                    && (!metricSplits.containsAll(Set.of("validation", "test"))
                            || !metricNames.containsAll(
                                    Set.of("PR_AUC", "BRIER_SCORE", "CPU_P95_MS"))
                            || model.testEvaluatedAt() == null)) {
                throw new IllegalStateException(
                        "A risk candidate omits frozen validation or test metrics.");
            }
        }
        if (!families.containsAll(REQUIRED_MODEL_FAMILIES)) {
            throw new IllegalStateException("The model registry omits families: "
                    + difference(REQUIRED_MODEL_FAMILIES, families));
        }
        if (registry.deployments() == null
                || !unique(registry.deployments(), Deployment::taskName, "deployment tasks")
                        .equals(REQUIRED_DEPLOYMENTS)) {
            throw new IllegalStateException("The active deployment set is incomplete.");
        }
        Map<String, Model> models = index(registry.models(), Model::versionId);
        Map<String, String> activeFamily = new HashMap<>();
        for (Deployment deployment : registry.deployments()) {
            Model active = models.get(deployment.activeVersionId());
            if (active == null || !"ACTIVE".equals(active.status()) || !active.selected()) {
                throw new IllegalStateException("A deployment does not reference an active selected model.");
            }
            if (deployment.rollbackVersionId() != null
                    && !modelVersions.contains(deployment.rollbackVersionId())) {
                throw new IllegalStateException("A deployment rollback model does not exist.");
            }
            activeFamily.put(deployment.taskName(), active.modelFamily());
            requireText(deployment.deployedAt(), "model deployment time");
            requireText(deployment.deployedBy(), "model deployment actor");
            offset(deployment.deployedAt());
        }
        if (!"BKT".equals(activeFamily.get("MASTERY"))
                || !Set.of("DKT", "AKT").contains(activeFamily.get("NEXT_CORRECT"))
                || !Set.of("LOGISTIC_REGRESSION", "LIGHTGBM", "CATBOOST")
                        .contains(activeFamily.get("RISK"))
                || !"SHAP".equals(activeFamily.get("EXPLANATION"))
                || !"RULE".equals(activeFamily.get("PLAN_RULES"))
                || !"DEEPSEEK".equals(activeFamily.get("DIAGNOSIS"))) {
            throw new IllegalStateException("The active model family assignments are invalid.");
        }
    }

    private boolean isAlreadyImported(SeedPackage seed) {
        String processingRunId = findExistingProcessingRunId(
                seed.registry().processingRun().outputManifestSha256());
        if (processingRunId == null) {
            return false;
        }
        verifyPersisted(seed, processingRunId);
        return true;
    }

    String findExistingProcessingRunId(String expectedManifestSha256) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dataset_version WHERE version_id = ?",
                Integer.class,
                DEMO_VERSION);
        if (count == null || count == 0) {
            return null;
        }
        if (count != 1) {
            throw new IllegalStateException("The registered demo dataset version is not unique.");
        }
        String processingRunId = jdbcTemplate.queryForObject(
                "SELECT processing_run_id FROM dataset_version WHERE version_id = ?",
                String.class,
                DEMO_VERSION);
        String manifest = jdbcTemplate.queryForObject(
                "SELECT output_manifest_sha256 FROM processing_run WHERE id = ?",
                String.class,
                processingRunId);
        if (!expectedManifestSha256.equals(manifest)) {
            throw new IllegalStateException("The existing processing run has a different manifest.");
        }
        return processingRunId;
    }

    private String findExistingProcessingRunIdForVersion(String versionId) {
        return jdbcTemplate.queryForObject(
                "SELECT processing_run_id FROM dataset_version WHERE version_id = ?",
                String.class,
                versionId);
    }

    private String prepareImportSlot() {
        long students = count("SELECT COUNT(*) FROM student_profile WHERE synthetic = TRUE");
        long events = count("SELECT COUNT(*) FROM answer_event WHERE data_version = ?", DEMO_VERSION);
        long version = count("SELECT COUNT(*) FROM dataset_version WHERE version_id = ?", DEMO_VERSION);
        long legacyVersion = count(
                "SELECT COUNT(*) FROM dataset_version WHERE version_id = ?", LEGACY_DEMO_VERSION);
        if (students == 0 && events == 0 && version == 0 && legacyVersion == 0) {
            return null;
        }
        if (events == 0 && version == 0 && legacyVersion == 1) {
            String legacyRunId = legacyProcessingRunId();
            LegacySeedState legacy = new LegacySeedState(
                    students,
                    count("SELECT COUNT(*) FROM teacher_profile WHERE staff_key LIKE 'DEMO-TEACHER-%'"),
                    count("SELECT COUNT(*) FROM course WHERE data_version = ?", LEGACY_DEMO_VERSION),
                    count("SELECT COUNT(*) FROM question WHERE data_version = ?", LEGACY_DEMO_VERSION),
                    count("SELECT COUNT(*) FROM knowledge_skill WHERE data_version = ?", LEGACY_DEMO_VERSION),
                    count("SELECT COUNT(*) FROM answer_event WHERE data_version = ? AND source_order_id >= ? AND source_order_id < ?",
                            LEGACY_DEMO_VERSION, SEED_SOURCE_ORDER_BASE, SEED_SOURCE_ORDER_LIMIT),
                    count("SELECT COUNT(*) FROM learning_activity_event WHERE data_version = ? AND synthetic = TRUE",
                            LEGACY_DEMO_VERSION),
                    legacyRunId == null ? 0 : count(
                            "SELECT COUNT(*) FROM synthetic_match WHERE processing_run_id = ?", legacyRunId));
            if (legacyRunId != null && isCompleteLegacySeed(legacy)) {
                LOGGER.info("Upgrading registered EduTwin demo seed {} to {}.",
                        LEGACY_DEMO_VERSION, DEMO_VERSION);
                return legacyRunId;
            }
        }
        throw new IllegalStateException(
                "A partial, mixed, or unregistered demo seed already exists; automatic overwrite is forbidden.");
    }

    private String legacyProcessingRunId() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dataset_version WHERE version_id = ?",
                Integer.class,
                LEGACY_DEMO_VERSION);
        if (count == null || count == 0) {
            return null;
        }
        if (count != 1) {
            throw new IllegalStateException(
                    "The registered legacy demo dataset version is not unique.");
        }
        return jdbcTemplate.queryForObject(
                "SELECT processing_run_id FROM dataset_version WHERE version_id = ?",
                String.class,
                LEGACY_DEMO_VERSION);
    }

    static boolean isCompleteLegacySeed(LegacySeedState state) {
        return state.students() == 2_000
                && state.teachers() == 12
                && state.courses() == 20
                && state.questions() == 600
                && state.skills() == 60
                && state.answerEvents() == 201_600
                && state.activityEvents() == 806_400
                && state.matches() == 2_000;
    }

    private void finishLegacyUpgrade(String legacyProcessingRunId) {
        jdbcTemplate.update("UPDATE course SET data_version = ? WHERE data_version = ?",
                DEMO_VERSION, LEGACY_DEMO_VERSION);
        jdbcTemplate.update("UPDATE question SET data_version = ? WHERE data_version = ?",
                DEMO_VERSION, LEGACY_DEMO_VERSION);
        jdbcTemplate.update("UPDATE knowledge_skill SET data_version = ? WHERE data_version = ?",
                DEMO_VERSION, LEGACY_DEMO_VERSION);
        jdbcTemplate.update("UPDATE answer_event SET data_version = ? WHERE data_version = ?",
                DEMO_VERSION, LEGACY_DEMO_VERSION);
        jdbcTemplate.update(
                "UPDATE learning_activity_event SET data_version = ? WHERE data_version = ?",
                DEMO_VERSION, LEGACY_DEMO_VERSION);
        jdbcTemplate.update(
                "UPDATE analysis_job SET data_versions = REPLACE(CAST(data_versions AS CHAR), ?, ?) "
                        + "WHERE CAST(data_versions AS CHAR) LIKE ?",
                LEGACY_DEMO_VERSION, DEMO_VERSION, "%" + LEGACY_DEMO_VERSION + "%");
        jdbcTemplate.update(
                "UPDATE twin_snapshot SET data_versions = REPLACE(CAST(data_versions AS CHAR), ?, ?) "
                        + "WHERE CAST(data_versions AS CHAR) LIKE ?",
                LEGACY_DEMO_VERSION, DEMO_VERSION, "%" + LEGACY_DEMO_VERSION + "%");
        jdbcTemplate.update(
                "UPDATE learning_plan SET data_versions = REPLACE(CAST(data_versions AS CHAR), ?, ?) "
                        + "WHERE CAST(data_versions AS CHAR) LIKE ?",
                LEGACY_DEMO_VERSION, DEMO_VERSION, "%" + LEGACY_DEMO_VERSION + "%");
        requireCount("remaining legacy courses", 0,
                "SELECT COUNT(*) FROM course WHERE data_version = ?", LEGACY_DEMO_VERSION);
        requireCount("remaining legacy questions", 0,
                "SELECT COUNT(*) FROM question WHERE data_version = ?", LEGACY_DEMO_VERSION);
        requireCount("remaining legacy skills", 0,
                "SELECT COUNT(*) FROM knowledge_skill WHERE data_version = ?", LEGACY_DEMO_VERSION);
        requireCount("remaining legacy answer events", 0,
                "SELECT COUNT(*) FROM answer_event WHERE data_version = ?", LEGACY_DEMO_VERSION);
        requireCount("remaining legacy activity events", 0,
                "SELECT COUNT(*) FROM learning_activity_event WHERE data_version = ?",
                LEGACY_DEMO_VERSION);
        jdbcTemplate.update("DELETE FROM source_file WHERE dataset_version_id = ?",
                LEGACY_DEMO_VERSION);
        int deleted = jdbcTemplate.update("DELETE FROM dataset_version WHERE version_id = ?",
                LEGACY_DEMO_VERSION);
        if (deleted != 1) {
            throw new IllegalStateException("The legacy demo registry was not removed exactly once.");
        }
        if (!legacyProcessingRunId.equals(findExistingProcessingRunIdForVersion(DEMO_VERSION))) {
            jdbcTemplate.update("DELETE FROM processing_run WHERE id = ? AND NOT EXISTS "
                    + "(SELECT 1 FROM dataset_version WHERE processing_run_id = ?)",
                    legacyProcessingRunId, legacyProcessingRunId);
        }
    }

    private void insertRegistry(DemoSeedRegistry registry) {
        var run = registry.processingRun();
        jdbcTemplate.update("""
                INSERT INTO processing_run(
                    id, pipeline_version, git_tree_sha256, config_sha256, random_seed,
                    status, started_at, completed_at, output_manifest_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    pipeline_version=VALUES(pipeline_version), git_tree_sha256=VALUES(git_tree_sha256),
                    config_sha256=VALUES(config_sha256), random_seed=VALUES(random_seed),
                    status=VALUES(status), started_at=VALUES(started_at),
                    completed_at=VALUES(completed_at),
                    output_manifest_sha256=VALUES(output_manifest_sha256)
                """,
                run.id(), run.pipelineVersion(), run.gitTreeSha256(), run.configSha256(),
                run.randomSeed(), run.status(), timestamp(run.startedAt()),
                timestamp(run.completedAt()), run.outputManifestSha256());
        for (Dataset dataset : registry.datasets()) {
            var source = dataset.source();
            jdbcTemplate.update("""
                INSERT INTO dataset_source(
                    id, source_key, name, official_url, license_name, license_url, citation_text)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE name=VALUES(name), official_url=VALUES(official_url),
                    license_name=VALUES(license_name), license_url=VALUES(license_url),
                    citation_text=VALUES(citation_text)
                """,
                    source.id(), source.sourceKey(), source.name(), source.officialUrl(),
                    source.licenseName(), source.licenseUrl(), source.citationText());
            var version = dataset.version();
            jdbcTemplate.update("""
                    INSERT INTO dataset_version(
                        version_id, source_id, source_sha256, schema_sha256,
                    processing_config_sha256, manifest_sha256, processing_run_id,
                    row_count, processed_at, manifest_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE source_id=VALUES(source_id),
                    source_sha256=VALUES(source_sha256), schema_sha256=VALUES(schema_sha256),
                    processing_config_sha256=VALUES(processing_config_sha256),
                    manifest_sha256=VALUES(manifest_sha256),
                    processing_run_id=VALUES(processing_run_id), row_count=VALUES(row_count),
                    processed_at=VALUES(processed_at), manifest_json=VALUES(manifest_json)
                """,
                    version.versionId(), source.id(), version.sourceSha256(),
                    version.schemaSha256(), version.processingConfigSha256(),
                    version.manifestSha256(), run.id(), version.rowCount(),
                    timestamp(version.processedAt()), json(version.manifestJson()));
            for (SourceFile file : dataset.files()) {
                UUID id = stableId("source-file", version.versionId(), file.fileName());
                jdbcTemplate.update("""
                        INSERT INTO source_file(
                            id, dataset_version_id, file_name, download_url, sha256, size_bytes)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE download_url=VALUES(download_url),
                            sha256=VALUES(sha256), size_bytes=VALUES(size_bytes)
                        """,
                        id.toString(), version.versionId(), file.fileName(), file.downloadUrl(),
                        file.sha256(), file.sizeBytes());
            }
        }
    }

    private void insertTeachingData(SeedPackage seed) {
        jdbcTemplate.update("""
                DELETE membership
                FROM user_organization_membership membership
                JOIN user_account account ON account.id = membership.user_id
                WHERE account.origin_type = 'DEMO_SYNTHETIC'
                  AND membership.membership_type = 'PRIMARY'
                """);
        jdbcTemplate.update("""
                INSERT INTO role_definition(code, description) VALUES
                    ('TEACHER', 'Teacher with access to assigned courses'),
                    ('STUDENT', 'Student with access to personal enrollments')
                ON DUPLICATE KEY UPDATE description=VALUES(description)
                """);
        batch("INSERT INTO organization_unit(id, code, display_name, unit_type, parent_id, enabled) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), unit_type=VALUES(unit_type), parent_id=VALUES(parent_id), enabled=VALUES(enabled)",
                seed.organizations(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.code());
                    statement.setString(3, row.displayName());
                    statement.setString(4, row.unitType());
                    statement.setString(5, row.parentId() == null ? null : row.parentId().toString());
                    statement.setBoolean(6, row.enabled());
                });
        batch("INSERT INTO academic_term(id, code, display_name, starts_on, ends_on, enabled) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), starts_on=VALUES(starts_on), ends_on=VALUES(ends_on), enabled=VALUES(enabled)",
                seed.academicTerms(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.code());
                    statement.setString(3, row.displayName());
                    statement.setObject(4, businessDate(row.startsOn()));
                    statement.setObject(5, businessDate(row.endsOn()));
                    statement.setBoolean(6, row.enabled());
                });
        String teacherHash = passwordEncoder.encode(teacherPassword);
        String studentHash = passwordEncoder.encode(studentPassword);
        Timestamp created = businessTimestamp(DEMO_REFERENCE_TIME.minusDays(180));
        batch("""
                INSERT INTO user_account(id, username, password_hash, display_name, enabled,
                    origin_type, created_at, updated_at)
                VALUES (?, ?, ?, ?, TRUE, 'DEMO_SYNTHETIC', ?, ?)
                ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), enabled=TRUE,
                    origin_type='DEMO_SYNTHETIC'
                """, seed.teachers(), (statement, row) -> {
                    statement.setString(1, row.id().toString()); statement.setString(2, row.username());
                    statement.setString(3, teacherHash); statement.setString(4, row.displayName());
                    statement.setTimestamp(5, created); statement.setTimestamp(6, created);
                });
        batch("INSERT IGNORE INTO user_role(user_id, role_code) VALUES (?, 'TEACHER')", seed.teachers(),
                (statement, row) -> statement.setString(1, row.id().toString()));
        batch("INSERT IGNORE INTO user_profile(user_id) VALUES (?)", seed.teachers(),
                (statement, row) -> statement.setString(1, row.id().toString()));
        batch("""
                INSERT INTO teacher_profile(user_id, staff_key, staff_number, college, department, academic_title)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE staff_key=VALUES(staff_key), staff_number=VALUES(staff_number),
                    college=VALUES(college), department=VALUES(department),
                    academic_title=VALUES(academic_title)
                """, seed.teachers(), (statement, row) -> {
                    statement.setString(1, row.id().toString()); statement.setString(2, row.staffKey());
                    statement.setString(3, row.staffNumber()); statement.setString(4, row.college());
                    statement.setString(5, row.department()); statement.setString(6, row.academicTitle());
                });
        batch("INSERT INTO user_organization_membership(user_id, organization_id, started_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE organization_id=VALUES(organization_id)",
                seed.teachers(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.organizationId().toString());
                    statement.setTimestamp(3, created);
                });

        batch("""
                INSERT INTO course(
                    id, code, title, term_label, starts_on, description, college, department,
                    organization_id, academic_term_id, credits, data_version, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PUBLISHED', ?)
                ON DUPLICATE KEY UPDATE code=VALUES(code), title=VALUES(title),
                    term_label=VALUES(term_label), starts_on=VALUES(starts_on),
                    description=VALUES(description), college=VALUES(college),
                    department=VALUES(department), organization_id=VALUES(organization_id),
                    academic_term_id=VALUES(academic_term_id), credits=VALUES(credits),
                    data_version=VALUES(data_version)
                """, seed.courses(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.code());
                    statement.setString(3, row.title());
                    statement.setString(4, row.presentation());
                    statement.setObject(5, businessDate(row.startsOn()));
                    statement.setString(6, row.description()); statement.setString(7, row.college());
                    statement.setString(8, row.department());
                    statement.setString(9, row.organizationId().toString());
                    statement.setString(10, row.academicTermId().toString());
                    statement.setBigDecimal(11, row.credits());
                    statement.setString(12, row.dataVersion()); statement.setTimestamp(13, created);
                });
        batch("""
                INSERT INTO teaching_assignment(course_id, teacher_id, assignment_role, assigned_at)
                VALUES (?, ?, 'OWNER', ?)
                ON DUPLICATE KEY UPDATE assignment_role=VALUES(assignment_role)
                """, seed.teachingAssignments(), (statement, row) -> {
                    statement.setString(1, row.courseId().toString());
                    statement.setString(2, row.teacherId().toString());
                    statement.setTimestamp(3, created);
                });

        batch("""
                INSERT INTO knowledge_skill(id, source_skill_key, name, data_version, content_origin)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE source_skill_key=VALUES(source_skill_key),
                    name=VALUES(name), data_version=VALUES(data_version),
                    content_origin=VALUES(content_origin)
                """, seed.skills(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.code());
                    statement.setString(3, row.name());
                    statement.setString(4, row.dataVersion());
                    statement.setString(5, row.contentOrigin());
                });
        batch("""
                INSERT INTO question(
                    id, source_problem_key, prompt_text, answer_type, options_json,
                    correct_answer, difficulty, data_version, knowledge_model_mode,
                    content_origin, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE source_problem_key=VALUES(source_problem_key),
                    prompt_text=VALUES(prompt_text), answer_type=VALUES(answer_type),
                    options_json=VALUES(options_json), correct_answer=VALUES(correct_answer),
                    difficulty=VALUES(difficulty), data_version=VALUES(data_version),
                    knowledge_model_mode=VALUES(knowledge_model_mode),
                    content_origin=VALUES(content_origin), active=VALUES(active)
                """, seed.questions(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.key());
                    statement.setString(3, row.prompt());
                    statement.setString(4, row.answerType());
                    statement.setString(5, row.optionsJson());
                    statement.setString(6, row.correctAnswer());
                    statement.setBigDecimal(7, row.difficulty());
                    statement.setString(8, row.dataVersion());
                    statement.setString(9, row.knowledgeModelMode());
                    statement.setString(10, row.contentOrigin());
                    statement.setBoolean(11, row.active());
                });
        batch("""
                INSERT INTO question_skill(question_id, skill_id, ordinal)
                VALUES (?, ?, 1)
                ON DUPLICATE KEY UPDATE skill_id=VALUES(skill_id), ordinal=VALUES(ordinal)
                """, seed.questions(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.skillId().toString());
                });
        batch("""
                INSERT INTO course_question(course_id, question_id, active, ordinal)
                VALUES (?, ?, TRUE, ?)
                ON DUPLICATE KEY UPDATE active=VALUES(active), ordinal=VALUES(ordinal)
                """, seed.courseQuestions(), (statement, row) -> {
                    statement.setString(1, row.courseId().toString());
                    statement.setString(2, row.questionId().toString());
                    statement.setInt(3, row.ordinal());
                });

        batch("""
                INSERT INTO user_account(
                    id, username, password_hash, display_name, enabled, origin_type, created_at, updated_at)
                VALUES (?, ?, ?, ?, TRUE, 'DEMO_SYNTHETIC', ?, ?)
                ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), enabled=TRUE,
                    origin_type='DEMO_SYNTHETIC'
                """, seed.students(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.username());
                    statement.setString(3, studentHash);
                    statement.setString(4, row.displayName());
                    statement.setTimestamp(5, created);
                    statement.setTimestamp(6, created);
                });
        batch("INSERT IGNORE INTO user_role(user_id, role_code) VALUES (?, 'STUDENT')",
                seed.students(), (statement, row) -> statement.setString(1, row.id().toString()));
        batch("INSERT IGNORE INTO user_profile(user_id) VALUES (?)", seed.students(),
                (statement, row) -> statement.setString(1, row.id().toString()));
        batch("""
                INSERT INTO student_profile(user_id, synthetic, synthetic_key, split_name,
                    student_number, college, major, cohort_year, class_name)
                VALUES (?, TRUE, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE synthetic=TRUE, synthetic_key=VALUES(synthetic_key),
                    split_name=VALUES(split_name), student_number=VALUES(student_number),
                    college=VALUES(college), major=VALUES(major),
                    cohort_year=VALUES(cohort_year), class_name=VALUES(class_name)
                """, seed.students(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.syntheticKey());
                    statement.setString(3, row.split());
                    statement.setString(4, row.studentNumber()); statement.setString(5, row.college());
                    statement.setString(6, row.major()); statement.setInt(7, row.cohortYear());
                    statement.setString(8, row.className());
                });
        batch("INSERT INTO user_organization_membership(user_id, organization_id, started_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE organization_id=VALUES(organization_id)",
                seed.students(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.organizationId().toString());
                    statement.setTimestamp(3, created);
                });
        batch("""
                INSERT INTO course_enrollment(course_id, student_id, status, enrolled_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status=course_enrollment.status
                """, seed.enrollments(), (statement, row) -> {
                    statement.setString(1, row.courseId().toString());
                    statement.setString(2, row.studentId().toString());
                    statement.setString(3, row.status());
                    statement.setTimestamp(4, created);
                });
        batch("INSERT INTO lms_section(id, course_id, title, description, position, status) VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE title=VALUES(title), description=VALUES(description), position=VALUES(position), status=VALUES(status)",
                seed.sections(), (statement, row) -> { statement.setString(1, row.id().toString()); statement.setString(2, row.courseId().toString()); statement.setString(3, row.title()); statement.setString(4, row.description()); statement.setInt(5, row.position()); statement.setString(6, row.status()); });
        batch("INSERT INTO lms_lesson(id, section_id, title, summary, body, resource_url, position, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE title=VALUES(title), summary=VALUES(summary), body=VALUES(body), resource_url=VALUES(resource_url), position=VALUES(position), status=VALUES(status)",
                seed.lessons(), (statement, row) -> { statement.setString(1, row.id().toString()); statement.setString(2, row.sectionId().toString()); statement.setString(3, row.title()); statement.setString(4, row.summary()); statement.setString(5, row.body()); statement.setString(6, row.resourceUrl()); statement.setInt(7, row.position()); statement.setString(8, row.status()); });
        batch("INSERT INTO lms_assessment(id, course_id, title, description, assessment_type, status, due_at, published_at, closed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE title=VALUES(title), description=VALUES(description), assessment_type=VALUES(assessment_type), status=VALUES(status), due_at=VALUES(due_at), published_at=VALUES(published_at), closed_at=VALUES(closed_at)",
                seed.assessments(), (statement, row) -> { statement.setString(1, row.id().toString()); statement.setString(2, row.courseId().toString()); statement.setString(3, row.title()); statement.setString(4, row.description()); statement.setString(5, row.type()); statement.setString(6, row.status()); statement.setTimestamp(7, businessTimestamp(row.dueAt())); statement.setTimestamp(8, businessTimestamp(row.publishedAt())); statement.setTimestamp(9, businessTimestamp(row.closedAt())); });
        batch("INSERT INTO lms_assessment_question(id, assessment_id, source_question_id, prompt, options_json, correct_choice_id, points, position) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE prompt=VALUES(prompt), options_json=VALUES(options_json), correct_choice_id=VALUES(correct_choice_id), points=VALUES(points), position=VALUES(position)",
                seed.assessmentQuestions(), (statement, row) -> { statement.setString(1, row.id().toString()); statement.setString(2, row.assessmentId().toString()); statement.setString(3, row.sourceQuestionId().toString()); statement.setString(4, row.prompt()); statement.setString(5, row.optionsJson()); statement.setString(6, row.correctChoiceId()); statement.setBigDecimal(7, row.points()); statement.setInt(8, row.position()); });
        jdbcTemplate.update("""
                INSERT IGNORE INTO lms_assessment_question_skill(question_id, skill_id, ordinal)
                SELECT aq.id, qs.skill_id, qs.ordinal
                FROM lms_assessment_question aq
                JOIN question_skill qs ON qs.question_id = aq.source_question_id
                """);
        batch("INSERT IGNORE INTO lms_lesson_progress(lesson_id, student_id, completed_at) VALUES (?, ?, ?)",
                seed.lessonProgress(), (statement, row) -> {
                    statement.setString(1, row.lessonId().toString());
                    statement.setString(2, row.studentId().toString());
                    statement.setTimestamp(3, businessTimestamp(row.completedAt()));
                });
        batch("""
                INSERT INTO lms_submission(
                    id, assessment_id, student_id, attempt_number, attempt_type,
                    idempotency_key_hash, request_sha256, score, max_score,
                    valid_for_grade, submitted_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id=lms_submission.id
                """, seed.submissions(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.assessmentId().toString());
                    statement.setString(3, row.studentId().toString());
                    statement.setInt(4, row.attemptNumber());
                    statement.setString(5, row.attemptType());
                    statement.setString(6, row.idempotencyKeyHash());
                    statement.setString(7, row.requestSha256());
                    statement.setBigDecimal(8, row.score());
                    statement.setBigDecimal(9, row.maxScore());
                    statement.setBoolean(10, row.validForGrade());
                    statement.setTimestamp(11, businessTimestamp(row.submittedAt()));
                });
        batch("INSERT IGNORE INTO lms_submission_answer(submission_id, question_id, selected_choice_id, correct, points_awarded) VALUES (?, ?, ?, ?, ?)",
                seed.submissionAnswers(), (statement, row) -> {
                    statement.setString(1, row.submissionId().toString());
                    statement.setString(2, row.questionId().toString());
                    statement.setString(3, row.selectedChoiceId());
                    statement.setBoolean(4, row.correct());
                    statement.setBigDecimal(5, row.pointsAwarded());
                });

        String processingRunId = seed.registry().processingRun().id();
        batch("""
                INSERT INTO synthetic_match(
                    id, synthetic_student_id, assistments_user_sha256, oulad_student_sha256,
                    split_name, performance_z, activity_z, persistence_z,
                    match_distance, random_seed, processing_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE assistments_user_sha256=VALUES(assistments_user_sha256),
                    oulad_student_sha256=VALUES(oulad_student_sha256),
                    split_name=VALUES(split_name), performance_z=VALUES(performance_z),
                    activity_z=VALUES(activity_z), persistence_z=VALUES(persistence_z),
                    match_distance=VALUES(match_distance), random_seed=VALUES(random_seed),
                    processing_run_id=VALUES(processing_run_id)
                """, seed.sourceLineage(), (statement, row) -> {
                    statement.setString(1, stableId("synthetic-match", row.studentId().toString()).toString());
                    statement.setString(2, row.studentId().toString());
                    statement.setString(3, row.assistmentsUserSha256());
                    statement.setString(4, row.ouladStudentSha256());
                    statement.setString(5, row.split());
                    statement.setBigDecimal(6, row.performanceZ());
                    statement.setBigDecimal(7, row.activityZ());
                    statement.setBigDecimal(8, row.persistenceZ());
                    statement.setBigDecimal(9, row.distance());
                    statement.setInt(10, row.seed());
                    statement.setString(11, processingRunId);
                });

        batch("""
                INSERT INTO answer_event(
                    id, course_id, student_id, question_id, source_order_id,
                    submitted_answer, correct, response_time_ms, attempt_number,
                    event_sequence, occurred_at, received_at, data_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE course_id=VALUES(course_id),
                    student_id=VALUES(student_id), question_id=VALUES(question_id),
                    source_order_id=VALUES(source_order_id),
                    submitted_answer=VALUES(submitted_answer), correct=VALUES(correct),
                    response_time_ms=VALUES(response_time_ms),
                    attempt_number=VALUES(attempt_number), event_sequence=VALUES(event_sequence),
                    occurred_at=VALUES(occurred_at), received_at=VALUES(received_at),
                    data_version=VALUES(data_version)
                """, seed.events(), (statement, row) -> {
                    statement.setString(1, row.id().toString());
                    statement.setString(2, row.courseId().toString());
                    statement.setString(3, row.studentId().toString());
                    statement.setString(4, row.questionId().toString());
                    statement.setLong(5, row.sourceOrderId());
                    statement.setString(6, row.selectedChoice());
                    statement.setBoolean(7, row.correct());
                    statement.setInt(8, row.responseTimeMs());
                    statement.setInt(9, row.attemptNumber());
                    statement.setLong(10, row.sequence());
                    Timestamp occurred = businessTimestamp(row.occurredAt());
                    statement.setTimestamp(11, occurred);
                    statement.setTimestamp(12, occurred);
                    statement.setString(13, DEMO_VERSION);
                });
        batch("""
                INSERT INTO answer_event_skill(answer_event_id, skill_id, ordinal)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE skill_id=VALUES(skill_id), ordinal=VALUES(ordinal)
                """, seed.eventSkills(), (statement, row) -> {
                    statement.setString(1, row.eventId().toString());
                    statement.setString(2, row.skillId().toString());
                    statement.setInt(3, row.ordinal());
                });
        batch("""
                INSERT INTO learning_activity_event(id, course_id, student_id, event_type,
                    resource_type, resource_id, answer_event_id, occurred_at, duration_seconds,
                    progress_percent, metadata_json, synthetic, data_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                ON DUPLICATE KEY UPDATE course_id=VALUES(course_id),
                    student_id=VALUES(student_id), event_type=VALUES(event_type),
                    resource_type=VALUES(resource_type), resource_id=VALUES(resource_id),
                    answer_event_id=VALUES(answer_event_id), occurred_at=VALUES(occurred_at),
                    duration_seconds=VALUES(duration_seconds),
                    progress_percent=VALUES(progress_percent), metadata_json=VALUES(metadata_json),
                    synthetic=TRUE, data_version=VALUES(data_version)
                """, seed.activities(), (statement, row) -> {
                    statement.setString(1, row.id().toString()); statement.setString(2, row.courseId().toString());
                    statement.setString(3, row.studentId().toString()); statement.setString(4, row.eventType());
                    statement.setString(5, row.resourceType()); statement.setString(6, row.resourceId());
                    statement.setString(7, row.answerEventId() == null ? null : row.answerEventId().toString());
                    statement.setTimestamp(8, businessTimestamp(row.occurredAt())); statement.setInt(9, row.durationSeconds());
                    statement.setBigDecimal(10, row.progressPercent()); statement.setString(11, row.metadataJson());
                    statement.setString(12, row.dataVersion());
                });
    }

    private void synchronizeDemoAccountPasswords() {
        synchronizeDemoAccountPassword("TEACHER", teacherPassword);
        synchronizeDemoAccountPassword("STUDENT", studentPassword);
    }

    void synchronizeDemoAccountPassword(String roleCode, String password) {
        List<String> existingHashes = jdbcTemplate.queryForList("""
                SELECT DISTINCT u.password_hash
                FROM user_account u
                JOIN user_role r ON r.user_id = u.id
                WHERE u.origin_type = 'DEMO_SYNTHETIC' AND r.role_code = ?
                """, String.class, roleCode);
        if (existingHashes.isEmpty()) {
            throw new IllegalStateException("Imported demo accounts are missing for role " + roleCode + ".");
        }
        if (existingHashes.stream().allMatch(hash -> passwordEncoder.matches(password, hash))) {
            return;
        }
        String synchronizedHash = passwordEncoder.encode(password);
        int updated = jdbcTemplate.update("""
                UPDATE user_account u
                JOIN user_role r ON r.user_id = u.id
                SET u.password_hash = ?, u.must_change_password = FALSE
                WHERE u.origin_type = 'DEMO_SYNTHETIC' AND r.role_code = ?
                """, synchronizedHash, roleCode);
        if (updated == 0) {
            throw new IllegalStateException("Demo account password synchronization updated no "
                    + roleCode + " accounts.");
        }
        LOGGER.info("Synchronized {} demo account passwords for role {}.", updated, roleCode);
    }

    private void insertModelRegistry(DemoSeedRegistry registry) {
        for (Model model : registry.models()) {
            jdbcTemplate.update("""
                    INSERT INTO model_version(
                        version_id, model_family, task_name, dataset_version_id,
                        status, random_seed, config_json, feature_contract_sha256,
                        calibrator_type, calibrator_sha256, manifest_sha256, selected,
                        frozen_at, test_evaluated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE model_family=VALUES(model_family),
                        task_name=VALUES(task_name), dataset_version_id=VALUES(dataset_version_id),
                        status=VALUES(status), random_seed=VALUES(random_seed),
                        config_json=VALUES(config_json),
                        feature_contract_sha256=VALUES(feature_contract_sha256),
                        calibrator_type=VALUES(calibrator_type),
                        calibrator_sha256=VALUES(calibrator_sha256),
                        manifest_sha256=VALUES(manifest_sha256), selected=VALUES(selected),
                        frozen_at=VALUES(frozen_at), test_evaluated_at=VALUES(test_evaluated_at)
                    """,
                    model.versionId(), model.modelFamily(), model.taskName(),
                    model.datasetVersionId(), model.status(), model.randomSeed(),
                    json(model.configJson()), model.featureContractSha256(),
                    model.calibratorType(), model.calibratorSha256(), model.manifestSha256(),
                    model.selected(), timestamp(model.frozenAt()), timestamp(model.testEvaluatedAt()));
            for (Metric metric : model.metrics()) {
                jdbcTemplate.update("""
                        INSERT INTO model_metric(
                            model_version_id, split_name, metric_name, metric_value, measured_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE metric_value=VALUES(metric_value),
                            measured_at=VALUES(measured_at)
                        """, model.versionId(), metric.splitName(), metric.metricName(),
                        metric.metricValue(), timestamp(metric.measuredAt()));
            }
            for (Artifact artifact : model.artifacts()) {
                jdbcTemplate.update("""
                        INSERT INTO model_artifact(
                            id, model_version_id, artifact_role, artifact_uri,
                            sha256, size_bytes, dependency_versions)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE artifact_uri=VALUES(artifact_uri),
                            sha256=VALUES(sha256), size_bytes=VALUES(size_bytes),
                            dependency_versions=VALUES(dependency_versions)
                        """,
                        stableId("model-artifact", model.versionId(), artifact.artifactRole()).toString(),
                        model.versionId(), artifact.artifactRole(), artifact.artifactUri(),
                        artifact.sha256(), artifact.sizeBytes(), json(artifact.dependencyVersions()));
            }
        }
        for (Deployment deployment : registry.deployments()) {
            jdbcTemplate.update("""
                    INSERT IGNORE INTO model_deployment(
                        task_name, active_version_id, rollback_version_id, deployed_at, deployed_by)
                    VALUES (?, ?, ?, ?, ?)
                    """, deployment.taskName(), deployment.activeVersionId(),
                    deployment.rollbackVersionId(), timestamp(deployment.deployedAt()),
                    deployment.deployedBy());
        }
    }

    private void verifyPersisted(SeedPackage seed) {
        verifyPersisted(seed, seed.registry().processingRun().id());
    }

    private void verifyPersisted(SeedPackage seed, String processingRunId) {
        requireCount("synthetic students", 2_000,
                "SELECT COUNT(*) FROM student_profile WHERE synthetic = TRUE");
        requireCount("demo answer events", seed.events().size(),
                "SELECT COUNT(*) FROM answer_event WHERE data_version = ? "
                        + "AND source_order_id >= ? AND source_order_id < ?",
                DEMO_VERSION, SEED_SOURCE_ORDER_BASE, SEED_SOURCE_ORDER_LIMIT);
        requireCount("unique demo answer events", seed.events().size(),
                "SELECT COUNT(DISTINCT id) FROM answer_event WHERE data_version = ? "
                        + "AND source_order_id >= ? AND source_order_id < ?",
                DEMO_VERSION, SEED_SOURCE_ORDER_BASE, SEED_SOURCE_ORDER_LIMIT);
        requireCount("demo courses", 20,
                "SELECT COUNT(*) FROM course WHERE data_version = ?", DEMO_VERSION);
        requireCount("source mappings", 2_000,
                "SELECT COUNT(*) FROM synthetic_match WHERE processing_run_id = ?",
                processingRunId);
        requireCount("demo questions", 600,
                "SELECT COUNT(*) FROM question WHERE data_version = ?", DEMO_VERSION);
        requireCount("learning activity events", seed.activities().size(),
                "SELECT COUNT(*) FROM learning_activity_event WHERE data_version = ? AND synthetic = TRUE",
                DEMO_VERSION);
        requireMinimumCount("organization units", seed.organizations().size(),
                "SELECT COUNT(*) FROM organization_unit");
        requireMinimumCount("lesson progress", seed.lessonProgress().size(),
                "SELECT COUNT(*) FROM lms_lesson_progress");
        requireMinimumCount("assessment attempts", seed.submissions().size(),
                "SELECT COUNT(*) FROM lms_submission");
        requireMinimumCount("submission answers", seed.submissionAnswers().size(),
                "SELECT COUNT(*) FROM lms_submission_answer");
        requireCount("active deployments", 6,
                "SELECT COUNT(*) FROM model_deployment WHERE task_name IN "
                        + "('MASTERY','NEXT_CORRECT','RISK','EXPLANATION','PLAN_RULES','DIAGNOSIS')");
        requireCount("model candidates", 7,
                "SELECT COUNT(*) FROM model_version WHERE model_family IN "
                        + "('IRT','BKT','DKT','AKT','LOGISTIC_REGRESSION','LIGHTGBM','CATBOOST')");
        requireCount("demo teachers", 12,
                "SELECT COUNT(*) FROM teacher_profile WHERE staff_key LIKE 'DEMO-TEACHER-%'");
        requireCount("distinct ASSISTments student mappings", 2_000,
                "SELECT COUNT(DISTINCT assistments_user_sha256) FROM synthetic_match "
                        + "WHERE processing_run_id = ?", processingRunId);
        requireCount("distinct OULAD student mappings", 2_000,
                "SELECT COUNT(DISTINCT oulad_student_sha256) FROM synthetic_match "
                        + "WHERE processing_run_id = ?", processingRunId);
        requireCount("distinct ASSISTments skill mappings", 60,
                "SELECT COUNT(DISTINCT source_skill_key) FROM knowledge_skill "
                        + "WHERE data_version = ?", DEMO_VERSION);
        requireCount("distinct ASSISTments problem mappings", 600,
                "SELECT COUNT(DISTINCT source_problem_key) FROM question "
                        + "WHERE data_version = ?", DEMO_VERSION);
        initialStateImporter.verifyPersisted(seed.initialStates());

        for (Dataset dataset : seed.registry().datasets()) {
            var version = dataset.version();
            Map<String, Object> persisted = jdbcTemplate.queryForMap("""
                    SELECT source_sha256, schema_sha256, processing_config_sha256,
                           manifest_sha256, processing_run_id, row_count
                    FROM dataset_version WHERE version_id = ?
                    """, version.versionId());
            if (!version.sourceSha256().equals(persisted.get("source_sha256"))
                    || !version.schemaSha256().equals(persisted.get("schema_sha256"))
                    || !version.processingConfigSha256().equals(
                            persisted.get("processing_config_sha256"))
                    || !version.manifestSha256().equals(persisted.get("manifest_sha256"))
                    || !processingRunId.equals(persisted.get("processing_run_id"))
                    || ((Number) persisted.get("row_count")).longValue() != version.rowCount()) {
                throw new IllegalStateException(
                        "Persisted dataset version differs: " + version.versionId());
            }
            requireCount("source files for " + version.versionId(), dataset.files().size(),
                    "SELECT COUNT(*) FROM source_file WHERE dataset_version_id = ?",
                    version.versionId());
        }
        for (Model model : seed.registry().models()) {
            Map<String, Object> persisted = jdbcTemplate.queryForMap("""
                    SELECT model_family, task_name, dataset_version_id,
                           random_seed, feature_contract_sha256, calibrator_sha256,
                           manifest_sha256
                    FROM model_version WHERE version_id = ?
                    """, model.versionId());
            if (!model.modelFamily().equals(persisted.get("model_family"))
                    || !model.taskName().equals(persisted.get("task_name"))
                    || !model.datasetVersionId().equals(persisted.get("dataset_version_id"))
                    || ((Number) persisted.get("random_seed")).intValue() != model.randomSeed()
                    || !model.featureContractSha256().equals(
                            persisted.get("feature_contract_sha256"))
                    || !Objects.equals(model.calibratorSha256(),
                            persisted.get("calibrator_sha256"))
                    || !model.manifestSha256().equals(persisted.get("manifest_sha256"))) {
                throw new IllegalStateException(
                        "Persisted model version differs: " + model.versionId());
            }
            requireCount("metrics for " + model.versionId(), model.metrics().size(),
                    "SELECT COUNT(*) FROM model_metric WHERE model_version_id = ?",
                    model.versionId());
            requireCount("artifacts for " + model.versionId(), model.artifacts().size(),
                    "SELECT COUNT(*) FROM model_artifact WHERE model_version_id = ?",
                    model.versionId());
            for (Artifact artifact : model.artifacts()) {
                requireCount("artifact " + model.versionId() + "/" + artifact.artifactRole(), 1,
                        "SELECT COUNT(*) FROM model_artifact WHERE model_version_id = ? "
                                + "AND artifact_role = ? AND sha256 = ? AND size_bytes = ?",
                        model.versionId(), artifact.artifactRole(), artifact.sha256(),
                        artifact.sizeBytes());
            }
        }
        Set<String> registeredModels = seed.registry().models().stream()
                .map(Model::versionId)
                .collect(java.util.stream.Collectors.toSet());
        for (Deployment deployment : seed.registry().deployments()) {
            Map<String, Object> persisted = jdbcTemplate.queryForMap("""
                    SELECT active_version_id, rollback_version_id
                    FROM model_deployment WHERE task_name = ?
                    """, deployment.taskName());
            String active = (String) persisted.get("active_version_id");
            String rollback = (String) persisted.get("rollback_version_id");
            if (!registeredModels.contains(active)
                    || (rollback != null && !registeredModels.contains(rollback))) {
                throw new IllegalStateException(
                        "A persisted deployment references an unregistered model: "
                                + deployment.taskName());
            }
        }
    }

    private CourseRow courseRow(Row row) {
        CourseRow result = new CourseRow(
                uuid(row, "course_id"),
                row.required("course_code"),
                row.required("title"),
                row.required("presentation"),
                LocalDate.parse(row.required("starts_on")),
                row.required("description"), row.required("college"), row.required("department"),
                uuid(row, "organization_id"), uuid(row, "academic_term_id"),
                BigDecimal.valueOf(row.doubleValue("credits")),
                row.required("data_version"),
                row.booleanValue("synthetic"));
        requireSyntheticVersion(result.dataVersion(), result.synthetic(), row);
        return result;
    }

    private OrganizationRow organizationRow(Row row) { return new OrganizationRow(uuid(row, "organization_id"), row.required("code"), row.required("display_name"), row.required("unit_type"), nullableUuid(row, "parent_id"), row.booleanValue("enabled")); }
    private AcademicTermRow academicTermRow(Row row) { return new AcademicTermRow(uuid(row, "academic_term_id"), row.required("code"), row.required("display_name"), LocalDate.parse(row.required("starts_on")), LocalDate.parse(row.required("ends_on")), row.booleanValue("enabled")); }
    private TeacherRow teacherRow(Row row) { return new TeacherRow(uuid(row, "teacher_id"), row.required("username"), row.required("display_name"), row.required("staff_number"), row.required("staff_key"), row.required("college"), row.required("department"), uuid(row, "organization_id"), row.required("academic_title"), row.booleanValue("synthetic")); }
    private TeachingAssignmentRow teachingAssignmentRow(Row row) { return new TeachingAssignmentRow(uuid(row, "course_id"), uuid(row, "teacher_id")); }

    private SkillRow skillRow(Row row) {
        SkillRow result = new SkillRow(
                uuid(row, "knowledge_skill_id"),
                row.required("skill_code"),
                row.required("name"),
                row.required("data_version"),
                row.required("content_origin"),
                row.booleanValue("synthetic"));
        requireSyntheticVersion(result.dataVersion(), result.synthetic(), row);
        return result;
    }

    private QuestionRow questionRow(Row row) {
        double difficulty = row.doubleValue("difficulty");
        if (difficulty < 0 || difficulty > 1) {
            throw row.invalid("difficulty", "must be in [0,1]");
        }
        QuestionRow result = new QuestionRow(
                uuid(row, "question_id"),
                row.required("question_key"),
                uuid(row, "knowledge_skill_id"),
                row.required("prompt_text"),
                row.required("answer_type"),
                row.required("options_json"),
                row.required("correct_answer"),
                BigDecimal.valueOf(difficulty),
                row.required("data_version"),
                row.required("knowledge_model_mode"),
                row.required("content_origin"),
                row.booleanValue("active"),
                row.booleanValue("synthetic"));
        requireSyntheticVersion(result.dataVersion(), result.synthetic(), row);
        return result;
    }

    private StudentRow studentRow(Row row) {
        double performance = probability(row, "performance");
        double activity = probability(row, "activity");
        double persistence = probability(row, "persistence");
        double risk = probability(row, "initial_risk_probability");
        return new StudentRow(
                uuid(row, "student_id"),
                row.required("username"),
                row.required("display_name"),
                row.required("student_number"), row.required("college"), row.required("major"),
                row.intValue("cohort_year"), row.required("class_name"),
                uuid(row, "organization_id"),
                row.required("synthetic_key"),
                row.booleanValue("synthetic"),
                row.required("split"),
                BigDecimal.valueOf(performance),
                BigDecimal.valueOf(activity),
                BigDecimal.valueOf(persistence),
                BigDecimal.valueOf(risk));
    }

    private EnrollmentRow enrollmentRow(Row row) { return new EnrollmentRow(uuid(row, "course_id"), uuid(row, "student_id"), row.required("status")); }
    private CourseQuestionRow courseQuestionRow(Row row) { return new CourseQuestionRow(uuid(row, "course_id"), uuid(row, "question_id"), row.intValue("ordinal")); }
    private SectionRow sectionRow(Row row) { return new SectionRow(uuid(row, "section_id"), uuid(row, "course_id"), row.required("title"), row.required("description"), row.intValue("position"), row.required("status")); }
    private LessonRow lessonRow(Row row) { return new LessonRow(uuid(row, "lesson_id"), uuid(row, "section_id"), row.required("title"), row.required("summary"), row.required("body"), row.value("resource_url"), row.intValue("position"), row.required("status")); }
    private AssessmentRow assessmentRow(Row row) { return new AssessmentRow(uuid(row, "assessment_id"), uuid(row, "course_id"), row.required("title"), row.required("description"), row.required("assessment_type"), row.required("status"), nullableOffset(row, "due_at"), nullableOffset(row, "published_at"), nullableOffset(row, "closed_at")); }
    private AssessmentQuestionRow assessmentQuestionRow(Row row) { return new AssessmentQuestionRow(uuid(row, "assessment_question_id"), uuid(row, "assessment_id"), uuid(row, "source_question_id"), row.required("prompt"), row.required("options_json"), row.required("correct_choice_id"), BigDecimal.valueOf(row.doubleValue("points")), row.intValue("position")); }
    private LessonProgressRow lessonProgressRow(Row row) { return new LessonProgressRow(uuid(row, "lesson_id"), uuid(row, "student_id"), OffsetDateTime.parse(row.required("completed_at"))); }
    private SubmissionRow submissionRow(Row row) {
        String idempotencyHash = row.required("idempotency_key_hash");
        String requestSha = row.required("request_sha256");
        if (!isSha256(idempotencyHash) || !isSha256(requestSha)) {
            throw row.invalid("request_sha256", "submission hashes must be lowercase SHA-256");
        }
        int attemptNumber = row.intValue("attempt_number");
        BigDecimal score = BigDecimal.valueOf(row.doubleValue("score"));
        BigDecimal maxScore = BigDecimal.valueOf(row.doubleValue("max_score"));
        if (attemptNumber < 1 || score.signum() < 0 || maxScore.signum() <= 0
                || score.compareTo(maxScore) > 0) {
            throw row.invalid("attempt_number", "submission score or attempt is outside its domain");
        }
        return new SubmissionRow(uuid(row, "submission_id"), uuid(row, "assessment_id"),
                uuid(row, "student_id"), attemptNumber, row.required("attempt_type"),
                idempotencyHash, requestSha, score, maxScore,
                row.booleanValue("valid_for_grade"),
                OffsetDateTime.parse(row.required("submitted_at")));
    }
    private SubmissionAnswerRow submissionAnswerRow(Row row) { return new SubmissionAnswerRow(uuid(row, "submission_id"), uuid(row, "assessment_question_id"), row.required("selected_choice_id"), row.booleanValue("correct"), BigDecimal.valueOf(row.doubleValue("points_awarded"))); }
    private ActivityRow activityRow(Row row) { return new ActivityRow(uuid(row, "learning_activity_event_id"), uuid(row, "course_id"), uuid(row, "student_id"), row.required("event_type"), blankToNull(row.value("resource_type")), blankToNull(row.value("resource_id")), nullableUuid(row, "answer_event_id"), OffsetDateTime.parse(row.required("occurred_at")), row.intValue("duration_seconds"), row.value("progress_percent").isBlank() ? null : BigDecimal.valueOf(row.doubleValue("progress_percent")), row.required("metadata_json"), row.booleanValue("synthetic"), row.required("data_version")); }

    private EventRow eventRow(Row row) {
        probability(row, "correct_probability");
        int response = row.intValue("response_time_ms");
        int attempt = row.intValue("attempt_number");
        if (response < 0 || attempt < 1) {
            throw row.invalid("response_time_ms", "or attempt_number is outside its domain");
        }
        String eventKey = row.required("event_key");
        java.util.regex.Matcher eventKeyMatcher = EVENT_KEY.matcher(eventKey);
        if (!eventKeyMatcher.matches()) {
            throw row.invalid("event_key", "must match DEMO-E-NNNNN-NNN");
        }
        long sequence = row.longValue("event_sequence");
        if (sequence != Long.parseLong(eventKeyMatcher.group(2))) {
            throw row.invalid("event_sequence", "must match the event key sequence");
        }
        long sourceOrderId = SEED_SOURCE_ORDER_BASE
                + Long.parseLong(eventKeyMatcher.group(1)) * 1_000L
                + sequence;
        return new EventRow(
                uuid(row, "answer_event_id"),
                eventKey,
                sourceOrderId,
                uuid(row, "student_id"),
                uuid(row, "course_id"),
                uuid(row, "question_id"),
                sequence,
                row.required("selected_choice"),
                row.booleanValue("correct"),
                response,
                attempt,
                OffsetDateTime.parse(row.required("occurred_at")),
                row.required("split"),
                row.booleanValue("synthetic"));
    }

    private EventSkillRow eventSkillRow(Row row) {
        return new EventSkillRow(
                uuid(row, "answer_event_id"),
                uuid(row, "knowledge_skill_id"),
                row.intValue("ordinal"),
                row.booleanValue("synthetic"));
    }

    private SourceLineageRow sourceLineageRow(Row row) {
        String assistmentsUserSha256 = row.required("assistments_user_sha256");
        String ouladStudentSha256 = row.required("oulad_student_sha256");
        if (!isSha256(assistmentsUserSha256)) {
            throw row.invalid("assistments_user_sha256", "must be lowercase SHA-256");
        }
        if (!isSha256(ouladStudentSha256)) {
            throw row.invalid("oulad_student_sha256", "must be lowercase SHA-256");
        }
        return new SourceLineageRow(
                uuid(row, "student_id"),
                assistmentsUserSha256,
                ouladStudentSha256,
                row.required("split"),
                BigDecimal.valueOf(row.doubleValue("performance_z")),
                BigDecimal.valueOf(row.doubleValue("activity_z")),
                BigDecimal.valueOf(row.doubleValue("persistence_z")),
                BigDecimal.valueOf(row.doubleValue("match_distance")),
                row.intValue("random_seed"));
    }

    private KnowledgeLineageRow knowledgeLineageRow(Row row) {
        return new KnowledgeLineageRow(
                uuid(row, "knowledge_skill_id"),
                row.required("source_skill_key"),
                uuid(row, "question_id"),
                row.required("source_problem_key"));
    }

    private void requirePasswords() {
        if (teacherPassword.length() < 12 || studentPassword.length() < 12) {
            throw new IllegalStateException(
                    "Demo teacher and student passwords must contain at least 12 characters.");
        }
    }

    private void validateJson(String value, String context) {
        try {
            if (objectMapper.readTree(value) == null) {
                throw new IllegalStateException(context + " is null JSON.");
            }
        } catch (IOException exception) {
            throw new IllegalStateException(context + " is invalid JSON.", exception);
        }
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Registry JSON serialization failed.", exception);
        }
    }

    private <T> void batch(
            String sql,
            List<T> rows,
            org.springframework.jdbc.core.ParameterizedPreparedStatementSetter<T> setter) {
        jdbcTemplate.batchUpdate(sql, rows, BATCH_SIZE, setter);
    }

    private long count(String sql, Object... arguments) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, arguments);
        return result == null ? 0 : result;
    }

    private void requireCount(String name, long expected, String sql, Object... arguments) {
        long actual = count(sql, arguments);
        if (actual != expected) {
            throw new IllegalStateException(
                    "Persisted " + name + " count differs: " + actual + " != " + expected);
        }
    }

    private void requireMinimumCount(String name, long expected, String sql, Object... arguments) {
        long actual = count(sql, arguments);
        if (actual < expected) {
            throw new IllegalStateException(
                    "Persisted " + name + " count is below the seed: " + actual + " < " + expected);
        }
    }

    private static <T, K> Set<K> unique(
            List<T> rows, Function<T, K> key, String context) {
        Set<K> result = new LinkedHashSet<>();
        for (T row : rows) {
            K value = key.apply(row);
            if (value == null || !result.add(value)) {
                throw new IllegalStateException("Duplicate or null " + context + ": " + value);
            }
        }
        return result;
    }

    private static <T, K> Map<K, T> index(List<T> rows, Function<T, K> key) {
        Map<K, T> result = new LinkedHashMap<>();
        for (T row : rows) {
            T previous = result.put(key.apply(row), row);
            if (previous != null) {
                throw new IllegalStateException("Duplicate indexed seed key: " + key.apply(row));
            }
        }
        return result;
    }

    private static void requireSize(List<?> rows, int expected, String context) {
        if (rows.size() != expected) {
            throw new IllegalStateException(
                    "Demo " + context + " count differs: " + rows.size() + " != " + expected);
        }
    }

    private static double probability(Row row, String column) {
        double value = row.doubleValue(column);
        if (value < 0 || value > 1) {
            throw row.invalid(column, "must be in [0,1]");
        }
        return value;
    }

    private static UUID uuid(Row row, String column) {
        try {
            return UUID.fromString(row.required(column));
        } catch (IllegalArgumentException exception) {
            throw row.invalid(column, "must be a UUID");
        }
    }

    private static UUID nullableUuid(Row row, String column) {
        return row.value(column).isBlank() ? null : uuid(row, column);
    }

    private static OffsetDateTime nullableOffset(Row row, String column) {
        return row.value(column).isBlank() ? null : OffsetDateTime.parse(row.required(column));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String enrollmentKey(UUID courseId, UUID studentId) {
        return courseId + "|" + studentId;
    }

    private static void requireSyntheticVersion(String version, boolean synthetic, Row row) {
        if (!DEMO_VERSION.equals(version) || !synthetic) {
            throw row.invalid("data_version", "must identify synthetic-demo-v6");
        }
    }

    private static void requireText(String value, String context) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(context + " must not be blank.");
        }
    }

    private static void requireSha(String value, String context) {
        if (!isSha256(value)) {
            throw new IllegalStateException(context + " is not a lowercase SHA-256.");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && SHA256.matcher(value).matches();
    }

    private static OffsetDateTime offset(String value) {
        if (value == null) {
            return null;
        }
        return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static Timestamp timestamp(String value) {
        OffsetDateTime parsed = offset(value);
        return parsed == null ? null : Timestamp.from(parsed.toInstant());
    }

    OffsetDateTime businessTime(OffsetDateTime value) {
        return value == null ? null : value.plusDays(
                ChronoUnit.DAYS.between(DEMO_REFERENCE_TIME.toLocalDate(), demoAsOf));
    }

    private LocalDate businessDate(LocalDate value) {
        return value.plusDays(
                ChronoUnit.DAYS.between(DEMO_REFERENCE_TIME.toLocalDate(), demoAsOf));
    }

    private Timestamp businessTimestamp(OffsetDateTime value) {
        OffsetDateTime shifted = businessTime(value);
        return shifted == null ? null : Timestamp.from(shifted.toInstant());
    }

    private static UUID stableId(String category, String... values) {
        StringBuilder payload = new StringBuilder(category);
        for (String value : values) {
            payload.append('|').append(value);
        }
        return UUID.nameUUIDFromBytes(payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = Files.newInputStream(path, StandardOpenOption.READ)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = stream.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static <T> Set<T> difference(Set<T> expected, Set<T> actual) {
        Set<T> result = new HashSet<>(expected);
        result.removeAll(actual);
        return result;
    }

    private record ImportFile(Path path, long rows, String sha256) {}

    record LegacySeedState(
            long students,
            long teachers,
            long courses,
            long questions,
            long skills,
            long answerEvents,
            long activityEvents,
            long matches) {}

    private record SeedPackage(
            DemoSeedRegistry registry,
            String demoManifestSha256,
            List<OrganizationRow> organizations,
            List<AcademicTermRow> academicTerms,
            List<CourseRow> courses,
            List<TeacherRow> teachers,
            List<TeachingAssignmentRow> teachingAssignments,
            List<SkillRow> skills,
            List<QuestionRow> questions,
            List<StudentRow> students,
            List<EnrollmentRow> enrollments,
            List<CourseQuestionRow> courseQuestions,
            List<SectionRow> sections,
            List<LessonRow> lessons,
            List<AssessmentRow> assessments,
            List<AssessmentQuestionRow> assessmentQuestions,
            List<LessonProgressRow> lessonProgress,
            List<SubmissionRow> submissions,
            List<SubmissionAnswerRow> submissionAnswers,
            List<ActivityRow> activities,
            List<EventRow> events,
            List<EventSkillRow> eventSkills,
            List<SourceLineageRow> sourceLineage,
            List<KnowledgeLineageRow> knowledgeLineage,
            DemoInitialStateImporter.Seed initialStates) {}

    private record CourseRow(
            UUID id,
            String code,
            String title,
            String presentation,
            LocalDate startsOn,
            String description,
            String college,
            String department,
            UUID organizationId,
            UUID academicTermId,
            BigDecimal credits,
            String dataVersion,
            boolean synthetic) {}

    private record OrganizationRow(UUID id, String code, String displayName, String unitType,
            UUID parentId, boolean enabled) {}
    private record AcademicTermRow(UUID id, String code, String displayName, LocalDate startsOn,
            LocalDate endsOn, boolean enabled) {}
    private record TeacherRow(UUID id, String username, String displayName, String staffNumber,
            String staffKey, String college, String department, UUID organizationId,
            String academicTitle, boolean synthetic) {}
    private record TeachingAssignmentRow(UUID courseId, UUID teacherId) {}

    private record SkillRow(
            UUID id, String code, String name, String dataVersion, String contentOrigin,
            boolean synthetic) {}

    private record QuestionRow(
            UUID id,
            String key,
            UUID skillId,
            String prompt,
            String answerType,
            String optionsJson,
            String correctAnswer,
            BigDecimal difficulty,
            String dataVersion,
            String knowledgeModelMode,
            String contentOrigin,
            boolean active,
            boolean synthetic) {}

    private record StudentRow(
            UUID id,
            String username,
            String displayName,
            String studentNumber,
            String college,
            String major,
            int cohortYear,
            String className,
            UUID organizationId,
            String syntheticKey,
            boolean synthetic,
            String split,
            BigDecimal performance,
            BigDecimal activity,
            BigDecimal persistence,
            BigDecimal initialRisk) {}

    private record EnrollmentRow(UUID courseId, UUID studentId, String status) {}
    private record SectionRow(UUID id, UUID courseId, String title, String description, int position, String status) {}
    private record LessonRow(UUID id, UUID sectionId, String title, String summary, String body, String resourceUrl, int position, String status) {}
    private record AssessmentRow(UUID id, UUID courseId, String title, String description,
            String type, String status, OffsetDateTime dueAt, OffsetDateTime publishedAt,
            OffsetDateTime closedAt) {}
    private record AssessmentQuestionRow(UUID id, UUID assessmentId, UUID sourceQuestionId, String prompt, String optionsJson, String correctChoiceId, BigDecimal points, int position) {}
    private record LessonProgressRow(UUID lessonId, UUID studentId, OffsetDateTime completedAt) {}
    private record SubmissionRow(UUID id, UUID assessmentId, UUID studentId, int attemptNumber,
            String attemptType, String idempotencyKeyHash, String requestSha256,
            BigDecimal score, BigDecimal maxScore, boolean validForGrade,
            OffsetDateTime submittedAt) {}
    private record SubmissionAnswerRow(UUID submissionId, UUID questionId,
            String selectedChoiceId, boolean correct, BigDecimal pointsAwarded) {}
    private record ActivityRow(UUID id, UUID courseId, UUID studentId, String eventType, String resourceType, String resourceId, UUID answerEventId, OffsetDateTime occurredAt, int durationSeconds, BigDecimal progressPercent, String metadataJson, boolean synthetic, String dataVersion) {}

    private record EventRow(
            UUID id,
            String eventKey,
            long sourceOrderId,
            UUID studentId,
            UUID courseId,
            UUID questionId,
            long sequence,
            String selectedChoice,
            boolean correct,
            int responseTimeMs,
            int attemptNumber,
            OffsetDateTime occurredAt,
            String split,
            boolean synthetic) {}

    private record EventSkillRow(UUID eventId, UUID skillId, int ordinal, boolean synthetic) {}

    private record SourceLineageRow(
            UUID studentId,
            String assistmentsUserSha256,
            String ouladStudentSha256,
            String split,
            BigDecimal performanceZ,
            BigDecimal activityZ,
            BigDecimal persistenceZ,
            BigDecimal distance,
            int seed) {}

    private record KnowledgeLineageRow(
            UUID skillId, String sourceSkillKey, UUID questionId, String sourceProblemKey) {}

    private record CourseQuestionRow(UUID courseId, UUID questionId, int ordinal) {}

    private static final class SeedReadException extends RuntimeException {
        private SeedReadException(IOException cause) {
            super(cause);
        }
    }
}
