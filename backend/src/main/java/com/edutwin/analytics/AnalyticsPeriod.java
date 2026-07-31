package com.edutwin.analytics;

import com.edutwin.shared.web.DomainException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;

public enum AnalyticsPeriod {
    SEVEN_DAYS("7D", 7),
    THIRTY_DAYS("30D", 30),
    TERM("TERM", 0);

    private final String value;
    private final int days;

    AnalyticsPeriod(String value, int days) {
        this.value = value;
        this.days = days;
    }

    public static AnalyticsPeriod parse(String value) {
        String normalized = value == null || value.isBlank() ? "30D" : value.trim().toUpperCase();
        for (AnalyticsPeriod period : values()) {
            if (period.value.equals(normalized)) {
                return period;
            }
        }
        throw new DomainException(HttpStatus.BAD_REQUEST, "ANALYTICS_PERIOD_INVALID",
                "Period must be one of 7D, 30D, or TERM.");
    }

    public OffsetDateTime since(OffsetDateTime now, LocalDate termStart) {
        return this == TERM
                ? termStart.atStartOfDay().atOffset(ZoneOffset.UTC)
                : now.minusDays(days - 1L).toLocalDate().atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    public String value() {
        return value;
    }
}
