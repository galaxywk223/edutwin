package com.edutwin.assistant;

import com.edutwin.assistant.AssistantDtos.ConversationList;
import com.edutwin.assistant.AssistantDtos.ConversationSummary;
import com.edutwin.assistant.AssistantDtos.CreateConversationRequest;
import com.edutwin.assistant.AssistantDtos.MessageList;
import com.edutwin.assistant.AssistantDtos.MessageReceipt;
import com.edutwin.assistant.AssistantDtos.RenameConversationRequest;
import com.edutwin.assistant.AssistantDtos.SendMessageRequest;
import com.edutwin.identity.EduTwinPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {

    private final AssistantService service;

    public AssistantController(AssistantService service) {
        this.service = service;
    }

    @GetMapping("/conversations")
    public ConversationList conversations() {
        return service.conversations(principal());
    }

    @PostMapping("/conversations")
    public ResponseEntity<ConversationSummary> createConversation(
            @Valid @RequestBody(required = false) CreateConversationRequest request) {
        ConversationSummary created = service.createConversation(
                principal(), request == null ? null : request.title());
        return ResponseEntity.created(URI.create(
                        "/api/v1/assistant/conversations/" + created.conversationId()))
                .body(created);
    }

    @PutMapping("/conversations/{conversationId}")
    public ConversationSummary renameConversation(
            @PathVariable UUID conversationId,
            @Valid @RequestBody RenameConversationRequest request) {
        return service.renameConversation(principal(), conversationId, request.title());
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(@PathVariable UUID conversationId) {
        service.deleteConversation(principal(), conversationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public MessageList messages(@PathVariable UUID conversationId) {
        return service.messages(principal(), conversationId);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<MessageReceipt> send(
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.accepted().body(service.send(principal(), conversationId, request));
    }

    @PostMapping("/messages/{messageId}/retry")
    public ResponseEntity<MessageReceipt> retry(@PathVariable UUID messageId) {
        return ResponseEntity.accepted().body(service.retry(principal(), messageId));
    }

    @GetMapping("/messages/{messageId}")
    public AssistantDtos.MessageView message(@PathVariable UUID messageId) {
        return service.message(principal(), messageId);
    }

    @GetMapping(path = "/messages/{messageId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable UUID messageId,
            @RequestHeader(name = "Last-Event-ID", required = false) Long lastEventId) {
        return service.stream(principal(), messageId, lastEventId);
    }

    private static EduTwinPrincipal principal() {
        return (EduTwinPrincipal) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
    }
}
