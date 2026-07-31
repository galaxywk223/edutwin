package com.edutwin.notification;

import com.edutwin.identity.EduTwinPrincipal;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping
    public NotificationDtos.NotificationPage list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(principal(), page, size);
    }

    @GetMapping("/unread-count")
    public NotificationDtos.UnreadCount unreadCount() { return service.unreadCount(principal()); }

    @PutMapping("/{notificationId}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID notificationId) {
        service.markRead(principal(), notificationId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        service.markAllRead(principal());
        return ResponseEntity.noContent().build();
    }

    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
