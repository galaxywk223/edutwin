import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AssistmentsCanonicalizer {
    private static final Charset LATIN_1 = StandardCharsets.ISO_8859_1;
    private static final String[] EXPECTED_HEADER = {
        "order_id", "assignment_id", "user_id", "assistment_id", "problem_id",
        "original", "correct", "attempt_count", "ms_first_response", "tutor_mode",
        "answer_type", "sequence_id", "student_class_id", "position", "type",
        "base_sequence_id", "skill_id", "skill_name", "teacher_id", "school_id",
        "hint_count", "hint_total", "overlap_time", "template_id", "answer_id",
        "answer_text", "first_action", "bottom_hint", "opportunity",
        "opportunity_original"
    };
    private static final Set<Integer> KNOWLEDGE_FIELDS = Set.of(16, 17, 28, 29);
    private static final int ANSWER_TEXT_INDEX = 25;
    private static final int OPPORTUNITY_INDEX = 28;
    private static final int OPPORTUNITY_ORIGINAL_INDEX = 29;
    private static final int EXPECTED_OFFICIAL_ROWS = 525_534;
    private static final int EXPECTED_CANONICAL_ROWS = 401_756;
    private static final int EXPECTED_REMOVED_ROWS = 123_778;
    private static final int EXPECTED_UNIQUE_ORDER_IDS = 346_860;

    private AssistmentsCanonicalizer() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 6
                || !"--official".equals(args[0])
                || !"--mirror".equals(args[2])
                || !"--canonical".equals(args[4])) {
            throw new IllegalArgumentException(
                    "Usage: AssistmentsCanonicalizer --official FILE --mirror FILE --canonical FILE");
        }
        Path official = Path.of(args[1]).toAbsolutePath().normalize();
        Path mirror = Path.of(args[3]).toAbsolutePath().normalize();
        Path canonical = Path.of(args[5]).toAbsolutePath().normalize();
        Result result = canonicalize(official, mirror, canonical);
        System.out.println(result.toJson());
    }

    private static Result canonicalize(Path official, Path mirror, Path canonical)
            throws IOException, NoSuchAlgorithmException {
        Files.createDirectories(canonical.getParent());
        Path partial = Path.of(canonical + ".partial");
        Files.deleteIfExists(partial);
        boolean completed = false;
        try (CsvReader officialReader = new CsvReader(official);
                CsvReader mirrorReader = new CsvReader(mirror);
                BufferedWriter writer = Files.newBufferedWriter(
                        partial, StandardCharsets.UTF_8)) {
            String[] officialHeader = requireRecord(officialReader, "Official header is missing.");
            String[] mirrorHeader = requireRecord(mirrorReader, "Mirror header is missing.");
            assertEqual(EXPECTED_HEADER, officialHeader, "Official header");
            assertEqual(officialHeader, mirrorHeader, "Mirror header");

            MessageDigest canonicalSemantic = MessageDigest.getInstance("SHA-256");
            addSemanticRecord(canonicalSemantic, officialHeader, true);
            writer.write(toCsv(officialHeader));
            writer.write('\n');

            Map<String, OfficialReference> officialByLineage =
                    new HashMap<>(EXPECTED_CANONICAL_ROWS * 4 / 3);
            Map<String, String> officialCoreByOrderId =
                    new HashMap<>(EXPECTED_UNIQUE_ORDER_IDS * 4 / 3);
            int officialRows = 0;
            int officialRepeatedLineageRows = 0;
            int officialOpportunityVariantRows = 0;

            String[] officialFields;
            while ((officialFields = officialReader.readRecord()) != null) {
                officialRows++;
                requireFieldCount(officialFields, officialRows, "Official");
                String orderId = officialFields[0];
                if (orderId == null || orderId.strip().isEmpty()) {
                    throw new IllegalStateException(
                            "Official ASSISTments row " + officialRows + " has an empty order_id.");
                }
                String coreKey = framedKey(officialFields, true);
                String previousCore = officialCoreByOrderId.putIfAbsent(orderId, coreKey);
                if (previousCore != null && !previousCore.equals(coreKey)) {
                    throw new IllegalStateException(
                            "Core-field conflict for ASSISTments order_id '" + orderId + "'.");
                }

                OfficialReference reference = new OfficialReference(
                        officialFields[ANSWER_TEXT_INDEX],
                        officialFields[OPPORTUNITY_INDEX],
                        officialFields[OPPORTUNITY_ORIGINAL_INDEX]);
                OfficialReference previous = officialByLineage.putIfAbsent(
                        lineageKey(officialFields), reference);
                if (previous != null) {
                    officialRepeatedLineageRows++;
                    if (!previous.sameOpportunity(reference)) {
                        officialOpportunityVariantRows++;
                    }
                }
            }

            int canonicalRows = officialByLineage.size();
            int removedRows = officialRows - canonicalRows;
            requireCount("official rows", EXPECTED_OFFICIAL_ROWS, officialRows);
            requireCount("canonical rows", EXPECTED_CANONICAL_ROWS, canonicalRows);
            requireCount("removed redundant rows", EXPECTED_REMOVED_ROWS, removedRows);
            requireCount(
                    "unique order_id values", EXPECTED_UNIQUE_ORDER_IDS,
                    officialCoreByOrderId.size());

            Map<String, String> mirrorCoreByOrderId =
                    new HashMap<>(EXPECTED_UNIQUE_ORDER_IDS * 4 / 3);
            int mirrorRows = 0;
            int mirrorAnswerTextMismatches = 0;
            int mirrorRetainedOpportunityMismatches = 0;
            String[] mirrorFields;
            while ((mirrorFields = mirrorReader.readRecord()) != null) {
                mirrorRows++;
                requireFieldCount(mirrorFields, mirrorRows, "Mirror");
                OfficialReference officialReference = officialByLineage.remove(
                        lineageKey(mirrorFields));
                if (officialReference == null) {
                    throw new IllegalStateException(
                            "ASSISTments mirror row " + mirrorRows
                                    + " has no unique official lineage match (order_id "
                                    + mirrorFields[0] + ").");
                }

                String mirrorCore = framedKey(mirrorFields, true);
                String previousMirrorCore = mirrorCoreByOrderId.putIfAbsent(
                        mirrorFields[0], mirrorCore);
                if (previousMirrorCore != null && !previousMirrorCore.equals(mirrorCore)) {
                    throw new IllegalStateException(
                            "Mirror core-field conflict for ASSISTments order_id '"
                                    + mirrorFields[0] + "'.");
                }
                if (!officialReference.answerText().equals(mirrorFields[ANSWER_TEXT_INDEX])) {
                    mirrorAnswerTextMismatches++;
                }
                if (!officialReference.opportunity().equals(mirrorFields[OPPORTUNITY_INDEX])
                        || !officialReference.opportunityOriginal().equals(
                                mirrorFields[OPPORTUNITY_ORIGINAL_INDEX])) {
                    mirrorRetainedOpportunityMismatches++;
                }

                String[] canonicalFields = mirrorFields.clone();
                canonicalFields[ANSWER_TEXT_INDEX] = officialReference.answerText();
                addSemanticRecord(canonicalSemantic, canonicalFields, true);
                writer.write(toCsv(canonicalFields));
                writer.write('\n');
            }
            if (!officialByLineage.isEmpty()) {
                throw new IllegalStateException(
                        "ASSISTments mirror omits " + officialByLineage.size()
                                + " unique official lineage rows.");
            }
            writer.flush();

            requireCount("mirror rows", EXPECTED_CANONICAL_ROWS, mirrorRows);
            requireCount(
                    "mirror unique order_id values", EXPECTED_UNIQUE_ORDER_IDS,
                    mirrorCoreByOrderId.size());

            String canonicalSemanticHash = HexFormat.of().formatHex(canonicalSemantic.digest());
            moveAtomically(partial, canonical);
            completed = true;
            return new Result(
                    officialRows,
                    officialRepeatedLineageRows,
                    officialOpportunityVariantRows,
                    canonicalRows,
                    mirrorRows,
                    officialCoreByOrderId.size(),
                    mirrorAnswerTextMismatches,
                    mirrorRetainedOpportunityMismatches,
                    canonicalSemanticHash,
                    sha256(canonical),
                    Files.size(canonical));
        } finally {
            if (!completed) {
                Files.deleteIfExists(partial);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String[] requireRecord(CsvReader reader, String message) throws IOException {
        String[] fields = reader.readRecord();
        if (fields == null) {
            throw new IllegalStateException(message);
        }
        return fields;
    }

    private static void requireFieldCount(String[] fields, int row, String source) {
        if (fields.length != EXPECTED_HEADER.length) {
            throw new IllegalStateException(
                    source + " ASSISTments row " + row + " has " + fields.length + " fields.");
        }
    }

    private static void assertEqual(String[] expected, String[] actual, String source) {
        int difference = differenceIndex(expected, actual);
        if (difference >= 0) {
            throw new IllegalStateException(source + " differs at column " + difference + ".");
        }
    }

    private static int differenceIndex(String[] left, String[] right) {
        int shared = Math.min(left.length, right.length);
        for (int index = 0; index < shared; index++) {
            if (!left[index].equals(right[index])) {
                return index;
            }
        }
        return left.length == right.length ? -1 : shared;
    }

    private static String framedKey(String[] fields, boolean omitKnowledgeFields) {
        StringBuilder builder = new StringBuilder();
        int count = omitKnowledgeFields ? fields.length - KNOWLEDGE_FIELDS.size() : fields.length;
        builder.append(count).append('|');
        for (int index = 0; index < fields.length; index++) {
            if (omitKnowledgeFields && KNOWLEDGE_FIELDS.contains(index)) {
                continue;
            }
            String value = fields[index];
            builder.append(value.length()).append(':').append(value);
        }
        return builder.toString();
    }

    private static String lineageKey(String[] fields) {
        StringBuilder builder = new StringBuilder();
        builder.append(fields.length - 3).append('|');
        for (int index = 0; index < fields.length; index++) {
            if (index == ANSWER_TEXT_INDEX
                    || index == OPPORTUNITY_INDEX
                    || index == OPPORTUNITY_ORIGINAL_INDEX) {
                continue;
            }
            String value = fields[index];
            builder.append(value.length()).append(':').append(value);
        }
        return builder.toString();
    }

    private static void addSemanticRecord(
            MessageDigest digest, String[] fields, boolean omitAnswerText) {
        int count = omitAnswerText ? fields.length - 1 : fields.length;
        digest.update(intBytes(count));
        for (int index = 0; index < fields.length; index++) {
            if (omitAnswerText && index == ANSWER_TEXT_INDEX) {
                continue;
            }
            String field = fields[index];
            byte[] value = field.getBytes(StandardCharsets.UTF_8);
            digest.update(intBytes(value.length));
            digest.update(value);
        }
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(Integer.BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(value)
                .array();
    }

    private static String toCsv(String[] fields) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            String value = fields[index];
            if (value.indexOf(',') >= 0
                    || value.indexOf('"') >= 0
                    || value.indexOf('\r') >= 0
                    || value.indexOf('\n') >= 0) {
                builder.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                builder.append(value);
            }
        }
        return builder.toString();
    }

    private static void requireCount(String label, int expected, int actual) {
        if (actual != expected) {
            throw new IllegalStateException(
                    "ASSISTments " + label + " mismatch: expected " + expected + ", got " + actual + ".");
        }
    }

    private static String sha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record Result(
            int officialRows,
            int officialRepeatedLineageRows,
            int officialOpportunityVariantRows,
            int canonicalRows,
            int mirrorRows,
            int uniqueOrderIds,
            int mirrorAnswerTextMismatches,
            int mirrorRetainedOpportunityMismatches,
            String canonicalSemanticSha256,
            String canonicalSha256,
            long canonicalBytes) {
        String toJson() {
            return "{" +
                    "\"official_rows\":" + officialRows + "," +
                    "\"official_repeated_lineage_rows\":" + officialRepeatedLineageRows + "," +
                    "\"official_opportunity_variant_rows\":" + officialOpportunityVariantRows + "," +
                    "\"canonical_rows\":" + canonicalRows + "," +
                    "\"mirror_rows\":" + mirrorRows + "," +
                    "\"unique_order_ids\":" + uniqueOrderIds + "," +
                    "\"mirror_answer_text_mismatches\":" + mirrorAnswerTextMismatches + "," +
                    "\"mirror_retained_opportunity_mismatches\":"
                    + mirrorRetainedOpportunityMismatches + "," +
                    "\"canonical_semantic_sha256\":\"" + canonicalSemanticSha256 + "\"," +
                    "\"canonical_sha256\":\"" + canonicalSha256 + "\"," +
                    "\"canonical_bytes\":" + canonicalBytes + "}";
        }
    }

    private record OfficialReference(
            String answerText, String opportunity, String opportunityOriginal) {
        boolean sameOpportunity(OfficialReference other) {
            return opportunity.equals(other.opportunity)
                    && opportunityOriginal.equals(other.opportunityOriginal);
        }
    }

    private static final class CsvReader implements AutoCloseable {
        private final PushbackReader reader;
        private long recordNumber;

        CsvReader(Path path) throws IOException {
            BufferedReader buffered = new BufferedReader(
                    Files.newBufferedReader(path, LATIN_1), 1024 * 1024);
            this.reader = new PushbackReader(buffered, 1);
        }

        String[] readRecord() throws IOException {
            List<String> fields = new ArrayList<>(EXPECTED_HEADER.length);
            StringBuilder field = new StringBuilder();
            boolean inQuotes = false;
            boolean atFieldStart = true;
            boolean consumed = false;
            while (true) {
                int value = reader.read();
                if (value < 0) {
                    if (inQuotes) {
                        throw malformed("unterminated quoted field");
                    }
                    if (!consumed && fields.isEmpty() && field.isEmpty()) {
                        return null;
                    }
                    fields.add(field.toString());
                    recordNumber++;
                    return fields.toArray(String[]::new);
                }
                consumed = true;
                char character = (char) value;
                if (inQuotes) {
                    if (character == '"') {
                        int next = reader.read();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            inQuotes = false;
                            if (next >= 0) {
                                reader.unread(next);
                            }
                        }
                    } else {
                        field.append(character);
                    }
                    continue;
                }
                if (character == '"') {
                    if (!atFieldStart) {
                        throw malformed("quote in unquoted field");
                    }
                    inQuotes = true;
                    atFieldStart = false;
                } else if (character == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    atFieldStart = true;
                } else if (character == '\n' || character == '\r') {
                    if (character == '\r') {
                        int next = reader.read();
                        if (next >= 0 && next != '\n') {
                            reader.unread(next);
                        }
                    }
                    fields.add(field.toString());
                    recordNumber++;
                    return fields.toArray(String[]::new);
                } else {
                    field.append(character);
                    atFieldStart = false;
                }
            }
        }

        private IllegalStateException malformed(String detail) {
            return new IllegalStateException(
                    "Malformed CSV near record " + (recordNumber + 1) + ": " + detail + ".");
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

}
