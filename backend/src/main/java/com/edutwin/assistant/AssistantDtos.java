package com.edutwin.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AssistantDtos {

    private AssistantDtos() {}

    public record ConversationList(List<ConversationSummary> items) {}

    public record ConversationSummary(
            UUID conversationId,
            String title,
            String activeRole,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record CreateConversationRequest(@Size(max = 120) String title) {}

    public record RenameConversationRequest(@NotBlank @Size(max = 120) String title) {}

    public record MessageList(List<MessageView> items) {}

    public record MessageView(
            UUID messageId,
            String role,
            String status,
            String content,
            List<SourceRef> sources,
            List<String> deepLinks,
            String errorCode,
            boolean retryable,
            OffsetDateTime createdAt,
            OffsetDateTime completedAt) {}

    public record SendMessageRequest(
            @NotBlank @Size(max = 4000) String content,
            UUID clientMessageId,
            Map<String, @Size(max = 500) String> routeContext) {}

    public record MessageReceipt(
            UUID userMessageId,
            UUID assistantMessageId,
            String status,
            String streamUrl) {}

    public record SourceRef(String label, OffsetDateTime queriedAt, String deepLink) {}

    public record EventEnvelope(
            long sequence,
            UUID messageId,
            String type,
            OffsetDateTime occurredAt,
            Object data) {}
}
