package com.SocialService.Communities.Listeners;

import com.SocialService.Communities.Models.OfflineMessage;
import com.SocialService.Communities.Repositories.OfflineMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketMailboxFlusher {

    private final OfflineMessageRepository offlineMessageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @EventListener
    public void handleSessionSubscribeEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = headerAccessor.getDestination();

        // Ensure they have a valid user principal
        if (headerAccessor.getUser() == null) return;

        String username = headerAccessor.getUser().getName();

        // When the React Native app subscribes to their personal queue...
        if (destination != null && destination.endsWith("/queue/messages")) {
            log.info("📡 User '{}' just connected to their message queue. Checking Mailbox...", username);

            // 1. Fetch pending messages
            List<OfflineMessage> pendingMessages = offlineMessageRepository.findByRecipientUsernameOrderByTimestampAsc(username);

            if (!pendingMessages.isEmpty()) {
                log.info("📬 Found {} offline messages for {}. Flushing to device...", pendingMessages.size(), username);

                // 2. Deliver each message
                for (OfflineMessage msg : pendingMessages) {
                    Map<String, String> payload = new HashMap<>();
                    payload.put("senderUsername", msg.getSenderUsername());
                    payload.put("recipientUsername", msg.getRecipientUsername());
                    payload.put("roomId", msg.getRoomId());
                    payload.put("encryptedContent", msg.getEncryptedPayload());

                    messagingTemplate.convertAndSendToUser(username, "/queue/messages", payload);
                }

                // 3. Purge from server (Zero-Trust enforced)
                offlineMessageRepository.deleteByRecipientUsername(username);
                log.info("🗑️ Mailbox wiped clean for {}", username);
            }
        }
    }
}