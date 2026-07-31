package com.edutwin.notification;

import com.edutwin.identity.EduTwinPrincipal;
import com.edutwin.shared.web.DomainException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public NotificationDtos.NotificationPage list(EduTwinPrincipal principal, int page, int size) {
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.min(100, Math.max(1, size));
        long total = jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE recipient_user_id = :userId AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(6))
                """).param("userId", principal.userId().toString()).query(Long.class).single();
        var items = jdbc.sql("""
                SELECT id, notification_type, title, body, deep_link, created_at, expires_at, read_at
                FROM user_notification
                WHERE recipient_user_id = :userId AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(6))
                ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset
                """).param("userId", principal.userId().toString()).param("limit", normalizedSize)
                .param("offset", normalizedPage * normalizedSize)
                .query((rs, row) -> new NotificationDtos.Notification(
                        UUID.fromString(rs.getString("id")), rs.getString("notification_type"),
                        rs.getString("title"), rs.getString("body"), rs.getString("deep_link"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class), rs.getObject("read_at") != null)).list();
        return new NotificationDtos.NotificationPage(items, normalizedPage, normalizedSize, total);
    }

    public NotificationDtos.UnreadCount unreadCount(EduTwinPrincipal principal) {
        long count = jdbc.sql("""
                SELECT COUNT(*) FROM user_notification
                WHERE recipient_user_id = :userId AND read_at IS NULL
                  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP(6))
                """).param("userId", principal.userId().toString()).query(Long.class).single();
        return new NotificationDtos.UnreadCount(count);
    }

    @Transactional
    public void markRead(EduTwinPrincipal principal, UUID notificationId) {
        int updated = jdbc.sql("""
                UPDATE user_notification SET read_at = COALESCE(read_at, CURRENT_TIMESTAMP(6))
                WHERE id = :id AND recipient_user_id = :userId
                """).param("id", notificationId.toString())
                .param("userId", principal.userId().toString()).update();
        if (updated == 0) throw new DomainException(HttpStatus.NOT_FOUND,
                "NOTIFICATION_NOT_FOUND", "The notification does not exist.");
    }

    @Transactional
    public void markAllRead(EduTwinPrincipal principal) {
        jdbc.sql("""
                UPDATE user_notification SET read_at = CURRENT_TIMESTAMP(6)
                WHERE recipient_user_id = :userId AND read_at IS NULL
                """).param("userId", principal.userId().toString()).update();
    }

    public boolean create(UUID recipientUserId, String type, String title, String body,
            String deepLink, String dedupeKey, OffsetDateTime expiresAt) {
        return jdbc.sql("""
                INSERT IGNORE INTO user_notification(
                    id, recipient_user_id, notification_type, title, body,
                    deep_link, dedupe_key, expires_at)
                VALUES (:id, :recipient, :type, :title, :body, :link, :dedupe, :expiresAt)
                """).param("id", UUID.randomUUID().toString())
                .param("recipient", recipientUserId.toString()).param("type", required(type, 50))
                .param("title", required(title, 160)).param("body", required(body, 1000))
                .param("link", optional(deepLink, 500)).param("dedupe", required(dedupeKey, 200))
                .param("expiresAt", expiresAt).update() == 1;
    }

    private static String required(String value, int max) {
        String result = optional(value, max);
        if (result == null) throw new IllegalArgumentException("Notification fields cannot be blank.");
        return result;
    }
    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw new IllegalArgumentException("Notification field is too long.");
        return result;
    }
}
