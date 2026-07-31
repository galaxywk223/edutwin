package com.edutwin.bootstrap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class CsvTable {

    private CsvTable() {}

    static <T> List<T> read(
            Path path, List<String> expectedHeader, Function<Row, T> mapper) throws IOException {
        try (PushbackReader reader = new PushbackReader(
                new BufferedReader(Files.newBufferedReader(path, StandardCharsets.UTF_8)), 1)) {
            List<String> header = readRecord(reader, path, 1);
            if (header == null || !header.equals(expectedHeader)) {
                throw new IllegalStateException(
                        "CSV header differs for " + path + ": " + header);
            }
            List<T> rows = new ArrayList<>();
            int recordNumber = 1;
            List<String> fields;
            while ((fields = readRecord(reader, path, recordNumber + 1)) != null) {
                recordNumber++;
                if (fields.size() != expectedHeader.size()) {
                    throw new IllegalStateException(
                            "CSV field count differs at " + path + " record " + recordNumber);
                }
                rows.add(mapper.apply(new Row(path, recordNumber, expectedHeader, fields)));
            }
            return rows;
        }
    }

    private static List<String> readRecord(
            PushbackReader reader, Path path, int recordNumber) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean quoteClosed = false;
        boolean anyCharacter = false;
        while (true) {
            int value = reader.read();
            if (value == -1) {
                if (quoted) {
                    throw new IllegalStateException(
                            "Unclosed CSV quote at " + path + " record " + recordNumber);
                }
                if (!anyCharacter && fields.isEmpty() && field.isEmpty()) {
                    return null;
                }
                fields.add(field.toString());
                return fields;
            }
            anyCharacter = true;
            char character = (char) value;
            if (quoted) {
                if (character == '"') {
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        quoteClosed = true;
                        if (next != -1) {
                            reader.unread(next);
                        }
                    }
                } else {
                    field.append(character);
                }
                continue;
            }
            if (quoteClosed && character != ',' && character != '\n' && character != '\r') {
                throw new IllegalStateException(
                        "Unexpected character after CSV quote at " + path
                                + " record " + recordNumber);
            }
            if (character == '"') {
                if (!field.isEmpty() || quoteClosed) {
                    throw new IllegalStateException(
                            "Unexpected CSV quote at " + path + " record " + recordNumber);
                }
                quoted = true;
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
                quoteClosed = false;
            } else if (character == '\n') {
                fields.add(field.toString());
                return fields;
            } else if (character == '\r') {
                int next = reader.read();
                if (next != '\n' && next != -1) {
                    reader.unread(next);
                }
                fields.add(field.toString());
                return fields;
            } else {
                field.append(character);
            }
        }
    }

    record Row(Path path, int recordNumber, List<String> header, List<String> fields) {

        String value(String name) {
            int index = header.indexOf(name);
            if (index < 0) {
                throw new IllegalArgumentException("Unknown CSV column: " + name);
            }
            return fields.get(index);
        }

        String required(String name) {
            String value = value(name);
            if (value.isBlank()) {
                throw invalid(name, "must not be blank");
            }
            return value;
        }

        boolean booleanValue(String name) {
            return switch (required(name)) {
                case "True", "true", "1" -> true;
                case "False", "false", "0" -> false;
                default -> throw invalid(name, "must be a boolean");
            };
        }

        int intValue(String name) {
            try {
                return Integer.parseInt(required(name));
            } catch (NumberFormatException exception) {
                throw invalid(name, "must be an integer");
            }
        }

        long longValue(String name) {
            try {
                return Long.parseLong(required(name));
            } catch (NumberFormatException exception) {
                throw invalid(name, "must be a long integer");
            }
        }

        double doubleValue(String name) {
            try {
                double value = Double.parseDouble(required(name));
                if (!Double.isFinite(value)) {
                    throw invalid(name, "must be finite");
                }
                return value;
            } catch (NumberFormatException exception) {
                throw invalid(name, "must be numeric");
            }
        }

        IllegalStateException invalid(String name, String detail) {
            return new IllegalStateException(
                    path + " record " + recordNumber + " column " + name + " " + detail);
        }
    }
}
