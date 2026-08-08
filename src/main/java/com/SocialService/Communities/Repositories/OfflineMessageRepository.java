package com.SocialService.Communities.Repositories;

import com.SocialService.Communities.Models.OfflineMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OfflineMessageRepository extends JpaRepository<OfflineMessage, Long> {

    // Fetch all pending messages for a user in chronological order
    List<OfflineMessage> findByRecipientUsernameOrderByTimestampAsc(String recipientUsername);

    // Clear the mailbox after successful delivery
    @Transactional
    void deleteByRecipientUsername(String recipientUsername);

    // 🟢 NEW: Batch delete messages older than a specific threshold
    @Modifying
    @Transactional
    @Query("DELETE FROM OfflineMessage o WHERE o.timestamp < :expiryDate")
    int deleteExpiredMessages(@Param("expiryDate") LocalDateTime expiryDate);
}