package com.edutwin.assistant;

import com.edutwin.assistant.AssistantDtos.EventEnvelope;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AssistantEventHub {

    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers =
            new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID messageId) {
        SseEmitter emitter = new SseEmitter(35_000L);
        subscribers.computeIfAbsent(messageId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> remove(messageId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        return emitter;
    }

    public void publish(EventEnvelope event) {
        List<SseEmitter> emitters = subscribers.getOrDefault(
                event.messageId(), new CopyOnWriteArrayList<>());
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, event);
            } catch (IOException | IllegalStateException exception) {
                remove(event.messageId(), emitter);
            }
        }
    }

    public void complete(UUID messageId) {
        List<SseEmitter> emitters = subscribers.remove(messageId);
        if (emitters != null) {
            emitters.forEach(SseEmitter::complete);
        }
    }

    public void send(SseEmitter emitter, EventEnvelope event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(Long.toString(event.sequence()))
                .name(event.type())
                .data(event));
    }

    private void remove(UUID messageId, SseEmitter emitter) {
        subscribers.computeIfPresent(messageId, (ignored, current) -> {
            current.remove(emitter);
            return current.isEmpty() ? null : current;
        });
    }
}
