package com.edutwin.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record Notification(UUID notificationId, String type, String title, String body,
            String deepLink, OffsetDateTime createdAt, OffsetDateTime expiresAt, boolean read) {}
    public record NotificationPage(List<Notification> items, int page, int size, long total) {}
    public record UnreadCount(long count) {}
}
