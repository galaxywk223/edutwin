package com.edutwin.lms;

import java.util.Map;
import java.util.UUID;

/** Integration boundary for the notification module introduced by the identity batch. */
public interface LmsNotificationPublisher {

    void publish(UUID recipientId, String eventType, String dedupeKey, Map<String, String> attributes);
}
