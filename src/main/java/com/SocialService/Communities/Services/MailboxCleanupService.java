package com.SocialService.Communities.Services;

import com.SocialService.Communities.Repositories.OfflineMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class MailboxCleanupService {

    private final OfflineMessageRepository offlineMessageRepository;

    /**
     * 🧹 Runs automatically every hour at the top of the hour.
     * Cron expression "0 0 * * * *" means:
     * Second 0, Minute 0, of every Hour, of every Day, Month, and Year.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void purgeExpiredOfflineMessages() {
        // Calculate the exact timestamp for 72 hours ago
        LocalDateTime expiryThreshold = LocalDateTime.now().minusHours(72);

        log.info("🧹 [MAILBOX CLEANUP] Waking up. Searching for vaulted messages older than 72 hours (Before {})...", expiryThreshold);

        try {
            // Execute the bulk delete
            int deletedCount = offlineMessageRepository.deleteExpiredMessages(expiryThreshold);

            if (deletedCount > 0) {
                log.info("🗑️ [MAILBOX CLEANUP] Successfully shredded {} expired offline messages to protect DB integrity.", deletedCount);
            } else {
                log.debug("✨ [MAILBOX CLEANUP] Mailbox is clean. No expired messages found.");
            }
        } catch (Exception e) {
            log.error("❌ [MAILBOX CLEANUP] Failed to purge expired messages: {}", e.getMessage());
        }
    }
}