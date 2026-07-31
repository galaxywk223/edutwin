package com.edutwin.lms;

import com.edutwin.notification.NotificationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
final class NoOpLmsNotificationPublisher implements LmsNotificationPublisher {

    private final ObjectProvider<NotificationService> notificationService;

    NoOpLmsNotificationPublisher(ObjectProvider<NotificationService> notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void publish(UUID recipientId, String eventType, String dedupeKey, Map<String, String> attributes) {
        NotificationService service = notificationService.getIfAvailable();
        if (service == null) return;
        String assessmentId = attributes.get("assessmentId");
        String courseId = attributes.get("courseId");
        String deepLink = courseId == null ? "/student/courses"
                : eventType.startsWith("ASSESSMENT_") || assessmentId != null
                        ? "/student/courses/" + courseId + "/assignments"
                        : "/student/courses/" + courseId + "/overview";
        service.create(recipientId, eventType, title(eventType), body(eventType), deepLink, dedupeKey, null);
    }

    private static String title(String eventType) {
        return switch (eventType) {
            case "ASSESSMENT_PUBLISHED" -> "New assessment published";
            case "ASSESSMENT_EXTENDED" -> "Assessment deadline extended";
            case "ASSESSMENT_CANCELLED" -> "Assessment cancelled";
            case "ATTEMPT_GRANTED", "ATTEMPT_REQUEST_APPROVED" -> "Additional attempt approved";
            case "ATTEMPT_REQUEST_REJECTED" -> "Additional attempt request declined";
            default -> "Course update";
        };
    }

    private static String body(String eventType) {
        return title(eventType) + ".";
    }
}
