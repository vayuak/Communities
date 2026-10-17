package com.SocialService.Communities.Repositories;

import com.SocialService.Communities.Models.CommentReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CommentReportRepository extends JpaRepository<CommentReport, Long> {
}