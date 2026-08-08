package com.SocialService.Communities.Repositories;

import com.SocialService.Communities.Models.Post;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    // 🟢 Fetches public alerts or radar pings for a specific city/location
    Page<Post> findByCityNameAndCategoryOrderByCreatedAtDesc(String cityName, String category, Pageable pageable);

    // Fallback feed fetcher
    Page<Post> findByCityNameOrderByCreatedAtDesc(String cityName, Pageable pageable);

    // 🟢 Fetches a user's personal timeline logs for their Profile Terminal
    Page<Post> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // 🟢 Global Database Keyword Search Engine (Scam Archive)
    @Query("SELECT p FROM Post p WHERE LOWER(p.cityName) LIKE LOWER(CONCAT('%', :kw, '%')) " +
            "OR LOWER(p.title) LIKE LOWER(CONCAT('%', :kw, '%')) " +
            "OR LOWER(p.content) LIKE LOWER(CONCAT('%', :kw, '%')) " +
            "OR LOWER(p.category) LIKE LOWER(CONCAT('%', :kw, '%')) " +
            "ORDER BY p.createdAt DESC")
    Page<Post> searchGlobalScams(@Param("kw") String keyword, Pageable pageable);
}