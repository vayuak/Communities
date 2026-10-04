package com.SocialService.Communities.Repositories;

import com.SocialService.Communities.Models.SafetyAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SafetyAlertRepository extends JpaRepository<SafetyAlert, Long> {
    Integer countByTargetPostIdAndReportWeightGreaterThan(Long targetPostId, Integer minWeight);
}