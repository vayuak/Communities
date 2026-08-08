package com.SocialService.Communities.Repositories;
import com.SocialService.Communities.Models.PeerVouch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
@Repository
public interface PeerVouchRepository extends JpaRepository<PeerVouch, Long> {
    long countByTargetUserId(Long targetUserId);
    boolean existsByVoucherIdAndTargetUserId(Long voucherId, Long targetUserId);
}