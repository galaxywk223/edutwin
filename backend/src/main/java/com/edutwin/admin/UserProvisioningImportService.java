package com.edutwin.admin;

import com.edutwin.dashboard.TeacherDashboardService;
import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UserProvisioningImportService {
    private static final List<String> USER_HEADERS = List.of(
            "username", "display_name", "roles", "enabled", "student_number", "staff_number",
            "organization_code", "college", "department", "major", "cohort_year", "class_name", "academic_title");
    private static final List<String> LEGACY_USER_HEADERS = List.of(
            "username", "display_name", "role", "enabled", "student_number", "staff_number",
            "college", "department", "major", "cohort_year", "class_name", "academic_title");
    private static final List<String> MEMBERSHIP_HEADERS = List.of(
            "username", "course_code", "membership_type", "membership_status");
    private static final Set<String> ROLES = Set.of("ADMIN", "TEACHER", "STUDENT", "COUNSELOR");
    private static final Set<String> STUDENT_STATUSES = Set.of("ACTIVE", "COMPLETED", "WITHDRAWN");
    private static final Set<String> TEACHING_ROLES = Set.of("OWNER", "CO_TEACHER");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transaction;
    private final TeacherDashboardService teacherDashboardService;

    public UserProvisioningImportService(JdbcClient jdbc, PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager,
            TeacherDashboardService teacherDashboardService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.transaction = new TransactionTemplate(transactionManager);
        this.teacherDashboardService = teacherDashboardService;
    }

    public TemplateFile template(String requested) {
        String value = requested == null ? "" : requested.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "xlsx", "excel" -> excelTemplate();
            case "users.csv", "users" -> new TemplateFile("users.csv", "text/csv; charset=utf-8",
                    csvTemplate(USER_HEADERS));
            case "course_memberships.csv", "course-memberships" -> new TemplateFile(
                    "course_memberships.csv", "text/csv; charset=utf-8", csvTemplate(MEMBERSHIP_HEADERS));
            default -> throw badRequest("USER_IMPORT_TEMPLATE_INVALID",
                    "Template must be xlsx, users.csv, or course_memberships.csv.");
        };
    }

    public AdminDtos.UserImportPreview preview(List<MultipartFile> files) {
        ImportData data = parse(files);
        Validation validation = validate(data);
        return new AdminDtos.UserImportPreview(data.sha256(), data.format(), data.users().size(),
                data.memberships().size(), validation.creates(), validation.updates(),
                validation.errors(), validation.errors().isEmpty());
    }

    public AdminDtos.UserImportResult commit(EduTwinPrincipal actor, List<MultipartFile> files,
            String expectedSha256) {
        ImportData data = parse(files);
        if (expectedSha256 == null || !MessageDigest.isEqual(
                data.sha256().getBytes(StandardCharsets.US_ASCII),
                expectedSha256.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw conflict("USER_IMPORT_DIGEST_MISMATCH", "The uploaded files differ from the previewed files.");
        }
        Validation validation = validate(data);
        requireValid(validation);

        Map<String, PreparedUser> prepared = new LinkedHashMap<>();
        List<AdminDtos.TemporaryCredential> credentials = new ArrayList<>();
        for (UserRow row : data.users()) {
            ExistingUser existing = findUser(row.username());
            if (existing != null) {
                prepared.put(row.username(), new PreparedUser(existing.id(), row, null));
                continue;
            }
            UUID id = UUID.randomUUID();
            String password = temporaryPassword();
            prepared.put(row.username(), new PreparedUser(id, row, passwordEncoder.encode(password)));
            credentials.add(new AdminDtos.TemporaryCredential(
                    row.username(), row.displayName(), String.join("|", row.roles()), password));
        }

        Integer memberships = transaction.execute(status -> {
            lockActiveAdministrators();
            Validation current = validate(data);
            requireValid(current);
            for (PreparedUser user : prepared.values()) upsertUser(actor, user);
            int count = 0;
            for (MembershipRow membership : data.memberships()) {
                applyMembership(membership, prepared);
                count++;
            }
            return count;
        });
        if (memberships == null) throw new IllegalStateException("User import transaction returned no result.");
        return new AdminDtos.UserImportResult(data.sha256(), validation.creates(), validation.updates(),
                memberships, List.copyOf(credentials));
    }

    private Validation validate(ImportData data) {
        List<AdminDtos.UserImportError> errors = new ArrayList<>(data.parseErrors());
        int creates = 0;
        int updates = 0;
        Set<String> usernames = new HashSet<>();
        Map<String, UserRow> rowsByUsername = new HashMap<>();
        int projectedActiveAdmins = activeAdminCount();
        for (UserRow row : data.users()) {
            if (!usernames.add(row.username())) {
                error(errors, row.file(), row.sheet(), row.row(), "username", "USER_IMPORT_DUPLICATE_USERNAME",
                        "The username occurs more than once in the import.");
                continue;
            }
            rowsByUsername.put(row.username(), row);
            validateUserRow(row, errors);
            ExistingUser existing = findUser(row.username());
            if (existing == null) creates++;
            else {
                updates++;
                if (existing.enabled() && existing.roles().contains("ADMIN")) projectedActiveAdmins--;
                if ("DEMO_SYNTHETIC".equals(existing.originType()) || existing.synthetic())
                    error(errors, row.file(), row.sheet(), row.row(), "username", "USER_IMPORT_SYNTHETIC_CONFLICT",
                            "Synthetic demo accounts cannot be updated through import.");
            }
            if (Boolean.TRUE.equals(row.enabled()) && row.roles().contains("ADMIN")) projectedActiveAdmins++;
            validateIdentifierConflicts(row, existing, errors);
        }
        if (!data.users().isEmpty() && projectedActiveAdmins < 1) {
            UserRow row = data.users().getFirst();
            error(errors, row.file(), row.sheet(), row.row(), "roles", "LAST_ADMIN_REQUIRED",
                    "The import must leave at least one enabled administrator.");
        }
        for (MembershipRow row : data.memberships()) {
            UserRow imported = rowsByUsername.get(row.username());
            ExistingUser user = findUser(row.username());
            Set<String> roles = imported == null ? user == null ? Set.of() : user.roles() : imported.roles();
            if (roles.isEmpty()) {
                error(errors, row.file(), row.sheet(), row.row(), "username", "USER_IMPORT_USER_NOT_FOUND",
                        "Membership usernames must exist or be present in users.");
                continue;
            }
            CourseFact course = course(row.courseCode());
            if (course == null) error(errors, row.file(), row.sheet(), row.row(), "course_code",
                    "USER_IMPORT_COURSE_NOT_FOUND", "Courses must exist before memberships are imported.");
            validateMembership(row, roles, course, user, errors);
        }
        return new Validation(creates, updates, List.copyOf(errors));
    }

    private void validateUserRow(UserRow row, List<AdminDtos.UserImportError> errors) {
        if (row.username().isBlank()) error(errors, row.file(), row.sheet(), row.row(), "username",
                "USER_IMPORT_REQUIRED", "username is required.");
        if (row.displayName().isBlank()) error(errors, row.file(), row.sheet(), row.row(), "display_name",
                "USER_IMPORT_REQUIRED", "display_name is required.");
        if (row.roles().isEmpty() || row.roles().stream().anyMatch(value -> !ROLES.contains(value)))
            error(errors, row.file(), row.sheet(), row.row(), "roles",
                    "USER_IMPORT_ROLE_INVALID", "roles must be a | separated set of supported roles.");
        if (row.enabled() == null) error(errors, row.file(), row.sheet(), row.row(), "enabled",
                "USER_IMPORT_BOOLEAN_INVALID", "enabled must be a supported boolean value.");
        if (row.roles().contains("STUDENT")) {
            required(row, errors, "student_number", row.studentNumber());
            requireOrganizationOrLegacy(row, errors, "major", row.major());
            requireOrganizationOrLegacy(row, errors, "cohort_year", row.cohortYear());
            requireOrganizationOrLegacy(row, errors, "class_name", row.className());
            if (!row.cohortYear().isBlank()) {
                try {
                    int year = Integer.parseInt(row.cohortYear());
                    if (year < 2000 || year > 2200) throw new NumberFormatException();
                } catch (NumberFormatException exception) {
                    error(errors, row.file(), row.sheet(), row.row(), "cohort_year",
                            "USER_IMPORT_YEAR_INVALID", "cohort_year must be between 2000 and 2200.");
                }
            }
        }
        if (row.roles().stream().anyMatch(Set.of("TEACHER", "COUNSELOR")::contains)) {
            required(row, errors, "staff_number", row.staffNumber());
            requireOrganizationOrLegacy(row, errors, "department", row.department());
        }
        if (!row.organizationCode().isBlank() && organization(row.organizationCode()) == null)
            error(errors, row.file(), row.sheet(), row.row(), "organization_code",
                    "USER_IMPORT_ORGANIZATION_NOT_FOUND", "organization_code must reference an enabled organization.");
    }

    private void validateIdentifierConflicts(UserRow row, ExistingUser existing,
            List<AdminDtos.UserImportError> errors) {
        if (!row.studentNumber().isBlank()) {
            String owner = jdbc.sql("SELECT user_id FROM student_profile WHERE student_number = :value")
                    .param("value", row.studentNumber()).query(String.class).optional().orElse(null);
            if (owner != null && (existing == null || !owner.equals(existing.id().toString())))
                error(errors, row.file(), row.sheet(), row.row(), "student_number",
                        "USER_IMPORT_STUDENT_NUMBER_CONFLICT", "student_number belongs to another account.");
        }
        if (!row.staffNumber().isBlank()) {
            String sql = row.roles().contains("COUNSELOR") && !row.roles().contains("TEACHER")
                    ? "SELECT user_id FROM counselor_profile WHERE staff_number = :value"
                    : "SELECT user_id FROM teacher_profile WHERE staff_number = :value";
            String owner = jdbc.sql(sql).param("value", row.staffNumber()).query(String.class).optional().orElse(null);
            if (owner != null && (existing == null || !owner.equals(existing.id().toString())))
                error(errors, row.file(), row.sheet(), row.row(), "staff_number",
                        "USER_IMPORT_STAFF_NUMBER_CONFLICT", "staff_number belongs to another account.");
        }
    }

    private void validateMembership(MembershipRow row, Set<String> roles, CourseFact course,
            ExistingUser existing, List<AdminDtos.UserImportError> errors) {
        if (roles.contains("STUDENT") && (row.membershipType().isBlank() || "LEARNER".equals(row.membershipType()))) {
            if (!row.membershipType().isBlank() && !"LEARNER".equals(row.membershipType()))
                error(errors, row.file(), row.sheet(), row.row(), "membership_type",
                        "USER_IMPORT_MEMBERSHIP_TYPE_INVALID", "Student membership_type must be LEARNER or blank.");
            if (!STUDENT_STATUSES.contains(row.membershipStatus())) error(errors, row.file(), row.sheet(), row.row(),
                    "membership_status", "USER_IMPORT_MEMBERSHIP_STATUS_INVALID",
                    "Student membership_status must be ACTIVE, COMPLETED, or WITHDRAWN.");
        } else if (roles.contains("TEACHER")) {
            if (!TEACHING_ROLES.contains(row.membershipType())) error(errors, row.file(), row.sheet(), row.row(),
                    "membership_type", "USER_IMPORT_MEMBERSHIP_TYPE_INVALID",
                    "Teacher membership_type must be OWNER or CO_TEACHER.");
            if (!row.membershipStatus().isBlank()) error(errors, row.file(), row.sheet(), row.row(),
                    "membership_status", "USER_IMPORT_MEMBERSHIP_STATUS_INVALID",
                    "Teacher membership_status must be blank.");
            if (course != null && "OWNER".equals(row.membershipType()) && course.ownerId() != null
                    && (existing == null || !course.ownerId().equals(existing.id())))
                error(errors, row.file(), row.sheet(), row.row(), "membership_type",
                        "USER_IMPORT_OWNER_CONFLICT", "The course already has a different owner.");
        } else error(errors, row.file(), row.sheet(), row.row(), "username",
                "USER_IMPORT_MEMBERSHIP_ROLE_INVALID", "Only students and teachers can receive course memberships.");
    }

    private void upsertUser(EduTwinPrincipal actor, PreparedUser prepared) {
        UserRow row = prepared.row();
        ExistingUser existing = findUser(row.username());
        if (existing == null) {
            jdbc.sql("""
                    INSERT INTO user_account(id, username, password_hash, display_name, enabled,
                        must_change_password, origin_type)
                    VALUES (:id, :username, :password, :displayName, :enabled, TRUE, 'BULK_IMPORT')
                    """).param("id", prepared.id().toString()).param("username", row.username())
                    .param("password", prepared.passwordHash()).param("displayName", row.displayName())
                    .param("enabled", row.enabled()).update();
            for (String role : row.roles()) {
                jdbc.sql("INSERT INTO user_role(user_id, role_code, granted_by) VALUES (:id, :role, :actor)")
                        .param("id", prepared.id().toString()).param("role", role)
                        .param("actor", actor.userId().toString()).update();
                roleHistory(prepared.id(), role, "GRANTED", actor.userId());
            }
            jdbc.sql("UPDATE user_account SET last_active_role = :role WHERE id = :id")
                    .param("role", preferredRole(row.roles())).param("id", prepared.id().toString()).update();
            insertProfile(prepared.id(), row);
            jdbc.sql("INSERT INTO user_profile(user_id) VALUES (:id)")
                    .param("id", prepared.id().toString()).update();
        } else {
            replaceRoles(actor, existing, row.roles());
            jdbc.sql("""
                    UPDATE user_account SET display_name = :name, enabled = :enabled,
                        token_version = token_version + 1,
                        last_active_role = CASE
                            WHEN last_active_role IN (SELECT role_code FROM user_role WHERE user_id = :id)
                            THEN last_active_role ELSE :fallback END
                    WHERE id = :id
                    """)
                    .param("name", row.displayName()).param("enabled", row.enabled())
                    .param("fallback", preferredRole(row.roles()))
                    .param("id", existing.id().toString()).update();
            updateProfile(existing.id(), row);
        }
        assignOrganization(prepared.id(), row.organizationCode());
    }

    private void insertProfile(UUID id, UserRow row) {
        if (row.roles().contains("STUDENT")) jdbc.sql("""
                    INSERT INTO student_profile(user_id, synthetic, synthetic_key, split_name,
                        student_number, college, major, cohort_year, class_name)
                    VALUES (:id, FALSE, NULL, 'test', :number, :college, :major, :year, :className)
                    """).param("id", id.toString()).param("number", row.studentNumber())
                    .param("college", row.college()).param("major", row.major())
                    .param("year", integerOrNull(row.cohortYear())).param("className", blankToNull(row.className())).update();
        if (row.roles().contains("TEACHER")) jdbc.sql("""
                    INSERT INTO teacher_profile(user_id, staff_key, staff_number, college, department, academic_title)
                    VALUES (:id, :key, :number, :college, :department, :title)
                    """).param("id", id.toString()).param("key", "IMPORT-" + id)
                    .param("number", row.staffNumber()).param("college", row.college())
                    .param("department", blankToNull(row.department())).param("title", blankToNull(row.academicTitle())).update();
        if (row.roles().contains("COUNSELOR")) jdbc.sql("""
                    INSERT INTO counselor_profile(user_id, staff_number, college, department)
                    VALUES (:id, :number, :college, :department)
                    """).param("id", id.toString()).param("number", row.staffNumber())
                    .param("college", blankToNull(row.college())).param("department", blankToNull(row.department())).update();
    }

    private void updateProfile(UUID id, UserRow row) {
        if (row.roles().contains("STUDENT")) jdbc.sql("""
                    INSERT IGNORE INTO student_profile(user_id, synthetic, synthetic_key, split_name)
                    VALUES (:id, FALSE, NULL, 'test')
                    """).param("id", id.toString()).update();
        if (row.roles().contains("STUDENT")) jdbc.sql("""
                    UPDATE student_profile SET student_number = COALESCE(NULLIF(:number,''), student_number),
                        college = COALESCE(NULLIF(:college,''), college), major = COALESCE(NULLIF(:major,''), major),
                        cohort_year = COALESCE(:year, cohort_year), class_name = COALESCE(NULLIF(:className,''), class_name)
                    WHERE user_id = :id
                    """).param("id", id.toString()).param("number", row.studentNumber())
                    .param("college", row.college()).param("major", row.major())
                    .param("year", row.cohortYear().isBlank() ? null : Integer.parseInt(row.cohortYear()))
                    .param("className", row.className()).update();
        if (row.roles().contains("TEACHER")) jdbc.sql("""
                    INSERT IGNORE INTO teacher_profile(user_id, staff_key) VALUES (:id, :key)
                    """).param("id", id.toString()).param("key", "IMPORT-" + id).update();
        if (row.roles().contains("TEACHER")) jdbc.sql("""
                    UPDATE teacher_profile SET staff_number = COALESCE(NULLIF(:number,''), staff_number),
                        college = COALESCE(NULLIF(:college,''), college),
                        department = COALESCE(NULLIF(:department,''), department),
                        academic_title = COALESCE(NULLIF(:title,''), academic_title) WHERE user_id = :id
                    """).param("id", id.toString()).param("number", row.staffNumber())
                    .param("college", row.college()).param("department", row.department())
                    .param("title", row.academicTitle()).update();
        if (row.roles().contains("COUNSELOR")) jdbc.sql("""
                    INSERT IGNORE INTO counselor_profile(user_id) VALUES (:id)
                    """).param("id", id.toString()).update();
        if (row.roles().contains("COUNSELOR")) jdbc.sql("""
                    UPDATE counselor_profile SET staff_number = COALESCE(NULLIF(:number,''), staff_number),
                        college = COALESCE(NULLIF(:college,''), college),
                        department = COALESCE(NULLIF(:department,''), department) WHERE user_id = :id
                    """).param("id", id.toString()).param("number", row.staffNumber())
                    .param("college", row.college()).param("department", row.department()).update();
    }

    private void applyMembership(MembershipRow row, Map<String, PreparedUser> prepared) {
        PreparedUser preparedUser = prepared.get(row.username());
        UUID userId = preparedUser == null ? findUser(row.username()).id() : preparedUser.id();
        UserRow userRow = preparedUser == null ? null : preparedUser.row();
        Set<String> roles = userRow == null ? findUser(row.username()).roles() : userRow.roles();
        UUID courseId = course(row.courseCode()).id();
        if (roles.contains("STUDENT") && (row.membershipType().isBlank() || "LEARNER".equals(row.membershipType()))) {
            String current = jdbc.sql("""
                    SELECT status FROM course_enrollment
                    WHERE course_id = :courseId AND student_id = :userId
                    """).param("courseId", courseId.toString()).param("userId", userId.toString())
                    .query(String.class).optional().orElse(null);
            if (row.membershipStatus().equals(current)) return;
            jdbc.sql("""
                    INSERT INTO course_enrollment(course_id, student_id, status)
                    VALUES (:courseId, :userId, :status)
                    ON DUPLICATE KEY UPDATE status = VALUES(status)
                    """).param("courseId", courseId.toString()).param("userId", userId.toString())
                    .param("status", row.membershipStatus()).update();
        } else {
            String current = jdbc.sql("""
                    SELECT assignment_role FROM teaching_assignment
                    WHERE course_id = :courseId AND teacher_id = :userId
                    """).param("courseId", courseId.toString()).param("userId", userId.toString())
                    .query(String.class).optional().orElse(null);
            if (row.membershipType().equals(current)) return;
            jdbc.sql("""
                    INSERT INTO teaching_assignment(course_id, teacher_id, assignment_role)
                    VALUES (:courseId, :userId, :role)
                    ON DUPLICATE KEY UPDATE assignment_role = VALUES(assignment_role)
                    """).param("courseId", courseId.toString()).param("userId", userId.toString())
                    .param("role", row.membershipType()).update();
        }
        jdbc.sql("UPDATE user_account SET token_version = token_version + 1 WHERE id = :userId")
                .param("userId", userId.toString())
                .update();
        evictDashboardAfterCommit(courseId);
    }

    private void evictDashboardAfterCommit(UUID courseId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            teacherDashboardService.evict(courseId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                teacherDashboardService.evict(courseId);
            }
        });
    }

    private ImportData parse(List<MultipartFile> uploads) {
        if (uploads == null || uploads.isEmpty() || uploads.size() > 2)
            throw badRequest("USER_IMPORT_FILES_INVALID", "Upload one Excel file or users.csv plus an optional membership CSV.");
        try {
            List<Upload> files = new ArrayList<>();
            for (MultipartFile upload : uploads) {
                if (upload == null || upload.isEmpty()) throw badRequest("USER_IMPORT_FILE_EMPTY", "Import files cannot be empty.");
                files.add(new Upload(cleanFileName(upload.getOriginalFilename()), upload.getBytes()));
            }
            String sha = digest(files);
            if (files.size() == 1 && files.get(0).name().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                return parseWorkbook(files.get(0), sha);
            return parseCsvFiles(files, sha);
        } catch (IOException exception) {
            throw badRequest("USER_IMPORT_READ_FAILED", "The uploaded import file could not be read.");
        }
    }

    private ImportData parseWorkbook(Upload upload, String sha) {
        List<AdminDtos.UserImportError> errors = new ArrayList<>();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(upload.bytes()))) {
            Sheet users = workbook.getSheet("users");
            Sheet memberships = workbook.getSheet("course_memberships");
            if (users == null) {
                error(errors, upload.name(), "users", 1, "", "USER_IMPORT_SHEET_MISSING", "The users sheet is required.");
                return new ImportData(sha, "XLSX", List.of(), List.of(), errors);
            }
            List<UserRow> userRows = parseUserMaps(sheetRows(upload.name(), users, USER_HEADERS, errors), errors);
            List<MembershipRow> membershipRows = memberships == null ? List.of()
                    : parseMembershipMaps(sheetRows(upload.name(), memberships, MEMBERSHIP_HEADERS, errors), errors);
            return new ImportData(sha, "XLSX", userRows, membershipRows, errors);
        } catch (IOException | RuntimeException exception) {
            throw badRequest("USER_IMPORT_XLSX_INVALID", "The Excel workbook is invalid or unreadable.");
        }
    }

    private ImportData parseCsvFiles(List<Upload> uploads, String sha) {
        List<AdminDtos.UserImportError> errors = new ArrayList<>();
        Upload users = uploads.stream().filter(file -> file.name().equalsIgnoreCase("users.csv")).findFirst().orElse(null);
        Upload memberships = uploads.stream().filter(file -> file.name().equalsIgnoreCase("course_memberships.csv")).findFirst().orElse(null);
        if (users == null || uploads.stream().anyMatch(file -> !file.name().equalsIgnoreCase("users.csv")
                && !file.name().equalsIgnoreCase("course_memberships.csv")))
            throw badRequest("USER_IMPORT_CSV_NAMES_INVALID", "CSV files must be named users.csv and course_memberships.csv.");
        List<UserRow> userRows = parseUserMaps(csvRows(users, USER_HEADERS, errors), errors);
        List<MembershipRow> membershipRows = memberships == null ? List.of()
                : parseMembershipMaps(csvRows(memberships, MEMBERSHIP_HEADERS, errors), errors);
        return new ImportData(sha, "CSV", userRows, membershipRows, errors);
    }

    private List<RowMap> csvRows(Upload upload, List<String> headers,
            List<AdminDtos.UserImportError> errors) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(upload.bytes())).toString();
            if (text.startsWith("\ufeff")) text = text.substring(1);
            CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                    .setIgnoreEmptyLines(true).setTrim(true).get();
            try (Reader reader = new InputStreamReader(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
                 CSVParser parser = format.parse(reader)) {
                List<String> actualHeaders = parser.getHeaderNames();
                if (!actualHeaders.equals(headers) && !(headers == USER_HEADERS && actualHeaders.equals(LEGACY_USER_HEADERS))) {
                    error(errors, upload.name(), "", 1, "", "USER_IMPORT_HEADERS_INVALID",
                            "CSV headers must exactly match the template.");
                    return List.of();
                }
                List<RowMap> rows = new ArrayList<>();
                for (CSVRecord record : parser) {
                    Map<String, String> values = new LinkedHashMap<>();
                    for (String header : actualHeaders) values.put(header, record.get(header).trim());
                    rows.add(new RowMap(upload.name(), "", Math.toIntExact(record.getRecordNumber() + 1), values));
                }
                return rows;
            }
        } catch (CharacterCodingException exception) {
            throw badRequest("USER_IMPORT_CSV_ENCODING_INVALID", "CSV files must use valid UTF-8 encoding.");
        } catch (IOException | IllegalArgumentException exception) {
            throw badRequest("USER_IMPORT_CSV_INVALID", "A CSV file is invalid or unreadable.");
        }
    }

    private List<RowMap> sheetRows(String file, Sheet sheet, List<String> headers,
            List<AdminDtos.UserImportError> errors) {
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        Row headerRow = sheet.getRow(0);
        List<String> actual = new ArrayList<>();
        int expectedColumns = headerRow == null ? headers.size() : headerRow.getLastCellNum();
        if (headerRow != null) for (int index = 0; index < expectedColumns; index++)
            actual.add(formatter.formatCellValue(headerRow.getCell(index)).trim());
        if (!actual.equals(headers) && !(headers == USER_HEADERS && actual.equals(LEGACY_USER_HEADERS))) {
            error(errors, file, sheet.getSheetName(), 1, "", "USER_IMPORT_HEADERS_INVALID",
                    "Worksheet headers must exactly match the template.");
            return List.of();
        }
        List<RowMap> rows = new ArrayList<>();
        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<String, String> values = new LinkedHashMap<>();
            boolean any = false;
            for (int column = 0; column < actual.size(); column++) {
                Cell cell = row.getCell(column);
                String value = cell == null ? "" : formatter.formatCellValue(cell).trim();
                values.put(actual.get(column), value); any |= !value.isBlank();
            }
            if (any) rows.add(new RowMap(file, sheet.getSheetName(), rowIndex + 1, values));
        }
        return rows;
    }

    private List<UserRow> parseUserMaps(List<RowMap> rows, List<AdminDtos.UserImportError> errors) {
        List<UserRow> result = new ArrayList<>();
        for (RowMap row : rows) result.add(new UserRow(row.file(), row.sheet(), row.row(),
                value(row, "username"), value(row, "display_name"),
                parseRoles(value(row, "roles").isBlank() ? value(row, "role") : value(row, "roles")),
                booleanValue(row, "enabled"), value(row, "student_number"), value(row, "staff_number"),
                upper(row, "organization_code"), value(row, "college"), value(row, "department"), value(row, "major"),
                value(row, "cohort_year"), value(row, "class_name"), value(row, "academic_title")));
        return result;
    }

    private List<MembershipRow> parseMembershipMaps(List<RowMap> rows,
            List<AdminDtos.UserImportError> errors) {
        return rows.stream().map(row -> new MembershipRow(row.file(), row.sheet(), row.row(),
                value(row, "username"), value(row, "course_code"), upper(row, "membership_type"),
                upper(row, "membership_status"))).toList();
    }

    private TemplateFile excelTemplate() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Sheet instructions = workbook.createSheet("instructions");
            instructions.createRow(0).createCell(0).setCellValue("EduTwin user provisioning import template");
            instructions.createRow(1).createCell(0).setCellValue("Do not rename worksheets or headers. Preview before commit.");
            createTemplateSheet(workbook, "users", USER_HEADERS);
            createTemplateSheet(workbook, "course_memberships", MEMBERSHIP_HEADERS);
            workbook.write(output);
            return new TemplateFile("edutwin-user-provisioning.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        } catch (IOException exception) {
            throw new IllegalStateException("Excel template generation failed.", exception);
        }
    }

    private static void createTemplateSheet(Workbook workbook, String name, List<String> headers) {
        Sheet sheet = workbook.createSheet(name);
        Row row = sheet.createRow(0);
        for (int index = 0; index < headers.size(); index++) {
            row.createCell(index).setCellValue(headers.get(index));
            sheet.setColumnWidth(index, 18 * 256);
        }
        sheet.createFreezePane(0, 1);
    }

    private static byte[] csvTemplate(List<String> headers) {
        return ("\ufeff" + String.join(",", headers) + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private ExistingUser findUser(String username) {
        if (username == null || username.isBlank()) return null;
        return jdbc.sql("""
                SELECT u.id, GROUP_CONCAT(ur.role_code ORDER BY ur.role_code) role_codes, u.origin_type,
                       u.enabled,
                       COALESCE(sp.synthetic, FALSE) synthetic
                FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                LEFT JOIN student_profile sp ON sp.user_id = u.id
                WHERE u.username = :username
                GROUP BY u.id, u.origin_type, u.enabled, sp.synthetic
                """).param("username", username)
                .query((rs, row) -> new ExistingUser(UUID.fromString(rs.getString("id")),
                        parseRoles(rs.getString("role_codes").replace(',', '|')),
                        rs.getString("origin_type"), rs.getBoolean("synthetic"), rs.getBoolean("enabled")))
                .optional().orElse(null);
    }

    private CourseFact course(String code) {
        if (code == null || code.isBlank()) return null;
        return jdbc.sql("""
                SELECT c.id, owner.teacher_id owner_id FROM course c
                LEFT JOIN teaching_assignment owner ON owner.course_id = c.id
                    AND owner.assignment_role = 'OWNER'
                WHERE c.code = :code
                """).param("code", code)
                .query((rs, row) -> new CourseFact(UUID.fromString(rs.getString("id")),
                        rs.getString("owner_id") == null ? null : UUID.fromString(rs.getString("owner_id"))))
                .optional().orElse(null);
    }

    private OrganizationFact organization(String code) {
        if (code == null || code.isBlank()) return null;
        return jdbc.sql("""
                SELECT id, code FROM organization_unit WHERE code = :code AND enabled = TRUE
                """).param("code", code).query((rs, row) -> new OrganizationFact(
                        UUID.fromString(rs.getString("id")), rs.getString("code")))
                .optional().orElse(null);
    }

    private int activeAdminCount() {
        return jdbc.sql("""
                SELECT COUNT(*) FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                WHERE u.enabled = TRUE AND ur.role_code = 'ADMIN'
                """).query(Integer.class).single();
    }

    private void lockActiveAdministrators() {
        jdbc.sql("""
                SELECT u.id FROM user_account u JOIN user_role ur ON ur.user_id = u.id
                WHERE u.enabled = TRUE AND ur.role_code = 'ADMIN'
                ORDER BY u.id FOR UPDATE
                """).query(String.class).list();
    }

    private void assignOrganization(UUID userId, String organizationCode) {
        if (organizationCode == null || organizationCode.isBlank()) return;
        OrganizationFact organization = organization(organizationCode);
        jdbc.sql("""
                UPDATE user_organization_membership SET ended_at = CURRENT_TIMESTAMP(6)
                WHERE user_id = :userId AND membership_type = 'PRIMARY' AND ended_at IS NULL
                """).param("userId", userId.toString()).update();
        jdbc.sql("""
                INSERT INTO user_organization_membership(
                    user_id, organization_id, membership_type, started_at, ended_at)
                VALUES (:userId, :organizationId, 'PRIMARY', CURRENT_TIMESTAMP(6), NULL)
                ON DUPLICATE KEY UPDATE started_at = CURRENT_TIMESTAMP(6), ended_at = NULL
                """).param("userId", userId.toString())
                .param("organizationId", organization.id().toString()).update();
    }

    private void replaceRoles(EduTwinPrincipal actor, ExistingUser existing, Set<String> roles) {
        for (String revoked : existing.roles().stream().filter(role -> !roles.contains(role)).toList()) {
            jdbc.sql("DELETE FROM user_role WHERE user_id = :id AND role_code = :role")
                    .param("id", existing.id().toString()).param("role", revoked).update();
            roleHistory(existing.id(), revoked, "REVOKED", actor.userId());
        }
        for (String granted : roles.stream().filter(role -> !existing.roles().contains(role)).toList()) {
            jdbc.sql("INSERT INTO user_role(user_id, role_code, granted_by) VALUES (:id, :role, :actor)")
                    .param("id", existing.id().toString()).param("role", granted)
                    .param("actor", actor.userId().toString()).update();
            roleHistory(existing.id(), granted, "GRANTED", actor.userId());
        }
    }

    private void roleHistory(UUID userId, String role, String event, UUID actor) {
        jdbc.sql("""
                INSERT INTO user_role_history(id, user_id, role_code, event_type, changed_by, reason)
                VALUES (:id, :userId, :role, :event, :actor, 'Bulk import role replacement')
                """).param("id", UUID.randomUUID().toString()).param("userId", userId.toString())
                .param("role", role).param("event", event).param("actor", actor.toString()).update();
    }

    private static String preferredRole(Set<String> roles) {
        return List.of("STUDENT", "COUNSELOR", "TEACHER", "ADMIN").stream()
                .filter(roles::contains).findFirst().orElseThrow();
    }

    private static String digest(List<Upload> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.stream().sorted(Comparator.comparing(Upload::name)).forEach(file -> {
                digest.update(file.name().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0); digest.update(file.bytes()); digest.update((byte) 0);
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private static String temporaryPassword() {
        byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void requireValid(Validation validation) {
        if (!validation.errors().isEmpty()) throw badRequest("USER_IMPORT_INVALID",
                "The import contains validation errors. Run preview and correct every reported row.");
    }

    private static void required(UserRow row, List<AdminDtos.UserImportError> errors,
            String field, String value) {
        if (value.isBlank()) error(errors, row.file(), row.sheet(), row.row(), field,
                "USER_IMPORT_REQUIRED", field + " is required for roles " + row.roles() + ".");
    }

    private static void requireOrganizationOrLegacy(UserRow row,
            List<AdminDtos.UserImportError> errors, String field, String value) {
        if (row.organizationCode().isBlank()) required(row, errors, field, value);
    }

    private static Boolean booleanValue(RowMap row, String field) {
        return switch (value(row, field).toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "是" -> true;
            case "false", "0", "no", "n", "否" -> false;
            default -> null;
        };
    }

    private static String value(RowMap row, String field) { return row.values().getOrDefault(field, "").trim(); }
    private static String upper(RowMap row, String field) { return value(row, field).toUpperCase(Locale.ROOT); }
    private static Set<String> parseRoles(String value) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        if (value != null) for (String item : value.split("\\|")) {
            if (!item.isBlank()) roles.add(item.trim().toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(roles);
    }
    private static Object blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static Integer integerOrNull(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }
    private static String cleanFileName(String name) {
        if (name == null) return "upload";
        return name.replace('\\', '/').substring(name.replace('\\', '/').lastIndexOf('/') + 1);
    }
    private static void error(List<AdminDtos.UserImportError> errors, String file, String sheet,
            int row, String field, String code, String message) {
        errors.add(new AdminDtos.UserImportError(file, sheet, row, field, code, message));
    }
    private static DomainException badRequest(String code, String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, code, detail);
    }
    private static DomainException conflict(String code, String detail) {
        return new DomainException(HttpStatus.CONFLICT, code, detail);
    }

    public record TemplateFile(String fileName, String contentType, byte[] bytes) {}
    private record Upload(String name, byte[] bytes) {}
    private record RowMap(String file, String sheet, int row, Map<String, String> values) {}
    private record UserRow(String file, String sheet, int row, String username, String displayName,
            Set<String> roles, Boolean enabled, String studentNumber, String staffNumber, String organizationCode, String college,
            String department, String major, String cohortYear, String className, String academicTitle) {}
    private record MembershipRow(String file, String sheet, int row, String username, String courseCode,
            String membershipType, String membershipStatus) {}
    private record ImportData(String sha256, String format, List<UserRow> users,
            List<MembershipRow> memberships, List<AdminDtos.UserImportError> parseErrors) {}
    private record Validation(int creates, int updates, List<AdminDtos.UserImportError> errors) {}
    private record ExistingUser(UUID id, Set<String> roles, String originType,
            boolean synthetic, boolean enabled) {}
    private record CourseFact(UUID id, UUID ownerId) {}
    private record OrganizationFact(UUID id, String code) {}
    private record PreparedUser(UUID id, UserRow row, String passwordHash) {}
}
