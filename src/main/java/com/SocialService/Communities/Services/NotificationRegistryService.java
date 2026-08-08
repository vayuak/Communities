package com.SocialService.Communities.Services;

import com.SocialService.Communities.DTOs.NotificationEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class NotificationRegistryService {

    // Maps active User IDs to their persistent SSE browser network streams
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter registerClient(Long userId) {
        // Initialize an emitter with a 30-minute timeout limit
        SseEmitter emitter = new SseEmitter(1800000L);

        emitters.put(userId, emitter);

        // Clean up connection states automatically on completion or network dropouts
        emitter.onCompletion(() -> emitters.remove(userId));
        emitter.onTimeout(() -> emitters.remove(userId));
        emitter.onError((e) -> emitters.remove(userId));

        try {
            // Send an immediate handshake packet to confirm connection link success
            emitter.send(SseEmitter.event()
                    .name("HANDSHAKE")
                    .data(Map.of("status", "CONNECTED", "msg", "Ghost Notification Mesh Active.")));
        } catch (IOException e) {
            emitters.remove(userId);
        }

        log.info("🔔 Stream Registered: User {} is now listening for real-time safety pushes.", userId);
        return emitter;
    }

    /**
     * Pushes an alert directly onto a specific user's phone or desktop screen
     */
    public void sendDirectNotification(Long userId, NotificationEvent event) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().name(event.getType()).data(event));
                log.info("📥 Direct push delivered to User {}", userId);
            } catch (IOException e) {
                emitters.remove(userId);
                log.warn("Failed to deliver broadcast to dropped stream for user: {}", userId);
            }
        }
    }

    /**
     * Broadcasts a location-based critical scam warning to EVERY active user in a given city
     */
    public void broadcastToCity(String cityName, NotificationEvent event) {
        log.info("📢 Broadcoasting safety warning to all active travelers in: {}", cityName);

        emitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().name(event.getType()).data(event));
            } catch (IOException e) {
                emitters.remove(userId);
            }
        });
    }
}